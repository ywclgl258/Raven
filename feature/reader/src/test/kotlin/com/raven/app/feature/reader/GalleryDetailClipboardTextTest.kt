package com.raven.app.feature.reader

import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.TagRef
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryDetailClipboardTextTest {

    @Test
    fun tagCopyPrefersReadableTranslatedLabel() {
        val tag = TagRef(slug = "solo-female", label = "solo female")

        assertEquals("单人女性", tagClipboardText(" 单人女性 ", tag))
    }

    @Test
    fun tagCopyFallsBackToRawLabelThenSlug() {
        assertEquals(
            "solo female",
            tagClipboardText(" ", TagRef(slug = "solo-female", label = " solo female ")),
        )
        assertEquals(
            "solo-female",
            tagClipboardText("", TagRef(slug = " solo-female ", label = " ")),
        )
    }

    @Test
    fun authorCopyUsesDisplayNameWithIdFallback() {
        assertEquals(
            "Alice",
            authorClipboardText(AuthorRef(id = "author-1", name = " Alice ")),
        )
        assertEquals(
            "author-1",
            authorClipboardText(AuthorRef(id = " author-1 ", name = " ")),
        )
    }
}
