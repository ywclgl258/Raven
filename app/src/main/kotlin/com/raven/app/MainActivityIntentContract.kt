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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlinx.serialization.json.Json

/**
 * Clean Intent contract between `PlayerActivity` / external `ACTION_SEND` intents and
 * [MainActivity] (`launchMode = "singleTop"`), eliminating multi-Activity stack leaks.
 */
object MainActivityIntentContract {

    const val EXTRA_NAVIGATE_ROUTE: String = "com.raven.app.extra.NAVIGATE_ROUTE"
    private const val EXTRA_INTERNAL_TOKEN: String = "com.raven.app.extra.INTERNAL_TOKEN"

    private val internalTokenBytes: ByteArray = ByteArray(16).apply {
        SecureRandom().nextBytes(this)
    }
    private val internalToken: String = internalTokenBytes.joinToString("") { b ->
        String.format(Locale.ROOT, "%02x", b)
    }

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
            putExtra(EXTRA_INTERNAL_TOKEN, internalToken)
        }

    fun parseNavigateRoute(intent: Intent?): RavenRoute? {
        if (intent == null) return null
        return parseAuthorizedNavigateRoute(
            rawRoute = intent.getStringExtra(EXTRA_NAVIGATE_ROUTE),
            suppliedToken = intent.getStringExtra(EXTRA_INTERNAL_TOKEN),
        )
    }

    internal fun parseAuthorizedNavigateRoute(
        rawRoute: String?,
        suppliedToken: String?,
    ): RavenRoute? {
        if (suppliedToken == null) return null
        val matches = MessageDigest.isEqual(
            suppliedToken.toByteArray(Charsets.UTF_8),
            internalTokenBytesHexBytes,
        )
        if (!matches) return null
        return decodeRoute(rawRoute)
    }

    internal fun internalTokenForTest(): String = internalToken

    private val internalTokenBytesHexBytes: ByteArray = internalToken.toByteArray(Charsets.UTF_8)

    fun extractSharedText(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isNotEmpty()) return text
        return intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun resolveTagRoute(
        siteRegistry: SiteRegistry,
        siteId: SiteId,
        tag: TagRef,
    ): RavenRoute {
        val label = tag.label.ifBlank { tag.slug }
        val resolved = tag.url.takeIf { it.isNotBlank() }?.let { url ->
            siteRegistry.get(siteId)?.linkResolver?.takeIf { it.canHandle(url) }?.resolve(url)
        }
        if (resolved is ResolvedLink.Directory) {
            return RavenRoute.SiteDirectory(
                siteId = resolved.siteId.value,
                section = resolved.section,
            )
        }
        return resolveTagFeedRoute(siteRegistry = siteRegistry, siteId = siteId, tag = tag)
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
