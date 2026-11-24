package com.raven.app

import android.content.Context
import android.content.Intent
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.serialization.json.Json

/**
 * Clean Intent contract between `PlayerActivity` / external `ACTION_SEND` intents and
 * [MainActivity] (`launchMode = "singleTop"`), eliminating multi-Activity stack leaks.
 */
object MainActivityIntentContract {

    const val EXTRA_NAVIGATE_ROUTE: String = "com.raven.app.extra.NAVIGATE_ROUTE"

    private val routeJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeRoute(route: RavenRoute): String =
        routeJson.encodeToString(RavenRoute.serializer(), route)

    fun decodeRoute(raw: String?): RavenRoute? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            routeJson.decodeFromString(RavenRoute.serializer(), raw)
        }.getOrNull()
    }

    fun createNavigateIntent(context: Context, route: RavenRoute): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_NAVIGATE_ROUTE, encodeRoute(route))
        }

    fun parseNavigateRoute(intent: Intent?): RavenRoute? {
        if (intent == null) return null
        return decodeRoute(intent.getStringExtra(EXTRA_NAVIGATE_ROUTE))
    }

    fun extractSharedText(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isNotEmpty()) return text
        return intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun resolveTagFeedRoute(
        siteRegistry: SiteRegistry,
        siteId: SiteId,
        tag: TagRef,
    ): RavenRoute.SiteFeed {
        val label = tag.label.ifBlank { tag.slug }
        val resolved = tag.url.takeIf { it.isNotBlank() }?.let { url ->
            siteRegistry.get(siteId)?.linkResolver?.takeIf { it.canHandle(url) }?.resolve(url)
        }
        if (resolved is ResolvedLink.Feed) {
            return RavenRoute.SiteFeed(
                siteId = resolved.request.siteId.value,
                section = resolved.request.section,
                category = resolved.request.category,
                query = resolved.request.query,
                scope = resolved.request.scope.name,
                title = label,
            )
        }
        return RavenRoute.SiteFeed(
            siteId = siteId.value,
            query = tag.slug.ifBlank { label },
            scope = SearchScope.TAG.name,
            title = label,
        )
    }

    fun resolveAuthorFeedRoute(
        siteRegistry: SiteRegistry,
        siteId: SiteId,
        author: AuthorRef,
    ): RavenRoute.SiteFeed {
        val display = author.name.ifBlank { author.id }
        val resolved = author.url.takeIf { it.isNotBlank() }?.let { url ->
            siteRegistry.get(siteId)?.linkResolver?.takeIf { it.canHandle(url) }?.resolve(url)
        }
        if (resolved is ResolvedLink.Feed) {
            return RavenRoute.SiteFeed(
                siteId = resolved.request.siteId.value,
                section = resolved.request.section,
                category = resolved.request.category,
                query = resolved.request.query,
                scope = resolved.request.scope.name,
                title = display,
            )
        }
        return RavenRoute.SiteFeed(
            siteId = siteId.value,
            query = author.id.ifBlank { display },
            scope = SearchScope.AUTHOR.name,
            title = display,
        )
    }
}
