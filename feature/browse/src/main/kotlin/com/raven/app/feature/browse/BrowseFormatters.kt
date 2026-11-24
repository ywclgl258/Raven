package com.raven.app.feature.browse

import com.raven.app.core.common.TagDictionary
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteException

fun FeedSort.displayLabel(): String = when (this) {
    FeedSort.DEFAULT -> "默认"
    FeedSort.RELEVANCE -> "相关度"
    FeedSort.NEWEST -> "最新"
    FeedSort.OLDEST -> "最旧"
    FeedSort.VIEWS -> "最多播放"
    FeedSort.LIKES -> "最多赞"
    FeedSort.POPULAR -> "最热门"
    FeedSort.TRENDING -> "趋势飙升"
    FeedSort.RATING -> "最高评分"
    FeedSort.DURATION -> "最长时长"
    FeedSort.RANDOM -> "随机"
}

fun SearchScope.displayLabel(): String = when (this) {
    SearchScope.ALL -> "全部"
    SearchScope.TAG -> "标签"
    SearchScope.AUTHOR -> "作者"
    SearchScope.CHARACTER -> "角色"
}

fun MediaKind.displayLabel(): String = when (this) {
    MediaKind.VIDEO -> "视频"
    MediaKind.COMIC -> "漫画"
    MediaKind.PHOTO -> "写真"
}

fun ReaderMode.displayLabel(): String = when (this) {
    ReaderMode.STRIP -> "条漫下拉"
    ReaderMode.LTR -> "从左到右"
    ReaderMode.RTL -> "从右到左"
    ReaderMode.SPREAD -> "双页跨页"
}

fun Throwable.isAuthError(): Boolean = when (this) {
    is SiteException.AuthRequired -> true
    is SiteException.Forbidden -> !isChallenge
    else -> false
}

fun Throwable.toBrowseErrorMessage(): String = when (this) {
    is SiteException.AuthRequired -> message.ifBlank { "此站点需要登录后才能访问" }
    is SiteException.Forbidden -> if (isChallenge) {
        message.ifBlank { "触发站点访问验证，请稍后重试或检查网络代理" }
    } else {
        message.ifBlank { "暂无访问权限，请检查站点登录状态" }
    }
    is SiteException.RateLimited -> {
        val retry = retryAfterSeconds
        if (retry != null && retry > 0L) {
            "请求过于频繁，请 ${retry} 秒后再试"
        } else {
            message.ifBlank { "请求过于频繁，请稍后重试" }
        }
    }
    is SiteException.NotFound -> message.ifBlank { "未找到对应内容" }
    is SiteException.QuotaExceeded -> message.ifBlank { "站点配额已达上限，请稍后再试" }
    is SiteException.TlsError -> message.ifBlank { "安全连接验证失败，请检查系统时间或网络环境" }
    is SiteException.ParseError -> message.ifBlank { "页面解析异常，站点结构可能已更新" }
    is SiteException.Unsupported -> message.ifBlank { "当前站点不支持此操作" }
    is SiteException.Network -> message.ifBlank { "网络连接失败，请检查网络或代理设置" }
    is SiteException.Cancelled -> "请求已取消"
    else -> message?.takeIf { it.isNotBlank() } ?: "加载失败，请稍后重试"
}

/**
 * Responsive column count for frameless comic & photo masonry grids (`ARCHITECTURE.md` §4.3):
 * 2 cols phone / 3 cols >= 600dp / 4 cols >= 840dp / 5 cols >= 1024dp / 6 cols >= 1280dp.
 */
fun computeGalleryGridColumns(widthDp: Int): Int = when {
    widthDp >= 1280 -> 6
    widthDp >= 1024 -> 5
    widthDp >= 840 -> 4
    widthDp >= 600 -> 3
    else -> 2
}

/**
 * Responsive column count for 16:9 cinema video grids on Explore, SiteFeed, Search, and Home tail.
 */
fun computeVideoGridColumns(widthDp: Int): Int = when {
    widthDp >= 1280 -> 5
    widthDp >= 960 -> 4
    widthDp >= 600 -> 3
    else -> 2
}

/**
 * Responsive column count for the compact Site Switcher grid so all 19 sites remain scannable.
 */
fun computeSiteSwitcherColumns(widthDp: Int): Int = when {
    widthDp >= 720 -> 4
    widthDp >= 480 -> 3
    else -> 2
}

fun MediaItem.toShelfItem(shelfId: String = Shelf.WATCH_LATER_ID): ShelfItem = ShelfItem(
    shelfId = shelfId,
    siteId = siteId,
    mediaId = id,
    url = url,
    kind = kind,
    title = title,
    coverUrl = coverUrl,
    durationSeconds = durationSeconds,
    pageCount = pageCount,
)

fun MediaItem.withLocalizedTags(
    tagDictionary: TagDictionary,
    translateTags: Boolean,
): MediaItem {
    if (!translateTags || tags.isEmpty()) return this
    var changed = false
    val mapped = tags.map { tag ->
        val translated = tagDictionary.translate(tag.slug) ?: tagDictionary.translate(tag.label)
        if (translated != null && translated != tag.label) {
            changed = true
            tag.copy(label = translated)
        } else {
            tag
        }
    }
    return if (changed) copy(tags = mapped) else this
}

fun List<MediaItem>.withLocalizedTags(
    tagDictionary: TagDictionary,
    translateTags: Boolean,
): List<MediaItem> = if (!translateTags || isEmpty()) {
    this
} else {
    map { it.withLocalizedTags(tagDictionary, translateTags = true) }
}

