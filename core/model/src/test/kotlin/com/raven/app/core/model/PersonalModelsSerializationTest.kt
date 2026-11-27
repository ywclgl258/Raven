package com.raven.app.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalModelsSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `backup payload round trips all domain collections losslessly`() {
        val mediaItem = MediaItem(
            siteId = SiteId.COSPLAYTELE,
            id = "set-100",
            url = "https://cosplaytele.com/set-100/",
            kind = MediaKind.PHOTO,
            title = "Cosplay Sample",
            coverUrl = "https://cosplaytele.com/wp-content/uploads/cover.jpg",
            pageCount = 42,
            tags = listOf(TagRef(slug = "cosplay", label = "角色扮演")),
        )
        val payload = RavenBackupPayload(
            exportedAt = 1_700_000_000_000L,
            appVersion = "1.0.0",
            watchProgress = listOf(
                WatchProgress(
                    siteId = SiteId.R34V,
                    mediaId = "12345",
                    url = "https://rule34video.com/video/12345/sample/",
                    title = "Sample Clip",
                    positionMs = 30_000L,
                    durationMs = 120_000L,
                    updatedAt = 1_700_000_001_000L,
                    quality = "1080p",
                ),
            ),
            readingProgress = listOf(
                ReadingProgress(
                    siteId = SiteId.EHENTAI,
                    mediaId = "9999",
                    url = "https://e-hentai.org/g/9999/abcdef1234/",
                    title = "Sample Manga",
                    currentPage = 12,
                    totalPages = 24,
                    scrollOffsetPx = 320,
                    updatedAt = 1_700_000_002_000L,
                    seriesId = "series-9999",
                    seriesTitle = "Sample Series",
                    seriesUrl = "https://e-hentai.org/s/9999/",
                ),
            ),
            favorites = listOf(
                FavoriteItem(
                    siteId = SiteId.NHENTAI,
                    targetId = "420000",
                    kind = FavoriteKind.COMIC,
                    title = "Fav Comic",
                    url = "https://nhentai.net/g/420000/",
                    addedAt = 1_700_000_003_000L,
                ),
            ),
            shelves = listOf(
                Shelf(id = Shelf.WATCH_LATER_ID, name = "稍后看", isSystem = true, createdAt = 1L),
                Shelf(id = "custom-1", name = "精选", note = "测试备注", isPinned = true, createdAt = 2L),
            ),
            shelfItems = listOf(
                ShelfItem(
                    shelfId = Shelf.WATCH_LATER_ID,
                    siteId = SiteId.COSPLAYTELE,
                    mediaId = "set-100",
                    url = mediaItem.url,
                    kind = MediaKind.PHOTO,
                    title = mediaItem.title,
                    position = 0,
                    addedAt = 1_700_000_004_000L,
                ),
            ),
            smartShelves = listOf(
                SmartShelf(
                    id = "smart-1",
                    name = "未看完漫画",
                    rule = SmartShelfRule.UNFINISHED,
                    mediaKindFilter = MediaKind.COMIC,
                ),
            ),
            pageBookmarks = listOf(
                PageBookmark(
                    id = "pb-1",
                    siteId = SiteId.EHENTAI,
                    mediaId = "9999",
                    url = "https://e-hentai.org/g/9999/abcdef1234/",
                    title = "Sample Manga",
                    page = 8,
                    totalPages = 24,
                    scrollOffsetPx = 150,
                ),
            ),
            timeBookmarks = listOf(
                TimeBookmark(
                    id = "tb-1",
                    siteId = SiteId.R34V,
                    mediaId = "12345",
                    url = "https://rule34video.com/video/12345/sample/",
                    title = "Sample Clip",
                    positionMs = 45_000L,
                    durationMs = 120_000L,
                    label = "高能片段",
                ),
            ),
            imageFavorites = listOf(
                ImageFavorite(
                    id = "if-1",
                    siteId = SiteId.COSPLAYTELE,
                    mediaId = "set-100",
                    mediaTitle = "Cosplay Sample",
                    pageUrl = "https://cosplaytele.com/set-100/#1-1-0",
                    imageUrl = "https://cosplaytele.com/wp-content/uploads/1.jpg",
                    pageIndex = 1,
                ),
            ),
            tagSubscriptions = listOf(
                TagSubscription(
                    siteId = SiteId.R34V,
                    slug = "blonde",
                    label = "金发",
                    url = "https://rule34video.com/tags/blonde/",
                ),
            ),
            savedSearches = listOf(
                SavedSearch(
                    id = "ss-1",
                    name = "中文精选",
                    query = "elf",
                    siteIds = listOf(SiteId.EHENTAI, SiteId.NHENTAI),
                    language = ComicLanguage.CHINESE,
                    filters = AdvancedSearchFilters(includeTags = listOf("elf"), minPages = 20),
                ),
            ),
            updateInbox = listOf(
                UpdateInboxItem(
                    id = "inbox-1",
                    sourceKind = InboxSourceKind.TAG_SUBSCRIPTION,
                    sourceId = "r34v:blonde",
                    sourceLabel = "金发",
                    item = mediaItem,
                    discoveredAt = 1_700_000_005_000L,
                ),
            ),
            blockedTerms = listOf(
                BlockedTerm(siteId = SiteId.R34V, term = "gore"),
                BlockedTerm(siteId = SiteId.GLOBAL, term = "scat"),
            ),
            searchHistory = listOf(SearchHistoryEntry(siteId = SiteId.R34V, query = "elf", searchedAt = 10L)),
            playbackPreferences = listOf(PlaybackPreferences(siteId = SiteId.IWARA, defaultSpeed = 1.25f)),
            readerPreferences = listOf(
                ReaderPreferences(
                    siteId = SiteId.EHENTAI,
                    mode = ReaderMode.RTL,
                    fit = ReaderFit.HEIGHT,
                    preferOriginalImages = true,
                ),
            ),
            siteLanguages = mapOf(SiteId.NHENTAI to ComicLanguage.CHINESE_AND_JAPANESE),
            settings = mapOf("translate_tags" to "true"),
            customTagTranslations = listOf(
                TagTranslationEntry(
                    namespace = "female",
                    rawSlug = "stockings",
                    chineseName = "过膝丝袜",
                    intro = "自定义覆盖",
                    source = TagTranslationSource.USER_CUSTOM,
                    updatedAt = 1_700_000_006_000L,
                ),
            ),
        )

        val encoded = json.encodeToString(payload)
        val decoded = json.decodeFromString<RavenBackupPayload>(encoded)
        assertEquals(payload, decoded)
        assertEquals(0.25f, decoded.watchProgress.first().progressFraction, 0.001f)
        assertEquals(0.5f, decoded.readingProgress.first().progressFraction, 0.001f)

        // Legacy backup JSON omitting customTagTranslations deserializes cleanly with default emptyList
        val legacyDecoded = json.decodeFromString<RavenBackupPayload>("""{"schemaVersion":1,"exportedAt":100}""")
        assertTrue(legacyDecoded.customTagTranslations.isEmpty())
    }

    @Test
    fun `gallery detail carries embedded videos for dual media sites`() {
        val galleryItem = MediaItem(
            siteId = SiteId.COSPLAYTELE,
            id = "post-7",
            url = "https://cosplaytele.com/post-7/",
            kind = MediaKind.PHOTO,
            title = "Mixed Set",
        )
        val embedded = MediaItem(
            siteId = SiteId.COSPLAYTELE,
            id = "video-11111111-2222-3333-4444-555555555555",
            url = "https://cosplaytele.com/post-7/#video-11111111-2222-3333-4444-555555555555",
            kind = MediaKind.VIDEO,
            title = "Embedded Clip 1",
        )
        val detail = GalleryDetail(
            item = galleryItem,
            pageCount = 1,
            pages = listOf(GalleryPage(index = 1, pageUrl = "${galleryItem.url}#1-1-0")),
            embeddedVideos = listOf(embedded),
        )
        val roundTripped = json.decodeFromString<GalleryDetail>(json.encodeToString(detail))
        assertEquals(1, roundTripped.embeddedVideos.size)
        assertEquals(MediaKind.VIDEO, roundTripped.embeddedVideos.first().kind)
    }

    @Test
    fun `advanced search filters emptiness reflects all fields`() {
        assertTrue(AdvancedSearchFilters().isEmpty)
        assertFalse(AdvancedSearchFilters(excludeTags = listOf("guro")).isEmpty)
        assertFalse(AdvancedSearchFilters(minRating = 4).isEmpty)
    }

    @Test
    fun `site exception maps deterministically to download error categories`() {
        assertEquals(DownloadErrorCategory.NETWORK, SiteException.Network("net").toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.RATE_LIMITED, SiteException.RateLimited(retryAfterSeconds = 30).toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.AUTH_REQUIRED, SiteException.AuthRequired().toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.RATE_LIMITED, SiteException.Forbidden(isChallenge = true).toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.AUTH_REQUIRED, SiteException.Forbidden(isChallenge = false).toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.NOT_FOUND, SiteException.NotFound().toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.QUOTA_EXCEEDED, SiteException.QuotaExceeded().toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.SOURCE_CHANGED, SiteException.ParseError("changed").toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.TLS, SiteException.TlsError("tls").toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.SOURCE_CHANGED, SiteException.Unsupported("nope").toDownloadErrorCategory())
        assertEquals(DownloadErrorCategory.UNKNOWN, SiteException.Cancelled().toDownloadErrorCategory())
    }
}
