package com.raven.app

import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
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

    @Test
    fun galleryTagSearchCarriesSourceSiteSoNonActiveSiteIsTargeted() {
        val tag = TagRef(slug = "blonde", label = "金发")

        assertEquals(
            RavenRoute.Search(initialQuery = "blonde", crossSite = false, sourceSiteId = SiteId.IWARA.value),
            tagSearchRoute(tag, crossSite = false, sourceSiteId = SiteId.IWARA),
        )
    }

    @Test
    fun galleryAuthorSearchCarriesSourceSiteSoNonActiveSiteIsTargeted() {
        val author = AuthorRef(id = "a-1", name = "Alice")

        assertEquals(
            RavenRoute.Search(initialQuery = "Alice", crossSite = false, sourceSiteId = SiteId.NHENTAI.value),
            authorSearchRoute(author, crossSite = false, sourceSiteId = SiteId.NHENTAI),
        )
    }
}