fun AdvancedSearchFilters.activeFilterCount(language: ComicLanguage = ComicLanguage.ALL): Int {
    var count = 0
    if (includeTags.isNotEmpty()) count++
    if (excludeTags.isNotEmpty()) count++
    if (!category.isNullOrBlank()) count++
    if (!brand.isNullOrBlank()) count++
    if (year != null) count++
    if (month != null) count++
    if (minDurationMinutes != null || maxDurationMinutes != null) count++
    if (minPages != null || maxPages != null) count++
    val rating = minRating
    if (rating != null && rating > 0) count++
    if (language != ComicLanguage.ALL) count++
    return count
}

/**
 * Extracts the active token under the cursor (trailing whitespace-delimited segment) for
 * live TagDictionary suggestion lookup, preserving leading tokens when inserted back.
 */
internal fun extractLastQueryToken(query: String): String {
    if (query.isBlank() || query.endsWith(' ')) return ""
    return query.substringAfterLast(' ').trim()
}

internal fun applySuggestionToken(query: String, slug: String): String {
    val cleanSlug = slug.trim()
    if (cleanSlug.isEmpty()) return query
    val trimmedEnd = query.trimEnd()
    if (trimmedEnd.isEmpty()) return cleanSlug
    val lastSpace = query.lastIndexOf(' ')
    if (lastSpace < 0) {
        val prefix = if (query.trimStart().startsWith('-')) "-" else ""
        return "$prefix$cleanSlug"
    }
    val head = query.substring(0, lastSpace + 1)
    val tail = query.substring(lastSpace + 1)
    val prefix = if (tail.startsWith('-')) "-" else ""
    return "$head$prefix$cleanSlug"
}

internal fun mergeUniqueMediaItems(
    existing: List<MediaItem>,
    incoming: List<MediaItem>,
): List<MediaItem> {
    if (existing.isEmpty()) return incoming.distinctBy { it.compositeKey }
    if (incoming.isEmpty()) return existing
    val seen = existing.mapTo(HashSet(existing.size + incoming.size)) { it.compositeKey }
    val appended = incoming.filter { seen.add(it.compositeKey) }
    return if (appended.isEmpty()) existing else existing + appended
}

private val DATE_SORT_KEY_REGEX = Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?:[T\\s](\\d{1,2}):(\\d{2}))?")

internal fun parseDateSortKey(raw: String?): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val match = DATE_SORT_KEY_REGEX.find(text) ?: return null
    val year = match.groupValues[1].toLongOrNull() ?: return null
    val month = match.groupValues[2].toLongOrNull()?.coerceIn(1L, 12L) ?: return null
    val day = match.groupValues[3].toLongOrNull()?.coerceIn(1L, 31L) ?: return null
    val hour = match.groupValues[4].toLongOrNull()?.coerceIn(0L, 23L) ?: 0L
    val minute = match.groupValues[5].toLongOrNull()?.coerceIn(0L, 59L) ?: 0L
    return ((((year * 100L + month) * 100L + day) * 100L + hour) * 100L) + minute
}

internal fun sortMediaItemsClientSide(
    items: List<MediaItem>,
    sort: FeedSort,
): List<MediaItem> {
    if (items.size <= 1) return items
    return when (sort) {
        FeedSort.DEFAULT, FeedSort.RELEVANCE, FeedSort.TRENDING, FeedSort.RANDOM -> items
        FeedSort.VIEWS -> items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<MediaItem>> { it.value.views == null }
                    .thenByDescending { it.value.views ?: Long.MIN_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }
        FeedSort.LIKES -> items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<MediaItem>> { it.value.likes == null }
                    .thenByDescending { it.value.likes ?: Long.MIN_VALUE }
                    .thenBy { it.value.rating == null }
                    .thenByDescending { it.value.rating ?: Float.NEGATIVE_INFINITY }
                    .thenBy { it.index },
            )
            .map { it.value }
        FeedSort.RATING -> items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<MediaItem>> { it.value.rating == null }
                    .thenByDescending { it.value.rating ?: Float.NEGATIVE_INFINITY }
                    .thenBy { it.value.likes == null }
                    .thenByDescending { it.value.likes ?: Long.MIN_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }
        FeedSort.DURATION -> items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<MediaItem>> { it.value.durationSeconds == null }
                    .thenByDescending { it.value.durationSeconds ?: Int.MIN_VALUE }
                    .thenBy { it.value.pageCount == null }
                    .thenByDescending { it.value.pageCount ?: Int.MIN_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }
        FeedSort.POPULAR -> items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<MediaItem>> {
                    it.value.views == null && it.value.likes == null && it.value.rating == null
                }
                    .thenByDescending { it.value.views ?: Long.MIN_VALUE }
                    .thenByDescending { it.value.likes ?: Long.MIN_VALUE }
                    .thenByDescending { it.value.rating ?: Float.NEGATIVE_INFINITY }
                    .thenBy { it.index },
            )
            .map { it.value }
        FeedSort.NEWEST -> {
            val keyed = items.mapIndexed { idx, item -> Triple(idx, item, parseDateSortKey(item.dateLabel)) }
            if (keyed.any { it.third != null }) {
                keyed.sortedWith(
                    compareBy<Triple<Int, MediaItem, Long?>> { it.third == null }
                        .thenByDescending { it.third ?: Long.MIN_VALUE }
                        .thenBy { it.first },
                ).map { it.second }
            } else {
                items
            }
        }
        FeedSort.OLDEST -> {
            val keyed = items.mapIndexed { idx, item -> Triple(idx, item, parseDateSortKey(item.dateLabel)) }
            if (keyed.any { it.third != null }) {
                keyed.sortedWith(
                    compareBy<Triple<Int, MediaItem, Long?>> { it.third == null }
                        .thenBy { it.third ?: Long.MAX_VALUE }
                        .thenByDescending { it.first },
                ).map { it.second }
            } else {
                items.asReversed()
            }
        }
    }
}
