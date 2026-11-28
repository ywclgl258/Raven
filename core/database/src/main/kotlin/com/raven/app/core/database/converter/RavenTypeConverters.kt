package com.raven.app.core.database.converter

import androidx.room.TypeConverter
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderStartPosition
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagTranslationSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room [TypeConverter] registry backed by `kotlinx.serialization` JSON for structured columns
 * and explicit enum names with safe fallbacks for forward schema compatibility.
 */
class RavenTypeConverters {

    // ---- Structured JSON collections & DTOs ----

    @TypeConverter
    fun fromStringList(value: List<String>): String =
        if (value.isEmpty()) "[]" else json.encodeToString(stringListSerializer, value)

    @TypeConverter
    fun toStringList(raw: String): List<String> =
        if (raw.isBlank() || raw == "[]") emptyList()
        else runCatching { json.decodeFromString(stringListSerializer, raw) }.getOrDefault(emptyList())

    @TypeConverter
    fun fromIntList(value: List<Int>): String =
        if (value.isEmpty()) "[]" else json.encodeToString(intListSerializer, value)

    @TypeConverter
    fun toIntList(raw: String): List<Int> =
        if (raw.isBlank() || raw == "[]") emptyList()
        else runCatching { json.decodeFromString(intListSerializer, raw) }.getOrDefault(emptyList())

    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String =
        if (value.isEmpty()) "{}" else json.encodeToString(stringMapSerializer, value)

    @TypeConverter
    fun toStringMap(raw: String): Map<String, String> =
        if (raw.isBlank() || raw == "{}") emptyMap()
        else runCatching { json.decodeFromString(stringMapSerializer, raw) }.getOrDefault(emptyMap())

    @TypeConverter
    fun fromAdvancedSearchFilters(value: AdvancedSearchFilters): String =
        json.encodeToString(AdvancedSearchFilters.serializer(), value)

    @TypeConverter
    fun toAdvancedSearchFilters(raw: String): AdvancedSearchFilters =
        if (raw.isBlank()) AdvancedSearchFilters()
        else runCatching { json.decodeFromString(AdvancedSearchFilters.serializer(), raw) }
            .getOrDefault(AdvancedSearchFilters())

    @TypeConverter
    fun fromMediaItem(value: MediaItem): String =
        json.encodeToString(MediaItem.serializer(), value)

    @TypeConverter
    fun toMediaItem(raw: String): MediaItem =
        runCatching { json.decodeFromString(MediaItem.serializer(), raw) }
            .getOrElse { CORRUPT_MEDIA_ITEM }

    // ---- Domain enums ----

    @TypeConverter
    fun fromMediaKind(value: MediaKind): String = value.name

    @TypeConverter
    fun toMediaKind(raw: String): MediaKind =
        MediaKind.entries.firstOrNull { it.name == raw } ?: MediaKind.VIDEO

    @TypeConverter
    fun fromFavoriteKind(value: FavoriteKind): String = value.name

    @TypeConverter
    fun toFavoriteKind(raw: String): FavoriteKind =
        FavoriteKind.entries.firstOrNull { it.name == raw } ?: FavoriteKind.VIDEO

    @TypeConverter
    fun fromSmartShelfRule(value: SmartShelfRule): String = value.name

    @TypeConverter
    fun toSmartShelfRule(raw: String): SmartShelfRule =
        SmartShelfRule.entries.firstOrNull { it.name == raw } ?: SmartShelfRule.UNFINISHED

    @TypeConverter
    fun fromSearchScope(value: SearchScope): String = value.name

    @TypeConverter
    fun toSearchScope(raw: String): SearchScope =
        SearchScope.entries.firstOrNull { it.name == raw } ?: SearchScope.ALL

    @TypeConverter
    fun fromFeedSort(value: FeedSort): String = value.name

    @TypeConverter
    fun toFeedSort(raw: String): FeedSort =
        FeedSort.entries.firstOrNull { it.name == raw } ?: FeedSort.DEFAULT

