package com.raven.app.core.model

import kotlinx.serialization.Serializable

/**
 * Strongly typed site identifier. Serializes transparently as a plain JSON string.
 * Explicit [SiteId] is required on all domain entities and requests — never rely on mutable global site state.
 */
@Serializable
@JvmInline
value class SiteId(val value: String) {
    init {
        require(value.isNotBlank()) { "SiteId must not be blank" }
    }

    val isLive: Boolean get() = this in LIVE_SET
    val isLocal: Boolean get() = this == LOCAL
    val isGlobal: Boolean get() = this == GLOBAL
    val isRetired: Boolean get() = this in RETIRED_HOSTS
    val isKnown: Boolean get() = isLive || isLocal || isRetired || isGlobal

    override fun toString(): String = value

    companion object {
        // Video sites (8)
        val R34V = SiteId("r34v")
        val HANIME1 = SiteId("hanime1")
        val IWARA = SiteId("iwara")
        val MISSAV = SiteId("missav")
        val JABLE = SiteId("jable")
        val ROUVIDEO = SiteId("rouvideo")
        val KBJFREE = SiteId("kbjfree")
        val PORNHUB = SiteId("pornhub")

        // Comic sites (6)
        val EHENTAI = SiteId("ehentai")
        val EXHENTAI = SiteId("exhentai")
        val NHENTAI = SiteId("nhentai")
        val HITOMI = SiteId("hitomi")
        val WNACG = SiteId("wnacg")
        val JMCOMIC = SiteId("jmcomic")

        // Photo / Gallery sites (5)
        val FOURKHD = SiteId("4khd")
        val EVERIA = SiteId("everia")
        val FOURKUP = SiteId("4kup")
        val GALLERYEPIC = SiteId("galleryepic")
        val COSPLAYTELE = SiteId("cosplaytele")

        // Local non-network library source
        val LOCAL = SiteId("local")

        // Global cross-site wildcard scope (used by global blocked terms; excluded from ALL_LIVE)
        val GLOBAL = SiteId("*")

        // Retired video site identities retained only for historical records & backups
        val RETIRED_KBJ = SiteId("kbj")
        val RETIRED_NETFLAV = SiteId("netflav")

        val VIDEO_SITES: List<SiteId> = listOf(
            R34V, HANIME1, IWARA, MISSAV, JABLE, ROUVIDEO, KBJFREE, PORNHUB,
        )

        val COMIC_SITES: List<SiteId> = listOf(
            EHENTAI, EXHENTAI, NHENTAI, HITOMI, WNACG, JMCOMIC,
        )

        val PHOTO_SITES: List<SiteId> = listOf(
            FOURKHD, EVERIA, FOURKUP, GALLERYEPIC, COSPLAYTELE,
        )

        /** All 19 live network sites in switcher display order. */
        val ALL_LIVE: List<SiteId> = VIDEO_SITES + COMIC_SITES + PHOTO_SITES

        private val LIVE_SET: Set<SiteId> = ALL_LIVE.toSet()

        /** All sources that may appear in personal library records (19 live + local). */
        val LIBRARY_SOURCES: List<SiteId> = ALL_LIVE + LOCAL

        val RETIRED_HOSTS: Map<SiteId, String> = mapOf(
            RETIRED_KBJ to "kbj.im",
            RETIRED_NETFLAV to "netflav.com",
        )

        fun of(raw: String): SiteId = SiteId(raw.trim().lowercase())
    }
}

@Serializable
enum class MediaKind {
    VIDEO,
    COMIC,
    PHOTO;

    val isVideo: Boolean get() = this == VIDEO
    val isGallery: Boolean get() = this == COMIC || this == PHOTO
}

@Serializable
enum class SiteCapability {
    FEED,
    EXPLORE,
    SEARCH,
    ADVANCED_SEARCH,
    DIRECTORY,
    TAGS,
    AUTHORS,
    CHARACTERS,
    COMMENTS,
    RELATED,
    AUTH,
    COOKIE_IMPORT,
    MIRRORS,
    LANGUAGE_FILTER,
    ORIGINAL_IMAGES,
    STREAM_QUALITIES,
    DUAL_MEDIA,
    PREVIEW_ONLY,
}

