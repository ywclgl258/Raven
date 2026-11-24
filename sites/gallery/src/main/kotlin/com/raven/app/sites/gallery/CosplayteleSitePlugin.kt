package com.raven.app.sites.gallery

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.VideoDetail
import com.raven.app.core.model.VideoStream
import com.raven.app.core.sitespi.CossoraAesProtocol
import com.raven.app.core.sitespi.CossoraEmbed
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.MediaTransportHook
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import com.raven.app.core.sitespi.VideoSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Cosplaytele (`https://cosplaytele.com`) DUAL-MEDIA photo & embedded video plugin built
 * declaratively on [WordPressPhotoEngine].
 *
 * Key protocol invariants:
 * - Strict `#gallery-1` image isolation prevents trailing `.crp_related` recommendation thumbnails
 *   from leaking into the active photo set.
 * - Articles may embed `https://cossora.stream/embed/<uuid>` HLS videos encrypted with AES-256-CBC
 *   ([CossoraAesProtocol]). Embedded videos stay strictly separate from gallery pages: they are
 *   surfaced via `GalleryDetail.embeddedVideos` AND playable via this plugin's [VideoSource].
 */
class CosplayteleSitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    internal val engine: WordPressPhotoEngine = WordPressPhotoEngine(
        config = createConfig(),
        httpClient = httpClient,
    )

    override val descriptor: SiteDescriptor = engine.descriptor
    override val feedSource: FeedSource = engine
    override val gallerySource: GallerySource = engine
    override val searchSource: SearchSource = engine

    override val videoSource: VideoSource = object : VideoSource {
        override suspend fun loadVideoDetail(url: String): VideoDetail =
            resolveEmbeddedVideo(url)
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean =
            engine.canHandle(url) || parseDirectCossoraEmbed(url) != null

        override fun canonicalize(url: String): String? {
            parseDirectCossoraEmbed(url)?.let { return it.embedUrl }
            val rawUri = GalleryUrlUtils.parseUri(url) ?: return null
            val fragment = rawUri.rawFragment?.trim().orEmpty()
            if (VIDEO_FRAGMENT_REGEX.matches(fragment)) {
                val canonicalArticle = engine.canonicalize(url)?.substringBefore('#') ?: return null
                return "$canonicalArticle#${fragment.lowercase()}"
            }
            return engine.canonicalize(url)
        }

        override fun resolve(url: String): ResolvedLink? {
            parseDirectCossoraEmbed(url)?.let { embed ->
                return ResolvedLink.Video(
                    siteId = SiteId.COSPLAYTELE,
                    id = embed.id,
                    canonicalUrl = embed.embedUrl,
                )
            }
            if (!engine.canHandle(url)) return null
            val normalized = UrlSanitizer.normalizeHttpsUrl(url, preserveFragment = true) ?: return null
            val uri = GalleryUrlUtils.parseUri(normalized) ?: return null
            val fragment = uri.rawFragment?.trim().orEmpty()
            if (VIDEO_FRAGMENT_REGEX.matches(fragment)) {
                val canonicalArticle = engine.canonicalizePostUrl(normalized) ?: return null
                val videoId = fragment.lowercase()
                return ResolvedLink.Video(
                    siteId = SiteId.COSPLAYTELE,
                    id = videoId,
                    canonicalUrl = "$canonicalArticle#$videoId",
                )
            }
            return engine.resolve(url)
        }
    }

    override val mediaTransportHook: MediaTransportHook = object : MediaTransportHook {
        override fun extraMediaHeaders(url: String): Map<String, String> {
            val host = UrlSanitizer.extractHost(url) ?: return emptyMap()
            return if (UrlSanitizer.hostMatchesDomain(host, "cossora.stream")) {
                mapOf("Referer" to "https://cossora.stream/")
            } else {
                emptyMap()
            }
        }
    }

    private suspend fun resolveEmbeddedVideo(rawUrl: String): VideoDetail {
        // Direct Cossora embed URL support (`https://cossora.stream/embed/<uuid>`)
        val directEmbed = parseDirectCossoraEmbed(rawUrl)
        if (directEmbed != null) {
            val embedHtml = fetchEmbedHtml(directEmbed.embedUrl, referer = DESCRIPTOR.networkProfile.referer)
            val media = CossoraAesProtocol.parseEmbedHtml(embedHtml)
            val stream = toHlsStream(media.streamUrl, media.qualityLabel)
            return VideoDetail(
                item = MediaItem(
                    siteId = SiteId.COSPLAYTELE,
                    id = directEmbed.id,
                    url = directEmbed.embedUrl,
                    kind = MediaKind.VIDEO,
                    title = "文章内视频",
                    coverUrl = media.posterUrl,
                ),
                description = "Cosplaytele 文章内嵌 Cossora 视频。",
                streams = listOf(stream),
            )
        }

        // Standard Cosplaytele article URL (`https://cosplaytele.com/<slug>/[#video-<uuid>]`)
        val normalized = UrlSanitizer.normalizeHttpsUrl(rawUrl, preserveFragment = true)
            ?: throw SiteException.ParseError("Invalid Cosplaytele video URL: $rawUrl", SiteId.COSPLAYTELE)
        val canonicalArticle = engine.canonicalizePostUrl(normalized)
            ?: throw SiteException.ParseError("Unrecognized Cosplaytele article URL: $rawUrl", SiteId.COSPLAYTELE)

        val (articleDoc, _) = engine.fetchDocument(canonicalArticle)
        val embeds = extractAllCossoraEmbeds(articleDoc)
        if (embeds.isEmpty()) {
            throw SiteException.ParseError(
                message = "Cosplaytele article contains no embedded Cossora videos: $canonicalArticle",
                siteId = SiteId.COSPLAYTELE,
            )
        }

        val requestedFragment = GalleryUrlUtils.parseUri(normalized)?.rawFragment?.trim().orEmpty()
        val selectedIndex: Int
        val selectedEmbed: CossoraEmbed
        if (VIDEO_FRAGMENT_REGEX.matches(requestedFragment)) {
            val idx = embeds.indexOfFirst { it.id.equals(requestedFragment, ignoreCase = true) }
            if (idx < 0) {
                throw SiteException.NotFound(
                    message = "Embedded video '$requestedFragment' not found in $canonicalArticle",
                    siteId = SiteId.COSPLAYTELE,
                )
            }
            selectedIndex = idx
            selectedEmbed = embeds[idx]
        } else if (embeds.size == 1) {
            selectedIndex = 0
            selectedEmbed = embeds.single()
        } else {
            throw SiteException.ParseError(
                message = "Article contains ${embeds.size} embedded videos; specify #video-<uuid> to select one",
                siteId = SiteId.COSPLAYTELE,
            )
        }

        val embedHtml = fetchEmbedHtml(selectedEmbed.embedUrl, referer = canonicalArticle)
        val media = CossoraAesProtocol.parseEmbedHtml(embedHtml)
        val articleTitle = engine.extractDetailTitle(articleDoc)
        val galleryImages = engine.extractImages(articleDoc)
        val coverUrl = media.posterUrl.ifBlank { galleryImages.firstOrNull().orEmpty() }
        val metadata = engine.extractDetailMetadata(articleDoc)
        val videoLabel = "文章内视频 ${selectedIndex + 1}"
        val videoUrl = "$canonicalArticle#${selectedEmbed.id}"

        return VideoDetail(
            item = MediaItem(
                siteId = SiteId.COSPLAYTELE,
                id = selectedEmbed.id,
                url = videoUrl,
                kind = MediaKind.VIDEO,
                title = "$articleTitle · $videoLabel",
                coverUrl = coverUrl,
                dateLabel = engine.extractDetailDate(articleDoc).ifBlank { null },
                author = metadata.authors.firstOrNull(),
                tags = metadata.tags,
            ),
            description = "$articleTitle 的文章内视频。图片请返回写真集阅读。",
            streams = listOf(toHlsStream(media.streamUrl, media.qualityLabel)),
            tags = metadata.tags,
            authors = metadata.authors,
            characters = metadata.characters,
            categories = metadata.categories,
        )
    }

    private suspend fun fetchEmbedHtml(embedUrl: String, referer: String): String {
        val response = httpClient.getText(
            url = embedUrl,
            siteId = SiteId.COSPLAYTELE,
            kind = SiteRequestKind.PAGE,
            headers = mapOf("Referer" to referer),
            allowCache = false,
        )
        val body = response.bodyText()
        return when (response.code) {
            in 200..299 -> {
                if (GalleryUrlUtils.isChallengeHtml(body, response.header("cf-mitigated"))) {
                    throw SiteException.Forbidden(
                        isChallenge = true,
                        message = "Cossora embed endpoint returned a browser challenge",
                        siteId = SiteId.COSPLAYTELE,
                    )
                }
                body
            }
            404 -> throw SiteException.NotFound("Cossora embed not found: $embedUrl", SiteId.COSPLAYTELE)
            429 -> throw SiteException.RateLimited(
                retryAfterSeconds = response.retryAfterSeconds,
                message = "Cossora embed rate limited: $embedUrl",
                siteId = SiteId.COSPLAYTELE,
            )
            403 -> throw SiteException.Forbidden(
                isChallenge = GalleryUrlUtils.isChallengeHtml(body, response.header("cf-mitigated")),
                message = "Cossora embed forbidden: $embedUrl",
                siteId = SiteId.COSPLAYTELE,
            )
            else -> throw SiteException.Network("HTTP ${response.code} fetching Cossora embed: $embedUrl", SiteId.COSPLAYTELE)
        }
    }

    private fun toHlsStream(streamUrl: String, qualityLabel: String): VideoStream = VideoStream(
        quality = qualityLabel,
        label = qualityLabel,
        url = streamUrl,
        isHls = true,
        mimeTypeHint = "application/vnd.apple.mpegurl",
        headers = mapOf("Referer" to "https://cossora.stream/"),
    )

    companion object {
        private val POST_PATH_REGEX = Regex("^/([^/]+?)(?:/\\d+)?/?$")
        private val TITLE_SUFFIX_REGEX = Regex("\\s*[-|–—]\\s*Cosplaytele(?:\\.com)?\\s*$", RegexOption.IGNORE_CASE)
        private val VIDEO_FRAGMENT_REGEX = Regex(
            "^video-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$",
            RegexOption.IGNORE_CASE,
        )
        private val COSSORA_EMBED_PATH_REGEX = Regex(
            "^/embed/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/?$",
            RegexOption.IGNORE_CASE,
        )
        private val BLOCKQUOTE_KV_REGEX = Regex("^(Cosplayer|Character|Appear\\s*In|Series)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
        private val BLOCKQUOTE_LINE_SPLIT_REGEX = Regex("<br\\s*/?>|</p>|</li>|\\r?\\n", RegexOption.IGNORE_CASE)

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.COSPLAYTELE,
            displayName = "Cosplaytele",
            shortName = "Cosplaytele",
            primaryKind = MediaKind.PHOTO,
            supportedKinds = setOf(MediaKind.PHOTO, MediaKind.VIDEO),
            defaultHost = "cosplaytele.com",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.CHARACTERS,
                SiteCapability.DUAL_MEDIA,
                SiteCapability.STREAM_QUALITIES,
            ),
            feedSorts = listOf(FeedSort.NEWEST),
            sections = listOf(
                SiteSectionSpec(
                    id = "latest",
                    title = "最新更新",
                    subtitle = "刚发布的写真",
                    defaultSort = FeedSort.NEWEST,
                    mediaKind = MediaKind.PHOTO,
                ),
            ),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                referer = "https://cosplaytele.com/",
                mediaReferer = "https://cosplaytele.com/",
                mediaDomains = setOf("cossora.stream"),
            ),
        )

        internal fun createConfig(): PhotoSiteConfig = PhotoSiteConfig(
            descriptor = DESCRIPTOR,
            routeScheme = PhotoRouteScheme.WordPressPath(),
            postUrlRule = PostUrlRule(
                pathRegex = POST_PATH_REGEX,
                canonicalBuilder = { baseUrl, match -> "$baseUrl/${match.groupValues[1]}/" },
                postIdExtractor = { match -> match.groupValues[1] },
                contentPageUrlBuilder = { canonical, page ->
                    if (page <= 1) canonical else "${canonical.trimEnd('/')}/$page/"
                },
            ),
            cardSpec = CardSpec(
                itemSelectors = listOf("div.col.post-item"),
                linkSelectors = listOf("h5.post-title a[href]"),
                thumbSelectors = listOf("img.wp-post-image", "img"),
                dateSelector = "time",
            ),
            imageSpec = ImageExtractionSpec.DomImages(
                // Strict #gallery-1 isolation: prevents trailing .crp_related thumbnails from mixing into the set.
                containerSelectors = listOf("div#gallery-1", "div.entry-content", "article"),
                imageSelector = "figure a[href], img",
                strictPrimaryContainer = true,
                allowedUrlSubstrings = setOf("cosplaytele.com/wp-content/uploads"),
                preferAnchorHref = true,
            ),
            metadataSpec = DetailMetadataSpec(
                titleSuffixRegex = TITLE_SUFFIX_REGEX,
                customMetadataExtractor = ::extractCosplayteleMetadata,
            ),
            embeddedVideoExtractor = ::extractEmbeddedVideoItems,
        )

        internal fun parseDirectCossoraEmbed(rawUrl: String): CossoraEmbed? {
            val uri = GalleryUrlUtils.parseUri(rawUrl) ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (!uri.rawUserInfo.isNullOrEmpty()) return null
            val host = uri.host?.lowercase() ?: return null
            if (host != "cossora.stream" && host != "www.cossora.stream") return null
            val uuid = COSSORA_EMBED_PATH_REGEX.matchEntire(uri.rawPath.orEmpty())
                ?.groupValues?.get(1)?.lowercase()
                ?: return null
            return CossoraEmbed(
                id = "video-$uuid",
                embedUrl = "https://cossora.stream/embed/$uuid",
            )
        }

        internal fun extractAllCossoraEmbeds(doc: Document): List<CossoraEmbed> {
            val embeds = LinkedHashMap<String, CossoraEmbed>()
            for (embed in CossoraAesProtocol.extractEmbeds(doc.outerHtml())) {
                embeds.putIfAbsent(embed.id, embed)
            }
            for (frame in doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")) {
                val candidateUrls = listOf(
                    frame.absUrl("src").ifBlank { frame.attr("src") },
                    frame.attr("data-src"),
                    frame.attr("data-lazy-src"),
                )
                for (raw in candidateUrls) {
                    val embed = parseDirectCossoraEmbed(raw) ?: continue
                    embeds.putIfAbsent(embed.id, embed)
                }
            }
            return embeds.values.toList()
        }

        internal fun extractEmbeddedVideoItems(
            doc: Document,
            canonicalUrl: String,
            articleTitle: String,
            coverUrl: String,
        ): List<MediaItem> {
            val baseArticleUrl = canonicalUrl.substringBefore('#')
            return extractAllCossoraEmbeds(doc).mapIndexed { index, embed ->
                MediaItem(
                    siteId = SiteId.COSPLAYTELE,
                    id = embed.id,
                    url = "$baseArticleUrl#${embed.id}",
                    kind = MediaKind.VIDEO,
                    title = "文章内视频 ${index + 1}",
                    coverUrl = coverUrl,
                    extra = mapOf(
                        "embedUrl" to embed.embedUrl,
                        "articleUrl" to baseArticleUrl,
                        "articleTitle" to articleTitle,
                    ),
                )
            }
        }

        private fun extractCosplayteleMetadata(doc: Document, baseUrl: String): ExtractedGalleryMetadata {
            val authors = mutableListOf<AuthorRef>()
            val characters = mutableListOf<TagRef>()
            val tags = mutableListOf<TagRef>()
            val categories = mutableListOf<TagRef>()

            val root = (doc.selectFirst("article") ?: doc.body()).clone()
            for (exclude in ImageExtractionSpec.DEFAULT_EXCLUDED_CONTAINERS) {
                root.select(exclude).remove()
            }

            // Parse Cosplaytele info blockquote lines (e.g., "Cosplayer: ...", "Character: ...")
            for (blockquote in root.select("blockquote")) {
                val lines = BLOCKQUOTE_LINE_SPLIT_REGEX.split(blockquote.html())
                for (rawLine in lines) {
                    val text = GalleryUrlUtils.cleanHtmlText(Jsoup.parseBodyFragment(rawLine).text())
                    val match = BLOCKQUOTE_KV_REGEX.matchEntire(text) ?: continue
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    if (value.isBlank()) continue
                    when {
                        key.startsWith("cosplayer") -> {
                            val slug = value.lowercase().replace(Regex("\\s+"), "-")
                            authors += AuthorRef(id = slug, name = value, role = "cosplayer")
                        }
                        key.startsWith("character") -> {
                            val slug = value.lowercase().replace(Regex("\\s+"), "-")
                            characters += TagRef(slug = slug, label = value, namespace = "character")
                        }
                        key.startsWith("appear") || key.startsWith("series") -> {
                            val slug = value.lowercase().replace(Regex("\\s+"), "-")
                            tags += TagRef(slug = slug, label = value, namespace = "series")
                        }
                    }
                }
            }

            for (anchor in root.select("a[rel~=\"category\"], .cat-links a[href], a[href*=\"/category/\"]")) {
                val label = GalleryUrlUtils.cleanHtmlText(anchor.text())
                if (label.isBlank()) continue
                val href = GalleryUrlUtils.resolveAbsoluteUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") }, baseUrl).orEmpty()
                val slug = GalleryUrlUtils.parseUri(href)?.let { GalleryUrlUtils.pathSegments(it).lastOrNull() }
                    ?: label.lowercase().replace(Regex("\\s+"), "-")
                categories += TagRef(slug = slug, label = label, namespace = "category", url = href)
            }

            for (anchor in root.select("a[rel=\"tag\"], .tags-links a[href], a[href*=\"/tag/\"]")) {
                val label = GalleryUrlUtils.cleanHtmlText(anchor.text())
                if (label.isBlank()) continue
                val href = GalleryUrlUtils.resolveAbsoluteUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") }, baseUrl).orEmpty()
                val slug = GalleryUrlUtils.parseUri(href)?.let { GalleryUrlUtils.pathSegments(it).lastOrNull() }
                    ?: label.lowercase().replace(Regex("\\s+"), "-")
                tags += TagRef(slug = slug, label = label, namespace = "tag", url = href)
            }

            return ExtractedGalleryMetadata(
                tags = tags.distinctBy { it.slug.lowercase() },
                authors = authors.distinctBy { it.name.lowercase() },
                characters = characters.distinctBy { it.label.lowercase() },
                categories = categories.distinctBy { it.slug.lowercase() },
            )
        }
    }
}
