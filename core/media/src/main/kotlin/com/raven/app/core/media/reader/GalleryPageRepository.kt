package com.raven.app.core.media.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.media.storage.DiskSidecarRecord
import com.raven.app.core.media.storage.DuplicateBookCandidate
import com.raven.app.core.media.storage.DuplicateSource
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.media.storage.StorageLayout
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.network.SiteHttpErrorMapper
import com.raven.app.core.network.SiteRequestTag
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.ImageUnscrambler
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GalleryStorageException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class RenderedGalleryPage(
    val page: GalleryPage,
    val bitmap: Bitmap,
    val fromCache: Boolean,
)

data class OriginalGalleryPage(
    val page: GalleryPage,
    val bytes: ByteArray,
    val fromCache: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OriginalGalleryPage) return false
        return page == other.page && bytes.contentEquals(other.bytes) && fromCache == other.fromCache
    }

    override fun hashCode(): Int {
        var result = page.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + fromCache.hashCode()
        return result
    }
}

data class GalleryAudit(
    val totalPages: Int,
    val validPages: List<Int>,
    val missingPages: List<Int>,
    val corruptPages: List<Int>,
    val totalBytes: Long,
) {
    val isComplete: Boolean get() = totalPages > 0 && validPages.size == totalPages
}

data class OfflineGallerySnapshot(
    val detail: GalleryDetail,
    val downloadedPages: List<Int>,
    val requestedPages: List<Int>,
    val sourceRoot: String,
    val isComplete: Boolean,
)

/**
 * Manages the 256 MiB LRU reading cache, permanent downloaded book storage, atomic `.part` writes,
 * 4MP `inSampleSize` power-of-2 decoding, JMComic strip unscrambling, EH `?nl=` error recovery
 * via [GallerySource.reloadUrlOnError], and single-page cache self-healing.
 */
