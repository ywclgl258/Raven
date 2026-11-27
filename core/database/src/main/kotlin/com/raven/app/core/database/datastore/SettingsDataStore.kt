package com.raven.app.core.database.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.raven.app.core.database.converter.RavenTypeConverters
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.model.DownloadViewPreferences
import com.raven.app.core.database.model.ExploreFilterState
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Preferences DataStore (`raven_settings.preferences_pb`) exposing reactive [Flow] of [AppSettings]
 * and suspend setters for every setting defined in `ARCHITECTURE.md` §6.2.
 */
class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
) {
    private val persistedSettings: Flow<AppSettings> = dataStore.data.map(::mapPreferences)

    private fun Flow<AppSettings>.retryTransientIo(): Flow<AppSettings> = retryWhen { cause, attempt ->
        if (cause !is IOException) throw cause
        delay((attempt + 1).coerceAtMost(30L) * 1_000L)
        true
    }

    // Long-lived UI/policy collectors wait and retry instead of crashing or silently completing.
    val settings: Flow<AppSettings> = persistedSettings.retryTransientIo()

    // One-shot UI work uses the same non-crashing policy and is cancelled with its caller.
    suspend fun currentSettings(): AppSettings = persistedSettings.retryTransientIo().first()

    // Storage-root selection and backup snapshots must never substitute defaults on an I/O error.
    suspend fun readPersistedSettings(): AppSettings = persistedSettings.first()

    suspend fun setActiveSiteId(siteId: SiteId) {
        dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_SITE_ID] = siteId.value
        }
    }

    suspend fun recordSiteOpen(siteId: SiteId) {
        dataStore.edit { prefs ->
            incrementSiteOpenIfAllowed(prefs, siteId)
        }
    }

    suspend fun selectActiveSiteAndRecordOpen(siteId: SiteId) {
        dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_SITE_ID] = siteId.value
            incrementSiteOpenIfAllowed(prefs, siteId)
        }
    }

    suspend fun recordSectionOpen(siteId: SiteId, sectionId: String) {
        val cleanSection = sectionId.trim()
        if (cleanSection.isEmpty() || cleanSection.equals("chapters", ignoreCase = true)) return
        dataStore.edit { prefs ->
            incrementSectionOpenIfAllowed(prefs, siteId, cleanSection)
        }
    }

    suspend fun setSiteExploreFilterAndRecordSection(
        siteId: SiteId,
        filter: ExploreFilterState?,
        recordedSectionId: String?,
    ) {
        val cleanSection = recordedSectionId?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("chapters", ignoreCase = true) }
        val key = Keys.siteExploreFilter(siteId)
        dataStore.edit { prefs ->
            if (filter == null || filter.isEmpty) {
                prefs.remove(key)
            } else {
                prefs[key] = json.encodeToString(ExploreFilterState.serializer(), filter)
            }
            if (cleanSection != null) {
                incrementSectionOpenIfAllowed(prefs, siteId, cleanSection)
            }
        }
    }

    suspend fun setStartupDefaultSiteId(siteId: SiteId?) {
        dataStore.edit { prefs ->
            if (siteId == null) {
                prefs.remove(Keys.STARTUP_DEFAULT_SITE_ID)
            } else {
                prefs[Keys.STARTUP_DEFAULT_SITE_ID] = siteId.value
            }
        }
    }

    suspend fun setPinnedSiteIds(siteIds: List<SiteId>) {
        val unique = siteIds.distinct()
        dataStore.edit { prefs ->
            prefs[Keys.PINNED_SITE_IDS] = encodeSiteIdList(unique)
        }
    }

    suspend fun togglePinnedSite(siteId: SiteId): List<SiteId> {
        var result: List<SiteId> = emptyList()
        dataStore.edit { prefs ->
            val current = decodeSiteIdList(prefs[Keys.PINNED_SITE_IDS])
            val next = if (siteId in current) current - siteId else current + siteId
            prefs[Keys.PINNED_SITE_IDS] = encodeSiteIdList(next)
            result = next
        }
        return result
    }

    /** Toggles usage-based ordering for the site switcher. Default is `true`. */
    suspend fun setDynamicSiteSorting(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.DYNAMIC_SITE_SORTING] = enabled }
    }

    suspend fun setCrossSearchSiteIds(siteIds: List<SiteId>) {
        val unique = siteIds.distinct()
        dataStore.edit { prefs ->
            prefs[Keys.CROSS_SEARCH_SITE_IDS] = encodeSiteIdList(unique)
        }
    }

    suspend fun setSiteLanguage(siteId: SiteId, language: ComicLanguage) {
        val key = Keys.siteLanguage(siteId)
        dataStore.edit { prefs ->
            if (language == ComicLanguage.ALL) {
                prefs.remove(key)
            } else {
                prefs[key] = language.name
            }
        }
    }

    suspend fun setSiteExploreFilter(siteId: SiteId, filter: ExploreFilterState?) {
        val key = Keys.siteExploreFilter(siteId)
        dataStore.edit { prefs ->
            if (filter == null || filter.isEmpty) {
                prefs.remove(key)
            } else {
                prefs[key] = json.encodeToString(ExploreFilterState.serializer(), filter)
            }
        }
    }

    suspend fun setTranslateTags(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.TRANSLATE_TAGS] = enabled }
    }

    suspend fun setAutoFullscreen(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_FULLSCREEN] = enabled }
    }

    suspend fun setPreferredQuality(quality: String) {
        val normalized = quality.trim().ifBlank { AppSettings.DEFAULT_PREFERRED_QUALITY }
        dataStore.edit { prefs -> prefs[Keys.PREFERRED_QUALITY] = normalized }
    }

    suspend fun setDefaultPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(AppSettings.MIN_PLAYBACK_SPEED, AppSettings.MAX_PLAYBACK_SPEED)
        dataStore.edit { prefs -> prefs[Keys.DEFAULT_PLAYBACK_SPEED] = clamped }
    }

    suspend fun setStreamCacheEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.STREAM_CACHE_ENABLED] = enabled }
    }

    suspend fun setSeekCacheEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SEEK_CACHE_ENABLED] = enabled }
    }

    suspend fun setDownloadUnmeteredOnly(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.DOWNLOAD_UNMETERED_ONLY] = enabled }
    }

    suspend fun setDownloadSort(sort: String) {
        dataStore.edit { prefs ->
            val current = decodeDownloadView(prefs[Keys.DOWNLOAD_VIEW])
            prefs[Keys.DOWNLOAD_VIEW] = json.encodeToString(
                DownloadViewPreferences.serializer(),
                current.copy(sort = sort),
            )
        }
    }

    suspend fun setDownloadGroupMode(groupMode: String) {
        dataStore.edit { prefs ->
            val current = decodeDownloadView(prefs[Keys.DOWNLOAD_VIEW])
            prefs[Keys.DOWNLOAD_VIEW] = json.encodeToString(
                DownloadViewPreferences.serializer(),
                current.copy(groupMode = groupMode),
            )
        }
    }

    suspend fun setDownloadTagMatchAll(matchAll: Boolean) {
        dataStore.edit { prefs ->
            val current = decodeDownloadView(prefs[Keys.DOWNLOAD_VIEW])
            prefs[Keys.DOWNLOAD_VIEW] = json.encodeToString(
                DownloadViewPreferences.serializer(),
                current.copy(tagMatchAll = matchAll),
            )
        }
    }

    suspend fun setStorageLocation(location: StorageLocation) {
        dataStore.edit { prefs -> prefs[Keys.STORAGE_LOCATION] = location.name }
    }

    suspend fun setPendingStorageLocation(location: StorageLocation?) {
        dataStore.edit { prefs ->
            if (location == null) {
                prefs[Keys.PENDING_STORAGE_LOCATION] = ""
            } else {
                prefs[Keys.PENDING_STORAGE_LOCATION] = location.name
            }
        }
    }

    /**
     * Applies any deferred storage root cleanly on cold start and clears `pending_storage_location`.
     * Returns the newly applied [StorageLocation], or `null` if none was pending.
     */
    suspend fun applyPendingStorageLocation(): StorageLocation? {
        var applied: StorageLocation? = null
        dataStore.edit { prefs ->
            val pending = parseStorageLocationOrNull(prefs[Keys.PENDING_STORAGE_LOCATION])
            if (pending != null) {
                prefs[Keys.STORAGE_LOCATION] = pending.name
                prefs[Keys.PENDING_STORAGE_LOCATION] = ""
                applied = pending
            }
        }
        return applied
    }

    suspend fun setReduceTransparency(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.REDUCE_TRANSPARENCY] = enabled }
    }

    suspend fun setUiSoundEffectsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.UI_SOUND_EFFECTS_ENABLED] = enabled }
    }

    suspend fun setAutoUpdateCheck(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_UPDATE_CHECK] = enabled }
    }

    suspend fun setSiteSwitchTutorialSeen(seen: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SITE_SWITCH_TUTORIAL_SEEN] = seen }
    }

    suspend fun setTagDictAutoSync(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.TAG_DICT_AUTO_SYNC] = enabled }
    }

    suspend fun setTagDictEtag(etag: String) {
        dataStore.edit { prefs -> prefs[Keys.TAG_DICT_ETAG] = etag.trim() }
    }

    suspend fun setTagDictReleaseTag(releaseTag: String) {
        dataStore.edit { prefs -> prefs[Keys.TAG_DICT_RELEASE_TAG] = releaseTag.trim() }
    }

    suspend fun setTagDictUpdatedAt(updatedAt: Long) {
        dataStore.edit { prefs -> prefs[Keys.TAG_DICT_UPDATED_AT] = updatedAt.coerceAtLeast(0L) }
    }

    suspend fun setTagDictSyncState(etag: String, releaseTag: String, updatedAt: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.TAG_DICT_ETAG] = etag.trim()
            prefs[Keys.TAG_DICT_RELEASE_TAG] = releaseTag.trim()
            prefs[Keys.TAG_DICT_UPDATED_AT] = updatedAt.coerceAtLeast(0L)
        }
    }

    suspend fun setBlockTagsGlobally(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.BLOCK_TAGS_GLOBALLY] = enabled }
    }

    suspend fun setAutoplayVideo(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTOPLAY_VIDEO] = enabled }
    }

    suspend fun setAutoContinueQueue(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_CONTINUE_QUEUE] = enabled }
    }

    suspend fun setBackgroundAudioPlayback(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.BACKGROUND_AUDIO_PLAYBACK] = enabled }
    }

    suspend fun setReaderTapZonesEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.READER_TAP_ZONES_ENABLED] = enabled }
    }

    suspend fun setReaderShowStatusBar(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.READER_SHOW_STATUS_BAR] = enabled }
    }

    suspend fun setReaderPrefetchPages(pages: Int) {
        val clamped = pages.coerceIn(AppSettings.MIN_READER_PREFETCH_PAGES, AppSettings.MAX_READER_PREFETCH_PAGES)
        dataStore.edit { prefs -> prefs[Keys.READER_PREFETCH_PAGES] = clamped }
    }

    suspend fun setReaderMemoryBudgetMb(budgetMb: Int) {
        val clamped = budgetMb.coerceIn(AppSettings.MIN_READER_MEMORY_BUDGET_MB, AppSettings.MAX_READER_MEMORY_BUDGET_MB)
        dataStore.edit { prefs -> prefs[Keys.READER_MEMORY_BUDGET_MB] = clamped }
    }

    suspend fun setReaderAutoSplitWidePages(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.READER_AUTO_SPLIT_WIDE_PAGES] = enabled }
    }

    suspend fun setImageLoadConcurrency(threads: Int) {
        val clamped = threads.coerceIn(AppSettings.MIN_IMAGE_CONCURRENCY, AppSettings.MAX_IMAGE_CONCURRENCY)
        dataStore.edit { prefs -> prefs[Keys.IMAGE_LOAD_CONCURRENCY] = clamped }
    }

    suspend fun setBackgroundSubscriptionSync(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.BACKGROUND_SUBSCRIPTION_SYNC] = enabled }
    }

    suspend fun setPrivacyScreenSecure(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.PRIVACY_SCREEN_SECURE] = enabled }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setAppLockTimeoutSeconds(seconds: Int) {
        dataStore.edit { prefs -> prefs[Keys.APP_LOCK_TIMEOUT_SECONDS] = seconds.coerceIn(0, 3600) }
    }

    suspend fun setIncognitoMode(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.INCOGNITO_MODE] = enabled }
    }

    suspend fun setWebDavLastSyncAt(epochMs: Long) {
        dataStore.edit { prefs -> prefs[Keys.WEBDAV_LAST_SYNC_AT] = epochMs.coerceAtLeast(0L) }
    }

    /**
     * Atomically marks `site_switch_tutorial_seen = true`.
     * Returns `true` strictly once when transitioning from unseen (`false`) to seen (`true`).
     */
    suspend fun consumeSiteSwitchTutorial(): Boolean {
        var consumed = false
        dataStore.edit { prefs ->
            val alreadySeen = prefs[Keys.SITE_SWITCH_TUTORIAL_SEEN] ?: false
            if (!alreadySeen) {
                prefs[Keys.SITE_SWITCH_TUTORIAL_SEEN] = true
                consumed = true
            }
        }
        return consumed
    }

    suspend fun markTutorialSeen(tutorialId: String) {
        val clean = tutorialId.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeStringSet(prefs[Keys.SEEN_TUTORIALS])
            if (clean !in current) {
                prefs[Keys.SEEN_TUTORIALS] = encodeStringSet(current + clean)
            }
        }
    }

    suspend fun markTutorialsSeen(tutorialIds: Collection<String>) {
        val cleaned = tutorialIds.map(String::trim).filter(String::isNotEmpty)
        if (cleaned.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeStringSet(prefs[Keys.SEEN_TUTORIALS])
            val merged = current + cleaned
            prefs[Keys.SEEN_TUTORIALS] = encodeStringSet(merged)
        }
    }

    suspend fun resetAllTutorials() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.SEEN_TUTORIALS)
            prefs[Keys.SITE_SWITCH_TUTORIAL_SEEN] = false
        }
    }

    /**
     * Exports ONLY safe portable user settings for encrypted backups.
     * Never exports device-local storage roots, tutorial flags, credentials, or keys.
     */
    suspend fun exportPortableSettings(): Map<String, String> {
        val current = readPersistedSettings()
        return buildMap {
            put(KEY_ACTIVE_SITE_ID, current.activeSiteId.value)
            current.startupDefaultSiteId?.value?.let { put(KEY_STARTUP_DEFAULT_SITE_ID, it) }
            put(KEY_PINNED_SITE_IDS, encodeSiteIdList(current.pinnedSiteIds))
            put(KEY_DYNAMIC_SITE_SORTING, current.dynamicSiteSorting.toString())
            put(KEY_CROSS_SEARCH_SITE_IDS, encodeSiteIdList(current.crossSearchSiteIds))
            put(KEY_TRANSLATE_TAGS, current.translateTags.toString())
            put(KEY_AUTO_FULLSCREEN, current.autoFullscreen.toString())
            put(KEY_PREFERRED_QUALITY, current.preferredQuality)
            put(KEY_DEFAULT_PLAYBACK_SPEED, current.defaultPlaybackSpeed.toString())
            put(KEY_STREAM_CACHE_ENABLED, current.streamCacheEnabled.toString())
            put(KEY_SEEK_CACHE_ENABLED, current.seekCacheEnabled.toString())
            put(KEY_DOWNLOAD_UNMETERED_ONLY, current.downloadUnmeteredOnly.toString())
            put(KEY_DOWNLOAD_VIEW, json.encodeToString(DownloadViewPreferences.serializer(), current.downloadView))
            put(KEY_REDUCE_TRANSPARENCY, current.reduceTransparency.toString())
            put(KEY_UI_SOUND_EFFECTS_ENABLED, current.uiSoundEffectsEnabled.toString())
            put(KEY_AUTO_UPDATE_CHECK, current.autoUpdateCheck.toString())
            put(KEY_TAG_DICT_AUTO_SYNC, current.tagDictAutoSync.toString())
            put(KEY_BLOCK_TAGS_GLOBALLY, current.blockTagsGlobally.toString())
        }
    }

    /**
     * Applies portable settings and per-site languages from a merged backup payload.
     * Strictly ignores unknown or device-local keys (`storage_location`, `pending_storage_location`, etc.).
     */
    suspend fun applyBackupSettings(
        settingsMap: Map<String, String>,
        siteLanguages: Map<SiteId, ComicLanguage>,
    ) {
        dataStore.edit { prefs ->
            applyPortableEntries(prefs, settingsMap)
            siteLanguages.forEach { (siteId, language) ->
                val key = Keys.siteLanguage(siteId)
                if (language == ComicLanguage.ALL) {
                    prefs.remove(key)
                } else {
                    prefs[key] = language.name
                }
            }
        }
    }

    private fun applyPortableEntries(prefs: MutablePreferences, settingsMap: Map<String, String>) {
        settingsMap[KEY_ACTIVE_SITE_ID]?.trim()?.takeIf(String::isNotEmpty)?.let { raw ->
            prefs[Keys.ACTIVE_SITE_ID] = SiteId.of(raw).value
        }
        settingsMap[KEY_STARTUP_DEFAULT_SITE_ID]?.let { raw ->
            val normalized = raw.trim()
            if (normalized.isEmpty()) {
                prefs.remove(Keys.STARTUP_DEFAULT_SITE_ID)
            } else {
                prefs[Keys.STARTUP_DEFAULT_SITE_ID] = SiteId.of(normalized).value
            }
        }
        settingsMap[KEY_PINNED_SITE_IDS]?.let { raw ->
            prefs[Keys.PINNED_SITE_IDS] = encodeSiteIdList(decodeSiteIdList(raw).distinct())
        }
        settingsMap[KEY_DYNAMIC_SITE_SORTING]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.DYNAMIC_SITE_SORTING] = it
        }
        settingsMap[KEY_CROSS_SEARCH_SITE_IDS]?.let { raw ->
            prefs[Keys.CROSS_SEARCH_SITE_IDS] = encodeSiteIdList(decodeSiteIdList(raw).distinct())
        }
        settingsMap[KEY_TRANSLATE_TAGS]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.TRANSLATE_TAGS] = it
        }
        settingsMap[KEY_AUTO_FULLSCREEN]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.AUTO_FULLSCREEN] = it
        }
        settingsMap[KEY_PREFERRED_QUALITY]?.trim()?.takeIf(String::isNotEmpty)?.let {
            prefs[Keys.PREFERRED_QUALITY] = it
        }
        settingsMap[KEY_DEFAULT_PLAYBACK_SPEED]?.toFloatOrNull()?.let { speed ->
            prefs[Keys.DEFAULT_PLAYBACK_SPEED] = speed.coerceIn(
                AppSettings.MIN_PLAYBACK_SPEED,
                AppSettings.MAX_PLAYBACK_SPEED,
            )
        }
        settingsMap[KEY_STREAM_CACHE_ENABLED]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.STREAM_CACHE_ENABLED] = it
        }
        settingsMap[KEY_SEEK_CACHE_ENABLED]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.SEEK_CACHE_ENABLED] = it
        }
        settingsMap[KEY_DOWNLOAD_UNMETERED_ONLY]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.DOWNLOAD_UNMETERED_ONLY] = it
        }
        settingsMap[KEY_DOWNLOAD_VIEW]?.let { raw ->
            prefs[Keys.DOWNLOAD_VIEW] = json.encodeToString(
                DownloadViewPreferences.serializer(),
                decodeDownloadView(raw),
            )
        }
        settingsMap[KEY_REDUCE_TRANSPARENCY]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.REDUCE_TRANSPARENCY] = it
        }
        settingsMap[KEY_UI_SOUND_EFFECTS_ENABLED]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.UI_SOUND_EFFECTS_ENABLED] = it
        }
        settingsMap[KEY_AUTO_UPDATE_CHECK]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.AUTO_UPDATE_CHECK] = it
        }
        settingsMap[KEY_TAG_DICT_AUTO_SYNC]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.TAG_DICT_AUTO_SYNC] = it
        }
        settingsMap[KEY_BLOCK_TAGS_GLOBALLY]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.BLOCK_TAGS_GLOBALLY] = it
        }
    }

    private fun mapPreferences(prefs: Preferences): AppSettings {
        val languages = linkedMapOf<SiteId, ComicLanguage>()
        val exploreFilters = linkedMapOf<SiteId, ExploreFilterState>()

        for ((key, rawValue) in prefs.asMap()) {
            val name = key.name
            val strValue = rawValue as? String ?: continue
            when {
                name.startsWith(SITE_LANGUAGE_PREFIX) -> {
                    val rawSite = name.removePrefix(SITE_LANGUAGE_PREFIX).trim()
                    if (rawSite.isNotEmpty()) {
                        val lang = ComicLanguage.entries.firstOrNull { it.name == strValue }
                        if (lang != null && lang != ComicLanguage.ALL) {
                            languages[SiteId.of(rawSite)] = lang
                        }
                    }
                }

                name.startsWith(SITE_EXPLORE_FILTER_PREFIX) -> {
                    val rawSite = name.removePrefix(SITE_EXPLORE_FILTER_PREFIX).trim()
                    if (rawSite.isNotEmpty() && strValue.isNotBlank()) {
                        runCatching {
                            json.decodeFromString(ExploreFilterState.serializer(), strValue)
                        }.getOrNull()?.takeUnless { it.isEmpty }?.let { filter ->
                            exploreFilters[SiteId.of(rawSite)] = filter
                        }
                    }
                }
            }
        }

        val activeSite = prefs[Keys.ACTIVE_SITE_ID]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(SiteId::of)
            ?: AppSettings.DEFAULT_ACTIVE_SITE_ID

        val siteOpenCounts = decodeSiteOpenCounts(prefs[Keys.SITE_OPEN_COUNTS])
        val sectionOpenCounts = decodeSectionOpenCounts(prefs[Keys.SECTION_OPEN_COUNTS])

        val storage = parseStorageLocationOrNull(prefs[Keys.STORAGE_LOCATION])
            ?: StorageLocation.PRIVATE
        val pendingStorage = parseStorageLocationOrNull(prefs[Keys.PENDING_STORAGE_LOCATION])

        val speed = (prefs[Keys.DEFAULT_PLAYBACK_SPEED] ?: AppSettings.DEFAULT_PLAYBACK_SPEED)
            .coerceIn(AppSettings.MIN_PLAYBACK_SPEED, AppSettings.MAX_PLAYBACK_SPEED)

        return AppSettings(
            activeSiteId = activeSite,
            startupDefaultSiteId = prefs[Keys.STARTUP_DEFAULT_SITE_ID]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(SiteId::of),
            pinnedSiteIds = decodeSiteIdList(prefs[Keys.PINNED_SITE_IDS]).distinct(),
            dynamicSiteSorting = prefs[Keys.DYNAMIC_SITE_SORTING]
                ?: AppSettings.DEFAULT_DYNAMIC_SITE_SORTING,
            crossSearchSiteIds = decodeSiteIdList(prefs[Keys.CROSS_SEARCH_SITE_IDS]).distinct(),
            siteOpenCounts = siteOpenCounts,
            sectionOpenCounts = sectionOpenCounts,
            siteLanguages = languages,
            siteExploreFilters = exploreFilters,
            translateTags = prefs[Keys.TRANSLATE_TAGS] ?: true,
            autoFullscreen = prefs[Keys.AUTO_FULLSCREEN] ?: false,
            preferredQuality = prefs[Keys.PREFERRED_QUALITY]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: AppSettings.DEFAULT_PREFERRED_QUALITY,
            defaultPlaybackSpeed = speed,
            streamCacheEnabled = prefs[Keys.STREAM_CACHE_ENABLED] ?: false,
            seekCacheEnabled = prefs[Keys.SEEK_CACHE_ENABLED] ?: true,
            downloadUnmeteredOnly = prefs[Keys.DOWNLOAD_UNMETERED_ONLY] ?: false,
            downloadView = decodeDownloadView(prefs[Keys.DOWNLOAD_VIEW]),
            storageLocation = storage,
            pendingStorageLocation = pendingStorage,
            reduceTransparency = prefs[Keys.REDUCE_TRANSPARENCY] ?: false,
            uiSoundEffectsEnabled = prefs[Keys.UI_SOUND_EFFECTS_ENABLED] ?: true,
            autoUpdateCheck = prefs[Keys.AUTO_UPDATE_CHECK] ?: true,
            siteSwitchTutorialSeen = prefs[Keys.SITE_SWITCH_TUTORIAL_SEEN] ?: false,
            seenTutorials = decodeStringSet(prefs[Keys.SEEN_TUTORIALS]),
            tagDictAutoSync = prefs[Keys.TAG_DICT_AUTO_SYNC] ?: true,
            tagDictEtag = prefs[Keys.TAG_DICT_ETAG].orEmpty(),
            tagDictReleaseTag = prefs[Keys.TAG_DICT_RELEASE_TAG].orEmpty(),
            tagDictUpdatedAt = prefs[Keys.TAG_DICT_UPDATED_AT] ?: 0L,
            blockTagsGlobally = prefs[Keys.BLOCK_TAGS_GLOBALLY] ?: true,
            autoplayVideo = prefs[Keys.AUTOPLAY_VIDEO] ?: true,
            autoContinueQueue = prefs[Keys.AUTO_CONTINUE_QUEUE] ?: false,
            backgroundAudioPlayback = prefs[Keys.BACKGROUND_AUDIO_PLAYBACK] ?: false,
            readerTapZonesEnabled = prefs[Keys.READER_TAP_ZONES_ENABLED] ?: true,
            readerShowStatusBar = prefs[Keys.READER_SHOW_STATUS_BAR] ?: false,
            readerPrefetchPages = (prefs[Keys.READER_PREFETCH_PAGES] ?: AppSettings.DEFAULT_READER_PREFETCH_PAGES)
                .coerceIn(AppSettings.MIN_READER_PREFETCH_PAGES, AppSettings.MAX_READER_PREFETCH_PAGES),
            readerMemoryBudgetMb = (prefs[Keys.READER_MEMORY_BUDGET_MB] ?: AppSettings.DEFAULT_READER_MEMORY_BUDGET_MB)
                .coerceIn(AppSettings.MIN_READER_MEMORY_BUDGET_MB, AppSettings.MAX_READER_MEMORY_BUDGET_MB),
            readerAutoSplitWidePages = prefs[Keys.READER_AUTO_SPLIT_WIDE_PAGES] ?: false,
            imageLoadConcurrency = (prefs[Keys.IMAGE_LOAD_CONCURRENCY] ?: AppSettings.DEFAULT_IMAGE_CONCURRENCY)
                .coerceIn(AppSettings.MIN_IMAGE_CONCURRENCY, AppSettings.MAX_IMAGE_CONCURRENCY),
            backgroundSubscriptionSync = prefs[Keys.BACKGROUND_SUBSCRIPTION_SYNC] ?: true,
            privacyScreenSecure = prefs[Keys.PRIVACY_SCREEN_SECURE] ?: false,
            appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
            appLockTimeoutSeconds = (prefs[Keys.APP_LOCK_TIMEOUT_SECONDS] ?: 0).coerceIn(0, 3600),
            incognitoMode = prefs[Keys.INCOGNITO_MODE] ?: false,
            webDavLastSyncAt = (prefs[Keys.WEBDAV_LAST_SYNC_AT] ?: 0L).coerceAtLeast(0L),
        )
    }

    object Keys {
        val ACTIVE_SITE_ID = stringPreferencesKey(KEY_ACTIVE_SITE_ID)
        val STARTUP_DEFAULT_SITE_ID = stringPreferencesKey(KEY_STARTUP_DEFAULT_SITE_ID)
        val PINNED_SITE_IDS = stringPreferencesKey(KEY_PINNED_SITE_IDS)
        val DYNAMIC_SITE_SORTING = booleanPreferencesKey(KEY_DYNAMIC_SITE_SORTING)
        val CROSS_SEARCH_SITE_IDS = stringPreferencesKey(KEY_CROSS_SEARCH_SITE_IDS)
        val SITE_OPEN_COUNTS = stringPreferencesKey(KEY_SITE_OPEN_COUNTS)
        val SECTION_OPEN_COUNTS = stringPreferencesKey(KEY_SECTION_OPEN_COUNTS)
        val TRANSLATE_TAGS = booleanPreferencesKey(KEY_TRANSLATE_TAGS)
        val AUTO_FULLSCREEN = booleanPreferencesKey(KEY_AUTO_FULLSCREEN)
        val PREFERRED_QUALITY = stringPreferencesKey(KEY_PREFERRED_QUALITY)
        val DEFAULT_PLAYBACK_SPEED = floatPreferencesKey(KEY_DEFAULT_PLAYBACK_SPEED)
        val STREAM_CACHE_ENABLED = booleanPreferencesKey(KEY_STREAM_CACHE_ENABLED)
        val SEEK_CACHE_ENABLED = booleanPreferencesKey(KEY_SEEK_CACHE_ENABLED)
        val DOWNLOAD_UNMETERED_ONLY = booleanPreferencesKey(KEY_DOWNLOAD_UNMETERED_ONLY)
        val DOWNLOAD_VIEW = stringPreferencesKey(KEY_DOWNLOAD_VIEW)
        val STORAGE_LOCATION = stringPreferencesKey(KEY_STORAGE_LOCATION)
        val PENDING_STORAGE_LOCATION = stringPreferencesKey(KEY_PENDING_STORAGE_LOCATION)
        val REDUCE_TRANSPARENCY = booleanPreferencesKey(KEY_REDUCE_TRANSPARENCY)
        val UI_SOUND_EFFECTS_ENABLED = booleanPreferencesKey(KEY_UI_SOUND_EFFECTS_ENABLED)
        val AUTO_UPDATE_CHECK = booleanPreferencesKey(KEY_AUTO_UPDATE_CHECK)
        val SITE_SWITCH_TUTORIAL_SEEN = booleanPreferencesKey(KEY_SITE_SWITCH_TUTORIAL_SEEN)
        val SEEN_TUTORIALS = stringPreferencesKey(KEY_SEEN_TUTORIALS)
        val TAG_DICT_AUTO_SYNC = booleanPreferencesKey(KEY_TAG_DICT_AUTO_SYNC)
        val TAG_DICT_ETAG = stringPreferencesKey(KEY_TAG_DICT_ETAG)
        val TAG_DICT_RELEASE_TAG = stringPreferencesKey(KEY_TAG_DICT_RELEASE_TAG)
        val TAG_DICT_UPDATED_AT = longPreferencesKey(KEY_TAG_DICT_UPDATED_AT)
        val BLOCK_TAGS_GLOBALLY = booleanPreferencesKey(KEY_BLOCK_TAGS_GLOBALLY)
        val AUTOPLAY_VIDEO = booleanPreferencesKey(KEY_AUTOPLAY_VIDEO)
        val AUTO_CONTINUE_QUEUE = booleanPreferencesKey(KEY_AUTO_CONTINUE_QUEUE)
        val BACKGROUND_AUDIO_PLAYBACK = booleanPreferencesKey(KEY_BACKGROUND_AUDIO_PLAYBACK)
        val READER_TAP_ZONES_ENABLED = booleanPreferencesKey(KEY_READER_TAP_ZONES_ENABLED)
        val READER_SHOW_STATUS_BAR = booleanPreferencesKey(KEY_READER_SHOW_STATUS_BAR)
        val READER_PREFETCH_PAGES = intPreferencesKey(KEY_READER_PREFETCH_PAGES)
        val READER_MEMORY_BUDGET_MB = intPreferencesKey(KEY_READER_MEMORY_BUDGET_MB)
        val READER_AUTO_SPLIT_WIDE_PAGES = booleanPreferencesKey(KEY_READER_AUTO_SPLIT_WIDE_PAGES)
        val IMAGE_LOAD_CONCURRENCY = intPreferencesKey(KEY_IMAGE_LOAD_CONCURRENCY)
        val BACKGROUND_SUBSCRIPTION_SYNC = booleanPreferencesKey(KEY_BACKGROUND_SUBSCRIPTION_SYNC)
        val PRIVACY_SCREEN_SECURE = booleanPreferencesKey(KEY_PRIVACY_SCREEN_SECURE)
        val APP_LOCK_ENABLED = booleanPreferencesKey(KEY_APP_LOCK_ENABLED)
        val APP_LOCK_TIMEOUT_SECONDS = intPreferencesKey(KEY_APP_LOCK_TIMEOUT_SECONDS)
        val INCOGNITO_MODE = booleanPreferencesKey(KEY_INCOGNITO_MODE)
        val WEBDAV_LAST_SYNC_AT = longPreferencesKey(KEY_WEBDAV_LAST_SYNC_AT)

        fun siteLanguage(siteId: SiteId): Preferences.Key<String> =
            stringPreferencesKey("$SITE_LANGUAGE_PREFIX${siteId.value}")

        fun siteExploreFilter(siteId: SiteId): Preferences.Key<String> =
            stringPreferencesKey("$SITE_EXPLORE_FILTER_PREFIX${siteId.value}")
    }

    companion object {
        /** Produces `raven_settings.preferences_pb` via [Context.preferencesDataStoreFile]. */
        const val FILE_NAME: String = "raven_settings"

        const val KEY_ACTIVE_SITE_ID: String = "active_site_id"
        const val KEY_STARTUP_DEFAULT_SITE_ID: String = "startup_default_site_id"
        const val KEY_PINNED_SITE_IDS: String = "pinned_site_ids"
        const val KEY_DYNAMIC_SITE_SORTING: String = "dynamic_site_sorting"
        const val KEY_CROSS_SEARCH_SITE_IDS: String = "cross_search_site_ids"
        const val KEY_SITE_OPEN_COUNTS: String = "site_open_counts"
        const val KEY_SECTION_OPEN_COUNTS: String = "section_open_counts"
        const val SITE_LANGUAGE_PREFIX: String = "site_language_"
        const val SITE_EXPLORE_FILTER_PREFIX: String = "site_explore_filter_"
        const val KEY_TRANSLATE_TAGS: String = "translate_tags"
        const val KEY_AUTO_FULLSCREEN: String = "auto_fullscreen"
        const val KEY_PREFERRED_QUALITY: String = "preferred_quality"
        const val KEY_DEFAULT_PLAYBACK_SPEED: String = "default_playback_speed"
        const val KEY_STREAM_CACHE_ENABLED: String = "stream_cache_enabled"
        const val KEY_SEEK_CACHE_ENABLED: String = "seek_cache_enabled"
        const val KEY_DOWNLOAD_UNMETERED_ONLY: String = "download_unmetered_only"
        const val KEY_DOWNLOAD_VIEW: String = "download_view"
        const val KEY_STORAGE_LOCATION: String = "storage_location"
        const val KEY_PENDING_STORAGE_LOCATION: String = "pending_storage_location"
        const val KEY_REDUCE_TRANSPARENCY: String = "reduce_transparency"
        const val KEY_UI_SOUND_EFFECTS_ENABLED: String = "ui_sound_effects_enabled"
        const val KEY_AUTO_UPDATE_CHECK: String = "auto_update_check"
        const val KEY_SITE_SWITCH_TUTORIAL_SEEN: String = "site_switch_tutorial_seen"
        const val KEY_SEEN_TUTORIALS: String = "seen_tutorials"
        const val KEY_TAG_DICT_AUTO_SYNC: String = "tag_dict_auto_sync"
        const val KEY_TAG_DICT_ETAG: String = "tag_dict_etag"
        const val KEY_TAG_DICT_RELEASE_TAG: String = "tag_dict_release_tag"
        const val KEY_TAG_DICT_UPDATED_AT: String = "tag_dict_updated_at"
        const val KEY_BLOCK_TAGS_GLOBALLY: String = "block_tags_globally"
        const val KEY_AUTOPLAY_VIDEO: String = "autoplay_video"
        const val KEY_AUTO_CONTINUE_QUEUE: String = "auto_continue_queue"
        const val KEY_BACKGROUND_AUDIO_PLAYBACK: String = "background_audio_playback"
        const val KEY_READER_TAP_ZONES_ENABLED: String = "reader_tap_zones_enabled"
        const val KEY_READER_SHOW_STATUS_BAR: String = "reader_show_status_bar"
        const val KEY_READER_PREFETCH_PAGES: String = "reader_prefetch_pages"
        const val KEY_READER_MEMORY_BUDGET_MB: String = "reader_memory_budget_mb"
        const val KEY_READER_AUTO_SPLIT_WIDE_PAGES: String = "reader_auto_split_wide_pages"
        const val KEY_IMAGE_LOAD_CONCURRENCY: String = "image_load_concurrency"
        const val KEY_BACKGROUND_SUBSCRIPTION_SYNC: String = "background_subscription_sync"
        const val KEY_PRIVACY_SCREEN_SECURE: String = "privacy_screen_secure"
        const val KEY_APP_LOCK_ENABLED: String = "app_lock_enabled"
        const val KEY_APP_LOCK_TIMEOUT_SECONDS: String = "app_lock_timeout_seconds"
        const val KEY_INCOGNITO_MODE: String = "incognito_mode"
        const val KEY_WEBDAV_LAST_SYNC_AT: String = "webdav_last_sync_at"

        /** Keys safe to include in encrypted cross-device backups (never device paths/credentials). */
        val PORTABLE_BACKUP_KEYS: Set<String> = setOf(
            KEY_ACTIVE_SITE_ID,
            KEY_STARTUP_DEFAULT_SITE_ID,
            KEY_PINNED_SITE_IDS,
            KEY_DYNAMIC_SITE_SORTING,
            KEY_CROSS_SEARCH_SITE_IDS,
            KEY_TRANSLATE_TAGS,
            KEY_AUTO_FULLSCREEN,
            KEY_PREFERRED_QUALITY,
            KEY_DEFAULT_PLAYBACK_SPEED,
            KEY_STREAM_CACHE_ENABLED,
            KEY_SEEK_CACHE_ENABLED,
            KEY_DOWNLOAD_UNMETERED_ONLY,
            KEY_DOWNLOAD_VIEW,
            KEY_REDUCE_TRANSPARENCY,
            KEY_UI_SOUND_EFFECTS_ENABLED,
            KEY_AUTO_UPDATE_CHECK,
            KEY_TAG_DICT_AUTO_SYNC,
            KEY_BLOCK_TAGS_GLOBALLY,
        )

        private val json = RavenTypeConverters.json
        private val stringListSerializer = ListSerializer(String.serializer())

        private fun decodeStringSet(raw: String?): Set<String> {
            if (raw.isNullOrBlank()) return emptySet()
            return runCatching {
                json.decodeFromString(stringListSerializer, raw)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toSet()
            }.getOrDefault(emptySet())
        }

        private fun encodeStringSet(values: Set<String>): String =
            json.encodeToString(stringListSerializer, values.toList().sorted())
        private val siteOpenCountsSerializer = MapSerializer(String.serializer(), Int.serializer())
        private val sectionOpenCountsSerializer = MapSerializer(
            String.serializer(),
            MapSerializer(String.serializer(), Int.serializer()),
        )

        private fun incrementSiteOpenIfAllowed(prefs: MutablePreferences, siteId: SiteId) {
            if (prefs[Keys.INCOGNITO_MODE] == true) return
            val current = decodeSiteOpenCounts(prefs[Keys.SITE_OPEN_COUNTS]).toMutableMap()
            val prev = current[siteId] ?: 0
            current[siteId] = (prev + 1).coerceAtLeast(1)
            prefs[Keys.SITE_OPEN_COUNTS] = encodeSiteOpenCounts(current)
        }

        private fun incrementSectionOpenIfAllowed(
            prefs: MutablePreferences,
            siteId: SiteId,
            sectionId: String,
        ) {
            if (prefs[Keys.INCOGNITO_MODE] == true) return
            val outer = decodeSectionOpenCounts(prefs[Keys.SECTION_OPEN_COUNTS])
                .mapValues { (_, inner) -> inner.toMutableMap() }
                .toMutableMap()
            val inner = outer.getOrPut(siteId) { LinkedHashMap() }
            val prev = inner[sectionId] ?: 0
            inner[sectionId] = (prev + 1).coerceAtLeast(1)
            prefs[Keys.SECTION_OPEN_COUNTS] = encodeSectionOpenCounts(outer)
        }

        internal fun encodeSiteOpenCounts(counts: Map<SiteId, Int>): String =
            if (counts.isEmpty()) "{}"
            else json.encodeToString(
                siteOpenCountsSerializer,
                counts.entries
                    .filter { (_, v) -> v > 0 }
                    .associate { (k, v) -> k.value to v },
            )

        internal fun decodeSiteOpenCounts(raw: String?): Map<SiteId, Int> {
            if (raw.isNullOrBlank() || raw == "{}") return emptyMap()
            return runCatching {
                json.decodeFromString(siteOpenCountsSerializer, raw)
                    .entries
                    .mapNotNull { (k, v) ->
                        val clean = k.trim()
                        if (clean.isNotEmpty() && v > 0) SiteId.of(clean) to v else null
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
        }

        internal fun encodeSectionOpenCounts(counts: Map<SiteId, Map<String, Int>>): String =
            if (counts.isEmpty()) "{}"
            else json.encodeToString(
                sectionOpenCountsSerializer,
                counts.entries
                    .mapNotNull { (siteId, inner) ->
                        val filtered = inner.entries
                            .mapNotNull { (sec, cnt) ->
                                val cleanSec = sec.trim()
                                if (cleanSec.isNotEmpty() && cnt > 0) cleanSec to cnt else null
                            }
                            .toMap()
                        if (filtered.isNotEmpty()) siteId.value to filtered else null
                    }
                    .toMap(),
            )

        internal fun decodeSectionOpenCounts(raw: String?): Map<SiteId, Map<String, Int>> {
            if (raw.isNullOrBlank() || raw == "{}") return emptyMap()
            return runCatching {
                json.decodeFromString(sectionOpenCountsSerializer, raw)
                    .entries
                    .mapNotNull { (rawSite, inner) ->
                        val cleanSite = rawSite.trim()
                        if (cleanSite.isEmpty()) return@mapNotNull null
                        val cleanedInner = inner.entries
                            .mapNotNull { (sec, cnt) ->
                                val cleanSec = sec.trim()
                                if (cleanSec.isNotEmpty() && cnt > 0) cleanSec to cnt else null
                            }
                            .toMap()
                        if (cleanedInner.isNotEmpty()) SiteId.of(cleanSite) to cleanedInner else null
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
        }

        fun createDataStore(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            )

        private fun decodeDownloadView(raw: String?): DownloadViewPreferences {
            if (raw.isNullOrBlank()) return DownloadViewPreferences()
            return runCatching {
                json.decodeFromString(DownloadViewPreferences.serializer(), raw)
            }.getOrDefault(DownloadViewPreferences())
        }

        internal fun encodeSiteIdList(siteIds: List<SiteId>): String =
            if (siteIds.isEmpty()) "[]"
            else json.encodeToString(stringListSerializer, siteIds.map { it.value })

        internal fun decodeSiteIdList(raw: String?): List<SiteId> {
            if (raw.isNullOrBlank() || raw == "[]") return emptyList()
            val trimmed = raw.trim()
            if (trimmed.startsWith('[')) {
                return runCatching {
                    json.decodeFromString(stringListSerializer, trimmed)
                        .mapNotNull { token -> token.trim().takeIf(String::isNotEmpty)?.let(SiteId::of) }
                }.getOrDefault(emptyList())
            }
            // Fallback for comma-separated legacy values (e.g. R34V `cross_search_sites`)
            return trimmed.split(',')
                .mapNotNull { token -> token.trim().takeIf(String::isNotEmpty)?.let(SiteId::of) }
        }

        private fun parseStorageLocationOrNull(raw: String?): StorageLocation? {
            if (raw.isNullOrBlank()) return null
            return StorageLocation.entries.firstOrNull { it.name == raw.trim() }
        }
    }
}
