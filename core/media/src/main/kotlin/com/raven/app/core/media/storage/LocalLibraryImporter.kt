package com.raven.app.core.media.storage

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.common.NaturalSortComparator
import com.raven.app.core.media.reader.ImageStructureValidator
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Metadata manifest persisted as `book.json` inside each imported local comic/video folder.
 */
@Serializable
data class LocalBook(
    val id: String,
    val title: String,
    val files: List<String>,
    val bytes: Long,
    val importedAt: Long,
    val kind: MediaKind = MediaKind.COMIC,
    val thumbFileName: String = "",
)

/**
 * Safely imports user-selected CBZ/ZIP archives, image folders, and local videos into app-owned
 * storage with Zip-Bomb guards, natural sorting, SHA-256 deduplication, and `ravenlocal://` URIs.
 *
 * Archive entry paths are NEVER used as output file paths (eliminating Zip-Slip by construction).
 */
class LocalLibraryImporter(
    private val context: Context?,
    private val rootDir: File,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val requireAndroidImageDecoder: Boolean = true,
    private val freeSpaceProvider: (File) -> Long = { it.usableSpace },
    private val videoThumbExtractor: ((File, File) -> Boolean)? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        dispatchers: AppDispatchers = DefaultAppDispatchers(),
    ) : this(
        context = context.applicationContext,
        rootDir = File(context.applicationContext.filesDir, "local-library"),
        dispatchers = dispatchers,
        requireAndroidImageDecoder = true,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val libraryRoot: File
        get() = rootDir.apply { mkdirs() }

    private val _books = MutableStateFlow<List<LocalBook>>(emptyList())
    val books: StateFlow<List<LocalBook>> = _books.asStateFlow()

    init {
        refreshBooksSnapshot()
    }

    fun refreshBooksSnapshot(): List<LocalBook> {
        val loaded = listBooks()
        _books.value = loaded
        return loaded
    }

    fun listBooks(): List<LocalBook> =
        libraryRoot.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("incoming-") }
            .mapNotNull { getBook(it.name) }
            .sortedByDescending { it.importedAt }

    fun getBook(id: String): LocalBook? = runCatching {
        if (!HEX_64_REGEX.matches(id)) return null
        val dir = bookFolder(id)
        if (!StorageRootManager.directChild(libraryRoot, dir)) return null
        val manifest = File(dir, "book.json")
        if (!manifest.isFile || manifest.length() !in 1L..MAX_MANIFEST_BYTES || !StorageRootManager.directChild(dir, manifest)) {
            return null
        }
        val parsed = json.decodeFromString(LocalBook.serializer(), manifest.readText())
        if (parsed.id != id || parsed.title.isBlank() || parsed.files.size !in 1..MAX_PAGES) return null
        if (parsed.files.any { !StorageRootManager.directChild(dir, File(dir, it)) }) return null
        when (parsed.kind) {
            MediaKind.VIDEO -> parsed.takeIf {
                it.files.size == 1 &&
                    it.files[0].substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXTENSIONS &&
                    File(dir, it.files[0]).isFile &&
                    (it.thumbFileName.isBlank() || (it.thumbFileName == THUMB_FILE_NAME && StorageRootManager.directChild(dir, File(dir, THUMB_FILE_NAME))))
            }
            else -> parsed.takeIf {
                it.thumbFileName.isBlank() &&
                    it.files.all { name -> PAGE_FILE_REGEX.matches(name) && File(dir, name).isFile }
            }
        }
    }.getOrNull()

    fun getPageFile(bookId: String, pageIndex: Int): File {
        val book = getBook(bookId) ?: error("本地内容不存在，请重新导入")
        require(book.kind != MediaKind.VIDEO) { "视频条目不包含图页" }
        require(pageIndex in 1..book.files.size) { "页码超出范围：$pageIndex / ${book.files.size}" }
        return File(bookFolder(bookId), book.files[pageIndex - 1])
    }

    fun getVideoFile(bookId: String): File? {
        val book = getBook(bookId)?.takeIf { it.kind == MediaKind.VIDEO } ?: return null
        val file = File(bookFolder(bookId), book.files.single())
        return file.takeIf { it.isFile && it.canRead() && it.length() > 0L && (book.bytes <= 0L || it.length() == book.bytes) }
    }

    fun getThumbnailFile(bookId: String): File? {
        val book = getBook(bookId) ?: return null
        val file = when (book.kind) {
            MediaKind.VIDEO -> book.thumbFileName.takeIf { it.isNotBlank() }?.let { File(bookFolder(bookId), it) }
            else -> book.files.firstOrNull()?.let { File(bookFolder(bookId), it) }
        } ?: return null
        return file.takeIf { it.isFile && StorageRootManager.directChild(bookFolder(bookId), it) }
    }

    fun isLocalVideoComplete(bookId: String, path: String? = null): Boolean = runCatching {
        val book = getBook(bookId)?.takeIf { it.kind == MediaKind.VIDEO } ?: return false
        val imported = File(bookFolder(bookId), book.files.single()).canonicalFile
        val target = path?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile } ?: imported
        target == imported && target.isFile && target.canRead() && target.length() > 0L && book.bytes == target.length()
    }.getOrDefault(false)

    fun isLocalComicComplete(bookId: String): Boolean = runCatching {
        val book = getBook(bookId)?.takeIf { it.kind != MediaKind.VIDEO } ?: return false
        val dir = bookFolder(bookId)
        var sum = 0L
        for (name in book.files) {
            val file = File(dir, name)
            if (!file.isFile || !file.canRead() || file.length() <= 0L) return false
            if (!ImageStructureValidator.isValidImageFile(file, MAX_SINGLE_IMAGE_BYTES, requireAndroidImageDecoder)) {
                return false
            }
            sum += file.length()
        }
        book.bytes <= 0L || sum == book.bytes
    }.getOrDefault(false)

    fun toMediaItem(book: LocalBook): MediaItem {
        val coverPath = getThumbnailFile(book.id)?.let { file ->
            if (book.kind == MediaKind.VIDEO) file.absolutePath else file.toURI().toString()
        }.orEmpty()
        return MediaItem(
            siteId = SiteId.LOCAL,
            id = book.id,
            url = buildLocalUri(book.id),
            kind = book.kind,
            title = book.title,
            coverUrl = coverPath,
            pageCount = if (book.kind == MediaKind.VIDEO) null else book.files.size,
            extra = buildMap {
                put("bytes", book.bytes.toString())
                if (book.kind == MediaKind.VIDEO) {
                    getVideoFile(book.id)?.absolutePath?.let { put("localVideoPath", it) }
                }
            },
        )
    }

    suspend fun removeBook(bookId: String) = withContext(dispatchers.io) {
        val dir = bookFolder(bookId)
        safeDeleteDirectory(dir)
        refreshBooksSnapshot()
    }

    // ---- Android SAF Entry Points ----

    suspend fun importArchive(
        uri: Uri,
        onProgress: (String) -> Unit = {},
    ): MediaItem = withContext(dispatchers.io) {
        val resolver = checkNotNull(context?.contentResolver) { "Android Context required for SAF import" }
        val title = queryDisplayName(uri, fallback = "本地漫画").substringBeforeLast('.').take(MAX_TITLE_LENGTH)
        val book = resolver.openInputStream(uri)?.use { stream ->
            importArchiveStream(title = title, inputStream = stream, onProgress = onProgress)
        } ?: error("无法打开所选压缩包")
        toMediaItem(book)
    }

    suspend fun importDirectory(
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
    ): MediaItem = withContext(dispatchers.io) {
        val resolver = checkNotNull(context?.contentResolver) { "Android Context required for SAF import" }
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val collected = mutableListOf<Pair<String, Uri>>()
        var visited = 0

        suspend fun walk(docId: String, prefix: String, depth: Int) {
            check(depth <= MAX_DIRECTORY_DEPTH) { "目录层级超过 $MAX_DIRECTORY_DEPTH 层上限" }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    visited += 1
                    check(visited <= MAX_ARCHIVE_ENTRIES) { "目录条目超过 $MAX_ARCHIVE_ENTRIES 上限" }
                    val childId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2).orEmpty()
                    if (displayName.startsWith('.') || displayName == "__MACOSX") continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(childId, "$prefix$displayName/", depth + 1)
                    } else if (ImageStructureValidator.isSupportedImageName(displayName)) {
                        check(collected.size < MAX_PAGES) { "单本最多导入 $MAX_PAGES 页" }
                        collected += "$prefix$displayName" to DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    }
                }
            } ?: error("无法读取所选目录")
        }

        walk(rootDocId, "", 0)
        val title = queryDisplayName(rootDocUri, fallback = "本地漫画").take(MAX_TITLE_LENGTH)
        val book = buildComicBook(title = title, onProgress = onProgress) { addEntry ->
            for ((label, docUri) in collected) {
                resolver.openInputStream(docUri)?.use { stream ->
                    addEntry(label, stream)
                } ?: error("无法读取 $label")
            }
        }
        toMediaItem(book)
    }

    suspend fun importVideo(
        uri: Uri,
        onProgress: (String) -> Unit = {},
    ): MediaItem = withContext(dispatchers.io) {
        val resolver = checkNotNull(context?.contentResolver) { "Android Context required for SAF import" }
        val label = queryDisplayName(uri, fallback = "本地视频.mp4")
        val ext = label.substringAfterLast('.', "").lowercase(Locale.ROOT).ifBlank { "mp4" }
        val title = label.substringBeforeLast('.').take(MAX_TITLE_LENGTH)
        val book = resolver.openInputStream(uri)?.use { stream ->
            importVideoStream(title = title, extension = ext, inputStream = stream, onProgress = onProgress)
        } ?: error("无法打开所选视频")
        toMediaItem(book)
    }

    // ---- Pure-Stream / Pure-File Implementations (JVM unit-testable) ----

    /**
     * Imports a CBZ/ZIP archive stream with strict Zip-Bomb & hidden-file guards:
     * - `<= 10,000` entries total
     * - `<= 2,000` image pages
     * - `<= 50 MiB` per entry
     * - `<= 1 GiB` total unpacked bytes (including ignored entries)
     * - Ignores `__MACOSX/` and dotfiles (`.` segments)
     * - Natural-sorted page ordering and SHA-256 content deduplication.
     */
    suspend fun importArchiveStream(
        title: String,
        inputStream: InputStream,
        maxEntries: Int = MAX_ARCHIVE_ENTRIES,
        maxPages: Int = MAX_PAGES,
        maxSingleImageBytes: Long = MAX_SINGLE_IMAGE_BYTES,
        maxBookBytes: Long = MAX_BOOK_BYTES,
        onProgress: (String) -> Unit = {},
    ): LocalBook = withContext(dispatchers.io) {
        buildComicBook(
            title = title,
            maxPages = maxPages,
            maxSingleImageBytes = maxSingleImageBytes,
            maxBookBytes = maxBookBytes,
            onProgress = onProgress,
        ) { addEntry ->
            ZipInputStream(inputStream.buffered()).use { zip ->
                var visited = 0
                var totalUnpacked = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    visited += 1
                    check(visited <= maxEntries) { "压缩包条目过多（上限 $maxEntries）" }

                    var entryBytes = 0L
                    val bounded = object : FilterInputStream(zip) {
                        private fun track(readCount: Int): Int {
                            if (readCount > 0) {
                                entryBytes += readCount
                                totalUnpacked += readCount
                                check(entryBytes <= maxSingleImageBytes && totalUnpacked <= maxBookBytes) {
                                    "压缩包解压大小超过上限（单页 ${maxSingleImageBytes / (1024 * 1024)} MiB，整本 ${maxBookBytes / (1024 * 1024)} MiB）"
                                }
                            }
                            return readCount
                        }

                        override fun read(): Int = super.read().also { if (it >= 0) track(1) }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = track(zip.read(b, off, len))
                    }

                    if (!entry.isDirectory && shouldIncludeArchiveEntry(entry.name)) {
                        addEntry(entry.name, bounded)
                    } else {
                        val drainBuffer = ByteArray(64 * 1024)
                        while (bounded.read(drainBuffer) >= 0) {
                            currentCoroutineContext().ensureActive()
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Imports a local filesystem directory of images (depth `<= 8`), useful for testing and direct folders.
     */
    suspend fun importLocalDirectory(
        directory: File,
        title: String = directory.name,
        onProgress: (String) -> Unit = {},
    ): LocalBook = withContext(dispatchers.io) {
        require(directory.isDirectory) { "目标不是目录：${directory.path}" }
        val entries = mutableListOf<Pair<String, File>>()
        var visited = 0

        suspend fun walk(current: File, prefix: String, depth: Int) {
            check(depth <= MAX_DIRECTORY_DEPTH) { "目录层级超过 $MAX_DIRECTORY_DEPTH 层上限" }
            for (child in current.listFiles().orEmpty()) {
                currentCoroutineContext().ensureActive()
                visited += 1
                check(visited <= MAX_ARCHIVE_ENTRIES) { "目录条目超过 $MAX_ARCHIVE_ENTRIES 上限" }
                if (child.name.startsWith('.') || child.name == "__MACOSX") continue
                if (child.isDirectory) {
                    walk(child, "$prefix${child.name}/", depth + 1)
                } else if (child.isFile && ImageStructureValidator.isSupportedImageName(child.name)) {
                    check(entries.size < MAX_PAGES) { "单本最多导入 $MAX_PAGES 页" }
                    entries += "$prefix${child.name}" to child
                }
            }
        }

        walk(directory, "", 0)
        buildComicBook(title.take(MAX_TITLE_LENGTH), onProgress = onProgress) { addEntry ->
            for ((label, file) in entries) {
                file.inputStream().use { addEntry(label, it) }
            }
        }
    }

    /**
     * Imports a local video stream (`<= 8 GiB`, requiring `> 1 GiB` free space), generates `thumb.png`,
     * and deduplicates by whole-file SHA-256.
     */
    suspend fun importVideoStream(
        title: String,
        extension: String,
        inputStream: InputStream,
        maxVideoBytes: Long = MAX_VIDEO_BYTES,
        minFreeSpaceBytes: Long = MIN_FREE_SPACE_VIDEO_BYTES,
        onProgress: (String) -> Unit = {},
    ): LocalBook = withContext(dispatchers.io) {
        val ext = extension.trim().lowercase(Locale.ROOT).removePrefix(".")
        check(ext in VIDEO_EXTENSIONS) { "暂不支持该视频格式（.$ext）" }

        val staging = File(libraryRoot, "incoming-${UUID.randomUUID()}").apply {
            check(mkdirs()) { "无法创建视频导入临时目录" }
        }
        try {
            onProgress("正在复制视频文件…")
            val targetFile = File(staging, "video.$ext")
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            val buffer = ByteArray(1024 * 1024)
            targetFile.outputStream().buffered().use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = inputStream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    check(copied <= maxVideoBytes && freeSpaceProvider(libraryRoot) > minFreeSpaceBytes) {
                        "视频超过 8 GiB 上限或应用剩余存储空间不足（需 >1 GiB）"
                    }
                    if (copied % (64L * 1024L * 1024L) < buffer.size) {
                        onProgress("已复制 ${formatHumanBytes(copied)}")
                    }
                }
            }
            check(copied > 0L) { "所选视频没有内容" }

            val id = digest.digest().toHexString()
            getBook(id)?.let { return@withContext it }

            onProgress("正在生成首帧缩略图…")
            val thumbFile = File(staging, THUMB_FILE_NAME)
            val savedThumb = extractFirstFrameThumbnail(targetFile, thumbFile)
            val book = LocalBook(
                id = id,
                title = title.trim().take(MAX_TITLE_LENGTH).ifBlank { "本地视频" },
                files = listOf(targetFile.name),
                bytes = copied,
                importedAt = clockMs(),
                kind = MediaKind.VIDEO,
                thumbFileName = if (savedThumb) THUMB_FILE_NAME else "",
            )
            File(staging, "book.json").writeText(json.encodeToString(LocalBook.serializer(), book))
            val destination = bookFolder(id)
            check(!destination.exists()) { "同名本地内容不完整，请先移除后重试" }
            check(staging.renameTo(destination)) { "无法保存本地视频" }
            refreshBooksSnapshot()
            book
        } finally {
            if (staging.exists()) safeDeleteDirectory(staging)
        }
    }

    internal fun duplicateBookCandidates(shouldStop: () -> Boolean = { false }): List<DuplicateBookCandidate> =
        buildList {
            for (dir in libraryRoot.listFiles().orEmpty()) {
                if (shouldStop()) return emptyList()
                val book = getBook(dir.name)?.takeIf { it.kind != MediaKind.VIDEO } ?: continue
                add(
                    DuplicateBookCandidate(
                        source = DuplicateSource.LOCAL,
                        id = book.id,
                        siteId = SiteId.LOCAL,
                        mediaId = book.id,
                        title = book.title,
                        pageFiles = book.files.map { File(dir, it) },
                        validatePage = { file ->
                            StorageRootManager.directChild(dir, file) &&
                                ImageStructureValidator.isValidImageFile(file, MAX_SINGLE_IMAGE_BYTES, requireAndroidImageDecoder)
                        },
                        sourceRoot = libraryRoot.absolutePath,
                    ),
                )
            }
        }

    private suspend fun buildComicBook(
        title: String,
        maxPages: Int = MAX_PAGES,
        maxSingleImageBytes: Long = MAX_SINGLE_IMAGE_BYTES,
        maxBookBytes: Long = MAX_BOOK_BYTES,
        onProgress: (String) -> Unit,
        producer: suspend (addEntry: suspend (label: String, stream: InputStream) -> Unit) -> Unit,
    ): LocalBook {
        val staging = File(libraryRoot, "incoming-${UUID.randomUUID()}").apply {
            check(mkdirs()) { "无法创建导入临时目录" }
        }
        val pages = mutableListOf<Pair<String, File>>()
        var totalBytes = 0L
        try {
            producer { label, stream ->
                check(pages.size < maxPages) { "单本最多导入 $maxPages 页" }
                val rawFile = File(staging, "${pages.size}.image")
                var entryBytes = 0L
                val buffer = ByteArray(64 * 1024)
                rawFile.outputStream().buffered().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalBytes += count
                        check(
                            entryBytes <= maxSingleImageBytes &&
                                totalBytes <= maxBookBytes &&
                                freeSpaceProvider(libraryRoot) > MIN_FREE_SPACE_BOOK_BYTES,
                        ) { "导入空间不足或文件超过上限（单页 50 MiB，整本 1 GiB）" }
                        output.write(buffer, 0, count)
                    }
                }
                check(ImageStructureValidator.isValidImageFile(rawFile, maxSingleImageBytes, requireAndroidImageDecoder)) {
                    "$label 不是有效的完整图片"
                }
                pages += label to rawFile
                onProgress("已导入 ${pages.size} 页")
            }

            check(pages.isNotEmpty()) {
                "没有找到支持的图片（JPG、PNG、WebP、GIF、BMP、AVIF、HEIF、HEIC）"
            }

            val sorted = pages.sortedWith { a, b -> NaturalSortComparator.compare(a.first, b.first) }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            for ((_, file) in sorted) {
                digest.update(ByteBuffer.allocate(8).putLong(file.length()).array())
                file.inputStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
            }
            val id = digest.digest().toHexString()
            getBook(id)?.let { return it }

            // Two-pass rename prevents collisions between archive indices and sorted indices.
            sorted.forEachIndexed { index, (_, file) ->
                check(file.renameTo(File(staging, "$index.sorted"))) { "无法整理导入图页顺序" }
            }
            val finalNames = sorted.indices.map { "$it.image" }
            finalNames.forEachIndexed { index, name ->
                check(File(staging, "$index.sorted").renameTo(File(staging, name))) { "无法写入整理后的图页" }
            }

            val book = LocalBook(
                id = id,
                title = title.trim().take(MAX_TITLE_LENGTH).ifBlank { "本地漫画" },
                files = finalNames,
                bytes = totalBytes,
                importedAt = clockMs(),
                kind = MediaKind.COMIC,
            )
            File(staging, "book.json").writeText(json.encodeToString(LocalBook.serializer(), book))
            val destination = bookFolder(id)
            check(!destination.exists()) { "同名本地内容不完整，请先移除后重试" }
            check(staging.renameTo(destination)) { "无法保存本地导入" }
            refreshBooksSnapshot()
            return book
        } finally {
            if (staging.exists()) safeDeleteDirectory(staging)
        }
    }

    private fun extractFirstFrameThumbnail(videoFile: File, thumbFile: File): Boolean {
        videoThumbExtractor?.let { return runCatching { it(videoFile, thumbFile) }.getOrDefault(false) }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val frame = runCatching {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: return false
            try {
                thumbFile.outputStream().use { out ->
                    frame.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            } finally {
                frame.recycle()
            }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryDisplayName(uri: Uri, fallback: String): String {
        val resolver = context?.contentResolver ?: return fallback
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { fallback }
    }

    private fun bookFolder(id: String): File {
        require(HEX_64_REGEX.matches(id)) { "无效的本地内容 ID" }
        return File(libraryRoot, id)
    }

    private fun safeDeleteDirectory(dir: File) {
        val canonicalRoot = libraryRoot.canonicalPath
        val canonicalDir = dir.canonicalPath
        check(canonicalDir.startsWith(canonicalRoot + File.separator)) { "禁止删除非本地库目录" }
        check(!dir.exists() || dir.deleteRecursively()) { "本地文件无法移除，请重试" }
    }

    companion object {
        const val LOCAL_URI_SCHEME: String = "ravenlocal"
        const val LEGACY_LOCAL_URI_SCHEME: String = "r34local"
        const val THUMB_FILE_NAME: String = "thumb.png"

        const val MAX_ARCHIVE_ENTRIES: Int = 10_000
        const val MAX_PAGES: Int = 2_000
        const val MAX_SINGLE_IMAGE_BYTES: Long = 50L * 1024L * 1024L
        const val MAX_BOOK_BYTES: Long = 1024L * 1024L * 1024L
        const val MAX_DIRECTORY_DEPTH: Int = 8
        const val MAX_VIDEO_BYTES: Long = 8L * 1024L * 1024L * 1024L
        const val MIN_FREE_SPACE_VIDEO_BYTES: Long = 1024L * 1024L * 1024L
        const val MIN_FREE_SPACE_BOOK_BYTES: Long = 16L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES: Long = 2L * 1024L * 1024L
        private const val MAX_TITLE_LENGTH: Int = 160

        val VIDEO_EXTENSIONS: Set<String> = setOf("mp4", "webm", "mkv", "m4v", "mov", "3gp", "ts")

        private val HEX_64_REGEX = Regex("^[a-f0-9]{64}$")
        private val PAGE_FILE_REGEX = Regex("^\\d+\\.image$")

        fun buildLocalUri(id: String): String = "$LOCAL_URI_SCHEME://$id"

        fun parseLocalBookId(uri: String): String? {
            val trimmed = uri.trim()
            val body = when {
                trimmed.startsWith("$LOCAL_URI_SCHEME://", ignoreCase = true) ->
                    trimmed.substring(LOCAL_URI_SCHEME.length + 3)
                trimmed.startsWith("$LEGACY_LOCAL_URI_SCHEME://", ignoreCase = true) ->
                    trimmed.substring(LEGACY_LOCAL_URI_SCHEME.length + 3)
                else -> return null
            }
            val candidate = body.substringBefore('/').substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            return candidate.takeIf(HEX_64_REGEX::matches)
        }

        /**
         * Determines whether a ZIP entry path should be imported as a page:
         * ignores `__MACOSX`, any hidden segment starting with `.`, and non-image extensions.
         */
        fun shouldIncludeArchiveEntry(rawName: String): Boolean {
            val normalized = rawName.replace('\\', '/').trimStart('/')
            if (normalized.isEmpty() || normalized.endsWith('/')) return false
            val segments = normalized.split('/')
            if (segments.any { seg -> seg.isEmpty() || seg.startsWith('.') || seg.equals("__MACOSX", ignoreCase = true) }) {
                return false
            }
            return ImageStructureValidator.isSupportedImageName(segments.last())
        }

        fun formatHumanBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
            else -> "${bytes / 1024L} KB"
        }

        private fun ByteArray.toHexString(): String =
            joinToString("") { b -> String.format(Locale.ROOT, "%02x", b) }
    }
}
