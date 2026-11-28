package com.raven.app.core.media.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.media.storage.AtomicFiles
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
import com.raven.app.core.network.HostPacer
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
    private val hostPacer: HostPacer? = null,
) {
    @Serializable
    internal data class KnownPageLinks(
        val links: Map<Int, String> = emptyMap(),
    )

    @Serializable
    private data class SavedPageMeta(
        val page: GalleryPage,
        val encodedBytes: Long,
    )

    @Serializable
    internal data class SelectionManifest(
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
            checkNotNull(context?.applicationContext?.cacheDir) { "Context or cacheRootOverride required" },
            "gallery-cache",
        )

    private val legacyReadingCacheBaseDir: File? = if (cacheRootOverride == null) {
        context?.applicationContext?.filesDir?.let { File(it, "gallery-cache") }
    } else {
        null
    }

    private val downloadAudit = GalleryDownloadAudit(
        dispatchers = dispatchers,
        storage = storageRootManager,
        json = json,
        requireAndroidImageDecoder = requireAndroidImageDecoder,
        defaultLayout = ::downloadLayout,
        bookFolder = ::bookFolder,
        resolveReadingRoot = ::resolveReadingRoot,
        readDiskBook = ::readDiskBook,
        atomicWrite = ::atomicWriteBytes,
    )

    private val readingCache = GalleryReadingCacheStore(
        baseDir = readingCacheBaseDir,
        legacyBaseDir = legacyReadingCacheBaseDir,
        siteDir = ::readingCacheDir,
    )


    private fun readingCacheDir(siteId: SiteId): File =
        File(readingCacheBaseDir, siteId.value).apply { mkdirs() }

    val downloadsAllowed: Boolean get() = storageRootManager.canWriteDownloads

    /**
     * Resolves a safe image-fetch concurrency (`2..3`, capped at `2` for strict-rate-limit sites).
     */
    fun recommendedImageConcurrency(
        siteId: SiteId,
        requested: Int = DEFAULT_IMAGE_CONCURRENCY,
    ): Int {
        val clamped = requested.coerceIn(1, MAX_IMAGE_CONCURRENCY)
        val descriptor = siteRegistryProvider().get(siteId)?.descriptor ?: return clamped
        val strictSite = descriptor.networkProfile.imageIntervalMs >= STRICT_IMAGE_INTERVAL_THRESHOLD_MS ||
            descriptor.networkProfile.pageIntervalMs >= STRICT_PAGE_INTERVAL_THRESHOLD_MS
        return if (strictSite) minOf(clamped, 2) else clamped
    }

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
        } catch (limited: SiteException.RateLimited) {
            // The host asked us to wait. Refreshing the page link would immediately retry it.
            throw limited
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
        val item = detail.item
        if (item.siteId.isLocal || offlineOnly) {
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
            return@withContext RenderedGalleryPage(original.page, thumb, original.fromCache)
        }

        val resolvedRoot = sourceRoot.ifBlank { resolveReadingRoot(item.siteId, item.id) }
        val knownPageUrl = cachedPageUrl(item.siteId, item.id, item.url, pageIndex, resolvedRoot)
            ?: detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl

        if (!knownPageUrl.isNullOrBlank()) {
            val layout = storageRootManager.resolveSourceLayout(resolvedRoot)
            val bookDir = bookFolder(item.siteId, item.id, layout)
            val downloadUrl = readLinksMap(bookDir)[pageIndex] ?: knownPageUrl
            val saved = readSavedPage(bookDir, downloadUrl, expectedIndex = pageIndex, json = json)
            if (saved != null && saved.isValid(requireAndroidImageDecoder)) {
                val bytes = readBytesBounded(saved.file, MAX_GALLERY_IMAGE_BYTES)
                if (bytes != null) {
                    val thumb = decodeThumbnailBitmap(bytes, targetSizePx)
                        ?: decodeSampledBitmap(bytes, ImageStructureValidator.DEFAULT_MAX_DECODE_PIXELS)
                    return@withContext RenderedGalleryPage(saved.page, thumb, fromCache = true)
                }
            }

            val cacheDir = readingCacheDir(item.siteId)
            val cached = readSavedPage(
                folder = cacheDir,
                pageUrl = knownPageUrl,
                expectedIndex = pageIndex,
                json = json,
                preferOriginal = preferOriginal,
            )
            if (cached != null && cached.isValid(requireAndroidImageDecoder)) {
                val bytes = readBytesBounded(cached.file, MAX_GALLERY_IMAGE_BYTES)
                if (bytes != null) {
                    val thumb = decodeThumbnailBitmap(bytes, targetSizePx)
                        ?: decodeSampledBitmap(bytes, ImageStructureValidator.DEFAULT_MAX_DECODE_PIXELS)
                    return@withContext RenderedGalleryPage(cached.page, thumb, fromCache = true)
                }
            }
        }

        val plugin = siteRegistryProvider().require(item.siteId)
        val gallerySource = plugin.gallerySource
            ?: throw SiteException.Unsupported("站点 ${item.siteId.value} 不支持图集读取", siteId = item.siteId)
        val resolvedPage = try {
            resolveOnlineGalleryPage(
                detail = detail,
                pageIndex = pageIndex,
                hintPageUrl = knownPageUrl,
                cacheDir = readingCacheDir(item.siteId),
                gallerySource = gallerySource,
                preferOriginal = preferOriginal,
                forceReload = false,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            if (!knownPageUrl.isNullOrBlank()) {
                forgetCachedPageLink(item.siteId, item.url, pageIndex, knownPageUrl)
                resolveOnlineGalleryPage(
                    detail = detail,
                    pageIndex = pageIndex,
                    hintPageUrl = null,
                    cacheDir = readingCacheDir(item.siteId),
                    gallerySource = gallerySource,
                    preferOriginal = preferOriginal,
                    forceReload = true,
                )
            } else {
                throw error
            }
        }

        val (fetchedPage, rawBytes) = fetchRawImageBytesWithReload(
            siteId = item.siteId,
            initialPage = resolvedPage,
            gallerySource = gallerySource,
            preferOriginal = preferOriginal,
            refererHint = detail.item.extra["imageReferer"],
        )
        val stripCount = unscrambler.resolveStripCount(fetchedPage, plugin.imageUnscrambler)
        val thumb = if (stripCount > 0 && requireAndroidImageDecoder) {
            unscrambler.decodeUnscrambledThumbnail(
                bytes = rawBytes,
                stripCount = stripCount,
                unscrambler = plugin.imageUnscrambler,
                targetSizePx = targetSizePx,
            )
        } else {
            decodeThumbnailBitmap(rawBytes, targetSizePx)
                ?: decodeSampledBitmap(rawBytes, ImageStructureValidator.DEFAULT_MAX_DECODE_PIXELS)
        }
        RenderedGalleryPage(fetchedPage, thumb, fromCache = false)
    }

    private suspend fun resolveOnlineGalleryPage(
        detail: GalleryDetail,
        pageIndex: Int,
        hintPageUrl: String?,
        cacheDir: File,
        gallerySource: GallerySource,
        preferOriginal: Boolean,
        forceReload: Boolean,
    ): GalleryPage {
        val resolvedPage = if (!hintPageUrl.isNullOrBlank()) {
            val previous = if (forceReload) {
                readSavedPage(
                    folder = cacheDir,
                    pageUrl = hintPageUrl,
                    expectedIndex = pageIndex,
                    json = json,
                    preferOriginal = preferOriginal,
                )?.page
            } else {
                null
            }
            val reloadUrl = if (forceReload && previous != null) {
                gallerySource.reloadUrlOnError(SiteException.Network("用户手动重试", siteId = detail.item.siteId), previous)
                    ?: hintPageUrl
            } else {
                hintPageUrl
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
        return resolvedPage
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
            val cached = readSavedPage(
                folder = cacheDir,
                pageUrl = initialPageUrl,
                expectedIndex = pageIndex,
                json = json,
                preferOriginal = preferOriginal,
            )
            if (cached != null && cached.isValid(requireAndroidImageDecoder)) {
                val bytes = readBytesBounded(cached.file, MAX_GALLERY_IMAGE_BYTES)
                if (bytes != null) {
                    cached.file.setLastModified(System.currentTimeMillis())
                    rememberCacheLink(siteId, galleryUrl, cached.page)
                    return OriginalGalleryPage(cached.page, bytes, fromCache = true)
                }
            }
        }

        val resolvedPage = resolveOnlineGalleryPage(
            detail = detail,
            pageIndex = pageIndex,
            hintPageUrl = initialPageUrl,
            cacheDir = cacheDir,
            gallerySource = gallerySource,
            preferOriginal = preferOriginal,
            forceReload = forceReload,
        )

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
        val key = readingCacheKey(canonicalPageUrl, normalizedPage.index, preferOriginal)
        val imageFile = File(cacheDir, "$key.image")
        val metaFile = File(cacheDir, "$key.page.json")
        runCatching {
            atomicWriteBytes(imageFile, finalBytes)
            atomicWriteBytes(
                metaFile,
                json.encodeToString(SavedPageMeta.serializer(), SavedPageMeta(normalizedPage, finalBytes.size.toLong())).toByteArray(),
            )
            rememberCacheLink(siteId, galleryUrl, normalizedPage)
            readingCache.trim()
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
        val cachedPage = existingLink?.let {
            readSavedPage(
                folder = cacheDir,
                pageUrl = it,
                expectedIndex = pageIndex,
                json = json,
                preferOriginal = preferOriginal,
            )
        }?.takeIf { it.isValid(requireAndroidImageDecoder) }
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
        // Key includes the page number: plugins such as 瓜子漫画 append the page index after `?`,
        // so hashing only the query-stripped URL made every page of a chapter share one file.
        val key = pageFileKey(normalized.pageUrl, normalized.index)
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
    ): GalleryAudit = downloadAudit.audit(siteId, mediaId, sourceRoot)

    suspend fun auditWriteTarget(
        siteId: SiteId,
        mediaId: String,
        layout: StorageLayout = downloadLayout(),
    ): GalleryAudit? = downloadAudit.auditWriteTarget(siteId, mediaId, layout)

    suspend fun commitDownload(
        siteId: SiteId,
        mediaId: String,
        layout: StorageLayout,
        requestedPages: Set<Int>,
    ): GalleryAudit = downloadAudit.commit(siteId, mediaId, layout, requestedPages)

    suspend fun removeDownload(
        siteId: SiteId,
        mediaId: String,
        sourceRoot: String = "",
        knownRoot: String = "",
    ) = downloadAudit.remove(siteId, mediaId, sourceRoot, knownRoot)

    fun inspectDiskBookForRecovery(
        siteId: SiteId,
        folder: File,
        sourceRoot: String,
    ): DiskSidecarRecord? = downloadAudit.inspectRecovery(siteId, folder, sourceRoot)

    fun duplicateBookCandidates(shouldStop: () -> Boolean = { false }): List<DuplicateBookCandidate> =
        downloadAudit.duplicateCandidates(shouldStop)

    // ---- Cache Inspection & Clearing ----

    fun readingCacheStats(siteId: SiteId): Pair<Int, Long> = readingCache.stats(siteId)

    fun readingCacheBytes(): Long = readingCache.totalBytes()

    fun clearReadingCache(siteId: SiteId) = readingCache.clearSite(siteId)

    fun clearAllReadingCaches() = readingCache.clearAll()

    // ---- Network Fetch + EH ?nl= Error Recovery + JMComic Unscrambling ----

    private suspend fun fetchAndUnscrambleWithReload(
        siteId: SiteId,
        initialPage: GalleryPage,
        gallerySource: GallerySource,
        imageUnscrambler: ImageUnscrambler,
        preferOriginal: Boolean,
        refererHint: String?,
    ): Pair<GalleryPage, ByteArray> {
        val (currentPage, rawBytes) = fetchRawImageBytesWithReload(
            siteId = siteId,
            initialPage = initialPage,
            gallerySource = gallerySource,
            preferOriginal = preferOriginal,
            refererHint = refererHint,
        )

        val finalBytes = if (unscrambler.needsUnscramble(currentPage, imageUnscrambler)) {
            unscrambler.unscrambleToBytes(rawBytes, currentPage, imageUnscrambler)
        } else {
            rawBytes
        }

        check(ImageStructureValidator.isValidImageBytes(finalBytes, MAX_GALLERY_IMAGE_BYTES, requireAndroidImageDecoder)) {
            "图源未返回可识别的完整图片，请重试此页"
        }
        return currentPage to finalBytes
    }

    private suspend fun fetchRawImageBytesWithReload(
        siteId: SiteId,
        initialPage: GalleryPage,
        gallerySource: GallerySource,
        preferOriginal: Boolean,
        refererHint: String?,
    ): Pair<GalleryPage, ByteArray> {
        var currentPage = initialPage
        suspend fun fetchAndValidate(page: GalleryPage): ByteArray {
            val bytes = fetchImageBytes(siteId, page, gallerySource, preferOriginal, refererHint)
            if (!ImageStructureValidator.isValidImageBytes(bytes, MAX_GALLERY_IMAGE_BYTES, requireAndroidImageDecoder)) {
                throw SiteException.Network("图源未返回可识别的完整图片，正在尝试备用线路", siteId = siteId)
            }
            return bytes
        }

        val rawBytes = try {
            fetchAndValidate(currentPage)
        } catch (ce: CancellationException) {
            throw ce
        } catch (cause: SiteException) {
            if (cause is SiteException.RateLimited) throw cause
            val reloadUrl = gallerySource.reloadUrlOnError(cause, currentPage)
                ?.takeIf { it.isNotBlank() }
                ?: throw cause
            currentPage = gallerySource.resolvePageImage(reloadUrl, preferOriginal = preferOriginal, forceReload = true)
            check(currentPage.index == initialPage.index) { "刷新图源返回了错误的图页编号" }
            fetchAndValidate(currentPage)
        }
        return currentPage to rawBytes
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
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }

        val descriptor = siteRegistryProvider().get(siteId)?.descriptor
        hostPacer?.paceForImageWithJitter(parsedTarget.host, descriptor)

        val response = try {
            executeCancellable(imageClient.newCall(reqBuilder.build()))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            throw SiteHttpErrorMapper.fromThrowable(e, siteId)
        }

        val ctx = currentCoroutineContext()
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
                when (res.code) {
                    429, 503 -> {
                        val cooldownMs = ((retryAfter ?: 4L) * 1_000L).coerceIn(1_500L, 60_000L)
                        hostPacer?.penalize(parsedTarget.host, cooldownMs)
                    }
                    509 -> hostPacer?.penalize(parsedTarget.host, 8_000L)
                }
                throw when (res.code) {
                    401 -> SiteException.AuthRequired("图片需要登录访问 (HTTP 401)", siteId = siteId)
                    403 -> SiteException.Forbidden(isChallenge = false, message = "图片访问被拒绝 (HTTP 403)", siteId = siteId)
                    404, 410 -> SiteException.NotFound("图片已被移除 (HTTP ${res.code})", siteId = siteId)
                    429, 503 -> SiteException.RateLimited(
                        retryAfterSeconds = retryAfter,
                        message = "图片服务暂时不可用 (HTTP ${res.code})",
                        siteId = siteId,
                    )
                    509 -> SiteException.QuotaExceeded("原站图片配额已用尽 (HTTP 509)", siteId = siteId)
                    else -> SiteException.Network("图片读取失败：HTTP ${res.code}", siteId = siteId)
                }
            }
            val body = res.body ?: throw SiteException.Network("图片响应为空", siteId = siteId)
            val rawBytes = body.byteStream().use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    ctx.ensureActive()
                    val count = stream.read(buf)
                    if (count < 0) break
                    if (out.size() + count > MAX_GALLERY_IMAGE_BYTES) {
                        throw SiteException.ParseError("单张图片超过 24 MiB 限制", siteId = siteId)
                    }
                    out.write(buf, 0, count)
                }
                out.toByteArray()
            }
            val transportHook = siteRegistryProvider().get(siteId)?.mediaTransportHook
            if (transportHook != null && transportHook.requiresFullResponseUnwrap(targetUrl)) {
                transportHook.unwrapMediaBytes(targetUrl, rawBytes)?.bytes ?: rawBytes
            } else {
                rawBytes
            }
        }
    }

    // ---- Disk Helpers ----

    private fun readDiskBook(folder: File, expectedSiteId: SiteId? = null, expectedMediaId: String? = null): DiskBook? =
        readBookDisk(folder, expectedSiteId, expectedMediaId, json, requireAndroidImageDecoder)

    private fun readLinksMap(folder: File): Map<Int, String> = readKnownLinks(folder, json)

    @Synchronized
    private fun rememberDownloadLink(folder: File, page: GalleryPage) = appendDownloadLink(folder, page, json)

    private fun rememberCacheLink(siteId: SiteId, galleryUrl: String, page: GalleryPage) {
        if (galleryUrl.isBlank()) return
        appendCacheLink(File(readingCacheDir(siteId), "${StorageRootManager.sha256Hex(galleryUrl)}.links.json"), page, json)
    }

    private fun <T> readJsonFile(file: File, serializer: kotlinx.serialization.KSerializer<T>, maxBytes: Long): T? =
        Companion.readJsonFile(file, serializer, maxBytes, json)

    private fun atomicWriteBytes(target: File, bytes: ByteArray) = writeBytesAtomic(target, bytes)

    private fun readBytesBounded(file: File, maxBytes: Long): ByteArray? = readBoundedBytes(file, maxBytes)

    companion object {
        const val READING_CACHE_MAX_BYTES: Long = 256L * 1024L * 1024L
        const val MAX_GALLERY_IMAGE_BYTES: Long = ImageStructureValidator.DEFAULT_MAX_GALLERY_IMAGE_BYTES
        const val DEFAULT_IMAGE_CONCURRENCY: Int = 3
        const val MAX_IMAGE_CONCURRENCY: Int = 3
        private const val STRICT_IMAGE_INTERVAL_THRESHOLD_MS: Long = 300L
        private const val STRICT_PAGE_INTERVAL_THRESHOLD_MS: Long = 1_000L
        internal const val MAX_PAGE_NUMBER: Int = 100_000
        internal const val MAX_BOOK_JSON_BYTES: Long = 16L * 1024L * 1024L
        private const val MAX_PAGE_JSON_BYTES: Long = 128L * 1024L

        const val RECOVERY_NOTE: String =
            "本文件夹是一份已下载的图集（漫画或写真），由 Raven（渡鸦）保存。\n\n" +
                "book.json  详情与页数\n" +
                "links.json 页码与图源的对应关系\n" +
                "*.image    原始图页（按页保存）\n" +
                "complete   整本下载完成标记\n\n" +
                "把整个文件夹放回 Raven/comics/<站点>/ 之下，应用重装后可自动识别恢复。"

        private fun readingCacheKey(pageUrl: String, pageIndex: Int, preferOriginal: Boolean): String =
            StorageRootManager.sha256Hex("${pageUrl.substringBefore('?')}#$pageIndex|orig=$preferOriginal")

        /**
         * Stable per-page file key. The page index is part of the key because some plugins encode
         * the page only in the query string, which [substringBefore] strips; two pages of the same
         * chapter therefore hashed to one file and the later write silently replaced the earlier.
         */
        internal fun pageFileKey(pageUrl: String, pageIndex: Int): String =
            StorageRootManager.sha256Hex("${pageUrl.substringBefore('?')}#$pageIndex")

        private fun legacyReadingCacheKey(pageUrl: String, preferOriginal: Boolean): String =
            StorageRootManager.sha256Hex("${pageUrl.substringBefore('?')}|orig=$preferOriginal")

        /**
         * Legacy key without the page index, kept only so downloads created before this fix keep
         * resolving. Never written to.
         */
        private fun legacyPageFileKey(pageUrl: String): String =
            StorageRootManager.sha256Hex(pageUrl.substringBefore('?'))

        internal fun <T> readJsonFile(
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
            preferOriginal: Boolean? = null,
        ): SavedPageFile? = runCatching {
            if (pageUrl.isBlank()) return null
            val candidates = buildList {
                // Current layout: page index is part of the key and of the preference variant.
                if (expectedIndex != null) {
                    add(if (preferOriginal != null) readingCacheKey(pageUrl, expectedIndex, preferOriginal) else pageFileKey(pageUrl, expectedIndex))
                }
                // Legacy layouts written before the page index was part of the key.
                if (preferOriginal != null) add(legacyReadingCacheKey(pageUrl, preferOriginal))
                add(legacyPageFileKey(pageUrl))
            }.distinct()
            for (key in candidates) {
                readSavedPageAt(folder, pageUrl, expectedIndex, json, key)?.let { return it }
            }
            null
        }.getOrNull()

        private fun readSavedPageAt(
            folder: File,
            pageUrl: String,
            expectedIndex: Int?,
            json: Json,
            key: String,
        ): SavedPageFile? = runCatching {
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
