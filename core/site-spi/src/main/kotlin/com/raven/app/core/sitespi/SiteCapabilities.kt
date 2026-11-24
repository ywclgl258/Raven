package com.raven.app.core.sitespi

import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DirectoryEntry
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.model.VideoDetail
import com.raven.app.core.model.VideoStream
import java.net.URI

interface FeedSource {
    suspend fun loadHome(language: ComicLanguage = ComicLanguage.ALL): HomeFeed
    suspend fun loadFeed(request: FeedRequest): FeedPage
    fun supportedSorts(request: FeedRequest): List<FeedSort> = listOf(FeedSort.NEWEST)
}

interface VideoSource {
    suspend fun loadVideoDetail(url: String): VideoDetail
    suspend fun refreshStreams(url: String): List<VideoStream> = loadVideoDetail(url).streams
}

interface GallerySource {
    suspend fun loadGalleryDetail(url: String): GalleryDetail
    suspend fun loadGalleryPage(
        detail: GalleryDetail,
        pageIndex: Int,
        forceReload: Boolean = false,
    ): GalleryPage

    suspend fun resolvePageImage(
        pageUrl: String,
        preferOriginal: Boolean = false,
        forceReload: Boolean = false,
    ): GalleryPage

    /**
     * Site-specific recovery URL when image download fails (e.g., E-Hentai `?nl=<token>` on 403/TLS/redirect).
     */
    fun reloadUrlOnError(cause: SiteException, page: GalleryPage): String? = null

    /**
     * When true, cross-host image redirects are treated as error/reload triggers (e.g., E-Hentai/ExHentai).
     */
    val rejectCrossHostImageRedirect: Boolean get() = false
}

interface SearchSource {
    suspend fun search(request: FeedRequest): FeedPage
    val supportedScopes: Set<SearchScope> get() = setOf(SearchScope.ALL)
    val supportedFilters: Set<AdvancedFilterField> get() = emptySet()
    val supportsExcludeTags: Boolean get() = false
    val isDirectorySearch: Boolean get() = false
}

interface DirectorySource {
    suspend fun loadDirectory(
        section: String,
        page: Int = 1,
        query: String? = null,
    ): List<DirectoryEntry>

    fun directorySections(): List<SiteSectionSpec> = emptyList()
}

sealed interface AuthMode {
    data object None : AuthMode
    data object FormCredentials : AuthMode
    data object JwtBearer : AuthMode
    data class WebViewCookie(
        val loginUrl: String,
        val targetUrl: String,
        val allowedHosts: Set<String>,
        val requiredCookies: Set<String>,
        val optionalCookies: Set<String> = emptySet(),
    ) : AuthMode

    data class CookieImport(
        val requiredCookies: Set<String>,
        val optionalCookies: Set<String> = emptySet(),
    ) : AuthMode
}

data class AuthState(
    val isAuthenticated: Boolean,
    val username: String? = null,
    val epoch: Long = 0L,
)

interface AuthProvider {
    val mode: AuthMode
    val requiresAuthGate: Boolean get() = false

    suspend fun loginWithCredentials(username: String, password: String): AuthState =
        throw SiteException.Unsupported("Credential login is not supported for this site")

    suspend fun importCookies(cookies: Map<String, String>): AuthState =
        throw SiteException.Unsupported("Cookie import is not supported for this site")

    suspend fun logout() {}

    suspend fun currentAuthState(): AuthState = AuthState(isAuthenticated = false)

    /**
     * Always-attached non-sensitive site cookies (e.g., E-Hentai/ExHentai `nw=1`).
     */
    fun defaultCookies(host: String): Map<String, String> = emptyMap()

    /**
     * Site-specific per-host request headers (e.g., Iwara `Origin`/`x-site` and `Authorization` strictly on API hosts).
     */
    fun decorateRequestHeaders(request: SiteHttpRequest, host: String): Map<String, String> = emptyMap()

    data object None : AuthProvider {
        override val mode: AuthMode = AuthMode.None
    }
}

sealed interface ResolvedLink {
    data class Feed(val request: FeedRequest) : ResolvedLink
    data class Video(val siteId: SiteId, val id: String, val canonicalUrl: String) : ResolvedLink
    data class Gallery(
        val siteId: SiteId,
        val id: String,
        val canonicalUrl: String,
        val pageIndex: Int? = null,
    ) : ResolvedLink

    data class Directory(val siteId: SiteId, val section: String, val canonicalUrl: String) : ResolvedLink
}

interface LinkResolver {
    fun canHandle(url: String): Boolean
    fun canonicalize(url: String): String?
    fun resolve(url: String): ResolvedLink?
}

interface HostRouter {
    suspend fun activeHost(routeKey: String = "default"): String
    fun candidateHosts(): List<String>
    fun rewriteUrl(url: String, host: String): String
    fun reportHostFailure(host: String, routeKey: String = "default", cause: Throwable? = null) {}
    fun reportHostSuccess(host: String, routeKey: String = "default") {}

    class Standard(hosts: List<String>) : HostRouter {
        private val normalizedHosts: List<String> = hosts
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        override suspend fun activeHost(routeKey: String): String =
            normalizedHosts.firstOrNull().orEmpty()

        override fun candidateHosts(): List<String> = normalizedHosts

        override fun rewriteUrl(url: String, host: String): String {
            val targetHost = host.trim().lowercase()
            if (targetHost.isEmpty()) return url
            val uri = runCatching { URI(url) }.getOrNull() ?: return url
            val scheme = uri.scheme?.lowercase() ?: "https"
            val portPart = if (uri.port > 0 && uri.port != 443 && uri.port != 80) ":${uri.port}" else ""
            val pathPart = uri.rawPath.orEmpty()
            val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragmentPart = uri.rawFragment?.let { "#$it" }.orEmpty()
            return "$scheme://$targetHost$portPart$pathPart$queryPart$fragmentPart"
        }
    }
}

data class UnwrappedMedia(
    val bytes: ByteArray,
    val contentType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnwrappedMedia) return false
        return bytes.contentEquals(other.bytes) && contentType == other.contentType
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
}

/**
 * Hook for site-specific playback transport behavior (e.g., rou.video `/api/hls/` MIME & PNG `roUd` unwrapping).
 */
interface MediaTransportHook {
    fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

    fun requiresFullResponseUnwrap(url: String): Boolean = false

    fun unwrapMediaBytes(url: String, bytes: ByteArray): UnwrappedMedia? = null

    fun extraMediaHeaders(url: String): Map<String, String> = emptyMap()

    data object None : MediaTransportHook
}

data class StripSlice(
    val sourceTop: Int,
    val destinationTop: Int,
    val height: Int,
)

/**
 * Pure-Kotlin geometry contract for site-specific scrambled gallery pages (e.g., JMComic strip reversal).
 */
interface ImageUnscrambler {
    fun computeStripCount(chapterId: Long, scrambleId: Long, filenameStem: String): Int
    fun computeSlicePlan(imageHeight: Int, stripCount: Int): List<StripSlice>

    data object None : ImageUnscrambler {
        override fun computeStripCount(chapterId: Long, scrambleId: Long, filenameStem: String): Int = 0
        override fun computeSlicePlan(imageHeight: Int, stripCount: Int): List<StripSlice> =
            if (imageHeight <= 0) emptyList() else listOf(StripSlice(0, 0, imageHeight))
    }
}
