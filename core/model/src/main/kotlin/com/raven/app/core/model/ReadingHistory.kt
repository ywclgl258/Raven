package com.raven.app.core.model

val ReadingProgress.workTitle: String
    get() = seriesTitle ?: if (workId == mediaId) title else title.substringBefore(" · ").ifBlank { title }

/** Keep the resume address and page on the latest chapter, even when an older chapter was unfinished. */
fun List<ReadingProgress>.latestReadingByWork(): List<ReadingProgress> {
    val works = LinkedHashMap<Pair<SiteId, String>, ReadingProgress>()
    for (progress in this) {
        val id = progress.workId
        val key = progress.siteId to id
        val previous = works[key]
        if (previous == null) {
            works[key] = if (progress.seriesId == null && id != progress.mediaId) progress.copy(seriesId = id) else progress
            continue
        }
        val newer = progress.updatedAt > previous.updatedAt ||
            (progress.updatedAt == previous.updatedAt && progress.mediaId > previous.mediaId)
        val latest = if (newer) progress else previous
        val older = if (newer) previous else progress
        val title = latest.seriesTitle ?: older.seriesTitle
            ?: latest.workTitle.takeUnless { it == id } ?: older.workTitle.takeUnless { it == id }
        val url = latest.seriesUrl ?: older.seriesUrl
            ?: latest.url.takeIf { latest.mediaId == id } ?: older.url.takeIf { older.mediaId == id }
        val cover = latest.coverUrl.ifBlank { older.coverUrl }
        works[key] = if (latest.seriesId == id && latest.seriesTitle == title && latest.seriesUrl == url && latest.coverUrl == cover) {
            latest
        } else {
            latest.copy(seriesId = id, seriesTitle = title, seriesUrl = url, coverUrl = cover)
        }
    }
    return works.values.sortedByDescending { it.updatedAt }
}
