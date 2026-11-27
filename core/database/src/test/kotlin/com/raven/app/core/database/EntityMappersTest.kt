package com.raven.app.core.database

import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.database.model.BrowsePosition
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.PlaybackPreferences
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderPreferences
import com.raven.app.core.model.ReaderStartPosition
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.TagTranslationEntry
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityMappersTest {

    @Test
    fun `all 18 domain models round trip cleanly through Room entities`() {
        val watch = WatchProgress(
            siteId = SiteId.R34V,
            mediaId = "vid-1",
            url = "https://rule34video.com/video/1/",
            title = "Clip 1",
            coverUrl = "https://cdn.example/1.jpg",
            positionMs = 45_000L,
            durationMs = 120_000L,
            updatedAt = 1000L,
            completed = false,
            quality = "1080p",
        )
        assertEquals(watch, watch.toEntity().toDomain())

        val reading = ReadingProgress(
            siteId = SiteId.NHENTAI,
            mediaId = "123456",
            url = "https://nhentai.net/g/123456/",
            title = "Doujin",
            coverUrl = "https://t.nhentai.net/cover.jpg",
            kind = MediaKind.COMIC,
            currentPage = 12,
            totalPages = 48,
            scrollOffsetPx = 320,
            updatedAt = 2000L,
            completed = false,
            seriesId = "series-9",
            seriesTitle = "Series Nine",
            seriesUrl = "https://example.com/series/9/",
        )
        assertEquals(reading, reading.toEntity().toDomain())

        val fav = FavoriteItem(
            siteId = SiteId.IWARA,
            targetId = "creator-9",
            kind = FavoriteKind.AUTHOR,
            title = "Creator Nine",
            url = "https://www.iwara.tv/profile/creator-9",
            coverUrl = "https://i.iwara.tv/avatar.png",
            addedAt = 3000L,
            extra = mapOf("role" to "animator"),
        )
        assertEquals(fav, fav.toEntity().toDomain())

        val shelf = Shelf(
            id = "shelf-1",
            name = "Favorites Archive",
            note = "Best 3D clips",
            isPinned = true,
            isSystem = false,
            coverCompositeKey = "r34v:vid-1",
            createdAt = 100L,
            modifiedAt = 200L,
        )
        assertEquals(shelf, shelf.toEntity().toDomain())

        val shelfItem = ShelfItem(
            shelfId = "shelf-1",
            siteId = SiteId.R34V,
            mediaId = "vid-1",
            url = "https://rule34video.com/video/1/",
            kind = MediaKind.VIDEO,
            title = "Clip 1",
            coverUrl = "https://cdn.example/1.jpg",
            position = 2,
            addedAt = 500L,
            completed = true,
            durationSeconds = 120,
            pageCount = null,
        )
        assertEquals(shelfItem, shelfItem.toEntity().toDomain())

        val smartShelf = SmartShelf(
            id = "smart-1",
            name = "Unfinished Comics",
            rule = SmartShelfRule.UNFINISHED,
            mediaKindFilter = MediaKind.COMIC,
            siteIdFilter = SiteId.EHENTAI,
            isPinned = true,
            createdAt = 4000L,
        )
        assertEquals(smartShelf, smartShelf.toEntity().toDomain())

        val pageMark = PageBookmark(
            id = "pb-1",
            siteId = SiteId.HITOMI,
            mediaId = "9999",
            url = "https://hitomi.la/galleries/9999.html",
            title = "Book",
            coverUrl = "https://cdn.example/b.webp",
            page = 14,
            totalPages = 30,
            scrollOffsetPx = 120,
            note = "Great art",
            createdAt = 5000L,
        )
        assertEquals(pageMark, pageMark.toEntity().toDomain())

        val timeMark = TimeBookmark(
            id = "tb-1",
            siteId = SiteId.HANIME1,
            mediaId = "777",
            url = "https://hanime1.me/watch?v=777",
            title = "Episode",
            coverUrl = "https://cdn.example/ep.jpg",
            positionMs = 90_000L,
            durationMs = 600_000L,
            label = "Action scene",
            groupName = "Highlights",
            createdAt = 6000L,
        )
        assertEquals(timeMark, timeMark.toEntity().toDomain())

        val imgFav = ImageFavorite(
            id = "if-1",
            siteId = SiteId.EVERIA,
            mediaId = "set-1",
            mediaTitle = "Photo Set",
            pageUrl = "https://everia.club/set-1/#3",
            imageUrl = "https://img.everia.club/3.jpg",
            pageIndex = 3,
            addedAt = 7000L,
        )
        assertEquals(imgFav, imgFav.toEntity().toDomain())

        val sub = TagSubscription(
            siteId = SiteId.R34V,
            slug = "overwatch",
            label = "守望先锋",
            url = "https://rule34video.com/tags/overwatch/",
            scope = SearchScope.TAG,
            sortOrder = 4,
            createdAt = 8000L,
        )
        assertEquals(sub, sub.toEntity().toDomain())

        val savedSearch = SavedSearch(
            id = "ss-1",
            name = "Chinese Doujin",
            query = "original",
            scope = SearchScope.ALL,
            siteIds = listOf(SiteId.NHENTAI, SiteId.WNACG),
            mediaKind = MediaKind.COMIC,
            sort = FeedSort.POPULAR,
            language = ComicLanguage.CHINESE,
            filters = AdvancedSearchFilters(includeTags = listOf("full_color"), minPages = 20),
            createdAt = 9000L,
            lastCheckedAt = 9500L,
        )
        assertEquals(savedSearch, savedSearch.toEntity().toDomain())

        val inbox = UpdateInboxItem(
            id = "in-1",
            sourceKind = InboxSourceKind.SAVED_SEARCH,
            sourceId = "ss-1",
            sourceLabel = "Chinese Doujin",
            item = MediaItem(
                siteId = SiteId.WNACG,
                id = "aid-10",
                url = "https://www.wnacg.com/photos-index-aid-10.html",
                kind = MediaKind.COMIC,
                title = "New Release",
            ),
            discoveredAt = 10_000L,
            isRead = false,
        )
        val inboxEntity = inbox.toEntity()
        assertEquals("wnacg", inboxEntity.siteId)
        assertEquals("aid-10", inboxEntity.mediaId)
        assertEquals(inbox, inboxEntity.toDomain())

        val blocked = BlockedTerm(siteId = SiteId.R34V, term = "gore", createdAt = 11_000L)
        assertEquals(blocked, blocked.toEntity().toDomain())

        val searchEntry = SearchHistoryEntry(
            siteId = SiteId.MISSAV,
            query = "fc2",
            scope = SearchScope.ALL,
            searchedAt = 12_000L,
        )
        assertEquals(searchEntry, searchEntry.toEntity().toDomain())

        val download = DownloadItem(
            id = "dl-1",
            siteId = SiteId.JMCOMIC,
            mediaId = "album-5",
            url = "https://18comic.vip/album/5",
            title = "Album 5",
            coverUrl = "https://cdn.example/5.jpg",
            kind = DownloadKind.COMIC,
            status = DownloadStatus.RETRY_WAIT,
            quality = null,
            groupName = "Batch A",
            progressFraction = 0.5f,
            completedUnits = 10,
            totalUnits = 20,
            downloadedBytes = 10_000_000L,
            totalBytes = 20_000_000L,
            selectedPages = listOf(1, 2, 3),
            errorCategory = DownloadErrorCategory.RATE_LIMITED,
            errorMessage = "HTTP 429",
            retryAtEpochMs = 99_000L,
            priority = 10,
            storageLocation = StorageLocation.SHARED,
            localPath = "/storage/emulated/0/Raven/comics/jmcomic/album-5",
            createdAt = 13_000L,
            updatedAt = 14_000L,
        )
        assertEquals(download, download.toEntity().toDomain())

        val browsePos = BrowsePosition(
            positionKey = "sha256-abc",
            siteId = SiteId.R34V,
            workId = "vid-99",
            throughPage = 3,
            shelfId = "latest",
            updatedAt = 15_000L,
        )
        assertEquals(browsePos, browsePos.toEntity().toDomain())

        val tagTranslation = TagTranslationEntry(
            namespace = "female",
            rawSlug = "big breasts",
            chineseName = "巨乳",
            intro = "说明文本",
            source = TagTranslationSource.COMMUNITY_EHTAG,
            updatedAt = 16_000L,
        )
        assertEquals(tagTranslation, tagTranslation.toEntity().toDomain())
    }

    @Test
    fun `playback and reader preferences map sentinel keys and clamp out-of-range values`() {
        val globalPlayback = PlaybackPreferences(
            siteId = null,
            defaultSpeed = 9.0f,
            longPressSpeed = 0.5f,
            seekStepSeconds = 999,
            gestureSensitivitySeconds = 5,
            preferredQuality = "  ",
            autoFullscreen = false,
            autoDownload = true,
        )
        val globalPlaybackEntity = globalPlayback.toEntity()
        assertEquals(PlaybackPreferencesEntity.GLOBAL_SITE_ID, globalPlaybackEntity.siteId)
        assertEquals(3.0f, globalPlaybackEntity.defaultSpeed, 0.001f)
        assertEquals(1.0f, globalPlaybackEntity.longPressSpeed, 0.001f)
        assertEquals(60, globalPlaybackEntity.seekStepSeconds)
        assertEquals(30, globalPlaybackEntity.gestureSensitivitySeconds)
        assertEquals("auto", globalPlaybackEntity.preferredQuality)
        assertNull(globalPlaybackEntity.toDomain().siteId)

        val siteReader = ReaderPreferences(
            siteId = SiteId.EHENTAI,
            mediaId = null,
            mode = ReaderMode.RTL,
            fit = ReaderFit.HEIGHT,
            startPosition = ReaderStartPosition.LAST,
            cropPercent = 50,
            combineCover = true,
            preferOriginalImages = true,
            volumeKeyPaging = true,
            reverseVolumeKeys = true,
            keepScreenOn = false,
            brightnessPercent = 200,
        )
        val siteReaderEntity = siteReader.toEntity()
        assertEquals("ehentai", siteReaderEntity.siteId)
        assertEquals(ReaderPreferencesEntity.SITE_DEFAULT_MEDIA_ID, siteReaderEntity.mediaId)
        assertEquals(20, siteReaderEntity.cropPercent)
        assertEquals(100, siteReaderEntity.brightnessPercent)
        val restored = siteReaderEntity.toDomain()
        assertEquals(SiteId.EHENTAI, restored.siteId)
        assertNull(restored.mediaId)
        assertEquals(ReaderMode.RTL, restored.mode)
        assertEquals(20, restored.cropPercent)
    }
}
