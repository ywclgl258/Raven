package com.raven.app

import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.TagRef
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryDetailSearchRouteTest {

    @Test
    fun authorTapOpensCrossSiteSearchWithTrimmedDisplayName() {
        val author = AuthorRef(id = "author-1", name = "  Alice  ")

        assertEquals(
            RavenRoute.Search(initialQuery = "Alice", crossSite = true),
            authorSearchRoute(author, crossSite = true),
        )
    }

    @Test
    fun authorSingleSiteSearchFallsBackToIdWhenNameIsBlank() {
        val author = AuthorRef(id = " author-42 ", name = "   ")

        assertEquals(
            RavenRoute.Search(initialQuery = "author-42", crossSite = false),
            authorSearchRoute(author, crossSite = false),
        )
    }

    @Test
    fun tagSearchUsesCanonicalSlugInsteadOfTranslatedLabel() {
        val tag = TagRef(slug = " solo-female ", label = "单人女性", namespace = "female")

        assertEquals(
            RavenRoute.Search(initialQuery = "solo-female", crossSite = false),
            tagSearchRoute(tag, crossSite = false),
        )
        assertEquals(
            RavenRoute.Search(initialQuery = "solo-female", crossSite = true),
            tagSearchRoute(tag, crossSite = true),
        )
    }

    @Test
    fun tagSearchFallsBackToRawLabelWhenSlugIsMissing() {
        val tag = TagRef(slug = "", label = " solo female ")

        assertEquals(
            RavenRoute.Search(initialQuery = "solo female", crossSite = true),
            tagSearchRoute(tag, crossSite = true),
        )
    }
}
