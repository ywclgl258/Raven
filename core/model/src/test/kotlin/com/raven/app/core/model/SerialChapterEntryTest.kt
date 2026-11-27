package com.raven.app.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialChapterEntryTest {

    private fun item(
        id: String,
        kind: MediaKind,
        extra: Map<String, String> = emptyMap(),
    ): MediaItem = MediaItem(
        siteId = SiteId.MANGACOPY,
        id = id,
        url = "https://example.invalid/$id",
        kind = kind,
        title = "chapter $id",
        extra = extra,
    )

    @Test
    fun `chapter of a serial comic opens as readable content`() {
        assertTrue(item("work-1:c2", MediaKind.SERIAL_COMIC, mapOf("comicId" to "work-1")).isSerialChapter())
        assertTrue(item("work-1:c2", MediaKind.SERIAL_COMIC, mapOf("seriesId" to "work-1")).isSerialChapter())
    }

    @Test
    fun `work root stays a detail page`() {
        assertFalse(item("work-1", MediaKind.SERIAL_COMIC, mapOf("comicId" to "work-1")).isSerialChapter())
        assertFalse(item("work-1", MediaKind.SERIAL_COMIC).isSerialChapter())
    }

    @Test
    fun `one-shot comics and photos are never chapters`() {
        assertFalse(item("comic-9", MediaKind.COMIC, mapOf("comicId" to "work-9")).isSerialChapter())
        assertFalse(item("photo-9", MediaKind.PHOTO).isSerialChapter())
        assertFalse(item("video-9", MediaKind.VIDEO).isSerialChapter())
    }
}
