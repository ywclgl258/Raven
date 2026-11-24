package com.raven.app.core.media.storage

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.model.SiteId
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class DuplicateSource {
    DOWNLOAD,
    LOCAL,
}

/**
 * Input candidate representing one complete comic/gallery book whose [pageFiles] are already
 * ordered in natural reader page order.
 */
data class DuplicateBookCandidate(
    val source: DuplicateSource,
    val id: String,
    val siteId: SiteId,
    val mediaId: String,
    val title: String,
    val pageFiles: List<File>,
    val validatePage: (File) -> Boolean = { it.isFile && it.length() > 0L },
    val sourceRoot: String = "",
)

/**
 * Verified duplicate copy entry surfaced to the user for explicit per-copy removal.
 */
data class DuplicateCopy(
    val source: DuplicateSource,
    val id: String,
    val siteId: SiteId,
    val mediaId: String,
    val title: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val sourceRoot: String = "",
) {
    val identityKey: Triple<DuplicateSource, String, String> get() = Triple(source, id, sourceRoot)
}

/**
 * Group of books sharing an identical page-by-page SHA-256 content fingerprint.
 */
data class DuplicateGroup(
    val fingerprint: String,
    val copies: List<DuplicateCopy>,
) {
    val unitBytes: Long get() = copies.firstOrNull()?.sizeBytes ?: 0L
    val reclaimableBytes: Long get() = if (copies.size > 1) unitBytes * (copies.size - 1) else 0L
}

/**
 * Aggregate scan report across downloaded books and local imported books.
 */
data class DuplicateScanReport(
    val groups: List<DuplicateGroup>,
    val scannedBooks: Int,
    val hashedCandidates: Int,
) {
    val totalReclaimableBytes: Long get() = groups.sumOf { it.reclaimableBytes }
}

/**
 * Whole-book duplicate detector (F41).
 *
 * 1. Pre-filters all complete downloaded & local books by `(pageCount, totalBytes)`.
 * 2. Computes page-by-page natural-order SHA-256 concatenated into an overall book SHA-256 digest
 *    only for colliding buckets (`size >= 2`), verifying file length and modification time stability.
 * 3. Never deletes any files directly — callers present the report for user confirmation and route
 *    deletions through `DownloadCoordinator` / `LocalLibraryImporter`.
 */
class DuplicateScanner(
    private val localImporter: LocalLibraryImporter? = null,
    private val downloadedBookProvider: ((shouldStop: () -> Boolean) -> List<DuplicateBookCandidate>)? = null,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
) {
    private data class MeasuredBook(
        val candidate: DuplicateBookCandidate,
        val pageLengths: List<Long>,
    ) {
        val totalBytes: Long = pageLengths.sum()
        val pageCount: Int = pageLengths.size

        fun toCopy(): DuplicateCopy = DuplicateCopy(
            source = candidate.source,
            id = candidate.id,
            siteId = candidate.siteId,
            mediaId = candidate.mediaId,
            title = candidate.title,
            pageCount = pageCount,
            sizeBytes = totalBytes,
            sourceRoot = candidate.sourceRoot,
        )
    }

    suspend fun scanAll(): DuplicateScanReport = withContext(dispatchers.io) {
        val coroutineCtx = currentCoroutineContext()
        val shouldStop: () -> Boolean = {
            runCatching { coroutineCtx.ensureActive(); false }.getOrDefault(true)
        }
        val books = buildList {
            downloadedBookProvider?.invoke(shouldStop)?.let(::addAll)
            localImporter?.duplicateBookCandidates(shouldStop)?.let(::addAll)
        }
        scanCandidates(books, shouldStop)
    }

    fun scanCandidates(
        books: List<DuplicateBookCandidate>,
        shouldStop: () -> Boolean = { false },
    ): DuplicateScanReport {
        val measured = books.mapNotNull { candidate ->
            if (shouldStop()) return DuplicateScanReport(emptyList(), 0, 0)
            val lengths = candidate.pageFiles.map { file ->
                if (shouldStop()) return DuplicateScanReport(emptyList(), 0, 0)
                if (file.isFile) file.length() else 0L
            }
            if (lengths.isEmpty() || lengths.any { it <= 0L }) null else MeasuredBook(candidate, lengths)
        }

        val groups = mutableListOf<DuplicateGroup>()
        var hashedCount = 0

        for (bucket in measured.groupBy { it.pageCount to it.totalBytes }.values) {
            if (bucket.size < 2) continue
            val fingerprinted = bucket.mapNotNull { entry ->
                if (shouldStop()) return DuplicateScanReport(emptyList(), measured.size, hashedCount)
                hashedCount += 1
                computeBookFingerprint(entry, shouldStop)?.let { digest -> digest to entry.toCopy() }
            }
            fingerprinted.groupBy({ it.first }, { it.second }).forEach { (digest, copies) ->
                if (copies.size > 1) {
                    val ordered = copies.sortedWith(
                        compareBy<DuplicateCopy> { it.siteId.value }.thenBy { it.title }.thenBy { it.id },
                    )
                    groups += DuplicateGroup(fingerprint = digest, copies = ordered)
                }
            }
        }

        return if (shouldStop()) {
            DuplicateScanReport(emptyList(), measured.size, hashedCount)
        } else {
            DuplicateScanReport(
                groups = groups.sortedByDescending { it.reclaimableBytes },
                scannedBooks = measured.size,
                hashedCandidates = hashedCount,
            )
        }
    }

    private fun computeBookFingerprint(
        entry: MeasuredBook,
        shouldStop: () -> Boolean,
    ): String? {
        val bookDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        entry.candidate.pageFiles.forEachIndexed { index, file ->
            if (shouldStop()) return null
            val pageDigest = MessageDigest.getInstance("SHA-256")
            val expectedLength = entry.pageLengths[index]
            try {
                val modifiedBefore = file.lastModified()
                if (file.length() != expectedLength || !entry.candidate.validatePage(file)) {
                    return null
                }
                file.inputStream().use { input ->
                    var remaining = expectedLength
                    while (remaining > 0L) {
                        if (shouldStop()) return null
                        val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) return null
                        pageDigest.update(buffer, 0, read)
                        remaining -= read.toLong()
                    }
                    if (input.read() != -1) return null
                }
                if (file.length() != expectedLength || file.lastModified() != modifiedBefore) {
                    return null
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
            bookDigest.update(pageDigest.digest())
        }
        return bookDigest.digest().joinToString("") { b -> String.format(Locale.ROOT, "%02x", b) }
    }
}