@Serializable
enum class FeedSort {
    DEFAULT,
    NEWEST,
    OLDEST,
    POPULAR,
    TRENDING,
    VIEWS,
    LIKES,
    RATING,
    DURATION,
    RELEVANCE,
    RANDOM,
}

@Serializable
enum class ComicLanguage {
    ALL,
    CHINESE,
    JAPANESE,
    CHINESE_AND_JAPANESE,
}

@Serializable
enum class SearchScope {
    ALL,
    TAG,
    AUTHOR,
    CHARACTER,
}

@Serializable
enum class AdvancedFilterField {
    INCLUDE_TAGS,
    EXCLUDE_TAGS,
    CATEGORY,
    YEAR,
    MONTH,
    DURATION,
    BRAND,
    PAGES,
    RATING,
    LANGUAGE,
}

@Serializable
data class SiteSectionSpec(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val category: String = "",
    val defaultSort: FeedSort = FeedSort.NEWEST,
    val mediaKind: MediaKind? = null,
    val isDirectory: Boolean = false,
)

@Serializable
data class SiteNetworkProfile(
    val userAgent: String = DEFAULT_MOBILE_UA,
    val referer: String = "",
    val mediaReferer: String = "",
    val apiBase: String = "",
    val apiHosts: Set<String> = emptySet(),
    val mediaDomains: Set<String> = emptySet(),
    val pageIntervalMs: Long = 400L,
    val imageIntervalMs: Long = 150L,
    val disableFeedSnapshot: Boolean = false,
    val requiresAuthGate: Boolean = false,
) {
    companion object {
        const val DEFAULT_MOBILE_UA: String =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_WINDOWS_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        const val RAVEN_API_UA: String = "Raven/1.0 (Android)"
    }
}

/**
 * Immutable declarative descriptor of a site plugin.
 * Carries everything routing, network, UI switcher, and search layers need without hard-coding site IDs.
 */
@Serializable
data class SiteDescriptor(
    val id: SiteId,
    val displayName: String,
    val shortName: String = displayName,
    val primaryKind: MediaKind,
    val supportedKinds: Set<MediaKind> = setOf(primaryKind),
    val defaultHost: String,
    val mirrorHosts: List<String> = emptyList(),
    val capabilities: Set<SiteCapability> = emptySet(),
    val accessNote: String? = null,
    val feedSorts: List<FeedSort> = listOf(FeedSort.NEWEST),
    val defaultLanguage: ComicLanguage = ComicLanguage.ALL,
    val sections: List<SiteSectionSpec> = emptyList(),
    val searchScopes: List<SearchScope> = listOf(SearchScope.ALL),
    val supportedFilters: Set<AdvancedFilterField> = emptySet(),
    val networkProfile: SiteNetworkProfile = SiteNetworkProfile(),
) {
    val baseUrl: String get() = if (defaultHost.isBlank()) "" else "https://$defaultHost"

    val allHosts: List<String>
        get() = buildList {
            if (defaultHost.isNotBlank()) add(defaultHost.lowercase())
            mirrorHosts.forEach { host ->
                val normalized = host.trim().lowercase()
                if (normalized.isNotBlank() && normalized !in this) add(normalized)
            }
        }

    fun hasCapability(capability: SiteCapability): Boolean = capability in capabilities

    fun matchesHost(hostname: String): Boolean {
        val candidate = hostname.trim().lowercase()
        if (candidate.isEmpty()) return false
        return allHosts.any { known ->
            val bare = known.removePrefix("www.")
            candidate == known || candidate == bare || candidate == "www.$bare"
        }
    }

    fun matchesRequestHost(hostname: String): Boolean {
        val candidate = hostname.trim().lowercase()
        if (candidate.isEmpty()) return false
        return matchesHost(candidate) || networkProfile.apiHosts.any { it.equals(candidate, ignoreCase = true) }
    }

    fun matchesMediaHost(hostname: String): Boolean {
        val candidate = hostname.trim().lowercase()
        if (candidate.isEmpty()) return false
        if (matchesRequestHost(candidate)) return true
        return networkProfile.mediaDomains.any { domain ->
            val norm = domain.trim().lowercase()
            candidate == norm || candidate.endsWith(".$norm")
        }
    }
}
