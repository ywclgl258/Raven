package com.raven.app.core.database.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.raven.app.core.database.converter.RavenTypeConverters
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.model.ExploreFilterState
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Preferences DataStore (`raven_settings.preferences_pb`) exposing reactive [Flow] of [AppSettings]
 * and suspend setters for every setting defined in `ARCHITECTURE.md` §6.2.
 */
class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map(::mapPreferences)

    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun setActiveSiteId(siteId: SiteId) {
        dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_SITE_ID] = siteId.value
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

    suspend fun setAutoUpdateCheck(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_UPDATE_CHECK] = enabled }
    }

    suspend fun setSiteSwitchTutorialSeen(seen: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SITE_SWITCH_TUTORIAL_SEEN] = seen }
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

    /**
     * Exports ONLY safe portable user settings for encrypted backups.
     * Never exports device-local storage roots, tutorial flags, credentials, or keys.
     */
    suspend fun exportPortableSettings(): Map<String, String> {
        val current = currentSettings()
        return buildMap {
            put(KEY_ACTIVE_SITE_ID, current.activeSiteId.value)
            put(KEY_PINNED_SITE_IDS, encodeSiteIdList(current.pinnedSiteIds))
            put(KEY_CROSS_SEARCH_SITE_IDS, encodeSiteIdList(current.crossSearchSiteIds))
            put(KEY_TRANSLATE_TAGS, current.translateTags.toString())
            put(KEY_AUTO_FULLSCREEN, current.autoFullscreen.toString())
            put(KEY_PREFERRED_QUALITY, current.preferredQuality)
            put(KEY_DEFAULT_PLAYBACK_SPEED, current.defaultPlaybackSpeed.toString())
            put(KEY_STREAM_CACHE_ENABLED, current.streamCacheEnabled.toString())
            put(KEY_SEEK_CACHE_ENABLED, current.seekCacheEnabled.toString())
            put(KEY_DOWNLOAD_UNMETERED_ONLY, current.downloadUnmeteredOnly.toString())
            put(KEY_REDUCE_TRANSPARENCY, current.reduceTransparency.toString())
            put(KEY_AUTO_UPDATE_CHECK, current.autoUpdateCheck.toString())
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
        settingsMap[KEY_PINNED_SITE_IDS]?.let { raw ->
            prefs[Keys.PINNED_SITE_IDS] = encodeSiteIdList(decodeSiteIdList(raw).distinct())
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
        settingsMap[KEY_REDUCE_TRANSPARENCY]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.REDUCE_TRANSPARENCY] = it
        }
        settingsMap[KEY_AUTO_UPDATE_CHECK]?.toBooleanStrictOrNull()?.let {
            prefs[Keys.AUTO_UPDATE_CHECK] = it
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
            ?: SiteId.R34V

        val storage = parseStorageLocationOrNull(prefs[Keys.STORAGE_LOCATION])
            ?: StorageLocation.PRIVATE
        val pendingStorage = parseStorageLocationOrNull(prefs[Keys.PENDING_STORAGE_LOCATION])

        val speed = (prefs[Keys.DEFAULT_PLAYBACK_SPEED] ?: AppSettings.DEFAULT_PLAYBACK_SPEED)
            .coerceIn(AppSettings.MIN_PLAYBACK_SPEED, AppSettings.MAX_PLAYBACK_SPEED)

        return AppSettings(
            activeSiteId = activeSite,
            pinnedSiteIds = decodeSiteIdList(prefs[Keys.PINNED_SITE_IDS]).distinct(),
            crossSearchSiteIds = decodeSiteIdList(prefs[Keys.CROSS_SEARCH_SITE_IDS]).distinct(),
            siteLanguages = languages,
            siteExploreFilters = exploreFilters,
            translateTags = prefs[Keys.TRANSLATE_TAGS] ?: true,
            autoFullscreen = prefs[Keys.AUTO_FULLSCREEN] ?: true,
            preferredQuality = prefs[Keys.PREFERRED_QUALITY]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: AppSettings.DEFAULT_PREFERRED_QUALITY,
            defaultPlaybackSpeed = speed,
            streamCacheEnabled = prefs[Keys.STREAM_CACHE_ENABLED] ?: false,
            seekCacheEnabled = prefs[Keys.SEEK_CACHE_ENABLED] ?: true,
            downloadUnmeteredOnly = prefs[Keys.DOWNLOAD_UNMETERED_ONLY] ?: false,
            storageLocation = storage,
            pendingStorageLocation = pendingStorage,
            reduceTransparency = prefs[Keys.REDUCE_TRANSPARENCY] ?: false,
            autoUpdateCheck = prefs[Keys.AUTO_UPDATE_CHECK] ?: true,
            siteSwitchTutorialSeen = prefs[Keys.SITE_SWITCH_TUTORIAL_SEEN] ?: false,
        )
    }

    object Keys {
        val ACTIVE_SITE_ID = stringPreferencesKey(KEY_ACTIVE_SITE_ID)
        val PINNED_SITE_IDS = stringPreferencesKey(KEY_PINNED_SITE_IDS)
        val CROSS_SEARCH_SITE_IDS = stringPreferencesKey(KEY_CROSS_SEARCH_SITE_IDS)
        val TRANSLATE_TAGS = booleanPreferencesKey(KEY_TRANSLATE_TAGS)
        val AUTO_FULLSCREEN = booleanPreferencesKey(KEY_AUTO_FULLSCREEN)
        val PREFERRED_QUALITY = stringPreferencesKey(KEY_PREFERRED_QUALITY)
        val DEFAULT_PLAYBACK_SPEED = floatPreferencesKey(KEY_DEFAULT_PLAYBACK_SPEED)
        val STREAM_CACHE_ENABLED = booleanPreferencesKey(KEY_STREAM_CACHE_ENABLED)
        val SEEK_CACHE_ENABLED = booleanPreferencesKey(KEY_SEEK_CACHE_ENABLED)
        val DOWNLOAD_UNMETERED_ONLY = booleanPreferencesKey(KEY_DOWNLOAD_UNMETERED_ONLY)
        val STORAGE_LOCATION = stringPreferencesKey(KEY_STORAGE_LOCATION)
        val PENDING_STORAGE_LOCATION = stringPreferencesKey(KEY_PENDING_STORAGE_LOCATION)
        val REDUCE_TRANSPARENCY = booleanPreferencesKey(KEY_REDUCE_TRANSPARENCY)
        val AUTO_UPDATE_CHECK = booleanPreferencesKey(KEY_AUTO_UPDATE_CHECK)
        val SITE_SWITCH_TUTORIAL_SEEN = booleanPreferencesKey(KEY_SITE_SWITCH_TUTORIAL_SEEN)

        fun siteLanguage(siteId: SiteId): Preferences.Key<String> =
            stringPreferencesKey("$SITE_LANGUAGE_PREFIX${siteId.value}")

        fun siteExploreFilter(siteId: SiteId): Preferences.Key<String> =
            stringPreferencesKey("$SITE_EXPLORE_FILTER_PREFIX${siteId.value}")
    }

    companion object {
        /** Produces `raven_settings.preferences_pb` via [Context.preferencesDataStoreFile]. */
        const val FILE_NAME: String = "raven_settings"

        const val KEY_ACTIVE_SITE_ID: String = "active_site_id"
        const val KEY_PINNED_SITE_IDS: String = "pinned_site_ids"
        const val KEY_CROSS_SEARCH_SITE_IDS: String = "cross_search_site_ids"
        const val SITE_LANGUAGE_PREFIX: String = "site_language_"
        const val SITE_EXPLORE_FILTER_PREFIX: String = "site_explore_filter_"
        const val KEY_TRANSLATE_TAGS: String = "translate_tags"
        const val KEY_AUTO_FULLSCREEN: String = "auto_fullscreen"
        const val KEY_PREFERRED_QUALITY: String = "preferred_quality"
        const val KEY_DEFAULT_PLAYBACK_SPEED: String = "default_playback_speed"
        const val KEY_STREAM_CACHE_ENABLED: String = "stream_cache_enabled"
        const val KEY_SEEK_CACHE_ENABLED: String = "seek_cache_enabled"
        const val KEY_DOWNLOAD_UNMETERED_ONLY: String = "download_unmetered_only"
        const val KEY_STORAGE_LOCATION: String = "storage_location"
        const val KEY_PENDING_STORAGE_LOCATION: String = "pending_storage_location"
        const val KEY_REDUCE_TRANSPARENCY: String = "reduce_transparency"
        const val KEY_AUTO_UPDATE_CHECK: String = "auto_update_check"
        const val KEY_SITE_SWITCH_TUTORIAL_SEEN: String = "site_switch_tutorial_seen"

        /** Keys safe to include in encrypted cross-device backups (never device paths/credentials). */
        val PORTABLE_BACKUP_KEYS: Set<String> = setOf(
            KEY_ACTIVE_SITE_ID,
            KEY_PINNED_SITE_IDS,
            KEY_CROSS_SEARCH_SITE_IDS,
            KEY_TRANSLATE_TAGS,
            KEY_AUTO_FULLSCREEN,
            KEY_PREFERRED_QUALITY,
            KEY_DEFAULT_PLAYBACK_SPEED,
            KEY_STREAM_CACHE_ENABLED,
            KEY_SEEK_CACHE_ENABLED,
            KEY_DOWNLOAD_UNMETERED_ONLY,
            KEY_REDUCE_TRANSPARENCY,
            KEY_AUTO_UPDATE_CHECK,
        )

        private val json = RavenTypeConverters.json
        private val stringListSerializer = ListSerializer(String.serializer())

        fun createDataStore(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            )

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
