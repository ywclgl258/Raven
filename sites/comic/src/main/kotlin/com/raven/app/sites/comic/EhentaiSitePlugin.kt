package com.raven.app.sites.comic

import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteSessionStore

/**
 * E-Hentai (`SiteId.EHENTAI`) plugin backed by [EhSharedEngine].
 * Public browsing is allowed without login; optional WebView/cookie login unlocks member perks
 * and original-resolution `fullimg.php` access.
 */
class EhentaiSitePlugin(
    httpClient: HttpClientAdapter,
    sessionStore: SiteSessionStore,
) : SitePlugin {

    override val descriptor: SiteDescriptor = EhSharedEngine.buildDescriptor(
        id = SiteId.EHENTAI,
        displayName = "E-Hentai",
        defaultHost = "e-hentai.org",
        accessNote = null,
        requiresAuthGate = false,
    )

    private val engine = EhSharedEngine(
        descriptor = descriptor,
        httpClient = httpClient,
        sessionStore = sessionStore,
        requiresAuthGate = false,
    )

    override val feedSource: FeedSource = engine
    override val gallerySource: GallerySource = engine
    override val searchSource: SearchSource = engine
    override val authProvider: AuthProvider = engine
    override val linkResolver: LinkResolver = engine
}
