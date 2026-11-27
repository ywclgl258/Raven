package com.raven.app.core.security

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.workId
import com.raven.app.core.sitespi.SiteRegistry

/**
 * Thrown when a native or legacy backup payload violates schema, bounds, host ownership,
 * local URI format, or credential-exclusion invariants.
 */
class BackupValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Structured preview returned by [BackupValidator] and [BackupCodec.decode] before any
 * database mutation occurs.
 */
data class BackupSummary(
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val watchProgressCount: Int,
    val readingProgressCount: Int,
    val favoritesCount: Int,
    val shelvesCount: Int,
    val shelfItemsCount: Int,
    val smartShelvesCount: Int,
    val pageBookmarksCount: Int,
    val timeBookmarksCount: Int,
    val imageFavoritesCount: Int,
    val tagSubscriptionsCount: Int,
    val savedSearchesCount: Int,
    val updateInboxCount: Int,
    val blockedTermsCount: Int,
    val searchHistoryCount: Int,
    val siteIdsPresent: Set<SiteId>,
    val retiredSiteIdsPresent: Set<SiteId>,
    val customTagTranslationsCount: Int = 0,
) {
    val totalHistoryCount: Int get() = watchProgressCount + readingProgressCount
    val totalBookmarksCount: Int get() = pageBookmarksCount + timeBookmarksCount

    fun toDisplayText(): String = buildString {
        append("收藏 $favoritesCount 项 · 订阅 $tagSubscriptionsCount 项")
        append("\n观看 / 阅读记录 $totalHistoryCount 项")
        append("\n片单 $shelvesCount 个（$shelfItemsCount 项） · 书签 $totalBookmarksCount 个")
        if (imageFavoritesCount > 0 || savedSearchesCount > 0) {
            append("\n单图收藏 $imageFavoritesCount 张 · 保存的搜索 $savedSearchesCount 个")
        }
        if (customTagTranslationsCount > 0) {
            append("\n自定义标签译名 $customTagTranslationsCount 条")
        }
        if (retiredSiteIdsPresent.isNotEmpty()) {
            append("\n包含已退役站点历史记录：${retiredSiteIdsPresent.joinToString(", ") { it.value }}")
        }
    }
}

/**
 * Validates [RavenBackupPayload] against schema version, collection bounds, strict HTTPS
 * host ownership (via [SiteRegistry] + retired hosts), local URI format (`ravenlocal://` and
 * legacy `r34local://`), and strict exclusion of credentials/secrets in settings.
 */
