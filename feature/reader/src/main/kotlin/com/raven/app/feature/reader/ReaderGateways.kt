package com.raven.app.feature.reader

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.reader.ExportMode
import com.raven.app.core.media.reader.GalleryExporter
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.reader.OfflineGallerySnapshot
import com.raven.app.core.media.reader.PreparedExport
import com.raven.app.core.media.reader.SafSaveOutcome
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.Flow

/**
 * Decoded page payload held inside [com.raven.app.core.media.reader.ReaderBuffer].
 * Carries explicit [pixelWidth], [pixelHeight], and [byteSize] metadata so aspect ratio
 * preservation and 24 MiB offscreen budget accounting work identically on Android and pure JVM tests.
 */
data class LoadedReaderPage(
    val page: GalleryPage,
    val bitmap: Bitmap?,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val byteSize: Long,
    val fromCache: Boolean,
) {
    val aspectRatio: Float?
        get() = if (pixelWidth > 0 && pixelHeight > 0) {
            pixelWidth.toFloat() / pixelHeight.toFloat()
        } else {
            val w = page.width
            val h = page.height
            if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h.toFloat() else null
        }
}

/**
 * Abstraction over [GalleryPageRepository] for detail, rendered-page, thumbnail, and original-byte loading.
 */
interface ReaderPageLoader {
    suspend fun loadDetail(
        siteId: SiteId,
        mediaId: String,
        url: String,
        offlineOnly: Boolean = false,
    ): OfflineGallerySnapshot

    suspend fun loadRenderedPage(
        detail: GalleryDetail,
        pageIndex: Int,
        preferOriginal: Boolean,
        forceReload: Boolean,
        offlineOnly: Boolean,
        sourceRoot: String,
    ): LoadedReaderPage

    suspend fun loadOriginalBytes(
        detail: GalleryDetail,
        pageIndex: Int,
        preferOriginal: Boolean,
        offlineOnly: Boolean,
        sourceRoot: String,
    ): ByteArray

    suspend fun loadThumbnailBitmap(
        detail: GalleryDetail,
        pageIndex: Int,
        targetSizePx: Int,
        preferOriginal: Boolean,
        offlineOnly: Boolean,
        sourceRoot: String,
    ): Bitmap?
}

/** Loads a serial comic's chapters in the source's official first-to-latest order. */
fun interface SerialChapterLoader {
    suspend fun loadChapters(siteId: SiteId, detail: GalleryDetail): List<MediaItem>
}

internal class SiteRegistrySerialChapterLoader(
    private val siteRegistry: SiteRegistry,
) : SerialChapterLoader {
    override suspend fun loadChapters(siteId: SiteId, detail: GalleryDetail): List<MediaItem> {
        if (detail.chaptersUrl.isNullOrBlank()) return emptyList()
        val feed = siteRegistry.get(siteId)?.feedSource ?: return emptyList()
        val comicId = detail.item.extra["comicId"]?.takeIf { it.isNotBlank() }
            ?: detail.item.id.substringBefore(':')
        if (comicId.isBlank()) return emptyList()
        return feed.loadFeed(
            FeedRequest(siteId = siteId, section = "chapters", category = comicId, page = 1),
        ).items
    }
}

/**
 * Production [ReaderPageLoader] backed directly by [GalleryPageRepository].
 */
class RepositoryReaderPageLoader(
    private val repository: GalleryPageRepository,
) : ReaderPageLoader {
    override suspend fun loadDetail(
        siteId: SiteId,
        mediaId: String,
        url: String,
        offlineOnly: Boolean,
    ): OfflineGallerySnapshot = repository.loadDetail(
        siteId = siteId,
        mediaId = mediaId,
        url = url,
        offlineOnly = offlineOnly,
    )

    override suspend fun loadRenderedPage(
        detail: GalleryDetail,
        pageIndex: Int,
        preferOriginal: Boolean,
        forceReload: Boolean,
        offlineOnly: Boolean,
        sourceRoot: String,
    ): LoadedReaderPage {
        val rendered = repository.loadRenderedPage(
            detail = detail,
            pageIndex = pageIndex,
            preferOriginal = preferOriginal,
            forceReload = forceReload,
            offlineOnly = offlineOnly,
            sourceRoot = sourceRoot,
        )
        val bmp = rendered.bitmap
        val bytes = maxOf(bmp.allocationByteCount.toLong(), bmp.byteCount.toLong())
        return LoadedReaderPage(
            page = rendered.page,
            bitmap = bmp,
            pixelWidth = bmp.width,
            pixelHeight = bmp.height,
            byteSize = bytes,
            fromCache = rendered.fromCache,
        )
    }

    override suspend fun loadOriginalBytes(
        detail: GalleryDetail,
        pageIndex: Int,
        preferOriginal: Boolean,
        offlineOnly: Boolean,
        sourceRoot: String,
    ): ByteArray = repository.loadOriginalPage(
        detail = detail,
        pageIndex = pageIndex,
        preferOriginal = preferOriginal,
        forceReload = false,
        offlineOnly = offlineOnly,
        sourceRoot = sourceRoot,
    ).bytes

    override suspend fun loadThumbnailBitmap(
        detail: GalleryDetail,
        pageIndex: Int,
        targetSizePx: Int,
        preferOriginal: Boolean,
        offlineOnly: Boolean,
        sourceRoot: String,
    ): Bitmap? = repository.loadThumbnail(
        detail = detail,
        pageIndex = pageIndex,
        targetSizePx = targetSizePx,
        preferOriginal = preferOriginal,
        offlineOnly = offlineOnly,
        sourceRoot = sourceRoot,
    ).bitmap
}

