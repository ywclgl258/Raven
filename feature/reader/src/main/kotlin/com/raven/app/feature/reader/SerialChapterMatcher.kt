package com.raven.app.feature.reader

import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.ReadingProgress

internal fun isMatchingSerialChapter(
    chapter: MediaItem,
    currentChapterId: String,
    currentChapterUrl: String,
    hints: List<String>,
): Boolean =
    chapter.id == currentChapterId ||
        (currentChapterUrl.isNotBlank() && chapter.url == currentChapterUrl) ||
        hints.any { hint -> hint.isNotBlank() && (chapter.id == hint || chapter.id.endsWith(":$hint")) }

/**
 * Optional read-state bundle consumed by the shared chapter picker.
 *
 * The reader's chapter sheet leaves every field at its default, so it keeps rendering exactly as
 * before; only serial gallery detail supplies per-chapter history and a next-unread target.
 */
data class ChapterPickerReadState(
    val progressByMediaId: Map<String, ReadingProgress> = emptyMap(),
    val nextUnreadChapter: MediaItem? = null,
    val nextUnreadBoundaryLabel: String? = null,
) {
    fun progressLabel(chapterId: String): String? = chapterProgressLabel(progressByMediaId[chapterId])

    fun isCompleted(chapterId: String): Boolean =
        progressByMediaId[chapterId]?.completed == true
}

/** "已读" / "读到 N 页" marker for a chapter tile; untouched chapters get no marker. */
internal fun chapterProgressLabel(progress: ReadingProgress?): String? = when {
    progress == null -> null
    progress.completed -> "已读"
    progress.currentPage > 0 -> "读到 ${progress.currentPage} 页"
    else -> null
}

private fun serialChapterHints(chapter: MediaItem): List<String> = listOfNotNull(
    chapter.extra["chapterId"],
    chapter.extra["chapterUuid"],
    chapter.extra["chapterHid"],
)

/**
 * First chapter strictly after [currentChapter] whose history is absent or unfinished.
 * Chapters before the current one are never suggested; a missing current match scans from the
 * beginning so an unrecognised entry still gets a useful target. Null means the boundary was hit.
 */
internal fun findNextUnreadChapter(
    chapters: List<MediaItem>,
    currentChapter: MediaItem,
    progressByMediaId: Map<String, ReadingProgress>,
): MediaItem? {
    if (chapters.isEmpty()) return null
    val currentIndex = chapters.indexOfFirst { chapter ->
        isMatchingSerialChapter(
            chapter = chapter,
            currentChapterId = currentChapter.id,
            currentChapterUrl = currentChapter.extra["chapterUrl"].orEmpty(),
            hints = serialChapterHints(currentChapter),
        )
    }
    val startIndex = (currentIndex + 1).coerceAtLeast(0)
    if (startIndex >= chapters.size) return null
    return chapters.subList(startIndex, chapters.size).firstOrNull { chapter ->
        progressByMediaId[chapter.id]?.completed != true
    }
}
