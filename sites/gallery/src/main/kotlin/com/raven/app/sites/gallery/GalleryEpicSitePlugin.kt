package com.raven.app.sites.gallery

import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.DirectoryEntry
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.DirectorySource
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * GalleryEpic (`https://galleryepic.com`) Next.js cosplay archive plugin built declaratively
 * on [WordPressPhotoEngine].
 *
 * Key protocol invariants:
 * - Directory-style search (`SearchSource.isDirectorySearch == true`) & [DirectorySource]:
 *   searching queries `/en/cosers/<page>?coserName=<q>`, while `/en/parody/<id>` lists characters.
 * - Full image list is extracted from Next.js RSC Flight payload UUID arrays (`"images":[...]`)
 *   mapped to `https://static.galleryepic.xyz/image/<uuid>` rather than truncated 12-preview HTML.
 * - Coser pagination strictly filters pager links to the active `/en/coser/<id>/` prefix.
 * - Metadata (`Cosplayer`, `Character`, `Series`, `Contents`) is extracted from `<dl><dt>/<dd>`.
 */
class GalleryEpicSitePlugin(
    httpClient: HttpClientAdapter,
) : SitePlugin {

    internal val engine: WordPressPhotoEngine = WordPressPhotoEngine(
        config = createConfig(),
        httpClient = httpClient,
    )

    override val descriptor: SiteDescriptor = engine.descriptor
    override val feedSource: FeedSource = engine
    override val gallerySource: GallerySource = engine
    override val searchSource: SearchSource = engine
    override val directorySource: DirectorySource = requireNotNull(engine.optionalDirectorySource)
    override val linkResolver: LinkResolver = engine

    companion object {
        private val COSPLAY_PATH_REGEX = Regex("^/en/cosplay/(\\d+)/?$")
        private val COSER_PREFIX_REGEX = Regex("^/en/coser/\\d+/")
        private val COSER_LINK_REGEX = Regex("^/en/coser/(\\d+)/(\\d+)/?$")
        private val CHARACTER_LINK_REGEX = Regex("^/en/character/(\\d+)/?$")
        private val PARODY_LINK_REGEX = Regex("^/en/parody/(\\d+)/?$")
        private val POSITIVE_NUMERIC_REGEX = Regex("^[1-9]\\d*$")
        private val PAGE_COUNT_OVERLAY_REGEX = Regex("(\\d+)\\s*P\\b")
        private val CARD_DATE_REGEX = Regex("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b")
        private val FILE_SIZE_REGEX = Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:MB|GB)\\b", RegexOption.IGNORE_CASE)
        private val TITLE_SUFFIX_REGEX = Regex("\\s*\\|\\s*Gallery\\s*Epic\\s*$", RegexOption.IGNORE_CASE)

        private const val CDN_IMAGE_PREFIX = "https://static.galleryepic.xyz/image/"
        private const val DEFAULT_COVER_ASPECT_RATIO = 600f / 800f

        private val COSERS_SECTION = SiteSectionSpec(
            id = "cosers",
            title = "Coser 索引",
            category = "cosers",
            defaultSort = FeedSort.NEWEST,
            mediaKind = MediaKind.PHOTO,
            isDirectory = true,
        )

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.GALLERYEPIC,
            displayName = "GalleryEpic",
            shortName = "GalleryEpic",
            primaryKind = MediaKind.PHOTO,
            supportedKinds = setOf(MediaKind.PHOTO),
            defaultHost = "galleryepic.com",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.DIRECTORY,
                SiteCapability.AUTHORS,
                SiteCapability.CHARACTERS,
                SiteCapability.TAGS,
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
                COSERS_SECTION,
            ),
            searchScopes = listOf(SearchScope.ALL, SearchScope.AUTHOR, SearchScope.CHARACTER),
            networkProfile = SiteNetworkProfile(
                referer = "https://galleryepic.com/",
                mediaReferer = "https://galleryepic.com/",
                mediaDomains = setOf("static.galleryepic.xyz"),
            ),
        )

        internal fun createConfig(): PhotoSiteConfig = PhotoSiteConfig(
            descriptor = DESCRIPTOR,
            routeScheme = PhotoRouteScheme.GalleryEpicRoutes,
            postUrlRule = PostUrlRule(
                pathRegex = COSPLAY_PATH_REGEX,
                canonicalBuilder = { baseUrl, match -> "$baseUrl/en/cosplay/${match.groupValues[1]}" },
                postIdExtractor = { match -> match.groupValues[1] },
                contentPageUrlBuilder = { canonical, _ -> canonical },
                reservedFirstSegments = emptySet(),
            ),
            cardSpec = CardSpec(
                itemSelectors = listOf("a[href^=/en/cosplay/]"),
                linkSelectors = emptyList(),
                thumbSelectors = listOf(
                    "img[src*=static.galleryepic.xyz]",
                    "img[data-src*=static.galleryepic.xyz]",
                    "img[data-lazy-src*=static.galleryepic.xyz]",
                ),
                requireThumbMatch = true,
                dateSelector = null,
                dateExtractor = { card -> CARD_DATE_REGEX.find(card.text())?.value },
                titleExtractor = ::extractCardTitle,
                pageCountExtractor = ::extractCardPageCount,
                defaultAspectRatio = DEFAULT_COVER_ASPECT_RATIO,
                totalPagesExtractor = ::extractFeedTotalPages,
            ),
            imageSpec = ImageExtractionSpec.NextJsRscUuidPayload(
                cdnPrefix = CDN_IMAGE_PREFIX,
            ),
            metadataSpec = DetailMetadataSpec(
                titleSuffixRegex = TITLE_SUFFIX_REGEX,
                preferH1Title = true,
                preferOgImageCover = true,
                defaultCategoryLabel = "Cosplay",
                defaultAspectRatio = DEFAULT_COVER_ASPECT_RATIO,
                descriptionBuilder = { doc, total, _ ->
                    doc.selectFirst("meta[name=description]")?.attr("content")
                        ?.let { GalleryUrlUtils.cleanHtmlText(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: "共 $total 张"
                },
                customMetadataExtractor = ::extractDlMetadata,
            ),
            directoryConfig = DirectoryConfig(
                sections = listOf(COSERS_SECTION),
                isDirectorySearch = true,
                urlBuilder = ::buildDirectoryUrl,
                parser = ::parseDirectoryDocument,
                directoryTotalPages = ::extractDirectoryTotalPages,
            ),
        )

        /**
         * Reads the caption block holding `<h3>` + sibling `<p>`, avoiding the cover overlay
         * photo-count `<p>` badge (`56P / 6S`).
         */
        private fun extractCardTitle(card: Element, @Suppress("UNUSED_PARAMETER") link: Element): String {
            val caption = card.selectFirst("h3")?.parent()
            val heading = GalleryUrlUtils.cleanHtmlText(caption?.selectFirst("h3")?.text().orEmpty())
            val subtitle = GalleryUrlUtils.cleanHtmlText(caption?.selectFirst("p")?.text().orEmpty())
            return listOf(heading, subtitle).filter { it.isNotBlank() }.joinToString(" – ")
        }

        private fun extractCardPageCount(card: Element, @Suppress("UNUSED_PARAMETER") title: String): Int? =
            PAGE_COUNT_OVERLAY_REGEX.find(card.text())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

        private fun extractFeedTotalPages(doc: Document): Int? {
            val path = GalleryUrlUtils.parseUri(doc.location())?.rawPath.orEmpty()
            if (path.startsWith("/en/character/")) return 1
            val prefix = COSER_PREFIX_REGEX.find(path)?.value ?: "/en/cosplays/"
            return maxPageWithPrefix(doc, prefix)
        }

        private fun extractDirectoryTotalPages(doc: Document): Int? {
            val path = GalleryUrlUtils.parseUri(doc.location())?.rawPath.orEmpty()
            if (path.startsWith("/en/parody/")) return 1
            return maxPageWithPrefix(doc, "/en/cosers/")
        }

        private fun maxPageWithPrefix(doc: Document, prefix: String): Int? {
            val baseUrl = doc.location().ifBlank { DESCRIPTOR.baseUrl }
            return doc.select("a[href]").mapNotNull { anchor ->
                val resolved = GalleryUrlUtils.resolveAbsoluteUrl(
                    anchor.absUrl("href").ifBlank { anchor.attr("href") },
                    baseUrl,
                ) ?: return@mapNotNull null
                val uri = GalleryUrlUtils.parseUri(resolved) ?: return@mapNotNull null
                val host = uri.host?.lowercase() ?: return@mapNotNull null
                if (!DESCRIPTOR.matchesHost(host)) return@mapNotNull null
                val candidatePath = uri.rawPath.orEmpty()
                if (!candidatePath.startsWith(prefix)) return@mapNotNull null
                candidatePath.removePrefix(prefix).trim('/').toIntOrNull()?.takeIf { it > 0 }
            }.maxOrNull()
        }

        internal fun buildDirectoryUrl(
            baseUrl: String,
            section: String,
            page: Int,
            query: String?,
        ): String? {
            val trimmedSection = section.trim()
            if (trimmedSection.startsWith("series:") || trimmedSection.startsWith("parody:")) {
                val id = trimmedSection.substringAfter(':').trim()
                if (!POSITIVE_NUMERIC_REGEX.matches(id)) return null
                return "$baseUrl/en/parody/$id"
            }

            // Support both clean (section="cosers", page, query) and encoded ("cosers:<page>:<query>")
            val effectivePage: Int
            val effectiveQuery: String
            if (trimmedSection.equals("cosers", ignoreCase = true) || trimmedSection.isEmpty()) {
                effectivePage = page.coerceAtLeast(1)
                effectiveQuery = query?.trim().orEmpty()
            } else if (trimmedSection.startsWith("cosers:")) {
                val parts = trimmedSection.split(':', limit = 3)
                if (parts.size != 3) return null
                effectivePage = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: page.coerceAtLeast(1)
                effectiveQuery = query?.trim()?.takeIf { it.isNotEmpty() } ?: parts[2].trim()
            } else {
                return null
            }

            return if (effectiveQuery.isNotEmpty()) {
                "$baseUrl/en/cosers/$effectivePage?coserName=${HtmlUrlCleaner.encodePathSegment(effectiveQuery)}"
            } else {
                "$baseUrl/en/cosers/$effectivePage"
            }
        }

        internal fun parseDirectoryDocument(
            doc: Document,
            descriptor: SiteDescriptor,
        ): List<DirectoryEntry> {
            val baseUrl = doc.location().ifBlank { descriptor.baseUrl }
            val locationPath = GalleryUrlUtils.parseUri(baseUrl)?.rawPath.orEmpty()
            val isSeriesDirectory = locationPath.startsWith("/en/parody/")
            val selector = if (isSeriesDirectory) "a[href^=/en/character/]" else "a[href^=/en/coser/]"

            return doc.select(selector).mapNotNull { anchor ->
                val rawHref = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                val resolved = GalleryUrlUtils.resolveAbsoluteUrl(rawHref, baseUrl) ?: return@mapNotNull null
                val uri = GalleryUrlUtils.parseUri(resolved) ?: return@mapNotNull null
                if (!uri.scheme.equals("https", ignoreCase = true)) return@mapNotNull null
                val host = uri.host?.lowercase() ?: return@mapNotNull null
                if (!descriptor.matchesHost(host)) return@mapNotNull null

                val path = uri.rawPath.orEmpty()
                val (id, kind, labelSelector) = if (isSeriesDirectory) {
                    val charId = CHARACTER_LINK_REGEX.matchEntire(path)?.groupValues?.get(1) ?: return@mapNotNull null
                    Triple(charId, SearchScope.CHARACTER, "p")
                } else {
                    val coserId = COSER_LINK_REGEX.matchEntire(path)?.groupValues?.get(1) ?: return@mapNotNull null
                    Triple(coserId, SearchScope.AUTHOR, "h3")
                }

                val label = GalleryUrlUtils.cleanHtmlText(
                    anchor.selectFirst(labelSelector)?.text().orEmpty().ifBlank { anchor.text() },
                )
                if (label.isBlank()) return@mapNotNull null

                val coverImg: Element? = anchor.selectFirst("img")
                val coverUrl = if (coverImg != null) {
                    GalleryUrlUtils.extractImgElementUrl(coverImg, baseUrl).orEmpty()
                } else {
                    ""
                }

                DirectoryEntry(
                    siteId = descriptor.id,
                    id = id,
                    title = label,
                    url = resolved,
                    kind = kind,
                    coverUrl = coverUrl,
                )
            }.distinctBy { it.url }
        }

        private fun extractDlMetadata(doc: Document, baseUrl: String): ExtractedGalleryMetadata {
            val authors = mutableListOf<AuthorRef>()
            val characters = mutableListOf<TagRef>()
            val tags = mutableListOf<TagRef>()
            var fileSize: String? = null

            for (term in doc.select("dl dt")) {
                val label = GalleryUrlUtils.cleanHtmlText(term.text())
                val value = term.nextElementSibling() ?: continue
                when (label) {
                    "Cosplayer" -> {
                        for (anchor in value.select("a[href]")) {
                            val name = GalleryUrlUtils.cleanHtmlText(anchor.text())
                            if (name.isBlank()) continue
                            val href = GalleryUrlUtils.resolveAbsoluteUrl(
                                anchor.absUrl("href").ifBlank { anchor.attr("href") },
                                baseUrl,
                            ) ?: continue
                            val id = GalleryUrlUtils.parseUri(href)
                                ?.rawPath
                                ?.let { COSER_LINK_REGEX.matchEntire(it)?.groupValues?.get(1) }
                                ?: name
                            authors += AuthorRef(id = id, name = name, role = "cosplayer", url = href)
                        }
                    }
                    "Character" -> {
                        for (anchor in value.select("a[href]")) {
                            val name = GalleryUrlUtils.cleanHtmlText(anchor.text())
                            if (name.isBlank()) continue
                            val href = GalleryUrlUtils.resolveAbsoluteUrl(
                                anchor.absUrl("href").ifBlank { anchor.attr("href") },
                                baseUrl,
                            ) ?: continue
                            val id = GalleryUrlUtils.parseUri(href)
                                ?.rawPath
                                ?.let { CHARACTER_LINK_REGEX.matchEntire(it)?.groupValues?.get(1) }
                                ?: name
                            characters += TagRef(slug = id, label = name, namespace = "character", url = href)
                        }
                    }
                    "Series" -> {
                        for (anchor in value.select("a[href]")) {
                            val name = GalleryUrlUtils.cleanHtmlText(anchor.text())
                            if (name.isBlank()) continue
                            val href = GalleryUrlUtils.resolveAbsoluteUrl(
                                anchor.absUrl("href").ifBlank { anchor.attr("href") },
                                baseUrl,
                            ) ?: continue
                            val id = GalleryUrlUtils.parseUri(href)
                                ?.rawPath
                                ?.let { PARODY_LINK_REGEX.matchEntire(it)?.groupValues?.get(1) }
                                ?.let { "series:$it" }
                                ?: name
                            tags += TagRef(slug = id, label = name, namespace = "series", url = href)
                        }
                    }
                    "Contents" -> {
                        fileSize = FILE_SIZE_REGEX.find(value.text())?.value
                    }
                }
            }

            return ExtractedGalleryMetadata(
                tags = tags.distinctBy { it.slug },
                authors = authors.distinctBy { it.id },
                characters = characters.distinctBy { it.slug },
                fileSizeLabel = fileSize,
            )
        }
    }
}
