package com.raven.app.core.media.reader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.media.storage.LocalLibraryImporter
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class ExportMode {
    SAVE_IMAGES,
    SHARE_IMAGES,
    CBZ,
}

data class ExportFile(
    val file: File,
    val mimeType: String,
)

data class PreparedExport(
    val folder: File,
    val files: List<ExportFile>,
    val mode: ExportMode,
)

data class SafSaveOutcome(
    val savedCount: Int,
    val deletedIncompleteCount: Int = 0,
    val remainingIncompleteCount: Int = 0,
)

/**
 * Prepares and delivers gallery exports:
 * - Save original image(s) via SAF `OutputStream` / `DocumentsContract` tree (`<= 2,000` pages / `1 GiB`)
 * - Share via `FileProvider` URIs (`<= 100` pages)
 * - Export full or selected-pages `.cbz` archive (`<= 2,000` pages / `1 GiB`) with atomic failure cleanup.
 */
class GalleryExporter(
    private val stagingRoot: File,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val requireAndroidImageDecoder: Boolean = true,
) {
    constructor(
        context: Context,
        dispatchers: AppDispatchers = DefaultAppDispatchers(),
    ) : this(
        stagingRoot = File(context.applicationContext.cacheDir, "raven-exports"),
        dispatchers = dispatchers,
        requireAndroidImageDecoder = true,
    )

    suspend fun prepare(
        title: String,
        pages: List<Int>,
        mode: ExportMode,
        loadPageBytes: suspend (pageIndex: Int) -> ByteArray,
        onProgress: (String) -> Unit = {},
    ): PreparedExport = withContext(dispatchers.io) {
        val uniquePages = pages.distinct().sorted()
        require(uniquePages.isNotEmpty() && uniquePages.size <= MAX_EXPORT_PAGES && uniquePages.all { it > 0 }) {
            "单次导出页数应在 1..$MAX_EXPORT_PAGES 之间"
        }
        require(mode != ExportMode.SHARE_IMAGES || uniquePages.size <= MAX_SHARE_PAGES) {
            "单次最多分享 $MAX_SHARE_PAGES 张图片，请分批选择"
        }

        stagingRoot.mkdirs()
        pruneStaleStagingFolders()

        val folder = File(stagingRoot, UUID.randomUUID().toString()).apply {
            check(mkdirs()) { "无法创建导出临时目录" }
        }

        try {
            var totalBytes = 0L
            val outputs = mutableListOf<ExportFile>()
            val safeTitle = safeFileBaseName(title)
            val cbzFile = if (mode == ExportMode.CBZ) File(folder, "$safeTitle.cbz") else null
            val zipOut = cbzFile?.let { ZipOutputStream(it.outputStream().buffered()) }

            zipOut.use { zip ->
                uniquePages.forEachIndexed { idx, pageNumber ->
                    currentCoroutineContext().ensureActive()
                    onProgress("正在准备 ${idx + 1} / ${uniquePages.size} · 第 $pageNumber 页")
                    val bytes = loadPageBytes(pageNumber)
                    require(bytes.size.toLong() <= MAX_SINGLE_IMAGE_BYTES) { "单张图片超过 50 MiB 上限" }
                    totalBytes += bytes.size
                    check(totalBytes <= MAX_EXPORT_TOTAL_BYTES) { "本次导出总大小超过 1 GiB 上限，请分批导出" }

                    check(ImageStructureValidator.isValidImageBytes(bytes, MAX_SINGLE_IMAGE_BYTES, requireAndroidImageDecoder)) {
                        "第 $pageNumber 页不是有效的完整图片"
                    }
                    val mime = ImageStructureValidator.detectContainerMime(bytes)
                        ?: error("第 $pageNumber 页无法识别图片格式")
                    if (mime == "image/heic") {
                        require(ImageStructureValidator.detectHeifContainerMime(bytes) != null) {
                            "HEIF / AVIF 图页不是有效的 ISO 容器"
                        }
                    }
                    val ext = ImageStructureValidator.extensionForMime(mime, bytes)
                    val entryName = String.format(Locale.ROOT, "%06d.%s", pageNumber, ext)
                    if (zip != null) {
                        zip.putNextEntry(ZipEntry(entryName))
                        writeChunked(bytes, zip)
                        zip.closeEntry()
                    } else {
                        val outFile = File(folder, "$safeTitle-$entryName")
                        outFile.outputStream().use { writeChunked(bytes, it) }
                        outputs += ExportFile(outFile, mime)
                    }
                }
            }
            if (cbzFile != null) {
                outputs += ExportFile(cbzFile, CBZ_MIME_TYPE)
            }
            currentCoroutineContext().ensureActive()
            PreparedExport(folder = folder, files = outputs, mode = mode)
        } catch (error: Throwable) {
            removeStagingFolder(folder)
            throw error
        }
    }

    /**
     * Generates `FileProvider` content URIs for system sharing (`<= 100` files).
     */
    fun prepareShareUris(
        context: Context,
        authority: String,
        prepared: PreparedExport,
    ): List<Uri> {
        require(prepared.mode == ExportMode.SHARE_IMAGES) { "仅 SHARE_IMAGES 模式支持生成分享 URI" }
        require(prepared.files.size <= MAX_SHARE_PAGES) { "单次最多分享 $MAX_SHARE_PAGES 张图片" }
        return prepared.files.map { item ->
            FileProvider.getUriForFile(context, authority, item.file)
        }
    }

    /**
     * Copies a [PreparedExport] to user-selected SAF destination (`ACTION_CREATE_DOCUMENT` single URI
     * when [isTree] is false, or `ACTION_OPEN_DOCUMENT_TREE` directory when [isTree] is true).
     *
     * On error or cancellation, deletes ONLY newly created SAF child documents (never deleting a
     * pre-existing user file returned by a single-document picker) and always cleans up staging files.
     */
    suspend fun saveToSaf(
        prepared: PreparedExport,
        targetUri: Uri,
        isTree: Boolean,
        contentResolver: ContentResolver,
        onProgress: (String) -> Unit = {},
    ): SafSaveOutcome = withContext(dispatchers.io) {
        val createdUris = mutableListOf<Uri>()
        try {
            val parentDocUri = if (isTree) {
                DocumentsContract.buildDocumentUriUsingTree(
                    targetUri,
                    DocumentsContract.getTreeDocumentId(targetUri),
                )
            } else {
                require(prepared.files.size == 1) { "单文件保存模式仅支持 1 个输出文件" }
                null
            }

            prepared.files.forEachIndexed { index, exportFile ->
                currentCoroutineContext().ensureActive()
                onProgress("正在保存 ${index + 1} / ${prepared.files.size}")
                val destination = if (parentDocUri == null) {
                    targetUri
                } else {
                    DocumentsContract.createDocument(
                        contentResolver,
                        parentDocUri,
                        exportFile.mimeType,
                        exportFile.file.name,
                    )?.also(createdUris::add) ?: error("无法在目标目录创建文件：${exportFile.file.name}")
                }
                contentResolver.openOutputStream(destination, "wt")?.use { out ->
                    copyFileChunked(exportFile.file, out)
                } ?: error("目标位置无法写入")
            }
            SafSaveOutcome(savedCount = prepared.files.size)
        } catch (error: Throwable) {
            withContext(NonCancellable + dispatchers.io) {
                createdUris.forEach { uri ->
                    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                }
            }
            throw error
        } finally {
            withContext(NonCancellable + dispatchers.io) {
                runCatching { removeStagingFolder(prepared.folder) }
            }
        }
    }

    fun removeStagingFolder(folder: File) {
        val canonicalParent = folder.canonicalFile.parentFile
        check(canonicalParent == stagingRoot.canonicalFile) { "无效的导出临时目录" }
        folder.deleteRecursively()
    }

    private fun pruneStaleStagingFolders() {
        val cutoff = System.currentTimeMillis() - STALE_EXPORT_RETENTION_MS
        stagingRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.lastModified() < cutoff }
            .forEach { runCatching { removeStagingFolder(it) } }
    }

    companion object {
        const val MAX_EXPORT_PAGES: Int = LocalLibraryImporter.MAX_PAGES
        const val MAX_SHARE_PAGES: Int = 100
        const val MAX_SINGLE_IMAGE_BYTES: Long = LocalLibraryImporter.MAX_SINGLE_IMAGE_BYTES
        const val MAX_EXPORT_TOTAL_BYTES: Long = LocalLibraryImporter.MAX_BOOK_BYTES
        const val CBZ_MIME_TYPE: String = "application/vnd.comicbook+zip"
        private const val STALE_EXPORT_RETENTION_MS: Long = 2L * 86_400_000L

        fun safeFileBaseName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trim()
                .trim('.')
                .take(80)
                .ifBlank { "Raven" }

        private suspend fun writeChunked(bytes: ByteArray, output: OutputStream) {
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(64 * 1024, bytes.size - offset)
                output.write(bytes, offset, count)
                offset += count
            }
        }

        suspend fun copyFileChunked(file: File, output: OutputStream) {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}