    @TypeConverter
    fun fromComicLanguage(value: ComicLanguage): String = value.name

    @TypeConverter
    fun toComicLanguage(raw: String): ComicLanguage =
        ComicLanguage.entries.firstOrNull { it.name == raw } ?: ComicLanguage.ALL

    @TypeConverter
    fun fromInboxSourceKind(value: InboxSourceKind): String = value.name

    @TypeConverter
    fun toInboxSourceKind(raw: String): InboxSourceKind =
        InboxSourceKind.entries.firstOrNull { it.name == raw } ?: InboxSourceKind.TAG_SUBSCRIPTION

    @TypeConverter
    fun fromReaderMode(value: ReaderMode): String = value.name

    @TypeConverter
    fun toReaderMode(raw: String): ReaderMode =
        ReaderMode.entries.firstOrNull { it.name == raw } ?: ReaderMode.STRIP

    @TypeConverter
    fun fromReaderFit(value: ReaderFit): String = value.name

    @TypeConverter
    fun toReaderFit(raw: String): ReaderFit =
        ReaderFit.entries.firstOrNull { it.name == raw } ?: ReaderFit.WIDTH

    @TypeConverter
    fun fromReaderStartPosition(value: ReaderStartPosition): String = value.name

    @TypeConverter
    fun toReaderStartPosition(raw: String): ReaderStartPosition =
        ReaderStartPosition.entries.firstOrNull { it.name == raw } ?: ReaderStartPosition.RESUME

    @TypeConverter
    fun fromDownloadKind(value: DownloadKind): String = value.name

    @TypeConverter
    fun toDownloadKind(raw: String): DownloadKind =
        DownloadKind.entries.firstOrNull { it.name == raw } ?: DownloadKind.VIDEO

    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toDownloadStatus(raw: String): DownloadStatus =
        DownloadStatus.entries.firstOrNull { it.name == raw } ?: DownloadStatus.QUEUED

    @TypeConverter
    fun fromDownloadErrorCategory(value: DownloadErrorCategory): String = value.name

    @TypeConverter
    fun toDownloadErrorCategory(raw: String): DownloadErrorCategory =
        DownloadErrorCategory.entries.firstOrNull { it.name == raw } ?: DownloadErrorCategory.UNKNOWN

    @TypeConverter
    fun fromStorageLocation(value: StorageLocation): String = value.name

    @TypeConverter
    fun toStorageLocation(raw: String): StorageLocation =
        StorageLocation.entries.firstOrNull { it.name == raw } ?: StorageLocation.PRIVATE

    @TypeConverter
    fun fromTagTranslationSource(value: TagTranslationSource): String = value.name

    @TypeConverter
    fun toTagTranslationSource(raw: String): TagTranslationSource =
        TagTranslationSource.entries.firstOrNull { it.name == raw } ?: TagTranslationSource.USER_CUSTOM

    companion object {
        internal val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

        private val stringListSerializer = ListSerializer(String.serializer())
        private val intListSerializer = ListSerializer(Int.serializer())
        private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())
        private val tagRefListSerializer = ListSerializer(TagRef.serializer())

        internal fun encodeTagRefList(tags: List<TagRef>): String =
            if (tags.isEmpty()) "[]" else json.encodeToString(tagRefListSerializer, tags)

        internal fun decodeTagRefList(raw: String): List<TagRef> =
            if (raw.isBlank() || raw == "[]") emptyList()
            else runCatching { json.decodeFromString(tagRefListSerializer, raw) }.getOrDefault(emptyList())

        private val CORRUPT_MEDIA_ITEM: MediaItem = MediaItem(
            siteId = com.raven.app.core.model.SiteId.LOCAL,
            id = "__corrupt__",
            url = "",
            kind = MediaKind.COMIC,
            title = "损坏条目",
        )
    }
}
