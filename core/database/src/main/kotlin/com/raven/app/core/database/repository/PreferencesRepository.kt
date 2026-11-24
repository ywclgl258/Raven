package com.raven.app.core.database.repository

import com.raven.app.core.database.dao.PlaybackPreferencesDao
import com.raven.app.core.database.dao.ReaderPreferencesDao
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.model.ExploreFilterState
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.PlaybackPreferences
import com.raven.app.core.model.ReaderPreferences
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Unified preferences facade combining [SettingsDataStore] with Room per-site
 * [PlaybackPreferences] and per-site/per-book [ReaderPreferences].
 */
class PreferencesRepository(
    private val settingsDataStore: SettingsDataStore,
    private val playbackPreferencesDao: PlaybackPreferencesDao,
    private val readerPreferencesDao: ReaderPreferencesDao,
) {
    // ---- DataStore AppSettings ----

    val settings: Flow<AppSettings> = settingsDataStore.settings

    suspend fun currentSettings(): AppSettings = settingsDataStore.currentSettings()

    suspend fun setActiveSiteId(siteId: SiteId) = settingsDataStore.setActiveSiteId(siteId)

    suspend fun setPinnedSiteIds(siteIds: List<SiteId>) = settingsDataStore.setPinnedSiteIds(siteIds)

    suspend fun togglePinnedSite(siteId: SiteId): List<SiteId> =
        settingsDataStore.togglePinnedSite(siteId)

    suspend fun setCrossSearchSiteIds(siteIds: List<SiteId>) =
        settingsDataStore.setCrossSearchSiteIds(siteIds)

    suspend fun setSiteLanguage(siteId: SiteId, language: ComicLanguage) =
        settingsDataStore.setSiteLanguage(siteId, language)

    suspend fun setSiteExploreFilter(siteId: SiteId, filter: ExploreFilterState?) =
        settingsDataStore.setSiteExploreFilter(siteId, filter)

    suspend fun setTranslateTags(enabled: Boolean) = settingsDataStore.setTranslateTags(enabled)

    suspend fun setAutoFullscreen(enabled: Boolean) = settingsDataStore.setAutoFullscreen(enabled)

    suspend fun setPreferredQuality(quality: String) =
        settingsDataStore.setPreferredQuality(quality)

    suspend fun setDefaultPlaybackSpeed(speed: Float) =
        settingsDataStore.setDefaultPlaybackSpeed(speed)

    suspend fun setStreamCacheEnabled(enabled: Boolean) =
        settingsDataStore.setStreamCacheEnabled(enabled)

    suspend fun setSeekCacheEnabled(enabled: Boolean) =
        settingsDataStore.setSeekCacheEnabled(enabled)

    suspend fun setDownloadUnmeteredOnly(enabled: Boolean) =
        settingsDataStore.setDownloadUnmeteredOnly(enabled)

    suspend fun setStorageLocation(location: StorageLocation) =
        settingsDataStore.setStorageLocation(location)

    suspend fun setPendingStorageLocation(location: StorageLocation?) =
        settingsDataStore.setPendingStorageLocation(location)

    suspend fun applyPendingStorageLocation(): StorageLocation? =
        settingsDataStore.applyPendingStorageLocation()

    suspend fun setReduceTransparency(enabled: Boolean) =
        settingsDataStore.setReduceTransparency(enabled)

    suspend fun setAutoUpdateCheck(enabled: Boolean) = settingsDataStore.setAutoUpdateCheck(enabled)

    suspend fun consumeSiteSwitchTutorial(): Boolean = settingsDataStore.consumeSiteSwitchTutorial()

    suspend fun setTagDictAutoSync(enabled: Boolean) = settingsDataStore.setTagDictAutoSync(enabled)

    suspend fun setTagDictEtag(etag: String) = settingsDataStore.setTagDictEtag(etag)

    suspend fun setTagDictReleaseTag(releaseTag: String) = settingsDataStore.setTagDictReleaseTag(releaseTag)

    suspend fun setTagDictUpdatedAt(updatedAt: Long) = settingsDataStore.setTagDictUpdatedAt(updatedAt)

    suspend fun setTagDictSyncState(etag: String, releaseTag: String, updatedAt: Long) =
        settingsDataStore.setTagDictSyncState(etag, releaseTag, updatedAt)

    suspend fun setBlockTagsGlobally(enabled: Boolean) = settingsDataStore.setBlockTagsGlobally(enabled)

    // ---- Room PlaybackPreferences (global "*" + per-site override) ----

    fun observeGlobalPlaybackPreferences(): Flow<PlaybackPreferences> =
        combine(
            playbackPreferencesDao.observeBySite(PlaybackPreferencesEntity.GLOBAL_SITE_ID),
            settings,
        ) { row, appSettings ->
            row?.toDomain() ?: PlaybackPreferences(
                siteId = null,
                defaultSpeed = appSettings.defaultPlaybackSpeed,
                preferredQuality = appSettings.preferredQuality,
                autoFullscreen = appSettings.autoFullscreen,
            )
        }

    fun observeSitePlaybackPreferences(siteId: SiteId): Flow<PlaybackPreferences?> =
        playbackPreferencesDao.observeBySite(siteId.value).map { it?.toDomain() }

    /**
     * Resolves effective playback preferences for [siteId]:
     * per-site Room override -> global `"*"` Room row -> [AppSettings] defaults.
     */
    fun observeEffectivePlaybackPreferences(siteId: SiteId): Flow<PlaybackPreferences> =
        combine(
            observeSitePlaybackPreferences(siteId),
            observeGlobalPlaybackPreferences(),
        ) { siteOverride, global ->
            siteOverride ?: global.copy(siteId = siteId)
        }

    suspend fun savePlaybackPreferences(preferences: PlaybackPreferences) {
        playbackPreferencesDao.upsert(preferences.toEntity())
    }

    suspend fun resetSitePlaybackPreferences(siteId: SiteId) {
        playbackPreferencesDao.deleteBySite(siteId.value)
    }

    // ---- Room ReaderPreferences (global "*" -> per-site "" -> per-book mediaId) ----

    fun observeGlobalReaderPreferences(): Flow<ReaderPreferences> =
        readerPreferencesDao.observe(
            ReaderPreferencesEntity.GLOBAL_SITE_ID,
            ReaderPreferencesEntity.SITE_DEFAULT_MEDIA_ID,
        ).map { it?.toDomain() ?: ReaderPreferences() }

    fun observeSiteReaderPreferences(siteId: SiteId): Flow<ReaderPreferences?> =
        readerPreferencesDao.observe(
            siteId.value,
            ReaderPreferencesEntity.SITE_DEFAULT_MEDIA_ID,
        ).map { it?.toDomain() }

    fun observeBookReaderPreferences(siteId: SiteId, mediaId: String): Flow<ReaderPreferences?> =
        readerPreferencesDao.observe(siteId.value, mediaId).map { it?.toDomain() }

    /**
     * Resolves effective reader preferences for `(siteId, mediaId)`:
     * per-book override -> per-site override -> global default -> [ReaderPreferences] defaults.
     */
    fun observeEffectiveReaderPreferences(
        siteId: SiteId,
        mediaId: String,
    ): Flow<ReaderPreferences> =
        combine(
            observeBookReaderPreferences(siteId, mediaId),
            observeSiteReaderPreferences(siteId),
            observeGlobalReaderPreferences(),
        ) { bookOverride, siteOverride, globalDefault ->
            bookOverride
                ?: siteOverride?.copy(mediaId = mediaId)
                ?: globalDefault.copy(siteId = siteId, mediaId = mediaId)
        }

    suspend fun saveReaderPreferences(preferences: ReaderPreferences) {
        readerPreferencesDao.upsert(preferences.toEntity())
    }

    suspend fun resetBookReaderPreferences(siteId: SiteId, mediaId: String) {
        readerPreferencesDao.delete(siteId.value, mediaId)
    }

    suspend fun resetSiteReaderPreferences(siteId: SiteId) {
        readerPreferencesDao.delete(
            siteId.value,
            ReaderPreferencesEntity.SITE_DEFAULT_MEDIA_ID,
        )
    }
}
