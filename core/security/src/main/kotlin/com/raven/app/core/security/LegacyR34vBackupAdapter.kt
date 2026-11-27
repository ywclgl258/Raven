package com.raven.app.core.security

import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.PlaybackPreferences
import com.raven.app.core.model.RavenBackupPayload
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
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.sitespi.SiteRegistry
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Lossless decoder & mapper for legacy R34V (`R34VBK01`) JSON backups into Raven's
 * [RavenBackupPayload], preserving `watch-later`, site-scoped collections, retired `kbj`/`netflav`,
 * and `r34local://<sha256>` local URIs.
 */
internal object LegacyR34vBackupAdapter {

    private val legacyJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = false
    }

    private inline fun <reified T> legacyRecordId(kind: String, record: T, occurrences: MutableMap<String, Int>): String {
        val encoded = legacyJson.encodeToString(record)
        val ordinal = occurrences.getOrDefault(encoded, 0)
        occurrences[encoded] = ordinal + 1
        return UUID.nameUUIDFromBytes("R34V:$kind:$ordinal:$encoded".toByteArray(Charsets.UTF_8)).toString()
    }

    private val legacyBooleanKeys = setOf(
        "translate_tags",
        "auto_fullscreen",
        "auto_download",
        "auto_update",
        "download_unmetered",
        "reduce_transparency",
        "stream_cache",
        "seek_cache",
    )

    private val legacyStringKeys = setOf(
        "quality",
        "native_speed",
        "cross_search_sites",
    )

    private val legacyKeyMapping = mapOf(
        "auto_update" to "auto_update_check",
        "download_unmetered" to "download_unmetered_only",
        "stream_cache" to "stream_cache_enabled",
        "seek_cache" to "seek_cache_enabled",
        "quality" to "preferred_quality",
        "native_speed" to "default_playback_speed",
        "cross_search_sites" to "cross_search_site_ids",
    )

    fun decodeToRavenPayload(
        rawJson: String,
        registry: SiteRegistry,
    ): RavenBackupPayload {
        val legacy = try {
            legacyJson.decodeFromString<LegacyLibraryBackup>(rawJson)
        } catch (e: Exception) {
            throw BackupValidationException("旧版 R34V 备份 JSON 解析失败", e)
        }

        if (legacy.schema != 1) {
            throw BackupValidationException("不支持的旧版 R34V 备份版本：${legacy.schema}")
        }

        // Reject duplicate site buckets or cross-site contamination before flattening
        val seenSiteBuckets = HashSet<String>()
        val watchProgress = mutableListOf<WatchProgress>()
        val readingProgress = mutableListOf<ReadingProgress>()
        val favorites = mutableListOf<FavoriteItem>()
        val tagSubscriptions = mutableListOf<TagSubscription>()
        val blockedTerms = mutableListOf<BlockedTerm>()
        val searchHistory = mutableListOf<SearchHistoryEntry>()
        val siteLanguages = LinkedHashMap<SiteId, ComicLanguage>()
        val settingsOut = LinkedHashMap<String, String>()

        for (bucket in legacy.sites) {
            val rawSite = bucket.site.trim().lowercase(Locale.ROOT)
            if (rawSite.isEmpty() || !seenSiteBuckets.add(rawSite)) {
                throw BackupValidationException("旧版备份包含重复或空的站点段：'${bucket.site}'")
            }
            val siteId = SiteId.of(rawSite)

            // Favorites
            for (fav in bucket.favorites) {
                val itemSite = fav.site?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: rawSite
                if (itemSite != rawSite) {
                    throw BackupValidationException("旧版收藏记录站点不匹配：$itemSite != $rawSite")
                }
                if (fav.target.isBlank()) {
                    throw BackupValidationException("旧版收藏目标 ID 不能为空")
                }
                val kind = mapFavoriteKind(fav.kind, siteId, registry)
                val resolvedUrl = resolveLegacyUrl(siteId, fav.path, fav.target, registry)
                val extra = buildMap {
                    if (fav.subtitle.isNotBlank()) put("subtitle", fav.subtitle)
                    put("legacyKind", fav.kind)
                }
                favorites += FavoriteItem(
                    siteId = siteId,
                    targetId = fav.target,
                    kind = kind,
                    title = fav.title,
                    url = resolvedUrl,
                    coverUrl = fav.thumb,
                    addedAt = fav.at,
                    extra = extra,
                )
            }

            // Watches (R34V stores pos/dur in SECONDS; Raven stores positionMs/durationMs in MILLISECONDS)
            for (watch in bucket.watches) {
                val itemSite = watch.site?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: rawSite
                if (itemSite != rawSite) {
                    throw BackupValidationException("旧版观看记录站点不匹配：$itemSite != $rawSite")
                }
                if (watch.id.isBlank() || watch.position < 0L || watch.duration < 0L) {
                    throw BackupValidationException("旧版观看记录字段无效")
                }
                val shortClip = watch.duration in 1L..40L
                val finished = watch.ended == true || when {
                    watch.duration <= 0L || watch.position <= 0L -> false
                    shortClip -> watch.ended == null && watch.position >= watch.duration
                    else -> watch.position >= watch.duration - 20L
                }
                watchProgress += WatchProgress(
                    siteId = siteId,
                    mediaId = watch.id,
                    url = resolveLegacyUrl(siteId, watch.path, watch.id, registry),
                    title = watch.title,
                    coverUrl = watch.thumb,
                    positionMs = watch.position * 1000L,
                    durationMs = watch.duration * 1000L,
                    updatedAt = watch.at,
                    completed = finished,
                    quality = watch.quality.takeIf { it.isNotBlank() },
                )
            }

            // Readings
            for (reading in bucket.readings) {
                val entrySite = reading.entry.site?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: rawSite
                if (entrySite != rawSite) {
                    throw BackupValidationException("旧版阅读记录站点不匹配：$entrySite != $rawSite")
                }
                readingProgress += ReadingProgress(
                    siteId = siteId,
                    mediaId = reading.entry.id,
                    url = resolveLegacyUrl(siteId, reading.entry.url, reading.entry.id, registry),
                    title = reading.entry.title,
                    coverUrl = reading.entry.thumb,
                    kind = inferMediaKind(siteId, reading.entry.mediaKind, registry),
                    currentPage = reading.page,
                    totalPages = reading.pages,
                    scrollOffsetPx = reading.offset,
                    updatedAt = reading.at,
                    completed = reading.pages > 0 && reading.page >= reading.pages,
                )
            }

            // Subscriptions
            bucket.subscriptions.forEachIndexed { index, sub ->
                val subSite = sub.site?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: rawSite
                if (subSite != rawSite) {
                    throw BackupValidationException("旧版订阅记录站点不匹配：$subSite != $rawSite")
                }
                tagSubscriptions += TagSubscription(
                    siteId = siteId,
                    slug = sub.slug,
                    label = sub.label.ifBlank { sub.slug },
                    url = resolveLegacyUrl(siteId, sub.url, "", registry),
                    scope = SearchScope.TAG,
                    sortOrder = index,
                    createdAt = sub.at,
                )
            }

            // Blocked terms
            for (term in bucket.blocked) {
                blockedTerms += BlockedTerm(siteId = siteId, term = term)
            }

            // Search history
            bucket.searches.forEachIndexed { idx, query ->
                searchHistory += SearchHistoryEntry(
                    siteId = siteId,
                    query = query,
                    scope = SearchScope.ALL,
                    searchedAt = (legacy.at - idx).coerceAtLeast(0L),
                )
            }

            // Language
            mapComicLanguage(bucket.language)?.let { lang ->
                if (lang != ComicLanguage.ALL) {
                    siteLanguages[siteId] = lang
                }
            } ?: throw BackupValidationException("旧版语言设置无效：${bucket.language}")

            // Preserve non-default subscription order losslessly
            if (bucket.order !in setOf("SUBSCRIBED", "INTERLEAVED", "TITLE")) {
                throw BackupValidationException("旧版订阅排序无效：${bucket.order}")
            }
            if (bucket.order != "SUBSCRIBED") {
                settingsOut["legacy_subscription_order_${siteId.value}"] = bucket.order
            }
        }

        val personal = legacy.personal

        // Shelves & ShelfItems (preserve watch-later shelf ID and custom shelves losslessly)
        val rawShelves = personal.shelves
        if (rawShelves.isEmpty() || rawShelves.count { it.id == Shelf.WATCH_LATER_ID } != 1) {
            throw BackupValidationException("旧版备份必须且仅包含一个 watch-later 片单")
        }
        val shelves = mutableListOf<Shelf>()
        val shelfItems = mutableListOf<ShelfItem>()
        for (shelf in rawShelves) {
            val normalizedCover = shelf.coverKey
                .takeIf { it.isNotBlank() }
                ?.let(::normalizeLegacyEntryKey)
            shelves += Shelf(
                id = shelf.id,
                name = shelf.name,
                note = shelf.note,
                isPinned = shelf.pinned,
                isSystem = shelf.id == Shelf.WATCH_LATER_ID,
                coverCompositeKey = normalizedCover,
                createdAt = shelf.modifiedAt,
                modifiedAt = shelf.modifiedAt,
            )
            shelf.entries.forEachIndexed { index, entry ->
                val v = entry.video
                val sid = SiteId.of(v.site?.takeIf { it.isNotBlank() } ?: "r34v")
                val addedAt = entry.addedAt.takeIf { it > 0L } ?: entry.at
                shelfItems += ShelfItem(
                    shelfId = shelf.id,
                    siteId = sid,
                    mediaId = v.id,
                    url = resolveLegacyUrl(sid, v.url, v.id, registry),
                    kind = inferMediaKind(sid, v.mediaKind, registry),
                    title = v.title,
                    coverUrl = v.thumb,
                    position = index,
                    addedAt = addedAt,
                    completed = entry.completed,
                    durationSeconds = parseDurationSecondsOrNull(v.duration),
                )
            }
        }

        // Smart shelves
        val smartShelfOccurrences = mutableMapOf<String, Int>()
        val smartShelves = personal.smartShelves.map { s ->
            val rule = when (s.rule) {
                "UNFINISHED" -> SmartShelfRule.UNFINISHED
                "OFFLINE_UNSEEN" -> SmartShelfRule.OFFLINE_UNSEEN
                "RECENT" -> SmartShelfRule.RECENT
                else -> throw BackupValidationException("旧版智能片单规则无效：${s.rule}")
            }
            SmartShelf(
                id = s.id.ifBlank { legacyRecordId("shelf", s, smartShelfOccurrences) },
                name = s.name,
                rule = rule,
                mediaKindFilter = mapCategoryOrNull(s.category),
                siteIdFilter = s.source.takeIf { it.isNotBlank() }?.let(SiteId::of),
                isPinned = false,
                createdAt = legacy.at,
            )
        }

        // Page bookmarks
        val pageBookmarks = personal.bookmarks.map { b ->
            val v = b.video
            val sid = SiteId.of(v.site?.takeIf { it.isNotBlank() } ?: "r34v")
            PageBookmark(
                id = "${sid.value}:${v.id}:${b.page}",
                siteId = sid,
                mediaId = v.id,
                url = resolveLegacyUrl(sid, v.url, v.id, registry),
                title = v.title,
                coverUrl = v.thumb,
                page = b.page,
                scrollOffsetPx = b.offset,
                createdAt = b.at,
            )
        }

        // Time bookmarks
        val timeBookmarks = personal.timeBookmarks.map { t ->
            val v = t.video
            val sid = SiteId.of(v.site?.takeIf { it.isNotBlank() } ?: "r34v")
            TimeBookmark(
                id = t.id,
                siteId = sid,
                mediaId = v.id,
                url = resolveLegacyUrl(sid, v.url, v.id, registry),
                title = v.title,
                coverUrl = v.thumb,
                positionMs = t.positionMs,
                label = t.note,
                groupName = t.group,
                createdAt = t.at,
            )
        }

        // Image favorites
        val imageFavorites = personal.imageFavorites.map { f ->
            val v = f.video
            val sid = SiteId.of(v.site?.takeIf { it.isNotBlank() } ?: "r34v")
            ImageFavorite(
                id = "${sid.value}:${v.id}:${f.page}",
                siteId = sid,
                mediaId = v.id,
                mediaTitle = v.title,
                pageUrl = if (sid.isLocal) f.pageUrl else resolveLegacyUrl(sid, f.pageUrl, "", registry),
                imageUrl = f.imageUrl,
                pageIndex = f.page,
                addedAt = f.at,
            )
        }

        // Saved searches
        val searchOccurrences = mutableMapOf<String, Int>()
        val savedSearches = personal.savedSearches.map { s ->
            val resolvedId = s.id?.takeIf { it.isNotBlank() }
                ?: legacyRecordId("search", s.copy(id = null, languages = s.languages.toSortedMap()), searchOccurrences)
            val sources = s.sources.map { SiteId.of(it) }
            val legacySiteLanguages = s.languages.map { (siteKey, raw) ->
                val mapped = mapComicLanguage(raw)
                    ?: throw BackupValidationException("保存搜索语言无效：$raw")
                SiteId.of(siteKey) to mapped
            }.toMap()
            SavedSearch(
                id = resolvedId,
                name = s.name,
                query = s.query,
                scope = mapLegacyScope(s.singleSiteScope),
                siteIds = sources,
                mediaKind = mapCategoryOrNull(s.category),
                filters = mapLegacyFilter(s.filter, s.includeTags, s.excludeTags)
                    .copy(siteLanguages = legacySiteLanguages),
                createdAt = legacy.at,
            )
        }

        // Update inbox (from both subscription inboxes and saved search update boxes)
        val updateInbox = mutableListOf<UpdateInboxItem>()
        for ((siteKey, inbox) in personal.inboxes) {
            val sid = SiteId.of(siteKey)
            inbox.entries.forEach { entry ->
                val media = toMediaItem(entry.video, sid, registry)
                updateInbox += UpdateInboxItem(
                    id = "sub:${sid.value}:${media.id}",
                    sourceKind = InboxSourceKind.TAG_SUBSCRIPTION,
                    sourceId = sid.value,
                    sourceLabel = sid.value,
                    item = media,
                    discoveredAt = entry.at,
                    isRead = entry.read,
                )
            }
        }
        val savedSearchById = savedSearches.associateBy { it.id }
        for ((searchId, box) in personal.searchUpdates) {
            val label = savedSearchById[searchId]?.name ?: searchId
            box.entries.forEach { entry ->
                val sid = SiteId.of(entry.video.site?.takeIf { it.isNotBlank() } ?: "r34v")
                val media = toMediaItem(entry.video, sid, registry)
                updateInbox += UpdateInboxItem(
                    id = "search:$searchId:${media.compositeKey}",
                    sourceKind = InboxSourceKind.SAVED_SEARCH,
                    sourceId = searchId,
                    sourceLabel = label,
                    item = media,
                    discoveredAt = entry.at,
                    isRead = entry.read,
                )
            }
        }

        // Playback preferences
        val playbackPreferences = personal.playbackPreferences.map { (siteKey, prefs) ->
            PlaybackPreferences(
                siteId = SiteId.of(siteKey),
                defaultSpeed = prefs.defaultRate,
                longPressSpeed = prefs.holdRate,
                seekStepSeconds = (prefs.seekStepMs / 1000L).toInt(),
                gestureSensitivitySeconds = (prefs.gestureRangeMs / 1000L).toInt(),
            )
        }

        // Reader preferences (merge site-level readerModes + readingBehaviors, plus per-book readerPreferences)
        val readerPreferences = mutableListOf<ReaderPreferences>()
        val siteReaderKeys = (personal.readerModes.keys + personal.readingBehaviors.keys).distinct()
        for (siteKey in siteReaderKeys) {
            val sid = SiteId.of(siteKey)
            val modeRaw = personal.readerModes[siteKey]
            val mode = if (modeRaw.isNullOrBlank()) ReaderMode.STRIP else mapReaderMode(modeRaw)
            val behavior = personal.readingBehaviors[siteKey]
            readerPreferences += ReaderPreferences(
                siteId = sid,
                mediaId = null,
                mode = mode,
                preferOriginalImages = behavior?.originalImages ?: false,
                volumeKeyPaging = behavior?.volumeKeys ?: false,
                reverseVolumeKeys = behavior?.reverseVolume ?: false,
                keepScreenOn = behavior?.keepAwake ?: true,
                brightnessPercent = behavior?.brightnessPercent ?: -1,
            )
        }
        for ((entryKey, prefs) in personal.readerPreferences) {
            if (!entryKey.contains(':')) {
                throw BackupValidationException("旧版单本阅读偏好键格式无效：$entryKey")
            }
            val sitePart = entryKey.substringBefore(':')
            val mediaPart = entryKey.substringAfter(':').removePrefix("gallery:")
            val mode = if (prefs.mode.isBlank()) ReaderMode.STRIP else mapReaderMode(prefs.mode)
            readerPreferences += ReaderPreferences(
                siteId = SiteId.of(sitePart),
                mediaId = mediaPart,
                mode = mode,
                fit = mapReaderFit(prefs.fit),
                startPosition = mapReaderStart(prefs.start),
                cropPercent = prefs.cropPercent,
                combineCover = prefs.doubleCover,
            )
        }

        // Settings validation & key mapping
        for ((key, value) in legacy.settings) {
            if (key !in legacyBooleanKeys && key !in legacyStringKeys) {
                throw BackupValidationException("旧版备份包含不允许的设置键：$key")
            }
            val mappedKey = legacyKeyMapping[key] ?: key
            settingsOut[mappedKey] = value
        }

        return RavenBackupPayload(
            schemaVersion = RavenBackupPayload.CURRENT_SCHEMA_VERSION,
            exportedAt = legacy.at,
            appVersion = "r34v-legacy",
            watchProgress = watchProgress,
            readingProgress = readingProgress,
            favorites = favorites,
            shelves = shelves,
            shelfItems = shelfItems,
            smartShelves = smartShelves,
            pageBookmarks = pageBookmarks,
            timeBookmarks = timeBookmarks,
            imageFavorites = imageFavorites,
            tagSubscriptions = tagSubscriptions,
            savedSearches = savedSearches,
            updateInbox = updateInbox,
            blockedTerms = blockedTerms,
            searchHistory = searchHistory,
            playbackPreferences = playbackPreferences,
            readerPreferences = readerPreferences,
            siteLanguages = siteLanguages,
            settings = settingsOut,
        )
    }

    private fun normalizeLegacyEntryKey(key: String): String {
        val parts = key.split(':')
        return if (parts.size == 3 && parts[1] == "gallery") {
            "${parts[0]}:${parts[2]}"
        } else {
            key
        }
    }

    private fun resolveLegacyUrl(
        siteId: SiteId,
        rawPathOrUrl: String,
        fallbackId: String,
        registry: SiteRegistry,
    ): String {
        val trimmed = rawPathOrUrl.trim()
        if (siteId.isLocal) {
            if (trimmed.isNotEmpty()) return trimmed
            return if (fallbackId.isNotBlank()) "r34local://$fallbackId" else ""
        }
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith('/')) {
            val base = registry.get(siteId)?.descriptor?.baseUrl
                ?: SiteId.RETIRED_HOSTS[siteId]?.let { "https://$it" }
            if (!base.isNullOrBlank()) return "$base$trimmed"
        }
        return trimmed
    }

    private fun inferMediaKind(siteId: SiteId, legacyMediaKind: String?, registry: SiteRegistry): MediaKind {
        val primary = registry.get(siteId)?.descriptor?.primaryKind
        if (legacyMediaKind.equals("GALLERY", ignoreCase = true)) {
            return when (primary) {
                MediaKind.PHOTO -> MediaKind.PHOTO
                MediaKind.SERIAL_COMIC -> MediaKind.SERIAL_COMIC
                else -> MediaKind.COMIC
            }
        }
        if (legacyMediaKind.equals("VIDEO", ignoreCase = true)) {
            return MediaKind.VIDEO
        }
        return primary ?: when {
            siteId in SiteId.PHOTO_SITES -> MediaKind.PHOTO
            siteId in SiteId.SERIAL_COMIC_SITES -> MediaKind.SERIAL_COMIC
            siteId in SiteId.COMIC_SITES -> MediaKind.COMIC
            else -> MediaKind.VIDEO
        }
    }

    private fun mapFavoriteKind(rawKind: String, siteId: SiteId, registry: SiteRegistry): FavoriteKind = when (rawKind) {
        "video" -> FavoriteKind.VIDEO
        "gallery" -> {
            val primary = registry.get(siteId)?.descriptor?.primaryKind
            if (primary == MediaKind.PHOTO || siteId in SiteId.PHOTO_SITES) FavoriteKind.PHOTO else FavoriteKind.COMIC
        }
        "character" -> FavoriteKind.CHARACTER
        "model" -> FavoriteKind.AUTHOR
        "tag" -> FavoriteKind.TAG
        else -> throw BackupValidationException("旧版收藏类型无效：$rawKind")
    }

    private fun mapComicLanguage(raw: String): ComicLanguage? = when (raw) {
        "ALL" -> ComicLanguage.ALL
        "CHINESE" -> ComicLanguage.CHINESE
        "JAPANESE" -> ComicLanguage.JAPANESE
        "CHINESE_JAPANESE", "CHINESE_AND_JAPANESE" -> ComicLanguage.CHINESE_AND_JAPANESE
        else -> null
    }

    private fun mapCategoryOrNull(raw: String): MediaKind? = when (raw.trim().uppercase()) {
        "" -> null
        "VIDEO" -> MediaKind.VIDEO
        "COMIC" -> MediaKind.COMIC
        "PHOTO" -> MediaKind.PHOTO
        else -> throw BackupValidationException("旧版站点分类无效：$raw")
    }

    private fun mapLegacyScope(raw: String?): SearchScope = when (raw?.trim()?.uppercase()) {
        "TAG" -> SearchScope.TAG
        "MODEL" -> SearchScope.AUTHOR
        "CHARACTER" -> SearchScope.CHARACTER
        else -> SearchScope.ALL
    }

    private fun mapLegacyFilter(
        filter: LegacyAdvancedFilter,
        includeTags: List<String>,
        excludeTags: List<String>,
    ): AdvancedSearchFilters = AdvancedSearchFilters(
        includeTags = includeTags,
        excludeTags = excludeTags,
        category = filter.category.takeIf { it.isNotBlank() },
        brand = filter.brand.takeIf { it.isNotBlank() },
        year = filter.year.takeIf { it > 0 },
        month = filter.month.takeIf { it > 0 },
        minDurationMinutes = filter.minDurationSec.takeIf { it > 0 }?.let { (it + 59) / 60 },
        maxDurationMinutes = filter.maxDurationSec.takeIf { it > 0 }?.let { (it + 59) / 60 },
        minPages = filter.minPages.takeIf { it > 0 },
        maxPages = filter.maxPages.takeIf { it > 0 },
        minRating = filter.minRating.takeIf { it > 0 },
    )

    private fun mapReaderMode(raw: String): ReaderMode = when (raw.trim().uppercase()) {
        "STRIP" -> ReaderMode.STRIP
        "LEFT_TO_RIGHT", "LTR" -> ReaderMode.LTR
        "RIGHT_TO_LEFT", "RTL" -> ReaderMode.RTL
        "SPREAD" -> ReaderMode.SPREAD
        else -> throw BackupValidationException("旧版阅读模式无效：$raw")
    }

    private fun mapReaderFit(raw: String): ReaderFit = when (raw.trim().uppercase()) {
        "WIDTH", "" -> ReaderFit.WIDTH
        "HEIGHT" -> ReaderFit.HEIGHT
        "ORIGINAL" -> ReaderFit.ORIGINAL
        else -> throw BackupValidationException("旧版阅读适应模式无效：$raw")
    }

    private fun mapReaderStart(raw: String): ReaderStartPosition = when (raw.trim().uppercase()) {
        "RESUME", "" -> ReaderStartPosition.RESUME
        "FIRST" -> ReaderStartPosition.FIRST
        "LAST" -> ReaderStartPosition.LAST
        else -> throw BackupValidationException("旧版阅读起始位置无效：$raw")
    }

    private fun toMediaItem(v: LegacyVideo, siteId: SiteId, registry: SiteRegistry): MediaItem = MediaItem(
        siteId = siteId,
        id = v.id,
        url = resolveLegacyUrl(siteId, v.url, v.id, registry),
        kind = inferMediaKind(siteId, v.mediaKind, registry),
        title = v.title,
        coverUrl = v.thumb,
        coverAspectRatio = if (v.thumbWidth > 0 && v.thumbHeight > 0) {
            (v.thumbWidth.toFloat() / v.thumbHeight.toFloat()).coerceIn(0.45f, 1.8f)
        } else {
            null
        },
        durationSeconds = parseDurationSecondsOrNull(v.duration),
        tags = v.tagSlugs.orEmpty().map { TagRef(slug = it) },
        previewUrl = v.preview.takeIf { it.isNotBlank() },
    )

    private fun parseDurationSecondsOrNull(raw: String): Int? {
        if (raw.isBlank()) return null
        val parts = raw.trim().split(':')
        if (parts.size !in 1..3 || parts.any { it.toIntOrNull() == null }) return null
        var total = 0
        for (p in parts) {
            total = total * 60 + p.toInt()
        }
        return total.takeIf { it > 0 }
    }

    @Serializable
    private data class LegacyLibraryBackup(
        val schema: Int = 1,
        val at: Long = 0L,
        val sites: List<LegacySiteBackup> = emptyList(),
        val personal: LegacyPersonalLibrary = LegacyPersonalLibrary(),
        val settings: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class LegacySiteBackup(
        val site: String = "",
        val favorites: List<LegacyFavorite> = emptyList(),
        val watches: List<LegacyWatch> = emptyList(),
        val readings: List<LegacyReadingProgress> = emptyList(),
        val subscriptions: List<LegacySubscription> = emptyList(),
        val blocked: List<String> = emptyList(),
        val searches: List<String> = emptyList(),
        val language: String = "ALL",
        val order: String = "SUBSCRIBED",
    )

    @Serializable
    private data class LegacyFavorite(
        @SerialName("k") val kind: String = "video",
        @SerialName("t") val target: String = "",
        val title: String = "",
        val path: String = "",
        val thumb: String = "",
        @SerialName("sub") val subtitle: String = "",
        @SerialName("at") val at: Long = 0L,
        @SerialName("site") val site: String? = null,
    )

    @Serializable
    private data class LegacyWatch(
        val id: String = "",
        val title: String = "",
        val thumb: String = "",
        val path: String = "",
        @SerialName("pos") val position: Long = 0L,
        @SerialName("dur") val duration: Long = 0L,
        @SerialName("at") val at: Long = 0L,
        @SerialName("q") val quality: String = "",
        @SerialName("site") val site: String? = null,
        @SerialName("ended") val ended: Boolean? = null,
    )

    @Serializable
    private data class LegacyVideo(
        val id: String = "",
        val title: String = "",
        val url: String = "",
        val thumb: String = "",
        val duration: String = "",
        val views: String = "",
        val rating: String = "",
        val type: String = "",
        val preview: String = "",
        @SerialName("site") val site: String? = null,
        val mediaKind: String = "VIDEO",
        val thumbWidth: Int = 0,
        val thumbHeight: Int = 0,
        val tagSlugs: List<String>? = null,
    )

    @Serializable
    private data class LegacyReadingProgress(
        val entry: LegacyVideo = LegacyVideo(),
        val page: Int = 1,
        val pages: Int = 1,
        val pageUrl: String = "",
        val at: Long = 0L,
        val offset: Int = 0,
    )

    @Serializable
    private data class LegacySubscription(
        val slug: String = "",
        val label: String = "",
        val url: String = "",
        val site: String? = null,
        val at: Long = 0L,
    )

    @Serializable
    private data class LegacyPersonalLibrary(
        val shelves: List<LegacyShelf> = listOf(LegacyShelf(id = Shelf.WATCH_LATER_ID, name = "稍后看")),
        val bookmarks: List<LegacyPageBookmark> = emptyList(),
        val readerModes: Map<String, String> = emptyMap(),
        val inboxes: Map<String, LegacySubscriptionInbox> = emptyMap(),
        val savedSearches: List<LegacySavedSearch> = emptyList(),
        val searchUpdates: Map<String, LegacySavedSearchInbox> = emptyMap(),
        val smartShelves: List<LegacySmartShelf> = emptyList(),
        val timeBookmarks: List<LegacyTimeBookmark> = emptyList(),
        val playbackPreferences: Map<String, LegacyPlaybackPreferences> = emptyMap(),
        val readerPreferences: Map<String, LegacyReaderPreferences> = emptyMap(),
        val readingBehaviors: Map<String, LegacyReadingBehavior> = emptyMap(),
        val imageFavorites: List<LegacyImageFavorite> = emptyList(),
    )

    @Serializable
    private data class LegacyShelfEntry(
        val video: LegacyVideo = LegacyVideo(),
        val completed: Boolean = false,
        val at: Long = 0L,
        val addedAt: Long = 0L,
    )

    @Serializable
    private data class LegacyShelf(
        val id: String = "",
        val name: String = "",
        val entries: List<LegacyShelfEntry> = emptyList(),
        val note: String = "",
        val pinned: Boolean = false,
        val coverKey: String = "",
        val modifiedAt: Long = 0L,
    )

    @Serializable
    private data class LegacyPageBookmark(
        val video: LegacyVideo = LegacyVideo(),
        val page: Int = 1,
        val offset: Int = 0,
        val at: Long = 0L,
    )

    @Serializable
    private data class LegacyInboxEntry(
        val video: LegacyVideo = LegacyVideo(),
        val read: Boolean = false,
        val at: Long = 0L,
    )

    @Serializable
    private data class LegacySubscriptionInbox(
        val known: Map<String, List<String>> = emptyMap(),
        val entries: List<LegacyInboxEntry> = emptyList(),
    )

    @Serializable
    private data class LegacyAdvancedFilter(
        val year: Int = 0,
        val month: Int = 0,
        val minDurationSec: Int = 0,
        val maxDurationSec: Int = 0,
        val brand: String = "",
        val category: String = "",
        val minRating: Int = 0,
        val minPages: Int = 0,
        val maxPages: Int = 0,
    )

    @Serializable
    private data class LegacySavedSearch(
        val id: String? = null,
        val name: String = "",
        val query: String = "",
        val sources: List<String> = emptyList(),
        val category: String = "",
        val languages: Map<String, String> = emptyMap(),
        val includeTags: List<String> = emptyList(),
        val excludeTags: List<String> = emptyList(),
        val filter: LegacyAdvancedFilter = LegacyAdvancedFilter(),
        val singleSiteScope: String? = null,
    )

    @Serializable
    private data class LegacySavedSearchInbox(
        val conditionKey: String = "",
        val known: Map<String, List<String>> = emptyMap(),
        val entries: List<LegacyInboxEntry> = emptyList(),
    )

    @Serializable
    private data class LegacySmartShelf(
        val id: String = "",
        val name: String = "",
        val rule: String = "UNFINISHED",
        val category: String = "",
        val source: String = "",
    )

    @Serializable
    private data class LegacyTimeBookmark(
        val id: String = "",
        val video: LegacyVideo = LegacyVideo(),
        val positionMs: Long = 0L,
        val note: String = "",
        val group: String = "",
        val at: Long = 0L,
    )

    @Serializable
    private data class LegacyPlaybackPreferences(
        val defaultRate: Float = 1.0f,
        val holdRate: Float = 2.0f,
        val seekStepMs: Long = 10_000L,
        val gestureRangeMs: Long = 120_000L,
    )

    @Serializable
    private data class LegacyReaderPreferences(
        val mode: String = "",
        val fit: String = "WIDTH",
        val start: String = "RESUME",
        val cropPercent: Int = 0,
        val doubleCover: Boolean = true,
    )

    @Serializable
    private data class LegacyReadingBehavior(
        val volumeKeys: Boolean = true,
        val reverseVolume: Boolean = false,
        val autoPageSeconds: Int = 0,
        val slideshowSeconds: Int = 0,
        val brightnessPercent: Int = -1,
        val keepAwake: Boolean = false,
        val originalImages: Boolean = false,
    )

    @Serializable
    private data class LegacyImageFavorite(
        val video: LegacyVideo = LegacyVideo(),
        val page: Int = 1,
        val pageUrl: String = "",
        val imageUrl: String = "",
        val at: Long = 0L,
    )
}
