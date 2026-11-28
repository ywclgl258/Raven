package com.raven.app.core.media.reader

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.media.storage.DiskSidecarRecord
import com.raven.app.core.media.storage.DuplicateBookCandidate
import com.raven.app.core.media.storage.DuplicateSource
import com.raven.app.core.media.storage.StorageLayout
import com.raven.app.core.media.storage.StorageRootManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Download lifecycle: audit, commit, removal, recovery inspection and duplicate-book scanning.
 *
 * Extracted from `GalleryPageRepository` with explicit dependencies so the on-disk contract
 * (`complete` marker, `selection.json`, `readme.txt`) lives in one place. Behaviour is unchanged;
 * `GalleryOfflineStorageTest` covers the partial/complete/rescan semantics.
 */
internal class GalleryDownloadAudit(
    private val dispatchers: AppDispatchers,
    private val storage: StorageRootManager,
    private val json: Json,
    private val requireAndroidImageDecoder: Boolean,
    private val defaultLayout: () -> StorageLayout,
    private val bookFolder: (SiteId, String, StorageLayout) -> File,
    private val resolveReadingRoot: (SiteId, String) -> String,
    private val readDiskBook: (File, SiteId?, String?) -> GalleryPageRepository.DiskBook?,
    private val atomicWrite: (File, ByteArray) -> Unit,
) {
    suspend fun audit(
        siteId: SiteId,
        mediaId: String,
        sourceRoot: String = "",
    ): GalleryAudit = withContext(dispatchers.io) {
        val layout = storage.resolveSourceLayout(sourceRoot.ifBlank { resolveReadingRoot(siteId, mediaId) })
        val folder = bookFolder(siteId, mediaId, layout)
        val diskBook = readDiskBook(folder, siteId, mediaId)
            ?: error("图集下载信息缺失，无法检查完整性")
        val ctx = currentCoroutineContext()
        diskBook.audit { ctx.ensureActive() }
    }

    suspend fun auditWriteTarget(
        siteId: SiteId,
        mediaId: String,
        layout: StorageLayout = defaultLayout(),
    ): GalleryAudit? = withContext(dispatchers.io) {
        val folder = bookFolder(siteId, mediaId, layout)
        val diskBook = readDiskBook(folder, siteId, mediaId) ?: return@withContext null
        val ctx = currentCoroutineContext()
        diskBook.audit { ctx.ensureActive() }
    }

    suspend fun commit(
        siteId: SiteId,
        mediaId: String,
        layout: StorageLayout,
        requestedPages: Set<Int>,
    ): GalleryAudit = withContext(dispatchers.io) {
        val folder = bookFolder(siteId, mediaId, layout)
        val diskBook = readDiskBook(folder, siteId, mediaId)
            ?: error("图集下载信息缺失，无法提交完成状态")
        val ctx = currentCoroutineContext()
        val audit = diskBook.audit { ctx.ensureActive() }
        check(audit.validPages.toSet().containsAll(requestedPages)) { "图集下载尚未完整保存" }
        ctx.ensureActive()

        if (audit.isComplete) {
            atomicWrite(File(folder, "readme.txt"), GalleryPageRepository.RECOVERY_NOTE.toByteArray())
            val selFile = File(folder, "selection.json")
            if (selFile.exists() && !selFile.delete()) {
                throw GalleryStorageException("无法移除旧选页记录")
            }
            atomicWrite(File(folder, "complete"), audit.totalPages.toString().toByteArray())
        } else {
            val manifest = GalleryPageRepository.SelectionManifest(selected = requestedPages.sorted(), completed = audit.validPages)
            atomicWrite(
                File(folder, "selection.json"),
                json.encodeToString(GalleryPageRepository.SelectionManifest.serializer(), manifest).toByteArray(),
            )
            File(folder, "complete").delete()
            atomicWrite(
                File(folder, "readme.txt"),
                ("此图集为部分保存，有效图页 ${audit.validPages.size} / ${audit.totalPages}。\n\n$GalleryPageRepository.RECOVERY_NOTE").toByteArray(),
            )
        }
        audit
    }

    suspend fun remove(
        siteId: SiteId,
        mediaId: String,
        sourceRoot: String = "",
        knownRoot: String = "",
    ) = withContext(dispatchers.io) {
        val relative = "${siteId.value}/${StorageRootManager.sha256Hex(mediaId)}"
        val layouts = storage.galleryRemovalLayouts(sourceRoot, knownRoot, relative)
        for (layout in layouts) {
            val folder = bookFolder(siteId, mediaId, layout)
            check(!folder.exists() || folder.deleteRecursively()) { "部分下载图页无法删除，请重试" }
            if (layout.location == com.raven.app.core.model.StorageLocation.SHARED) {
                storage.forgetSharedGallery(relative)
            }
        }
        storage.removeComicSidecar(siteId, mediaId, sourceRoot)
    }

    fun inspectRecovery(
        siteId: SiteId,
        folder: File,
        sourceRoot: String,
    ): DiskSidecarRecord? {
        val diskBook = readDiskBook(folder, siteId, null) ?: return null
        val audit = diskBook.audit()
        val isWhole = diskBook.hasCompleteMarker && audit.isComplete
        val intactSelection = if (isWhole) {
            emptyList()
        } else {
            diskBook.selection.takeIf { it.isNotEmpty() && audit.validPages.toSet().containsAll(it) }.orEmpty()
        }
        // A selective download never writes the `complete` marker, yet every picked page can be on
        // disk. Reporting it as PAUSED made the user re-run a finished task.
        val selectionComplete = !isWhole && intactSelection.isNotEmpty()
        val item = diskBook.detail.item
        val kind = if (item.kind == MediaKind.PHOTO) DownloadKind.PHOTO else DownloadKind.COMIC
        val updatedAt = folder.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: System.currentTimeMillis()
        return DiskSidecarRecord(
            kind = kind,
            siteId = siteId,
            mediaId = item.id,
            title = item.title,
            url = item.url,
            coverUrl = item.coverUrl,
            totalPages = audit.totalPages,
            completedPages = audit.validPages.size,
            bytes = audit.totalBytes,
            updatedAt = updatedAt,
            status = if (isWhole || selectionComplete) DownloadStatus.COMPLETED else DownloadStatus.PAUSED,
            detail = if (isWhole || selectionComplete) {
                ""
            } else {
                "已保存 ${audit.validPages.size} / ${audit.totalPages} 页"
            },
            sourceRoot = sourceRoot,
            intactSelection = intactSelection,
            // Reported even when incomplete so a reinstall keeps the user's picked range instead
            // of silently widening the task to the whole book.
            declaredSelection = diskBook.selection,
        )
    }

    fun duplicateCandidates(shouldStop: () -> Boolean = { false }): List<DuplicateBookCandidate> = buildList {
        for (layout in storage.readableLayouts()) {
            if (!StorageRootManager.directChild(layout.root, layout.comicsDir)) continue
            for (siteDir in layout.comicsDir.listFiles().orEmpty()) {
                if (shouldStop()) return emptyList()
                if (!siteDir.isDirectory || !StorageRootManager.directChild(layout.comicsDir, siteDir)) continue
                val siteId = runCatching { SiteId.of(siteDir.name) }.getOrNull()?.takeIf { it.isKnown } ?: continue
                for (folder in siteDir.listFiles().orEmpty()) {
                    if (shouldStop()) return emptyList()
                    if (!folder.isDirectory || !StorageRootManager.directChild(siteDir, folder)) continue
                    val diskBook = readDiskBook(folder, siteId, null) ?: continue
                    if (!diskBook.hasCompleteMarker || !diskBook.isComplete()) continue
                    val pages = (1..diskBook.detail.pageCount).mapNotNull { pageIndex ->
                        if (shouldStop()) return emptyList()
                        diskBook.page(pageIndex)
                    }
                    if (pages.size != diskBook.detail.pageCount) continue
                    val byFile = pages.associateBy { it.file }
                    if (byFile.size != pages.size) continue
                    val item = diskBook.detail.item
                    add(
                        DuplicateBookCandidate(
                            source = DuplicateSource.DOWNLOAD,
                            id = "${siteId.value}:${item.id}",
                            siteId = siteId,
                            mediaId = item.id,
                            title = item.title,
                            pageFiles = pages.map { it.file },
                            validatePage = { file -> byFile[file]?.isValid(requireAndroidImageDecoder) == true },
                            sourceRoot = layout.root.absolutePath,
                        ),
                    )
                }
            }
        }
    }
}
