package com.raven.app.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionLayoutTest {

    @Test
    fun `collection waterfall adapts between two and four columns`() {
        assertEquals(2, computeCollectionWaterfallColumns(360))
        assertEquals(2, computeCollectionWaterfallColumns(599))
        assertEquals(3, computeCollectionWaterfallColumns(600))
        assertEquals(3, computeCollectionWaterfallColumns(839))
        assertEquals(4, computeCollectionWaterfallColumns(840))
        assertEquals(4, computeCollectionWaterfallColumns(1400))
    }

    @Test
    fun `collection layout defaults to waterfall and toggles both ways`() {
        assertEquals(LibraryCollectionLayout.WATERFALL, defaultCollectionLayout())
        assertEquals(LibraryCollectionLayout.LIST, LibraryCollectionLayout.WATERFALL.toggled())
        assertEquals(LibraryCollectionLayout.WATERFALL, LibraryCollectionLayout.LIST.toggled())
    }
}