class GalleryPageRepository(
    private val context: Context?,
    private val imageClient: OkHttpClient,
    private val storageRootManager: StorageRootManager,
    private val localLibraryImporter: LocalLibraryImporter,
    private val siteRegistryProvider: () -> SiteRegistry,
    private val unscrambler: JmcomicBitmapUnscrambler = JmcomicBitmapUnscrambler(),
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val cacheRootOverride: File? = null,
    private val requireAndroidImageDecoder: Boolean = true,
) {
    @Serializable
    private data class KnownPageLinks(
        val links: Map<Int, String> = emptyMap(),
    )

    @Serializable
    private data class SavedPageMeta(
        val page: GalleryPage,
        val encodedBytes: Long,
    )

    @Serializable
    private data class SelectionManifest(
        val selected: List<Int> = emptyList(),
        val completed: List<Int> = emptyList(),
    )

    internal data class SavedPageFile(
        val page: GalleryPage,
        val file: File,
        val encodedBytes: Long,
    ) {
        fun isValid(requireAndroidDecoder: Boolean): Boolean =
            file.isFile &&
                file.length() == encodedBytes &&
                ImageStructureValidator.isValidImageFile(
                    file = file,
                    maxBytes = MAX_GALLERY_IMAGE_BYTES,
                    requireAndroidDecoder = requireAndroidDecoder,
                )
    }

    internal class DiskBook(
        val folder: File,
        val detail: GalleryDetail,
        val links: Map<Int, String>,
        val selection: List<Int>,
        val hasCompleteMarker: Boolean,
        private val json: Json,
        private val requireAndroidDecoder: Boolean,
    ) {
        fun page(index: Int): SavedPageFile? = links[index]?.let { url ->
            readSavedPage(folder, url, expectedIndex = index, json = json)
        }

        fun isComplete(): Boolean =
            hasCompleteMarker && (1..detail.pageCount).all { page(it)?.isValid(requireAndroidDecoder) == true }

        fun hasSelection(indices: Collection<Int> = selection): Boolean =
            indices.isNotEmpty() &&
                (hasCompleteMarker || selection.toSet().containsAll(indices)) &&
                indices.all { it in 1..detail.pageCount && page(it)?.isValid(requireAndroidDecoder) == true }

        fun audit(checkActive: () -> Unit = {}): GalleryAudit {
            val valid = mutableListOf<Int>()
            val missing = mutableListOf<Int>()
            val corrupt = mutableListOf<Int>()
            var bytes = 0L
            for (index in 1..detail.pageCount) {
                checkActive()
                val link = links[index]
                val saved = page(index)
                when {
                    link.isNullOrBlank() -> missing += index
                    saved == null || !saved.isValid(requireAndroidDecoder) -> corrupt += index
                    else -> {
                        valid += index
                        bytes += saved.file.length()
                    }
                }
            }
            return GalleryAudit(detail.pageCount, valid, missing, corrupt, bytes)
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val readingCacheBaseDir: File = cacheRootOverride
        ?: File(
            checkNotNull(context?.applicationContext?.filesDir) { "Context or cacheRootOverride required" },
            "gallery-cache",
        )

    private fun readingCacheDir(siteId: SiteId): File =
        File(readingCacheBaseDir, siteId.value).apply { mkdirs() }

    val downloadsAllowed: Boolean get() = storageRootManager.canWriteDownloads

    fun checkDownloadsAllowed() = storageRootManager.checkDownloadsAllowed()

    fun downloadLayout(): StorageLayout = storageRootManager.layout()

    fun bookFolder(siteId: SiteId, mediaId: String, layout: StorageLayout): File {
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
        val siteDir = File(layout.comicsDir, siteId.value)
        val bookDir = File(siteDir, StorageRootManager.sha256Hex(mediaId))
        check(
            StorageRootManager.directChild(layout.root, layout.comicsDir) &&
                StorageRootManager.directChild(layout.comicsDir, siteDir) &&
                StorageRootManager.directChild(siteDir, bookDir),
        ) { "下载目录超出允许范围" }
        return bookDir
    }

    fun resolveReadingRoot(siteId: SiteId, mediaId: String): String {
        if (siteId.isLocal) return localLibraryImporter.libraryRoot.absolutePath
        val active = storageRootManager.layout()
        val ordered = storageRootManager.readableLayouts().sortedBy { it.root.canonicalPath != active.root.canonicalPath }
        val found = ordered.mapNotNull { layout ->
            val folder = runCatching { bookFolder(siteId, mediaId, layout) }.getOrNull() ?: return@mapNotNull null
            readDiskBook(folder, expectedSiteId = siteId, expectedMediaId = mediaId)?.also {
                if (layout.location == com.raven.app.core.model.StorageLocation.SHARED) {
                    storageRootManager.noteSharedGallery("${siteId.value}/${folder.name}")
                }
            }?.let { layout to it }
        }
        val best = found.firstOrNull { it.second.isComplete() }?.first
            ?: found.firstOrNull { it.second.hasSelection() }?.first
            ?: found.firstOrNull()?.first
            ?: active
        return best.root.absolutePath
    }

    fun isDownloaded(siteId: SiteId, mediaId: String, sourceRoot: String = ""): Boolean {
        if (siteId.isLocal) return localLibraryImporter.isLocalComicComplete(mediaId)
        val layouts = if (sourceRoot.isBlank()) {
            storageRootManager.readableLayouts()
        } else {
            listOf(storageRootManager.resolveSourceLayout(sourceRoot))
        }
        return layouts.any { layout ->
            val folder = runCatching { bookFolder(siteId, mediaId, layout) }.getOrNull() ?: return@any false
            readDiskBook(folder, expectedSiteId = siteId, expectedMediaId = mediaId)?.isComplete() == true
        }
    }

    fun isCompleteBookDirectory(folder: File): Boolean =
        readDiskBook(folder)?.isComplete() == true

    // ---- Detail Loading ----

    suspend fun loadDetail(
        siteId: SiteId,
        mediaId: String,
        url: String,
        offlineOnly: Boolean = false,
        sourceRoot: String = "",
    ): OfflineGallerySnapshot = withContext(dispatchers.io) {
        if (siteId.isLocal) {
            val bookId = LocalLibraryImporter.parseLocalBookId(url) ?: mediaId
            val book = localLibraryImporter.getBook(bookId)
                ?: error("本地文件不存在，请重新导入同一文件或目录")
            val item = localLibraryImporter.toMediaItem(book)
            val pages = book.files.indices.map { idx ->
                val pageNum = idx + 1
                GalleryPage(
                    index = pageNum,
                    pageUrl = "${LocalLibraryImporter.buildLocalUri(book.id)}/$pageNum",
                    imageUrl = File(localLibraryImporter.libraryRoot, "${book.id}/${book.files[idx]}").toURI().toString(),
                )
            }
            val detail = GalleryDetail(
                item = item,
                description = "本地导入 · ${book.files.size} 页",
                pageCount = book.files.size,
                pages = pages,
            )
            return@withContext OfflineGallerySnapshot(
                detail = detail,
                downloadedPages = (1..book.files.size).toList(),
                requestedPages = (1..book.files.size).toList(),
                sourceRoot = localLibraryImporter.libraryRoot.absolutePath,
                isComplete = true,
            )
        }

        val resolvedRoot = sourceRoot.ifBlank { resolveReadingRoot(siteId, mediaId) }
        val layout = storageRootManager.resolveSourceLayout(resolvedRoot)
        val folder = bookFolder(siteId, mediaId, layout)
        val diskBook = readDiskBook(folder, expectedSiteId = siteId, expectedMediaId = mediaId)

        if (offlineOnly) {
            check(diskBook != null) { "图集下载信息缺失，请重新下载" }
            val ctx = currentCoroutineContext()
            val audit = diskBook.audit { ctx.ensureActive() }
            check(audit.validPages.isNotEmpty()) { "没有可离线读取的完整图页，请在下载中心检查并修复" }
            val requested = if (diskBook.hasCompleteMarker && audit.isComplete) {
                audit.validPages
            } else {
                diskBook.selection.ifEmpty { (1..audit.totalPages).toList() }
            }
            return@withContext OfflineGallerySnapshot(
                detail = diskBook.detail,
                downloadedPages = audit.validPages,
                requestedPages = requested,
                sourceRoot = resolvedRoot,
                isComplete = diskBook.hasCompleteMarker && audit.isComplete,
            )
        }

        val cacheFile = File(readingCacheDir(siteId), "${StorageRootManager.sha256Hex(url)}.gallery.json")
        val cachedDetail = readJsonFile(cacheFile, GalleryDetail.serializer(), MAX_BOOK_JSON_BYTES)
        val plugin = siteRegistryProvider().require(siteId)
        val gallerySource = plugin.gallerySource
            ?: throw SiteException.Unsupported("站点 ${siteId.value} 不支持图集浏览", siteId = siteId)

        try {
            val fresh = gallerySource.loadGalleryDetail(url)
            runCatching { atomicWriteBytes(cacheFile, json.encodeToString(GalleryDetail.serializer(), fresh).toByteArray()) }
            val audit = diskBook?.audit()
            OfflineGallerySnapshot(
                detail = fresh,
                downloadedPages = audit?.validPages.orEmpty(),
                requestedPages = diskBook?.selection.orEmpty(),
                sourceRoot = resolvedRoot,
                isComplete = diskBook?.hasCompleteMarker == true && audit?.isComplete == true,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (auth: SiteException.AuthRequired) {
            throw auth
        } catch (error: Exception) {
            val fallback = diskBook?.detail ?: cachedDetail
            if (fallback != null && (error is IOException || error is SiteException)) {
                val audit = diskBook?.audit()
                OfflineGallerySnapshot(
                    detail = fallback,
                    downloadedPages = audit?.validPages.orEmpty(),
                    requestedPages = diskBook?.selection.orEmpty(),
                    sourceRoot = resolvedRoot,
                    isComplete = diskBook?.hasCompleteMarker == true && audit?.isComplete == true,
                )
            } else {
                throw error
            }
        }
    }

    // ---- Page URL Cache & Self-Healing ----

    fun cachedPageUrl(siteId: SiteId, mediaId: String, galleryUrl: String, pageIndex: Int, sourceRoot: String = ""): String? {
        if (siteId.isLocal) {
            val id = LocalLibraryImporter.parseLocalBookId(galleryUrl) ?: mediaId
            return "${LocalLibraryImporter.buildLocalUri(id)}/$pageIndex"
        }
        val layout = storageRootManager.resolveSourceLayout(sourceRoot.ifBlank { resolveReadingRoot(siteId, mediaId) })
        val folder = runCatching { bookFolder(siteId, mediaId, layout) }.getOrNull()
        val fromDownload = folder?.let { readLinksMap(it)[pageIndex] }?.takeIf { it.isNotBlank() }
        if (fromDownload != null) return fromDownload
        val cacheLinksFile = File(readingCacheDir(siteId), "${StorageRootManager.sha256Hex(galleryUrl)}.links.json")
        return readJsonFile(cacheLinksFile, KnownPageLinks.serializer(), MAX_BOOK_JSON_BYTES)
            ?.links?.get(pageIndex)
            ?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun forgetCachedPageLink(siteId: SiteId, galleryUrl: String, pageIndex: Int, staleUrl: String) {
        if (siteId.isLocal || galleryUrl.isBlank()) return
        val file = File(readingCacheDir(siteId), "${StorageRootManager.sha256Hex(galleryUrl)}.links.json")
        val existing = readJsonFile(file, KnownPageLinks.serializer(), MAX_BOOK_JSON_BYTES)?.links.orEmpty()
        if (existing[pageIndex] == staleUrl) {
            runCatching {
                atomicWriteBytes(file, json.encodeToString(KnownPageLinks.serializer(), KnownPageLinks(existing - pageIndex)).toByteArray())
            }
        }
    }

    // ---- Page Loading & Decoding (4MP power-of-2 inSampleSize) ----

    suspend fun loadRenderedPage(
        detail: GalleryDetail,
        pageIndex: Int,
        preferOriginal: Boolean = false,
        forceReload: Boolean = false,
        offlineOnly: Boolean = false,
        sourceRoot: String = "",
    ): RenderedGalleryPage = withContext(dispatchers.io) {
        val original = loadOriginalPage(
            detail = detail,
            pageIndex = pageIndex,
            preferOriginal = preferOriginal,
            forceReload = forceReload,
            offlineOnly = offlineOnly,
            sourceRoot = sourceRoot,
        )
        RenderedGalleryPage(
            page = original.page,
            bitmap = decodeSampledBitmap(original.bytes, ImageStructureValidator.DEFAULT_MAX_DECODE_PIXELS),
            fromCache = original.fromCache,
        )
    }

    suspend fun loadOriginalPage(
        detail: GalleryDetail,
        pageIndex: Int,
        preferOriginal: Boolean = false,
        forceReload: Boolean = false,
        offlineOnly: Boolean = false,
        sourceRoot: String = "",
    ): OriginalGalleryPage = withContext(dispatchers.io) {
        val item = detail.item
        if (item.siteId.isLocal) {
            val bookId = LocalLibraryImporter.parseLocalBookId(item.url) ?: item.id
            val file = localLibraryImporter.getPageFile(bookId, pageIndex)
            check(ImageStructureValidator.isValidImageFile(file, LocalLibraryImporter.MAX_SINGLE_IMAGE_BYTES, requireAndroidImageDecoder)) {
                "本地图片文件已损坏，请重新导入"
            }
            val bytes = readBytesBounded(file, LocalLibraryImporter.MAX_SINGLE_IMAGE_BYTES)
                ?: error("无法读取本地图片")
            return@withContext OriginalGalleryPage(
                page = GalleryPage(
                    index = pageIndex,
                    pageUrl = "${LocalLibraryImporter.buildLocalUri(bookId)}/$pageIndex",
                    imageUrl = file.toURI().toString(),
                ),
                bytes = bytes,
                fromCache = true,
            )
        }

        val resolvedRoot = sourceRoot.ifBlank { resolveReadingRoot(item.siteId, item.id) }
        val knownPageUrl = cachedPageUrl(item.siteId, item.id, item.url, pageIndex, resolvedRoot)
            ?: detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl

        // 1. Check permanent downloaded book folder first (required in offlineOnly mode).
        if (!forceReload || offlineOnly) {
            val layout = storageRootManager.resolveSourceLayout(resolvedRoot)
            val bookDir = bookFolder(item.siteId, item.id, layout)
            val downloadUrl = readLinksMap(bookDir)[pageIndex] ?: knownPageUrl
            if (!downloadUrl.isNullOrBlank()) {
                val saved = readSavedPage(bookDir, downloadUrl, expectedIndex = pageIndex, json = json)
                if (saved != null) {
                    if (saved.isValid(requireAndroidImageDecoder)) {
                        val bytes = readBytesBounded(saved.file, MAX_GALLERY_IMAGE_BYTES)
                        if (bytes != null) {
                            return@withContext OriginalGalleryPage(saved.page, bytes, fromCache = true)
                        }
                    }
                    if (offlineOnly) {
                        error("下载图页已损坏，请在下载中心检查并修复")
                    }
                }
            }
        }
        check(!offlineOnly) { "下载图页缺失，请在下载中心重新下载；离线阅读不会连接网络" }

        // 2. Single-page self-heal: if a cached pageUrl fails resolution, forget it and re-resolve via detail once.
        val plugin = siteRegistryProvider().require(item.siteId)
        val gallerySource = plugin.gallerySource
            ?: throw SiteException.Unsupported("站点 ${item.siteId.value} 不支持图集读取", siteId = item.siteId)

        try {
            loadOnlinePageBytes(
                siteId = item.siteId,
                galleryUrl = item.url,
                detail = detail,
                pageIndex = pageIndex,
                hintPageUrl = knownPageUrl,
                gallerySource = gallerySource,
                imageUnscrambler = plugin.imageUnscrambler,
                preferOriginal = preferOriginal,
                forceReload = forceReload,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            if (!forceReload && !knownPageUrl.isNullOrBlank()) {
                forgetCachedPageLink(item.siteId, item.url, pageIndex, knownPageUrl)
                loadOnlinePageBytes(
                    siteId = item.siteId,
                    galleryUrl = item.url,
                    detail = detail,
                    pageIndex = pageIndex,
                    hintPageUrl = null,
                    gallerySource = gallerySource,
                    imageUnscrambler = plugin.imageUnscrambler,
                    preferOriginal = preferOriginal,
                    forceReload = true,
                )
            } else {
                throw error
            }
        }
    }

    suspend fun loadThumbnail(
        detail: GalleryDetail,
        pageIndex: Int,
        targetSizePx: Int,
        preferOriginal: Boolean = false,
        offlineOnly: Boolean = false,
        sourceRoot: String = "",
    ): RenderedGalleryPage = withContext(dispatchers.io) {
        val original = loadOriginalPage(
            detail = detail,
            pageIndex = pageIndex,
            preferOriginal = preferOriginal,
            forceReload = false,
            offlineOnly = offlineOnly,
            sourceRoot = sourceRoot,
        )
        val thumb = decodeThumbnailBitmap(original.bytes, targetSizePx)
            ?: decodeSampledBitmap(original.bytes, ImageStructureValidator.DEFAULT_MAX_DECODE_PIXELS)
        RenderedGalleryPage(original.page, thumb, original.fromCache)
    }

    private suspend fun loadOnlinePageBytes(
        siteId: SiteId,
        galleryUrl: String,
        detail: GalleryDetail,
        pageIndex: Int,
        hintPageUrl: String?,
        gallerySource: GallerySource,
        imageUnscrambler: ImageUnscrambler,
        preferOriginal: Boolean,
        forceReload: Boolean,
    ): OriginalGalleryPage {
        val cacheDir = readingCacheDir(siteId)
        val initialPageUrl = hintPageUrl
        if (!forceReload && !initialPageUrl.isNullOrBlank()) {
            val cached = readSavedPage(cacheDir, initialPageUrl, expectedIndex = pageIndex, json = json)
            if (cached != null && cached.isValid(requireAndroidImageDecoder)) {
                val bytes = readBytesBounded(cached.file, MAX_GALLERY_IMAGE_BYTES)
                if (bytes != null) {
                    cached.file.setLastModified(System.currentTimeMillis())
                    rememberCacheLink(siteId, galleryUrl, cached.page)
                    return OriginalGalleryPage(cached.page, bytes, fromCache = true)
                }
            }
        }

        // Resolve page metadata via GallerySource
        val resolvedPage = if (!initialPageUrl.isNullOrBlank()) {
            val previous = if (forceReload) {
                readSavedPage(cacheDir, initialPageUrl, expectedIndex = pageIndex, json = json)?.page
            } else {
                null
            }
            val reloadUrl = if (forceReload && previous != null) {
                gallerySource.reloadUrlOnError(SiteException.Network("用户手动重试", siteId = siteId), previous)
                    ?: initialPageUrl
            } else {
                initialPageUrl
            }
            gallerySource.resolvePageImage(reloadUrl, preferOriginal = preferOriginal, forceReload = forceReload)
        } else {
            val page = gallerySource.loadGalleryPage(detail, pageIndex, forceReload = forceReload)
            if (page.imageUrl.isBlank()) {
                gallerySource.resolvePageImage(page.pageUrl, preferOriginal = preferOriginal, forceReload = forceReload)
            } else {
                page
            }
        }
        check(resolvedPage.index == pageIndex) { "原站返回了错误的图页编号（期望 $pageIndex，实际 ${resolvedPage.index}）" }

        val (finalPage, finalBytes) = fetchAndUnscrambleWithReload(
            siteId = siteId,
            initialPage = resolvedPage,
            gallerySource = gallerySource,
            imageUnscrambler = imageUnscrambler,
            preferOriginal = preferOriginal,
            refererHint = detail.item.extra["imageReferer"],
        )

        val canonicalPageUrl = initialPageUrl ?: finalPage.pageUrl
        val normalizedPage = finalPage.copy(pageUrl = canonicalPageUrl)
        val key = StorageRootManager.sha256Hex(canonicalPageUrl.substringBefore('?'))
        val imageFile = File(cacheDir, "$key.image")
        val metaFile = File(cacheDir, "$key.page.json")
        runCatching {
            atomicWriteBytes(imageFile, finalBytes)
            atomicWriteBytes(
                metaFile,
                json.encodeToString(SavedPageMeta.serializer(), SavedPageMeta(normalizedPage, finalBytes.size.toLong())).toByteArray(),
            )
            rememberCacheLink(siteId, galleryUrl, normalizedPage)
            trimReadingCache(cacheDir)
        }
        return OriginalGalleryPage(normalizedPage, finalBytes, fromCache = false)
    }

    // ---- Permanent Download Operations (used by GalleryDownloadEngine) ----

    suspend fun prepareDownload(
        item: MediaItem,
        layout: StorageLayout = downloadLayout(),
    ): GalleryDetail = withContext(dispatchers.io) {
        val folder = bookFolder(item.siteId, item.id, layout)
        folder.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        val existing = readDiskBook(folder, expectedSiteId = item.siteId, expectedMediaId = item.id)?.detail
        val detail = existing ?: run {
            val plugin = siteRegistryProvider().require(item.siteId)
            val source = plugin.gallerySource
                ?: throw SiteException.Unsupported("站点 ${item.siteId.value} 不支持图集下载", siteId = item.siteId)
            source.loadGalleryDetail(item.url)
        }
        check(detail.pageCount in 1..MAX_PAGE_NUMBER) { "图集页数无效：${detail.pageCount}" }
        if (existing == null) {
            atomicWriteBytes(File(folder, "book.json"), json.encodeToString(GalleryDetail.serializer(), detail).toByteArray())
        }
        if (layout.location == com.raven.app.core.model.StorageLocation.SHARED) {
            storageRootManager.noteSharedGallery("${item.siteId.value}/${folder.name}")
        }
        detail
    }

    /**
     * Downloads and commits a single gallery page atomically without allocating a full decoded bitmap.
     */
    suspend fun downloadPage(
        detail: GalleryDetail,
        pageIndex: Int,
        layout: StorageLayout = downloadLayout(),
        preferOriginal: Boolean = false,
    ): Pair<GalleryPage, Long> = withContext(dispatchers.io) {
        val item = detail.item
        val siteId = item.siteId
        val folder = bookFolder(siteId, item.id, layout)
        val plugin = siteRegistryProvider().require(siteId)
        val gallerySource = plugin.gallerySource
            ?: throw SiteException.Unsupported("站点 ${siteId.value} 不支持图集下载", siteId = siteId)

        val existingLink = readLinksMap(folder)[pageIndex]
            ?: detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl
        if (!existingLink.isNullOrBlank()) {
            val saved = readSavedPage(folder, existingLink, expectedIndex = pageIndex, json = json)
            if (saved != null && saved.isValid(requireAndroidImageDecoder)) {
                rememberDownloadLink(folder, saved.page)
                return@withContext saved.page to saved.file.length()
            }
        }

        // Reuse already-cached reading copy if valid.
        val cacheDir = readingCacheDir(siteId)
        val cachedPage = existingLink?.let { readSavedPage(cacheDir, it, expectedIndex = pageIndex, json = json) }
            ?.takeIf { it.isValid(requireAndroidImageDecoder) }
        val cachedBytes = cachedPage?.let { readBytesBounded(it.file, MAX_GALLERY_IMAGE_BYTES) }

        val (finalPage, bytes) = if (cachedPage != null && cachedBytes != null) {
            cachedPage.page to cachedBytes
        } else {
            val resolved = if (!existingLink.isNullOrBlank()) {
                val inline = detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }
                inline ?: gallerySource.resolvePageImage(existingLink, preferOriginal = preferOriginal, forceReload = false)
            } else {
                val loaded = gallerySource.loadGalleryPage(detail, pageIndex, forceReload = false)
                if (loaded.imageUrl.isBlank()) {
                    gallerySource.resolvePageImage(loaded.pageUrl, preferOriginal = preferOriginal, forceReload = false)
                } else {
                    loaded
                }
            }
            check(resolved.index == pageIndex) { "原站返回了错误的图页编号（期望 $pageIndex，实际 ${resolved.index}）" }
            fetchAndUnscrambleWithReload(
                siteId = siteId,
                initialPage = resolved,
                gallerySource = gallerySource,
                imageUnscrambler = plugin.imageUnscrambler,
                preferOriginal = preferOriginal,
                refererHint = detail.item.extra["imageReferer"],
            )
        }

        val canonicalPageUrl = existingLink ?: finalPage.pageUrl
        val normalized = finalPage.copy(pageUrl = canonicalPageUrl)
        val key = StorageRootManager.sha256Hex(canonicalPageUrl.substringBefore('?'))
        atomicWriteBytes(File(folder, "$key.image"), bytes)
        atomicWriteBytes(
            File(folder, "$key.page.json"),
            json.encodeToString(SavedPageMeta.serializer(), SavedPageMeta(normalized, bytes.size.toLong())).toByteArray(),
        )
        rememberDownloadLink(folder, normalized)
        normalized to bytes.size.toLong()
    }

    suspend fun auditDownload(
        siteId: SiteId,
        mediaId: String,
        sourceRoot: String = "",
    ): GalleryAudit = withContext(dispatchers.io) {
        val layout = storageRootManager.resolveSourceLayout(sourceRoot.ifBlank { resolveReadingRoot(siteId, mediaId) })
        val folder = bookFolder(siteId, mediaId, layout)
        val diskBook = readDiskBook(folder, expectedSiteId = siteId, expectedMediaId = mediaId)
            ?: error("图集下载信息缺失，无法检查完整性")
        val ctx = currentCoroutineContext()
        diskBook.audit { ctx.ensureActive() }
    }

    suspend fun auditWriteTarget(
        siteId: SiteId,
        mediaId: String,
        layout: StorageLayout = downloadLayout(),
    ): GalleryAudit? = withContext(dispatchers.io) {
        val folder = bookFolder(siteId, mediaId, layout)
        val diskBook = readDiskBook(folder, expectedSiteId = siteId, expectedMediaId = mediaId) ?: return@withContext null
        val ctx = currentCoroutineContext()
        diskBook.audit { ctx.ensureActive() }
    }

    suspend fun commitDownload(
        siteId: SiteId,
        mediaId: String,
        layout: StorageLayout,
        requestedPages: Set<Int>,
    ): GalleryAudit = withContext(dispatchers.io) {
        val folder = bookFolder(siteId, mediaId, layout)
        val diskBook = readDiskBook(folder, expectedSiteId = siteId, expectedMediaId = mediaId)
            ?: error("图集下载信息缺失，无法提交完成状态")
        val ctx = currentCoroutineContext()
        val audit = diskBook.audit { ctx.ensureActive() }
        check(audit.validPages.toSet().containsAll(requestedPages)) { "图集下载尚未完整保存" }
        ctx.ensureActive()

        if (audit.isComplete) {
            atomicWriteBytes(File(folder, "readme.txt"), RECOVERY_NOTE.toByteArray())
            val selFile = File(folder, "selection.json")
            if (selFile.exists() && !selFile.delete()) {
                throw GalleryStorageException("无法移除旧选页记录")
            }
            atomicWriteBytes(File(folder, "complete"), audit.totalPages.toString().toByteArray())
        } else {
            val manifest = SelectionManifest(selected = requestedPages.sorted(), completed = audit.validPages)
            atomicWriteBytes(
                File(folder, "selection.json"),
                json.encodeToString(SelectionManifest.serializer(), manifest).toByteArray(),
            )
            File(folder, "complete").delete()
            atomicWriteBytes(
                File(folder, "readme.txt"),
                ("此图集为部分保存，有效图页 ${audit.validPages.size} / ${audit.totalPages}。\n\n$RECOVERY_NOTE").toByteArray(),
            )
        }
        audit
    }

    suspend fun removeDownload(
        siteId: SiteId,
        mediaId: String,
        sourceRoot: String = "",
        knownRoot: String = "",
    ) = withContext(dispatchers.io) {
        val relative = "${siteId.value}/${StorageRootManager.sha256Hex(mediaId)}"
        val layouts = storageRootManager.galleryRemovalLayouts(sourceRoot, knownRoot, relative)
        for (layout in layouts) {
            val folder = bookFolder(siteId, mediaId, layout)
            check(!folder.exists() || folder.deleteRecursively()) { "部分下载图页无法删除，请重试" }
            if (layout.location == com.raven.app.core.model.StorageLocation.SHARED) {
                storageRootManager.forgetSharedGallery(relative)
            }
        }
        storageRootManager.removeComicSidecar(siteId, mediaId, sourceRoot)
    }

    fun inspectDiskBookForRecovery(
        siteId: SiteId,
        folder: File,
        sourceRoot: String,
    ): DiskSidecarRecord? {
        val diskBook = readDiskBook(folder, expectedSiteId = siteId) ?: return null
        val audit = diskBook.audit()
        val isWhole = diskBook.hasCompleteMarker && audit.isComplete
        val intactSelection = if (isWhole) {
            emptyList()
        } else {
            diskBook.selection.takeIf { it.isNotEmpty() && audit.validPages.toSet().containsAll(it) }.orEmpty()
        }
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
            status = if (isWhole) DownloadStatus.COMPLETED else DownloadStatus.PAUSED,
            detail = if (isWhole) "" else "已保存 ${audit.validPages.size} / ${audit.totalPages} 页",
            sourceRoot = sourceRoot,
            intactSelection = intactSelection,
        )
    }

    fun duplicateBookCandidates(shouldStop: () -> Boolean = { false }): List<DuplicateBookCandidate> = buildList {
        for (layout in storageRootManager.readableLayouts()) {
            if (!StorageRootManager.directChild(layout.root, layout.comicsDir)) continue
            for (siteDir in layout.comicsDir.listFiles().orEmpty()) {
                if (shouldStop()) return emptyList()
                if (!siteDir.isDirectory || !StorageRootManager.directChild(layout.comicsDir, siteDir)) continue
                val siteId = runCatching { SiteId.of(siteDir.name) }.getOrNull()?.takeIf { it.isKnown } ?: continue
                for (folder in siteDir.listFiles().orEmpty()) {
                    if (shouldStop()) return emptyList()
                    if (!folder.isDirectory || !StorageRootManager.directChild(siteDir, folder)) continue
                    val diskBook = readDiskBook(folder, expectedSiteId = siteId) ?: continue
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

    // ---- Cache Inspection & Clearing ----

    fun readingCacheStats(siteId: SiteId): Pair<Int, Long> {
        val files = readingCacheImages(readingCacheDir(siteId))
        val uniquePages = files.distinctBy { it.nameWithoutExtension }.size
        return uniquePages to files.sumOf(File::length)
    }

    fun clearReadingCache(siteId: SiteId) {
        readingCacheDir(siteId).listFiles().orEmpty()
            .filter { it.isFile }
            .forEach { file -> check(file.delete()) { "部分图页缓存无法删除，请重试" } }
    }

    fun clearAllReadingCaches() {
        readingCacheBaseDir.listFiles().orEmpty().forEach { siteDir ->
            if (siteDir.isDirectory) {
                siteDir.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            }
        }
    }

    // ---- Network Fetch + EH ?nl= Error Recovery + JMComic Unscrambling ----

    private suspend fun fetchAndUnscrambleWithReload(
        siteId: SiteId,
        initialPage: GalleryPage,
        gallerySource: GallerySource,
        imageUnscrambler: ImageUnscrambler,
        preferOriginal: Boolean,
        refererHint: String?,
    ): Pair<GalleryPage, ByteArray> {
        var currentPage = initialPage
        val rawBytes = try {
            fetchImageBytes(siteId, currentPage, gallerySource, preferOriginal, refererHint)
        } catch (ce: CancellationException) {
            throw ce
        } catch (cause: SiteException) {
            val reloadUrl = gallerySource.reloadUrlOnError(cause, currentPage)
                ?.takeIf { it.isNotBlank() }
                ?: throw cause
            currentPage = gallerySource.resolvePageImage(reloadUrl, preferOriginal = preferOriginal, forceReload = true)
            check(currentPage.index == initialPage.index) { "刷新图源返回了错误的图页编号" }
            fetchImageBytes(siteId, currentPage, gallerySource, preferOriginal, refererHint)
        }

        val finalBytes = if (unscrambler.needsUnscramble(currentPage, imageUnscrambler)) {
            check(ImageStructureValidator.isValidImageBytes(rawBytes, MAX_GALLERY_IMAGE_BYTES, requireAndroidImageDecoder)) {
                "分条图页已损坏，请重试"
            }
            unscrambler.unscrambleToBytes(rawBytes, currentPage, imageUnscrambler)
        } else {
            rawBytes
        }

        check(ImageStructureValidator.isValidImageBytes(finalBytes, MAX_GALLERY_IMAGE_BYTES, requireAndroidImageDecoder)) {
            "图源未返回可识别的完整图片，请重试此页"
        }
        return currentPage to finalBytes
    }

    private suspend fun fetchImageBytes(
        siteId: SiteId,
        page: GalleryPage,
        gallerySource: GallerySource,
        preferOriginal: Boolean,
        refererHint: String?,
    ): ByteArray {
        val targetUrl = if (preferOriginal && !page.originalImageUrl.isNullOrBlank()) {
            page.originalImageUrl!!
        } else {
            page.imageUrl
        }
        val parsedTarget = targetUrl.toHttpUrlOrNull()
            ?: throw SiteException.Network("无效的图片地址：$targetUrl", siteId = siteId)
        val referer = refererHint?.takeIf { it.isNotBlank() }
            ?: page.pageUrl.substringBefore('#').takeIf { it.startsWith("http", ignoreCase = true) }

        val reqBuilder = Request.Builder()
            .url(parsedTarget)
            .tag(
                SiteRequestTag::class.java,
                SiteRequestTag(
                    siteId = siteId,
                    siteRequest = SiteHttpRequest(
                        url = targetUrl,
                        siteId = siteId,
                        kind = SiteRequestKind.IMAGE,
                    ),
                ),
            )
            .header("Cache-Control", "no-store")
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }

        val response = try {
            executeCancellable(imageClient.newCall(reqBuilder.build()))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            throw SiteHttpErrorMapper.fromThrowable(e, siteId)
        }

        return response.use { res ->
            if (gallerySource.rejectCrossHostImageRedirect &&
                !res.request.url.host.equals(parsedTarget.host, ignoreCase = true)
            ) {
                throw SiteException.Network(
                    message = "图片发生跨站重定向 (${parsedTarget.host} -> ${res.request.url.host})",
                    siteId = siteId,
                )
            }
            if (!res.isSuccessful) {
                val retryAfter = com.raven.app.core.media.download.DownloadErrorClassifier
                    .parseRetryAfterMs(res.header("Retry-After"))
                    ?.let { it / 1_000L }
                throw when (res.code) {
                    401 -> SiteException.AuthRequired("图片需要登录访问 (HTTP 401)", siteId = siteId)
                    403 -> SiteException.Forbidden(isChallenge = false, message = "图片访问被拒绝 (HTTP 403)", siteId = siteId)
                    404, 410 -> SiteException.NotFound("图片已被移除 (HTTP ${res.code})", siteId = siteId)
                    429 -> SiteException.RateLimited(retryAfterSeconds = retryAfter, message = "图片请求过于频繁 (HTTP 429)", siteId = siteId)
                    509 -> SiteException.QuotaExceeded("原站图片配额已用尽 (HTTP 509)", siteId = siteId)
                    else -> SiteException.Network("图片读取失败：HTTP ${res.code}", siteId = siteId)
                }
            }
            val body = res.body ?: throw SiteException.Network("图片响应为空", siteId = siteId)
            body.byteStream().use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.read(buf)
                    if (count < 0) break
                    if (out.size() + count > MAX_GALLERY_IMAGE_BYTES) {
                        throw SiteException.ParseError("单张图片超过 24 MiB 限制", siteId = siteId)
                    }
                    out.write(buf, 0, count)
                }
                out.toByteArray()
            }
        }
    }

    private suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                    } else {
                        cont.resume(response)
                    }
                }
            })
        }

    // ---- Disk Helpers ----

    private fun decodeSampledBitmap(bytes: ByteArray, maxPixels: Long): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图源未返回可识别的图片，请重试此页" }
        val sample = ImageStructureValidator.computeInSampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("图片解码失败，请重试此页")
    }

    private fun decodeThumbnailBitmap(bytes: ByteArray, targetSizePx: Int): Bitmap? {
        if (targetSizePx <= 0) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val width = opts.outWidth
        val height = opts.outHeight
        if (width <= 0 || height <= 0) return null
        val longEdge = maxOf(width, height)
        opts.inJustDecodeBounds = false
        opts.inSampleSize = ImageStructureValidator.computeThumbnailSampleSize(longEdge, targetSizePx)
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val dstW = ImageStructureValidator.computeThumbnailEdge(width, longEdge, targetSizePx)
        val dstH = ImageStructureValidator.computeThumbnailEdge(height, longEdge, targetSizePx)
        if (sampled.width == dstW && sampled.height == dstH) return sampled
        return try {
            sampled.scale(dstW, dstH, true)
        } finally {
            sampled.recycle()
        }
    }

    private fun readDiskBook(
        folder: File,
        expectedSiteId: SiteId? = null,
        expectedMediaId: String? = null,
    ): DiskBook? {
        val detail = readJsonFile(File(folder, "book.json"), GalleryDetail.serializer(), MAX_BOOK_JSON_BYTES)
            ?: return null
        if (detail.pageCount !in 1..MAX_PAGE_NUMBER) return null
        if (expectedSiteId != null && detail.item.siteId != expectedSiteId) return null
        if (expectedMediaId != null && detail.item.id != expectedMediaId) return null
        if (expectedSiteId != null && folder.name != StorageRootManager.sha256Hex(detail.item.id)) return null

        val selection = readJsonFile(File(folder, "selection.json"), SelectionManifest.serializer(), MAX_BOOK_JSON_BYTES)
            ?.selected.orEmpty().distinct().sorted()
        val completeFile = File(folder, "complete")
        val markerCount = runCatching {
            if (!completeFile.isFile || completeFile.length() !in 1L..32L || !StorageRootManager.directChild(folder, completeFile)) {
                null
            } else {
                completeFile.readText().trim().toIntOrNull()
            }
        }.getOrNull()

        return DiskBook(
            folder = folder,
            detail = detail,
            links = readLinksMap(folder),
            selection = selection,
            hasCompleteMarker = markerCount == detail.pageCount,
            json = json,
            requireAndroidDecoder = requireAndroidImageDecoder,
        )
    }

    private fun readLinksMap(folder: File): Map<Int, String> =
        readJsonFile(File(folder, "links.json"), KnownPageLinks.serializer(), MAX_BOOK_JSON_BYTES)
            ?.links.orEmpty()

    @Synchronized
    private fun rememberDownloadLink(folder: File, page: GalleryPage) {
        val file = File(folder, "links.json")
        val current = readLinksMap(folder)
        if (current[page.index] != page.pageUrl) {
            atomicWriteBytes(
                file,
                json.encodeToString(KnownPageLinks.serializer(), KnownPageLinks(current + (page.index to page.pageUrl))).toByteArray(),
            )
        }
    }

    @Synchronized
    private fun rememberCacheLink(siteId: SiteId, galleryUrl: String, page: GalleryPage) {
        if (galleryUrl.isBlank()) return
        val file = File(readingCacheDir(siteId), "${StorageRootManager.sha256Hex(galleryUrl)}.links.json")
        val current = readJsonFile(file, KnownPageLinks.serializer(), MAX_BOOK_JSON_BYTES)?.links.orEmpty()
        if (current[page.index] != page.pageUrl) {
            atomicWriteBytes(
                file,
                json.encodeToString(KnownPageLinks.serializer(), KnownPageLinks(current + (page.index to page.pageUrl))).toByteArray(),
            )
        }
    }

    private fun <T> readJsonFile(
        file: File,
        serializer: kotlinx.serialization.KSerializer<T>,
        maxBytes: Long,
    ): T? = Companion.readJsonFile(file, serializer, maxBytes, json)

    private fun atomicWriteBytes(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw GalleryStorageException("无效的文件路径：${target.path}")
        val temp = File(parent, "${target.name}.${System.nanoTime()}.part")
        try {
            check(parent.isDirectory || parent.mkdirs()) { "无法创建目录：${parent.path}" }
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "无法原子写入文件：${target.name}" }
            }
        } catch (e: Exception) {
            throw GalleryStorageException(e.message ?: "无法保存图页文件", e)
        } finally {
            temp.delete()
        }
    }

    private fun readBytesBounded(file: File, maxBytes: Long): ByteArray? = runCatching {
        val len = file.length()
        if (!file.isFile || len !in 1L..maxBytes) return null
        file.inputStream().use { input ->
            val bytes = ByteArray(len.toInt())
            DataInputStream(input).readFully(bytes)
            bytes.takeIf { input.read() == -1 }
        }
    }.getOrNull()

    private fun readingCacheImages(dir: File): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && (it.extension == "image" || it.extension == "strips") }

    private fun trimReadingCache(dir: File) {
        val files = readingCacheImages(dir).sortedBy { it.lastModified() }
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= READING_CACHE_MAX_BYTES) break
            val len = file.length()
            if (file.delete()) {
                total -= len
                File(dir, "${file.nameWithoutExtension}.page.json").delete()
            }
        }
    }

    companion object {
        const val READING_CACHE_MAX_BYTES: Long = 256L * 1024L * 1024L
        const val MAX_GALLERY_IMAGE_BYTES: Long = ImageStructureValidator.DEFAULT_MAX_GALLERY_IMAGE_BYTES
        private const val MAX_PAGE_NUMBER: Int = 100_000
        private const val MAX_BOOK_JSON_BYTES: Long = 16L * 1024L * 1024L
        private const val MAX_PAGE_JSON_BYTES: Long = 128L * 1024L

        const val RECOVERY_NOTE: String =
            "本文件夹是一份已下载的图集（漫画或写真），由 Raven（渡鸦）保存。\n\n" +
                "book.json  详情与页数\n" +
                "links.json 页码与图源的对应关系\n" +
                "*.image    原始图页（按页保存）\n" +
                "complete   整本下载完成标记\n\n" +
                "把整个文件夹放回 Raven/comics/<站点>/ 之下，应用重装后可自动识别恢复。"

        private fun <T> readJsonFile(
            file: File,
            serializer: kotlinx.serialization.KSerializer<T>,
            maxBytes: Long,
            json: Json,
        ): T? = runCatching {
            val parent = file.parentFile ?: return null
            if (!file.isFile || file.length() !in 1L..maxBytes || !StorageRootManager.directChild(parent, file)) {
                return null
            }
            json.decodeFromString(serializer, file.readText())
        }.getOrNull()

        internal fun readSavedPage(
            folder: File,
            pageUrl: String,
            expectedIndex: Int?,
            json: Json,
        ): SavedPageFile? = runCatching {
            if (pageUrl.isBlank()) return null
            val key = StorageRootManager.sha256Hex(pageUrl.substringBefore('?'))
            val metaFile = File(folder, "$key.page.json")
            val meta = readJsonFile(metaFile, SavedPageMeta.serializer(), MAX_PAGE_JSON_BYTES, json) ?: return null
            if (meta.page.pageUrl != pageUrl ||
                meta.page.index !in 1..MAX_PAGE_NUMBER ||
                (expectedIndex != null && meta.page.index != expectedIndex) ||
                meta.encodedBytes !in 1L..MAX_GALLERY_IMAGE_BYTES
            ) {
                return null
            }
            val imageFile = File(folder, "$key.image")
            if (!StorageRootManager.directChild(folder, imageFile)) return null
            SavedPageFile(meta.page, imageFile, meta.encodedBytes)
        }.getOrNull()
    }
}