/**
 * Abstraction over [DownloadCoordinator] for observing and enqueueing gallery downloads.
 */
interface GalleryDownloadGateway {
    fun observeByMedia(siteId: SiteId, mediaId: String): Flow<List<DownloadItem>>

    suspend fun enqueueGallery(
        item: MediaItem,
        selectedPages: Collection<Int>? = null,
        groupName: String = "",
        recheckSavedPages: Boolean = false,
    ): DownloadItem
}

/**
 * Production [GalleryDownloadGateway] delegating directly to [DownloadCoordinator].
 */
class CoordinatorGalleryDownloadGateway(
    private val coordinator: DownloadCoordinator,
) : GalleryDownloadGateway {
    override fun observeByMedia(siteId: SiteId, mediaId: String): Flow<List<DownloadItem>> =
        coordinator.observeByMedia(siteId, mediaId)

    override suspend fun enqueueGallery(
        item: MediaItem,
        selectedPages: Collection<Int>?,
        groupName: String,
        recheckSavedPages: Boolean,
    ): DownloadItem = coordinator.enqueueGallery(
        item = item,
        selectedPages = selectedPages,
        groupName = groupName,
        recheckSavedPages = recheckSavedPages,
    )
}

/**
 * Abstraction over [GalleryExporter] for preparing exports and committing SAF outputs.
 */
interface GalleryExportGateway {
    suspend fun prepare(
        title: String,
        pages: List<Int>,
        mode: ExportMode,
        loadPageBytes: suspend (pageIndex: Int) -> ByteArray,
        onProgress: (String) -> Unit = {},
    ): PreparedExport

    suspend fun saveToSaf(
        prepared: PreparedExport,
        targetUri: Uri,
        isTree: Boolean,
        contentResolver: ContentResolver,
        onProgress: (String) -> Unit = {},
    ): SafSaveOutcome

    fun prepareShareUris(
        context: Context,
        authority: String,
        prepared: PreparedExport,
    ): List<Uri>

    fun removeStagingFolder(folder: java.io.File)
}

/**
 * Production [GalleryExportGateway] backed directly by [GalleryExporter].
 */
class MediaGalleryExportGateway(
    private val exporter: GalleryExporter,
) : GalleryExportGateway {
    override suspend fun prepare(
        title: String,
        pages: List<Int>,
        mode: ExportMode,
        loadPageBytes: suspend (pageIndex: Int) -> ByteArray,
        onProgress: (String) -> Unit,
    ): PreparedExport = exporter.prepare(
        title = title,
        pages = pages,
        mode = mode,
        loadPageBytes = loadPageBytes,
        onProgress = onProgress,
    )

    override suspend fun saveToSaf(
        prepared: PreparedExport,
        targetUri: Uri,
        isTree: Boolean,
        contentResolver: ContentResolver,
        onProgress: (String) -> Unit,
    ): SafSaveOutcome = exporter.saveToSaf(
        prepared = prepared,
        targetUri = targetUri,
        isTree = isTree,
        contentResolver = contentResolver,
        onProgress = onProgress,
    )

    override fun prepareShareUris(
        context: Context,
        authority: String,
        prepared: PreparedExport,
    ): List<Uri> = exporter.prepareShareUris(
        context = context,
        authority = authority,
        prepared = prepared,
    )

    override fun removeStagingFolder(folder: java.io.File) {
        exporter.removeStagingFolder(folder)
    }
}
