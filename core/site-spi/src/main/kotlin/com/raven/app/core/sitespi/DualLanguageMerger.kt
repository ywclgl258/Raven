package com.raven.app.core.sitespi

import com.raven.app.core.model.SiteException
import java.net.URLDecoder
import java.net.URLEncoder

enum class DualMergeStrategy {
    /** Pick item with higher numeric ID first (newest feed merge). */
    DESCENDING_NUMERIC_ID,

    /** Alternate between streams (popular feed merge). */
    INTERLEAVED,
}

data class DualCursor(
    val pageA: Int = 1,
    val indexA: Int = 0,
    val pageB: Int = 1,
    val indexB: Int = 0,
    val nextStream: Int = 0,
    val emittedIds: Set<String> = emptySet(),
) {
    fun encode(): String {
        val positions = "$pageA,$indexA,$pageB,$indexB"
        if (emittedIds.isEmpty() && nextStream == 0) return positions
        val history = emittedIds.joinToString("|") { URLEncoder.encode(it, "UTF-8") }
        return "$positions;$nextStream;$history"
    }

    companion object {
        private const val MAX_SLOT_VALUE = 1_000_000

        fun parse(raw: String?): DualCursor {
            if (raw.isNullOrBlank()) return DualCursor()
            val fields = raw.trim().split(';', limit = 3)
            val parts = fields.first().split(',')
            if (parts.size != 4 || fields.size == 2) {
                throw SiteException.ParseError("Invalid dual-language cursor format: '$raw'")
            }
            val nums = parts.map { token ->
                token.trim().toIntOrNull()?.takeIf { it in 0..MAX_SLOT_VALUE }
                    ?: throw SiteException.ParseError("Invalid dual-language cursor value in '$raw'")
            }
            val positions = DualCursor(nums[0], nums[1], nums[2], nums[3])
            if (fields.size == 1) return positions
            val nextStream = fields[1].toIntOrNull()?.takeIf { it in 0..1 }
                ?: throw SiteException.ParseError("Invalid dual-language stream index")
            val history = try {
                fields[2].split('|').mapTo(mutableSetOf()) { URLDecoder.decode(it, "UTF-8") }
            } catch (ex: IllegalArgumentException) {
                throw SiteException.ParseError("Invalid dual-language item ID")
            }
            return positions.copy(nextStream = nextStream, emittedIds = history)
        }
    }
}

data class StreamSlice<T>(
    val items: List<T>,
    val hasNext: Boolean,
)

data class MergedPage<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

/**
 * Pure-Kotlin dual-stream cursor merger for `ComicLanguage.CHINESE_AND_JAPANESE`
 * (shared by nhentai and Hitomi when no native OR query operator exists).
 */
object DualLanguageMerger {

    private const val MAX_CONSECUTIVE_EMPTY_PAGES = 5

    suspend fun <T> merge(
        cursorRaw: String?,
        pageSize: Int,
        strategy: DualMergeStrategy,
        idOf: (T) -> String,
        fetchStreamA: suspend (page: Int) -> StreamSlice<T>,
        fetchStreamB: suspend (page: Int) -> StreamSlice<T>,
    ): MergedPage<T> {
        require(pageSize > 0) { "pageSize must be positive" }
        val initial = DualCursor.parse(cursorRaw)
        val pages = intArrayOf(initial.pageA, initial.pageB)
        val indices = intArrayOf(initial.indexA, initial.indexB)
        val emptyRuns = intArrayOf(0, 0)
        val cache = arrayOf(mutableMapOf<Int, StreamSlice<T>>(), mutableMapOf<Int, StreamSlice<T>>())

        suspend fun peek(stream: Int): T? {
            while (pages[stream] > 0) {
                val p = pages[stream]
                val slice = cache[stream].getOrPut(p) {
                    if (stream == 0) fetchStreamA(p) else fetchStreamB(p)
                }
                val item = slice.items.getOrNull(indices[stream])
                if (item != null) {
                    emptyRuns[stream] = 0
                    return item
                }
                if (!slice.hasNext) {
                    pages[stream] = 0
                    indices[stream] = 0
                    break
                }
                if (slice.items.isEmpty()) {
                    emptyRuns[stream] += 1
                    if (emptyRuns[stream] >= MAX_CONSECUTIVE_EMPTY_PAGES) {
                        pages[stream] = 0
                        indices[stream] = 0
                        break
                    }
                } else {
                    emptyRuns[stream] = 0
                }
                pages[stream] = p + 1
                indices[stream] = 0
            }
            return null
        }

        val merged = mutableListOf<T>()
        val seenIds = initial.emittedIds.toMutableSet()
        var turn = initial.nextStream

        while (merged.size < pageSize) {
            val headA = peek(0)
            val headB = peek(1)
            if (headA == null && headB == null) break

            val chosenStream = when {
                headA == null -> 1
                headB == null -> 0
                strategy == DualMergeStrategy.INTERLEAVED -> turn % 2
                else -> {
                    val idA = idOf(headA).toLongOrNull() ?: 0L
                    val idB = idOf(headB).toLongOrNull() ?: 0L
                    if (idA >= idB) 0 else 1
                }
            }
            val chosenItem = if (chosenStream == 0) headA!! else headB!!
            indices[chosenStream]++
            turn++
            if (seenIds.add(idOf(chosenItem))) {
                merged.add(chosenItem)
            }
        }

        // Advance both cursors past duplicates before splitting the output page.
        // In numeric order, all copies of the last emitted ID are adjacent.
        for (stream in 0..1) {
            while (true) {
                val head = peek(stream) ?: break
                if (idOf(head) !in seenIds) break
                indices[stream]++
            }
        }
        val hasMore = pages.any { it > 0 }
        val nextCursor = if (hasMore) {
            val positions = DualCursor(pages[0], indices[0], pages[1], indices[1])
            if (strategy == DualMergeStrategy.INTERLEAVED) {
                // Popularity ordering can place the same ID arbitrarily far apart.
                positions.copy(nextStream = turn % 2, emittedIds = seenIds).encode()
            } else {
                positions.encode()
            }
        } else {
            null
        }
        return MergedPage(items = merged, nextCursor = nextCursor, hasMore = hasMore)
    }
}
