package com.raven.app.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.model.ExploreFilterState
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDataStoreTest {

    @Test
    fun `exposes exact defaults and reacts to every setting setter`() = runTest {
        val settingsStore = SettingsDataStore(InMemoryPreferencesDataStore())

        settingsStore.settings.test {
            val initial = awaitItem()
            assertEquals(AppSettings(), initial)
            assertEquals(SiteId.R34V, initial.activeSiteId)
            assertNull(initial.startupDefaultSiteId)
            assertTrue(initial.translateTags)
            assertFalse(initial.autoFullscreen)
            assertEquals("auto", initial.preferredQuality)
            assertEquals(1.0f, initial.defaultPlaybackSpeed, 0.001f)
            assertFalse(initial.streamCacheEnabled)
            assertTrue(initial.seekCacheEnabled)
            assertFalse(initial.downloadUnmeteredOnly)
            assertEquals(StorageLocation.PRIVATE, initial.storageLocation)
            assertNull(initial.pendingStorageLocation)
            assertFalse(initial.reduceTransparency)
            assertTrue(initial.uiSoundEffectsEnabled)
            assertTrue(initial.autoUpdateCheck)
            assertFalse(initial.siteSwitchTutorialSeen)
            assertTrue(initial.tagDictAutoSync)
            assertEquals("", initial.tagDictEtag)
            assertEquals("", initial.tagDictReleaseTag)
            assertEquals(0L, initial.tagDictUpdatedAt)
            assertTrue(initial.blockTagsGlobally)

            settingsStore.setActiveSiteId(SiteId.EHENTAI)
            assertEquals(SiteId.EHENTAI, awaitItem().activeSiteId)

            settingsStore.setStartupDefaultSiteId(SiteId.MANGACOPY)
            assertEquals(SiteId.MANGACOPY, awaitItem().startupDefaultSiteId)

            settingsStore.setStartupDefaultSiteId(null)
            assertNull(awaitItem().startupDefaultSiteId)

            val pinnedAfterAdd = settingsStore.togglePinnedSite(SiteId.NHENTAI)
            assertEquals(listOf(SiteId.NHENTAI), pinnedAfterAdd)
            assertEquals(listOf(SiteId.NHENTAI), awaitItem().pinnedSiteIds)

            settingsStore.setCrossSearchSiteIds(listOf(SiteId.R34V, SiteId.IWARA, SiteId.R34V))
            assertEquals(listOf(SiteId.R34V, SiteId.IWARA), awaitItem().crossSearchSiteIds)

            settingsStore.setSiteLanguage(SiteId.NHENTAI, ComicLanguage.CHINESE_AND_JAPANESE)
            val afterLang = awaitItem()
            assertEquals(ComicLanguage.CHINESE_AND_JAPANESE, afterLang.languageFor(SiteId.NHENTAI))
            assertEquals(ComicLanguage.ALL, afterLang.languageFor(SiteId.HITOMI))

            val filter = ExploreFilterState(
                section = "popular",
                category = "doujinshi",
                sort = FeedSort.POPULAR,
                language = ComicLanguage.CHINESE,
                advancedFilters = AdvancedSearchFilters(includeTags = listOf("full_color"), minPages = 10),
            )
            settingsStore.setSiteExploreFilter(SiteId.EHENTAI, filter)
            assertEquals(filter, awaitItem().exploreFilterFor(SiteId.EHENTAI))

            settingsStore.setDefaultPlaybackSpeed(5.0f) // clamped to 3.0f
            assertEquals(3.0f, awaitItem().defaultPlaybackSpeed, 0.001f)

            settingsStore.setTranslateTags(false)
            assertFalse(awaitItem().translateTags)

            settingsStore.setAutoFullscreen(true)
            assertTrue(awaitItem().autoFullscreen)

            settingsStore.setPreferredQuality("1080p")
            assertEquals("1080p", awaitItem().preferredQuality)

            settingsStore.setStreamCacheEnabled(true)
            assertTrue(awaitItem().streamCacheEnabled)

            settingsStore.setSeekCacheEnabled(false)
            assertFalse(awaitItem().seekCacheEnabled)

            settingsStore.setDownloadUnmeteredOnly(true)
            assertTrue(awaitItem().downloadUnmeteredOnly)

            settingsStore.setDownloadSort("TITLE")
            assertEquals("TITLE", awaitItem().downloadView.sort)

            settingsStore.setDownloadGroupMode("TAG")
            assertEquals("TAG", awaitItem().downloadView.groupMode)

            settingsStore.setDownloadTagMatchAll(true)
            assertTrue(awaitItem().downloadView.tagMatchAll)

            settingsStore.setReduceTransparency(true)
            assertTrue(awaitItem().reduceTransparency)

            settingsStore.setUiSoundEffectsEnabled(false)
            assertFalse(awaitItem().uiSoundEffectsEnabled)

            settingsStore.setAutoUpdateCheck(false)
            assertFalse(awaitItem().autoUpdateCheck)

            settingsStore.setTagDictAutoSync(false)
            assertFalse(awaitItem().tagDictAutoSync)

            settingsStore.setTagDictSyncState(etag = "\"v2\"", releaseTag = "2026.11", updatedAt = 999_000L)
            val synced = awaitItem()
            assertEquals("\"v2\"", synced.tagDictEtag)
            assertEquals("2026.11", synced.tagDictReleaseTag)
            assertEquals(999_000L, synced.tagDictUpdatedAt)

            settingsStore.setBlockTagsGlobally(false)
            assertFalse(awaitItem().blockTagsGlobally)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deferred storage migration, one-shot tutorial consumption, and portable backup export work cleanly`() = runTest {
        val settingsStore = SettingsDataStore(InMemoryPreferencesDataStore())

        // One-shot tutorial flag
        assertTrue(settingsStore.consumeSiteSwitchTutorial())
        assertFalse(settingsStore.consumeSiteSwitchTutorial())
        assertTrue(settingsStore.currentSettings().siteSwitchTutorialSeen)

        // Pending storage migration applied cleanly on cold start
        settingsStore.setPendingStorageLocation(StorageLocation.SHARED)
        assertEquals(StorageLocation.PRIVATE, settingsStore.currentSettings().storageLocation)
        assertEquals(StorageLocation.SHARED, settingsStore.currentSettings().pendingStorageLocation)
        assertEquals(StorageLocation.SHARED, settingsStore.applyPendingStorageLocation())
        assertEquals(StorageLocation.SHARED, settingsStore.currentSettings().storageLocation)
        assertNull(settingsStore.currentSettings().pendingStorageLocation)
        assertNull(settingsStore.applyPendingStorageLocation())

        // Export excludes device-local storage, sync metadata & tutorial keys, includes portable tag/block flags
        settingsStore.setPreferredQuality("720p")
        settingsStore.setStartupDefaultSiteId(SiteId.MANGACOPY)
        settingsStore.setUiSoundEffectsEnabled(false)
        settingsStore.setTagDictAutoSync(false)
        settingsStore.setBlockTagsGlobally(false)
        settingsStore.setDownloadSort("TITLE")
        settingsStore.setDownloadGroupMode("CUSTOM")
        settingsStore.setTagDictSyncState(etag = "etag-1", releaseTag = "r1", updatedAt = 1234L)
        val exported = settingsStore.exportPortableSettings()
        assertEquals("720p", exported[SettingsDataStore.KEY_PREFERRED_QUALITY])
        assertEquals("mangacopy", exported[SettingsDataStore.KEY_STARTUP_DEFAULT_SITE_ID])
        assertEquals("false", exported[SettingsDataStore.KEY_UI_SOUND_EFFECTS_ENABLED])
        assertEquals("false", exported[SettingsDataStore.KEY_TAG_DICT_AUTO_SYNC])
        assertEquals("false", exported[SettingsDataStore.KEY_BLOCK_TAGS_GLOBALLY])
        assertTrue(exported[SettingsDataStore.KEY_DOWNLOAD_VIEW]?.contains("\"sort\":\"TITLE\"") == true)
        assertTrue(exported[SettingsDataStore.KEY_DOWNLOAD_VIEW]?.contains("\"groupMode\":\"CUSTOM\"") == true)
        assertFalse(SettingsDataStore.KEY_TAG_DICT_ETAG in exported)
        assertFalse(SettingsDataStore.KEY_TAG_DICT_RELEASE_TAG in exported)
        assertFalse(SettingsDataStore.KEY_TAG_DICT_UPDATED_AT in exported)
        assertFalse(SettingsDataStore.KEY_STORAGE_LOCATION in exported)
        assertFalse(SettingsDataStore.KEY_PENDING_STORAGE_LOCATION in exported)
        assertFalse(SettingsDataStore.KEY_SITE_SWITCH_TUTORIAL_SEEN in exported)

        // Apply ignores non-portable keys and applies portable keys + site languages
        settingsStore.applyBackupSettings(
            settingsMap = mapOf(
                SettingsDataStore.KEY_PREFERRED_QUALITY to "4k",
                SettingsDataStore.KEY_STARTUP_DEFAULT_SITE_ID to "",
                SettingsDataStore.KEY_UI_SOUND_EFFECTS_ENABLED to "true",
                SettingsDataStore.KEY_DOWNLOAD_VIEW to
                    "{\"sort\":\"SIZE\",\"groupMode\":\"TAG\",\"tagMatchAll\":true}",
                SettingsDataStore.KEY_STORAGE_LOCATION to "PRIVATE",
                SettingsDataStore.KEY_SITE_SWITCH_TUTORIAL_SEEN to "false",
            ),
            siteLanguages = mapOf(SiteId.WNACG to ComicLanguage.CHINESE),
        )
        val afterRestore = settingsStore.currentSettings()
        assertEquals("4k", afterRestore.preferredQuality)
        assertNull(afterRestore.startupDefaultSiteId)
        assertTrue(afterRestore.uiSoundEffectsEnabled)
        assertEquals("SIZE", afterRestore.downloadView.sort)
        assertEquals("TAG", afterRestore.downloadView.groupMode)
        assertTrue(afterRestore.downloadView.tagMatchAll)
        assertEquals(StorageLocation.SHARED, afterRestore.storageLocation)
        assertTrue(afterRestore.siteSwitchTutorialSeen)
        assertEquals(ComicLanguage.CHINESE, afterRestore.languageFor(SiteId.WNACG))
    }

    @Test
    fun `recovers to default settings on DataStore IOException`() = runTest {
        val failingStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("simulated corruption") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("read-only")
        }
        val settingsStore = SettingsDataStore(failingStore)
        assertEquals(AppSettings(), settingsStore.currentSettings())
    }
}