class BackupValidator(
    private val siteRegistryProvider: () -> SiteRegistry,
) {
    constructor(siteRegistry: SiteRegistry) : this({ siteRegistry })

    fun validate(
        payload: RavenBackupPayload,
        registryOverride: SiteRegistry? = null,
    ): BackupSummary {
        val registry = registryOverride ?: siteRegistryProvider()

        checkRule(payload.schemaVersion == RavenBackupPayload.CURRENT_SCHEMA_VERSION) {
            "不支持的备份版本：${payload.schemaVersion}"
        }
        checkRule(payload.exportedAt >= 0L) { "备份导出时间戳无效" }
        checkRule(payload.appVersion.length <= 128) { "应用版本字段过长" }

        val sitesPresent = LinkedHashSet<SiteId>()

        fun validateSite(siteId: SiteId) {
            val supported = siteId.isLocal || siteId.isRetired || registry.get(siteId) != null
            checkRule(supported) { "备份包含未知来源站点：${siteId.value}" }
            sitesPresent.add(siteId)
        }

        fun validateSiteUrl(url: String, siteId: SiteId, allowBlank: Boolean = true, allowRelative: Boolean = false) {
            checkRule(url.length <= MAX_URL_LENGTH) { "备份地址超过最大长度限制" }
            if (url.isBlank()) {
                checkRule(allowBlank) { "备份作品地址不能为空" }
                return
            }
            if (siteId.isLocal) {
                checkRule(LOCAL_ITEM_URI_REGEX.matches(url)) {
                    "本地备份地址格式无效：$url"
                }
                return
            }
            if (allowRelative && url.startsWith('/')) {
                return
            }
            val uri = UrlSanitizer.parseUriOrNull(url)
            checkRule(uri != null && UrlSanitizer.isHttpsUrl(url)) {
                "备份地址必须为合法的 HTTPS 链接：$url"
            }
            val host = uri!!.host?.lowercase().orEmpty()
            checkRule(hostMatchesSite(registry, siteId, host)) {
                "备份作品地址域名 ($host) 不属于来源站点 (${siteId.value})"
            }
        }

        fun validateOptionalHttpsOrLocalImageUrl(url: String, siteId: SiteId) {
            if (url.isBlank()) return
            checkRule(url.length <= MAX_URL_LENGTH) { "备份图片地址过长" }
            if (siteId.isLocal && LOCAL_PAGE_OR_ITEM_URI_REGEX.matches(url)) return
            checkRule(UrlSanitizer.isHttpsUrl(url)) {
                "备份图片地址必须为合法的 HTTPS 链接：$url"
            }
        }

        fun validateMediaItem(item: MediaItem) {
            validateSite(item.siteId)
            checkRule(item.id.isNotBlank() && item.id.length <= MAX_ID_LENGTH) { "作品 ID 无效" }
            checkRule(item.title.length <= MAX_TEXT_LENGTH) { "作品标题过长" }
            validateSiteUrl(item.url, item.siteId, allowBlank = true, allowRelative = true)
            validateOptionalHttpsOrLocalImageUrl(item.coverUrl, item.siteId)
        }

        // 1. Watch progress
        val watchCountBySite = HashMap<SiteId, Int>()
        for (watch in payload.watchProgress) {
            validateSite(watch.siteId)
            val count = (watchCountBySite[watch.siteId] ?: 0) + 1
            checkRule(count <= MAX_HISTORY_PER_SITE) { "站点 ${watch.siteId.value} 观看记录超过上限 ($MAX_HISTORY_PER_SITE)" }
            watchCountBySite[watch.siteId] = count
            checkRule(watch.mediaId.isNotBlank() && watch.mediaId.length <= MAX_ID_LENGTH) { "观看记录作品 ID 无效" }
            checkRule(watch.positionMs >= 0L && watch.durationMs >= 0L && watch.updatedAt >= 0L) { "观看进度数值无效" }
            validateSiteUrl(watch.url, watch.siteId, allowBlank = true, allowRelative = true)
            validateOptionalHttpsOrLocalImageUrl(watch.coverUrl, watch.siteId)
        }

        // 2. Reading progress
        val readingWorksBySite = HashMap<SiteId, MutableSet<String>>()
        for (reading in payload.readingProgress) {
            validateSite(reading.siteId)
            val works = readingWorksBySite.getOrPut(reading.siteId) { linkedSetOf() }
            val workId = reading.workId
            if (workId !in works) {
                checkRule(works.size < MAX_HISTORY_PER_SITE) { "站点 ${reading.siteId.value} 阅读记录超过上限 ($MAX_HISTORY_PER_SITE)" }
                works.add(workId)
            }
            checkRule(reading.mediaId.isNotBlank() && reading.mediaId.length <= MAX_ID_LENGTH) { "阅读记录作品 ID 无效" }
            checkRule(reading.currentPage in 1..MAX_PAGE_INDEX) { "阅读页码超出范围：${reading.currentPage}" }
            checkRule(reading.totalPages == 0 || reading.totalPages >= reading.currentPage) { "总页数小于当前页码" }
            checkRule(reading.scrollOffsetPx in -MAX_SCROLL_OFFSET..MAX_SCROLL_OFFSET) { "滚动偏移超出范围" }
            checkRule(reading.updatedAt >= 0L) { "阅读时间戳无效" }
            validateSiteUrl(reading.url, reading.siteId, allowBlank = true, allowRelative = true)
            validateOptionalHttpsOrLocalImageUrl(reading.coverUrl, reading.siteId)
            checkRule(reading.seriesId.isNullOrBlank() || reading.seriesId!!.length <= MAX_ID_LENGTH) { "阅读记录作品系列 ID 无效" }
            checkRule((reading.seriesTitle?.length ?: 0) <= MAX_TEXT_LENGTH) { "阅读记录系列标题过长" }
            if (!reading.seriesUrl.isNullOrBlank()) {
                validateSiteUrl(reading.seriesUrl!!, reading.siteId, allowBlank = true, allowRelative = true)
            }
        }

        // 3. Favorites
        val favCountBySite = HashMap<SiteId, Int>()
        for (fav in payload.favorites) {
            validateSite(fav.siteId)
            val count = (favCountBySite[fav.siteId] ?: 0) + 1
            checkRule(count <= MAX_FAVORITES_PER_SITE) { "站点 ${fav.siteId.value} 收藏超过上限 ($MAX_FAVORITES_PER_SITE)" }
            favCountBySite[fav.siteId] = count
            checkRule(fav.targetId.isNotBlank() && fav.targetId.length <= MAX_ID_LENGTH) { "收藏目标 ID 无效" }
            validateSiteUrl(fav.url, fav.siteId, allowBlank = true, allowRelative = true)
            validateOptionalHttpsOrLocalImageUrl(fav.coverUrl, fav.siteId)
        }

        // 4. Shelves & ShelfItems
        checkRule(payload.shelves.size <= MAX_SHELVES) { "片单数量超过上限 ($MAX_SHELVES)" }
        val shelfIds = HashSet<String>(payload.shelves.size)
        for (shelf in payload.shelves) {
            checkRule(shelf.id.isNotBlank() && shelf.id.length <= MAX_ID_LENGTH) { "片单 ID 无效" }
            checkRule(shelfIds.add(shelf.id)) { "备份包含重复片单 ID：${shelf.id}" }
            checkRule(shelf.name.length in 1..MAX_NAME_LENGTH) { "片单名称长度必须在 1..$MAX_NAME_LENGTH 之间" }
            checkRule(shelf.note.length <= MAX_NOTE_LENGTH) { "片单备注超过上限 ($MAX_NOTE_LENGTH)" }
            checkRule(shelf.createdAt >= 0L && shelf.modifiedAt >= 0L) { "片单时间戳无效" }
        }
        val itemsPerShelf = HashMap<String, Int>()
        val itemKeysPerShelf = HashMap<String, MutableSet<String>>()
        for (item in payload.shelfItems) {
            checkRule(item.shelfId in shelfIds) { "片单条目引用了不存在的片单：${item.shelfId}" }
            val count = (itemsPerShelf[item.shelfId] ?: 0) + 1
            checkRule(count <= MAX_ITEMS_PER_SHELF) { "片单 ${item.shelfId} 条目数超过上限 ($MAX_ITEMS_PER_SHELF)" }
            itemsPerShelf[item.shelfId] = count
            validateSite(item.siteId)
            checkRule(item.mediaId.isNotBlank() && item.mediaId.length <= MAX_ID_LENGTH) { "片单作品 ID 无效" }
            validateSiteUrl(item.url, item.siteId, allowBlank = true, allowRelative = true)
            validateOptionalHttpsOrLocalImageUrl(item.coverUrl, item.siteId)
            itemKeysPerShelf.getOrPut(item.shelfId) { HashSet() }.add(item.compositeKey)
        }
        for (shelf in payload.shelves) {
            val coverKey = shelf.coverCompositeKey
            if (!coverKey.isNullOrBlank()) {
                val validKeys = itemKeysPerShelf[shelf.id].orEmpty()
                checkRule(coverKey in validKeys) {
                    "片单 ${shelf.id} 的封面条目 ($coverKey) 不在片单内容中"
                }
            }
        }

        // 5. Smart shelves
        checkRule(payload.smartShelves.size <= MAX_SMART_SHELVES) { "智能片单超过上限 ($MAX_SMART_SHELVES)" }
        val smartShelfIds = HashSet<String>(payload.smartShelves.size)
        for (smart in payload.smartShelves) {
            checkRule(smart.id.isNotBlank() && smart.id.length <= MAX_ID_LENGTH) { "智能片单 ID 无效" }
            checkRule(smartShelfIds.add(smart.id)) { "备份包含重复智能片单 ID：${smart.id}" }
            checkRule(smart.name.length in 1..MAX_NAME_LENGTH) { "智能片单名称无效" }
            smart.siteIdFilter?.let(::validateSite)
        }

        // 6. Page bookmarks
        checkRule(payload.pageBookmarks.size <= MAX_PAGE_BOOKMARKS) { "页码书签超过上限 ($MAX_PAGE_BOOKMARKS)" }
        for (mark in payload.pageBookmarks) {
            validateSite(mark.siteId)
            checkRule(mark.id.isNotBlank() && mark.id.length <= MAX_ID_LENGTH) { "页码书签 ID 无效" }
            checkRule(mark.mediaId.isNotBlank() && mark.mediaId.length <= MAX_ID_LENGTH) { "页码书签作品 ID 无效" }
            checkRule(mark.page in 1..MAX_PAGE_INDEX) { "书签页码超出范围：${mark.page}" }
            checkRule(mark.scrollOffsetPx in -MAX_SCROLL_OFFSET..MAX_SCROLL_OFFSET) { "书签滚动偏移超出范围" }
            checkRule(mark.note.length <= MAX_NOTE_LENGTH) { "书签备注过长" }
            validateSiteUrl(mark.url, mark.siteId, allowBlank = true, allowRelative = true)
            // Portable backups never carry device paths. Local comics can hold a file://
            // thumbnail, which export strips (BackupDataRepository.portableSnapshot); a payload
            // that still contains one is a broken package and must be rejected.
            validateOptionalHttpsOrLocalImageUrl(mark.coverUrl, mark.siteId)
        }

        // 7. Time bookmarks
        checkRule(payload.timeBookmarks.size <= MAX_TIME_BOOKMARKS) { "时间书签超过上限 ($MAX_TIME_BOOKMARKS)" }
        val timeBookmarkIds = HashSet<String>(payload.timeBookmarks.size)
        for (mark in payload.timeBookmarks) {
            validateSite(mark.siteId)
            checkRule(mark.id.isNotBlank() && mark.id.length <= MAX_ID_LENGTH) { "时间书签 ID 无效" }
            checkRule(timeBookmarkIds.add(mark.id)) { "备份包含重复时间书签 ID：${mark.id}" }
            checkRule(mark.mediaId.isNotBlank() && mark.mediaId.length <= MAX_ID_LENGTH) { "时间书签作品 ID 无效" }
            checkRule(mark.positionMs >= 0L && mark.durationMs >= 0L) { "时间书签位置无效" }
            checkRule(mark.label.length <= 500 && mark.groupName.length <= MAX_NAME_LENGTH) { "时间书签标签或分组过长" }
            validateSiteUrl(mark.url, mark.siteId, allowBlank = true, allowRelative = true)
            validateOptionalHttpsOrLocalImageUrl(mark.coverUrl, mark.siteId)
        }

        // 8. Image favorites
        checkRule(payload.imageFavorites.size <= MAX_IMAGE_FAVORITES) { "单图收藏超过上限 ($MAX_IMAGE_FAVORITES)" }
        val imageFavIds = HashSet<String>(payload.imageFavorites.size)
        for (fav in payload.imageFavorites) {
            validateSite(fav.siteId)
            checkRule(fav.id.isNotBlank() && fav.id.length <= MAX_ID_LENGTH) { "单图收藏 ID 无效" }
            checkRule(imageFavIds.add(fav.id)) { "备份包含重复单图收藏 ID：${fav.id}" }
            checkRule(fav.mediaId.isNotBlank() && fav.mediaId.length <= MAX_ID_LENGTH) { "单图收藏作品 ID 无效" }
            checkRule(fav.pageIndex in 1..MAX_PAGE_INDEX) { "单图收藏页码无效：${fav.pageIndex}" }
            if (fav.siteId.isLocal) {
                checkRule(LOCAL_SHA256_REGEX.matches(fav.mediaId)) { "本地单图收藏 ID 必须为 64 位 SHA-256" }
                val expectedR34 = "r34local://${fav.mediaId}/${fav.pageIndex}"
                val expectedRaven = "ravenlocal://${fav.mediaId}/${fav.pageIndex}"
                checkRule(fav.pageUrl == expectedR34 || fav.pageUrl == expectedRaven) {
                    "本地单图收藏页址不匹配：${fav.pageUrl}"
                }
                checkRule(fav.imageUrl.isBlank() || UrlSanitizer.isHttpsUrl(fav.imageUrl)) {
                    "本地单图收藏不允许携带本地文件路径：${fav.imageUrl}"
                }
            } else {
                validateSiteUrl(fav.pageUrl, fav.siteId, allowBlank = false, allowRelative = true)
                validateOptionalHttpsOrLocalImageUrl(fav.imageUrl, fav.siteId)
            }
        }

        // 9. Tag subscriptions
        val subCountBySite = HashMap<SiteId, Int>()
        for (sub in payload.tagSubscriptions) {
            validateSite(sub.siteId)
            val count = (subCountBySite[sub.siteId] ?: 0) + 1
            checkRule(count <= MAX_SUBSCRIPTIONS_PER_SITE) { "站点 ${sub.siteId.value} 订阅超过上限 ($MAX_SUBSCRIPTIONS_PER_SITE)" }
            subCountBySite[sub.siteId] = count
            checkRule(sub.slug.isNotBlank() && sub.slug.length <= MAX_ID_LENGTH) { "订阅标签 slug 无效" }
            validateSiteUrl(sub.url, sub.siteId, allowBlank = true, allowRelative = true)
        }

        // 10. Saved searches
        checkRule(payload.savedSearches.size <= MAX_SAVED_SEARCHES) { "保存的搜索超过上限 ($MAX_SAVED_SEARCHES)" }
        val savedSearchIds = HashSet<String>(payload.savedSearches.size)
        for (search in payload.savedSearches) {
            checkRule(search.id.isNotBlank() && search.id.length <= MAX_ID_LENGTH) { "保存的搜索 ID 无效" }
            checkRule(savedSearchIds.add(search.id)) { "备份包含重复搜索 ID：${search.id}" }
            checkRule(search.name.length in 1..MAX_NAME_LENGTH) { "保存的搜索名称无效" }
            checkRule(search.query.length <= MAX_ID_LENGTH) { "搜索关键词过长" }
            checkRule(search.siteIds.isNotEmpty() && search.siteIds.distinct().size == search.siteIds.size) {
                "保存的搜索来源列表无效"
            }
            for (sid in search.siteIds) {
                checkRule(!sid.isLocal) { "保存的搜索不能包含本地源" }
                validateSite(sid)
            }
            validateFilters(search.filters)
        }

        // 11. Update inbox
        checkRule(payload.updateInbox.size <= MAX_INBOX_ITEMS) { "订阅更新超过上限 ($MAX_INBOX_ITEMS)" }
        for (inboxItem in payload.updateInbox) {
            checkRule(inboxItem.id.isNotBlank() && inboxItem.id.length <= MAX_ID_LENGTH) { "收件箱条目 ID 无效" }
            validateMediaItem(inboxItem.item)
        }

        // 12. Blocked terms (allows SiteId.GLOBAL "*" in addition to live/local/retired sites)
        val blockedBySite = HashMap<SiteId, Int>()
        for (term in payload.blockedTerms) {
            if (!term.siteId.isGlobal) {
                validateSite(term.siteId)
            }
            val count = (blockedBySite[term.siteId] ?: 0) + 1
            checkRule(count <= MAX_BLOCKED_PER_SITE) { "站点 ${term.siteId.value} 屏蔽词超过上限 ($MAX_BLOCKED_PER_SITE)" }
            blockedBySite[term.siteId] = count
            checkRule(term.term.length in 1..MAX_ID_LENGTH) { "屏蔽词长度无效" }
        }

        // 13. Search history (cross-site searches are recorded under SiteId.GLOBAL "*", exactly
        // like global blocked terms; every other record type stays restricted to real sites)
        val historyBySite = HashMap<SiteId, Int>()
        for (entry in payload.searchHistory) {
            if (!entry.siteId.isGlobal) {
                validateSite(entry.siteId)
            }
            val count = (historyBySite[entry.siteId] ?: 0) + 1
            checkRule(count <= MAX_SEARCH_HISTORY_PER_SITE) { "站点 ${entry.siteId.value} 搜索历史超过上限 ($MAX_SEARCH_HISTORY_PER_SITE)" }
            historyBySite[entry.siteId] = count
            checkRule(entry.query.length <= MAX_ID_LENGTH) { "搜索历史词过长" }
        }

        // 14. Playback & Reader preferences
        for (pref in payload.playbackPreferences) {
            pref.siteId?.let(::validateSite)
            checkRule(pref.defaultSpeed in 0.25f..3.0f) { "默认播放倍速超出范围：${pref.defaultSpeed}" }
            checkRule(pref.longPressSpeed in 1.0f..4.0f) { "长按倍速超出范围：${pref.longPressSpeed}" }
            checkRule(pref.seekStepSeconds in 1..120) { "快进步长超出范围：${pref.seekStepSeconds}" }
            checkRule(pref.gestureSensitivitySeconds in 10..600) { "手势灵敏度超出范围：${pref.gestureSensitivitySeconds}" }
        }
        for (pref in payload.readerPreferences) {
            pref.siteId?.let(::validateSite)
            checkRule(pref.cropPercent in 0..20) { "裁边比例超出范围：${pref.cropPercent}" }
            checkRule(pref.brightnessPercent in -1..100) { "阅读亮度超出范围：${pref.brightnessPercent}" }
        }

        // 15. Site languages
        for (siteId in payload.siteLanguages.keys) {
            validateSite(siteId)
        }

        // 16. Settings allowlist (NEVER allow cookies, tokens, keys, passwords, or storage paths)
        for ((key, value) in payload.settings) {
            checkRule(isAllowedSettingKey(key)) { "备份包含不受支持或敏感的设置键：$key" }
            checkRule(value.length <= MAX_ID_LENGTH) { "设置项 $key 值过长" }
            if (key in BOOLEAN_SETTINGS_KEYS) {
                checkRule(value == "true" || value == "false") { "布尔设置项 $key 值无效：$value" }
            }
            if (key == "default_playback_speed" || key == "native_speed") {
                val parsed = value.toFloatOrNull()
                checkRule(parsed != null && parsed in 0.25f..3.0f) { "默认倍速设置无效：$value" }
            }
            if ((key == "active_site_id" || key == "startup_default_site_id") && value.isNotBlank()) {
                validateSite(SiteId.of(value))
            }
            if (key == "cross_search_site_ids" || key == "cross_search_sites" || key == "pinned_site_ids") {
                parseSiteIdList(value).forEach(::validateSite)
            }
        }

        // 17. Custom tag translations
        checkRule(payload.customTagTranslations.size <= MAX_CUSTOM_TAG_TRANSLATIONS) {
            "自定义标签词条超过上限 ($MAX_CUSTOM_TAG_TRANSLATIONS)"
        }
        for (entry in payload.customTagTranslations) {
            checkRule(entry.namespace.length <= MAX_NAMESPACE_LENGTH) { "自定义标签命名空间过长" }
            checkRule(entry.rawSlug.isNotBlank() && entry.rawSlug.length <= MAX_TAG_LENGTH) {
                "自定义标签英文词条无效"
            }
            checkRule(entry.chineseName.isNotBlank() && entry.chineseName.length <= MAX_TAG_LENGTH) {
                "自定义标签中文译名无效"
            }
            checkRule(entry.intro.length <= MAX_ID_LENGTH) { "自定义标签说明过长" }
            checkRule(entry.updatedAt >= 0L) { "自定义标签时间戳无效" }
        }

        val retiredPresent = sitesPresent.filterTo(LinkedHashSet()) { it.isRetired }
        return BackupSummary(
            schemaVersion = payload.schemaVersion,
            exportedAt = payload.exportedAt,
            appVersion = payload.appVersion,
            watchProgressCount = payload.watchProgress.size,
            readingProgressCount = payload.readingProgress.map { it.siteId to it.workId }.distinct().size,
            favoritesCount = payload.favorites.size,
            shelvesCount = payload.shelves.size,
            shelfItemsCount = payload.shelfItems.size,
            smartShelvesCount = payload.smartShelves.size,
            pageBookmarksCount = payload.pageBookmarks.size,
            timeBookmarksCount = payload.timeBookmarks.size,
            imageFavoritesCount = payload.imageFavorites.size,
            tagSubscriptionsCount = payload.tagSubscriptions.size,
            savedSearchesCount = payload.savedSearches.size,
            updateInboxCount = payload.updateInbox.size,
            blockedTermsCount = payload.blockedTerms.size,
            searchHistoryCount = payload.searchHistory.size,
            siteIdsPresent = sitesPresent.toSet(),
            retiredSiteIdsPresent = retiredPresent,
            customTagTranslationsCount = payload.customTagTranslations.size,
        )
    }

    private fun validateFilters(filters: AdvancedSearchFilters) {
        checkRule(filters.includeTags.size + filters.excludeTags.size <= 200) { "筛选标签过多" }
        checkRule((filters.includeTags + filters.excludeTags).all { it.length in 1..200 }) { "筛选标签长度无效" }
        filters.year?.let { checkRule(it in 1900..2200) { "年份筛选超出范围：$it" } }
        filters.month?.let { checkRule(it in 1..12) { "月份筛选超出范围：$it" } }
        filters.minDurationMinutes?.let { checkRule(it >= 0) { "最小时长无效" } }
        filters.maxDurationMinutes?.let { max ->
            checkRule(max >= 0) { "最大时长无效" }
            val min = filters.minDurationMinutes ?: 0
            checkRule(max == 0 || min <= max) { "时长范围无效" }
        }
        filters.minPages?.let { checkRule(it >= 0) { "最小页数无效" } }
        filters.maxPages?.let { max ->
            checkRule(max >= 0) { "最大页数无效" }
            val min = filters.minPages ?: 0
            checkRule(max == 0 || min <= max) { "页数范围无效" }
        }
        filters.minRating?.let { checkRule(it in 0..5) { "最低评分超出范围：$it" } }
        filters.brand?.let { checkRule(it.length <= 200) { "品牌筛选过长" } }
        filters.category?.let { checkRule(it.length <= 200) { "分类筛选过长" } }
    }

    private fun hostMatchesSite(registry: SiteRegistry, siteId: SiteId, host: String): Boolean {
        if (host.isBlank()) return false
        val retired = SiteId.RETIRED_HOSTS[siteId]
        if (retired != null) {
            val bare = retired.removePrefix("www.")
            return host == bare || host == "www.$bare"
        }
        val plugin = registry.get(siteId) ?: return false
        return plugin.descriptor.matchesRequestHost(host)
    }

    private fun isAllowedSettingKey(key: String): Boolean {
        if (key in ALLOWED_SETTINGS_KEYS) return true
        if (key.startsWith("legacy_subscription_order_")) {
            val sitePart = key.removePrefix("legacy_subscription_order_")
            return sitePart.isNotBlank() && sitePart.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
        return false
    }

    private fun parseSiteIdList(raw: String): List<SiteId> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        val unwrapped = if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return unwrapped.split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .map { SiteId.of(it) }
    }

    private inline fun checkRule(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) throw BackupValidationException(lazyMessage())
    }

    companion object {
        const val MAX_URL_LENGTH: Int = 16_384
        const val MAX_ID_LENGTH: Int = 2_048
        const val MAX_TEXT_LENGTH: Int = 4_096
        const val MAX_NAME_LENGTH: Int = 80
        const val MAX_NOTE_LENGTH: Int = 1_000
        const val MAX_PAGE_INDEX: Int = 100_000
        const val MAX_SCROLL_OFFSET: Int = 10_000_000

        const val MAX_HISTORY_PER_SITE: Int = 500
        const val MAX_FAVORITES_PER_SITE: Int = 50_000
        // Canonical bounds live in `RavenBackupPayload` so the merger and the validator cannot drift.
        const val MAX_SHELVES: Int = RavenBackupPayload.MAX_SHELVES
        const val MAX_ITEMS_PER_SHELF: Int = RavenBackupPayload.MAX_ITEMS_PER_SHELF
        const val MAX_SMART_SHELVES: Int = RavenBackupPayload.MAX_SMART_SHELVES
        const val MAX_PAGE_BOOKMARKS: Int = RavenBackupPayload.MAX_PAGE_BOOKMARKS
        const val MAX_TIME_BOOKMARKS: Int = RavenBackupPayload.MAX_TIME_BOOKMARKS
        const val MAX_IMAGE_FAVORITES: Int = RavenBackupPayload.MAX_IMAGE_FAVORITES
        const val MAX_SUBSCRIPTIONS_PER_SITE: Int = 5_000
        const val MAX_SAVED_SEARCHES: Int = RavenBackupPayload.MAX_SAVED_SEARCHES
        const val MAX_INBOX_ITEMS: Int = RavenBackupPayload.MAX_INBOX_ITEMS
        const val MAX_BLOCKED_PER_SITE: Int = 10_000
        const val MAX_SEARCH_HISTORY_PER_SITE: Int = RavenBackupPayload.MAX_SEARCH_HISTORY_PER_SITE
        const val MAX_CUSTOM_TAG_TRANSLATIONS: Int = RavenBackupPayload.MAX_CUSTOM_TAG_TRANSLATIONS
        const val MAX_NAMESPACE_LENGTH: Int = RavenBackupPayload.MAX_TAG_NAMESPACE_LENGTH
        const val MAX_TAG_LENGTH: Int = RavenBackupPayload.MAX_TAG_CHINESE_NAME_LENGTH

        val LOCAL_SHA256_REGEX: Regex = Regex("^[a-f0-9]{64}$")
        val LOCAL_ITEM_URI_REGEX: Regex = Regex("^(ravenlocal|r34local)://[a-f0-9]{64}$")
        val LOCAL_PAGE_OR_ITEM_URI_REGEX: Regex = Regex("^(ravenlocal|r34local)://[a-f0-9]{64}(/[1-9][0-9]*)?$")

        val BOOLEAN_SETTINGS_KEYS: Set<String> = setOf(
            "translate_tags",
            "auto_fullscreen",
            "auto_download",
            "auto_update_check",
            "auto_update",
            "download_unmetered_only",
            "download_unmetered",
            "reduce_transparency",
            "ui_sound_effects_enabled",
            "stream_cache_enabled",
            "stream_cache",
            "seek_cache_enabled",
            "seek_cache",
            "tag_dict_auto_sync",
            "block_tags_globally",
            // Pinned-site display order toggle; exported by exportPortableSettings and re-applied
            // by applyPortableEntries, so the validator must accept it or backups fail to export.
            "dynamic_site_sorting",
        )

        val STRING_SETTINGS_KEYS: Set<String> = setOf(
            "active_site_id",
            "startup_default_site_id",
            "pinned_site_ids",
            "cross_search_site_ids",
            "cross_search_sites",
            "preferred_quality",
            "quality",
            "default_playback_speed",
            "native_speed",
            "download_view",
        )

        val ALLOWED_SETTINGS_KEYS: Set<String> = BOOLEAN_SETTINGS_KEYS + STRING_SETTINGS_KEYS
    }
}
