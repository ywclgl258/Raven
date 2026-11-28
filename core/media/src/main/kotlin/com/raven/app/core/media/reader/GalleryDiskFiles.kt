package com.raven.app.core.media.reader

import com.raven.app.core.media.storage.AtomicFiles
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.SiteId
import java.io.DataInputStream
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * On-disk book files for gallery downloads and the reading cache: JSON reads, atomic publishes,
 * bounded reads and the `links.json` page-link index.
 *
 * Stateless helpers extracted from `GalleryPageRepository`; the repository keeps thin delegates so
 * its public behaviour and synchronization stay unchanged.
 */

internal fun readBookDisk(
    folder: File,
    expectedSiteId: SiteId? = null,
    expectedMediaId: String? = null,
    json: Json,
    requireAndroidDecoder: Boolean,
): GalleryPageRepository.DiskBook? {
    val detail = GalleryPageRepository.readJsonFile(
        File(folder, "book.json"),
        GalleryDetail.serializer(),
        GalleryPageRepository.MAX_BOOK_JSON_BYTES,
        json,
    ) ?: return null
    if (detail.pageCount !in 1..GalleryPageRepository.MAX_PAGE_NUMBER) return null
    if (expectedSiteId != null && detail.item.siteId != expectedSiteId) return null
    if (expectedMediaId != null && detail.item.id != expectedMediaId) return null
    if (expectedSiteId != null && folder.name != StorageRootManager.sha256Hex(detail.item.id)) return null

    val selection = GalleryPageRepository.readJsonFile(
        File(folder, "selection.json"),
        GalleryPageRepository.SelectionManifest.serializer(),
        GalleryPageRepository.MAX_BOOK_JSON_BYTES,
        json,
    )?.selected.orEmpty().distinct().sorted()
    val completeFile = File(folder, "complete")
    val markerCount = runCatching {
        if (!completeFile.isFile || completeFile.length() !in 1L..32L || !StorageRootManager.directChild(folder, completeFile)) {
            null
        } else {
            completeFile.readText().trim().toIntOrNull()
        }
    }.getOrNull()

    return GalleryPageRepository.DiskBook(
        folder = folder,
        detail = detail,
        links = readKnownLinks(folder, json),
        selection = selection,
        hasCompleteMarker = markerCount == detail.pageCount,
        json = json,
        requireAndroidDecoder = requireAndroidDecoder,
    )
}

internal fun readKnownLinks(folder: File, json: Json): Map<Int, String> =
    GalleryPageRepository.readJsonFile(
        File(folder, "links.json"),
        GalleryPageRepository.KnownPageLinks.serializer(),
        GalleryPageRepository.MAX_BOOK_JSON_BYTES,
        json,
    )?.links.orEmpty()

internal fun appendDownloadLink(folder: File, page: GalleryPage, json: Json) {
    val file = File(folder, "links.json")
    val current = readKnownLinks(folder, json)
    if (current[page.index] != page.pageUrl) {
        writeBytesAtomic(
            file,
            json.encodeToString(
                GalleryPageRepository.KnownPageLinks.serializer(),
                GalleryPageRepository.KnownPageLinks(current + (page.index to page.pageUrl)),
            ).toByteArray(),
        )
    }
}

internal fun appendCacheLink(file: File, page: GalleryPage, json: Json) {
    val current = GalleryPageRepository.readJsonFile(
        file,
        GalleryPageRepository.KnownPageLinks.serializer(),
        GalleryPageRepository.MAX_BOOK_JSON_BYTES,
        json,
    )?.links.orEmpty()
    if (current[page.index] != page.pageUrl) {
        writeBytesAtomic(
            file,
            json.encodeToString(
                GalleryPageRepository.KnownPageLinks.serializer(),
                GalleryPageRepository.KnownPageLinks(current + (page.index to page.pageUrl)),
            ).toByteArray(),
        )
    }
}

/** Publishes [bytes] without ever deleting the previous copy before the new one is renamed in. */
internal fun writeBytesAtomic(target: File, bytes: ByteArray) {
    val parent = target.parentFile ?: throw GalleryStorageException("无效的文件路径：${target.path}")
    try {
        check(parent.isDirectory || parent.mkdirs()) { "无法创建目录：${parent.path}" }
        // Never delete the previous copy before the new bytes are published: a refused rename
        // would otherwise leave neither the old nor the new page behind.
        AtomicFiles.writeBytes(target, bytes)
    } catch (e: Exception) {
        throw GalleryStorageException(e.message ?: "无法保存图页文件", e)
    }
}

internal fun readBoundedBytes(file: File, maxBytes: Long): ByteArray? = runCatching {
    val len = file.length()
    if (!file.isFile || len !in 1L..maxBytes) return null
    file.inputStream().use { input ->
        val bytes = ByteArray(len.toInt())
        DataInputStream(input).readFully(bytes)
        bytes.takeIf { input.read() == -1 }
    }
}.getOrNull()
