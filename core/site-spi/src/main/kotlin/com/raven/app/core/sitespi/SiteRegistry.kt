package com.raven.app.core.sitespi

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId

/**
 * Immutable registry of all active [SitePlugin] implementations.
 * Intentionally holds NO mutable `current` site — active site selection lives in reactive UI/DataStore state.
 */
class SiteRegistry(
    plugins: Collection<SitePlugin>,
) {
    private val orderedPlugins: List<SitePlugin>
    private val byId: Map<SiteId, SitePlugin>

    init {
        val map = LinkedHashMap<SiteId, SitePlugin>(plugins.size)
        for (plugin in plugins) {
            val previous = map.put(plugin.id, plugin)
            require(previous == null) { "Duplicate SitePlugin registered for siteId=${plugin.id}" }
        }
        orderedPlugins = map.values.toList()
        byId = map
    }

    val size: Int get() = orderedPlugins.size

    fun allPlugins(): List<SitePlugin> = orderedPlugins

    fun allDescriptors(): List<SiteDescriptor> = orderedPlugins.map { it.descriptor }

    fun get(siteId: SiteId): SitePlugin? = byId[siteId]

    fun require(siteId: SiteId): SitePlugin = byId[siteId] ?: throw when {
        siteId.isRetired -> SiteException.Unsupported(
            message = "Site '${siteId.value}' has been retired and is no longer supported",
            siteId = siteId,
        )
        else -> SiteException.Unsupported(
            message = "No SitePlugin registered for '${siteId.value}'",
            siteId = siteId,
        )
    }

    fun byPrimaryKind(kind: MediaKind): List<SitePlugin> =
        orderedPlugins.filter { it.descriptor.primaryKind == kind }

    fun supportingKind(kind: MediaKind): List<SitePlugin> =
        orderedPlugins.filter { kind in it.descriptor.supportedKinds }

    fun withCapability(capability: SiteCapability): List<SitePlugin> =
        orderedPlugins.filter { it.descriptor.hasCapability(capability) }

    fun findForHost(hostname: String): SitePlugin? =
        orderedPlugins.firstOrNull { it.descriptor.matchesHost(hostname) }

    fun findForRequestHost(hostname: String): SitePlugin? =
        orderedPlugins.firstOrNull { it.descriptor.matchesRequestHost(hostname) }

    fun findForMediaHost(hostname: String): SitePlugin? =
        findForRequestHost(hostname)
            ?: orderedPlugins.firstOrNull { it.descriptor.matchesMediaHost(hostname) }

    /**
     * True only when [hostname] is a registered primary or mirror site host (never CDN or update hosts).
     */
    fun isRegisteredSiteHost(hostname: String): Boolean = findForHost(hostname) != null

    fun resolveLink(url: String): Pair<SitePlugin, ResolvedLink>? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        for (plugin in orderedPlugins) {
            val resolver = plugin.linkResolver ?: continue
            if (!resolver.canHandle(trimmed)) continue
            val resolved = resolver.resolve(trimmed) ?: continue
            return plugin to resolved
        }
        return null
    }

    fun resolveLinkFromText(text: String): Pair<SitePlugin, ResolvedLink>? {
        val candidates = runCatching { UrlSanitizer.extractUrlsFromText(text) }.getOrDefault(emptyList())
        for (candidate in candidates) {
            resolveLink(candidate)?.let { return it }
        }
        return null
    }
}
