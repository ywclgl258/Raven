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
 * ExHentai (`SiteId.EXHENTAI`) plugin backed by [EhSharedEngine] with hard authentication gating
 * (`requiresAuthGate = true`) and live access verification prior to persisting any imported session.
 */
class ExhentaiSitePlugin(
    httpClient: HttpClientAdapter,
    sessionStore: SiteSessionStore,
) : SitePlugin {

    override val descriptor: SiteDescriptor = EhSharedEngine.buildDescriptor(
        id = SiteId.EXHENTAI,
        displayName = "ExHentai",
        defaultHost = "exhentai.org",
        accessNote = "需登录及账号权限",
        requiresAuthGate = true,
    )

    private val engine = EhSharedEngine(
        descriptor = descriptor,
        httpClient = httpClient,
        sessionStore = sessionStore,
        requiresAuthGate = true,
    )

    override val feedSource: FeedSource = engine
    override val gallerySource: GallerySource = engine
    override val searchSource: SearchSource = engine
    override val authProvider: AuthProvider = engine
    override val linkResolver: LinkResolver = engine
}
