package com.raven.app.core.database.domain

import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.RavenDatabase
import com.raven.app.core.database.dao.ImageFavoriteDao
import com.raven.app.core.database.dao.ReadingProgressDao
import com.raven.app.core.database.dao.SearchHistoryDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.mapper.sanitized
import com.raven.app.core.database.model.BackupSummary
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.PlaybackPreferences
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.ReaderPreferences
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.TagTranslationEntry
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.model.WatchProgress
import java.util.UUID

/**
 * Deterministic merge and deduplication engine for [RavenBackupPayload] per `ARCHITECTURE.md` §7.2:
 * - Watch & reading progress: keep entry with newer `updatedAt` (capped at 500 per site).
 * - Shelf items: keep earliest positive `addedAt` and latest `completed` state (determined by
 *   parent shelf `modifiedAt`, then item `addedAt`, then logical OR tie-break).
 * - Favorites, bookmarks, image favorites (capped at 20,000), blocked terms, subscriptions,
 *   saved searches, and update inbox (preserving `isRead = true`): deterministic union deduplicated
 *   by composite key.
 * - Never merges device-local storage paths, credentials, tokens, or encryption keys.
 */
object BackupMerger {

    fun summarize(payload: RavenBackupPayload): BackupSummary = BackupSummary(
        schemaVersion = payload.schemaVersion,
        exportedAt = payload.exportedAt,
        appVersion = payload.appVersion,
        watchHistoryCount = payload.watchProgress.size,
        readingHistoryCount = payload.readingProgress.size,
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
        customTagTranslationsCount = payload.customTagTranslations.size,
    )

    fun merge(
        local: RavenBackupPayload,
        incoming: RavenBackupPayload,
        now: Long = System.currentTimeMillis(),
    ): RavenBackupPayload {
        require(incoming.schemaVersion == RavenBackupPayload.CURRENT_SCHEMA_VERSION) {
            "Unsupported backup schemaVersion: ${incoming.schemaVersion}"
        }

        val mergedWatch = mergeWatchProgress(local.watchProgress, incoming.watchProgress)
        val mergedReading = mergeReadingProgress(local.readingProgress, incoming.readingProgress)
        val mergedFavorites = mergeFavorites(local.favorites, incoming.favorites)
        val (mergedShelves, mergedShelfItems) = mergeShelvesAndItems(
            localShelves = local.shelves,
            localItems = local.shelfItems,
            incomingShelves = incoming.shelves,
            incomingItems = incoming.shelfItems,
            now = now,
        )
        val mergedSmartShelves = mergeSmartShelves(local.smartShelves, incoming.smartShelves)
        val mergedPageBookmarks = mergePageBookmarks(local.pageBookmarks, incoming.pageBookmarks)
        val mergedTimeBookmarks = mergeTimeBookmarks(local.timeBookmarks, incoming.timeBookmarks)
        val mergedImageFavorites = mergeImageFavorites(local.imageFavorites, incoming.imageFavorites)
        val mergedSubscriptions = mergeTagSubscriptions(local.tagSubscriptions, incoming.tagSubscriptions)
        val mergedSavedSearches = mergeSavedSearches(local.savedSearches, incoming.savedSearches)
        val mergedInbox = mergeUpdateInbox(local.updateInbox, incoming.updateInbox)
        val mergedBlocked = mergeBlockedTerms(local.blockedTerms, incoming.blockedTerms)
        val mergedSearchHistory = mergeSearchHistory(local.searchHistory, incoming.searchHistory)
        val mergedPlaybackPrefs = mergePlaybackPreferences(local.playbackPreferences, incoming.playbackPreferences)
        val mergedReaderPrefs = mergeReaderPreferences(local.readerPreferences, incoming.readerPreferences)
        val mergedLanguages = local.siteLanguages + incoming.siteLanguages
        val mergedSettings = filterPortableSettings(local.settings) + filterPortableSettings(incoming.settings)
        val mergedCustomTags = mergeCustomTagTranslations(
            local = local.customTagTranslations,
            incoming = incoming.customTagTranslations,
        )

        return RavenBackupPayload(
            schemaVersion = RavenBackupPayload.CURRENT_SCHEMA_VERSION,
            exportedAt = maxOf(local.exportedAt, incoming.exportedAt, now),
            appVersion = local.appVersion.ifBlank { incoming.appVersion },
            watchProgress = mergedWatch,
            readingProgress = mergedReading,
            favorites = mergedFavorites,
            shelves = mergedShelves,
            shelfItems = mergedShelfItems,
            smartShelves = mergedSmartShelves,
            pageBookmarks = mergedPageBookmarks,
            timeBookmarks = mergedTimeBookmarks,
            imageFavorites = mergedImageFavorites,
            tagSubscriptions = mergedSubscriptions,
            savedSearches = mergedSavedSearches,
            updateInbox = mergedInbox,
            blockedTerms = mergedBlocked,
            searchHistory = mergedSearchHistory,
            playbackPreferences = mergedPlaybackPrefs,
            readerPreferences = mergedReaderPrefs,
            siteLanguages = mergedLanguages,
            settings = mergedSettings,
            customTagTranslations = mergedCustomTags,
        )
    }

    private fun mergeWatchProgress(
        local: List<WatchProgress>,
        incoming: List<WatchProgress>,
    ): List<WatchProgress> {
        val byKey = linkedMapOf<String, WatchProgress>()
        for (entry in local) {
            if (entry.mediaId.isBlank()) continue
            val resolved = CompletionPolicy.resolveWatchProgress(entry)
            val prev = byKey[resolved.compositeKey]
            if (prev == null || resolved.updatedAt > prev.updatedAt) {
                byKey[resolved.compositeKey] = resolved
            }
        }
        for (entry in incoming) {
            if (entry.mediaId.isBlank()) continue
            val resolved = CompletionPolicy.resolveWatchProgress(entry)
            val prev = byKey[resolved.compositeKey]
            if (prev == null || resolved.updatedAt >= prev.updatedAt) {
                byKey[resolved.compositeKey] = resolved
            }
        }
        return byKey.values
            .groupBy { it.siteId }
            .values
            .flatMap { siteList ->
                siteList.sortedByDescending { it.updatedAt }.take(WatchProgressDao.MAX_PER_SITE)
            }
            .sortedByDescending { it.updatedAt }
    }

    private fun mergeReadingProgress(
        local: List<ReadingProgress>,
        incoming: List<ReadingProgress>,
    ): List<ReadingProgress> {
        val byKey = linkedMapOf<String, ReadingProgress>()
        for (entry in local) {
            if (entry.mediaId.isBlank()) continue
            val resolved = CompletionPolicy.resolveReadingProgress(entry)
            val prev = byKey[resolved.compositeKey]
            if (prev == null || resolved.updatedAt > prev.updatedAt) {
                byKey[resolved.compositeKey] = resolved
            }
        }
        for (entry in incoming) {
            if (entry.mediaId.isBlank()) continue
            val resolved = CompletionPolicy.resolveReadingProgress(entry)
            val prev = byKey[resolved.compositeKey]
            if (prev == null || resolved.updatedAt >= prev.updatedAt) {
                byKey[resolved.compositeKey] = resolved
            }
        }
        return byKey.values
            .groupBy { it.siteId }
            .values
            .flatMap { siteList ->
                siteList.sortedByDescending { it.updatedAt }.take(ReadingProgressDao.MAX_PER_SITE)
            }
            .sortedByDescending { it.updatedAt }
    }

    private fun mergeFavorites(
        local: List<FavoriteItem>,
        incoming: List<FavoriteItem>,
    ): List<FavoriteItem> {
        val byKey = linkedMapOf<String, FavoriteItem>()
        for (entry in local) {
            if (entry.targetId.isBlank()) continue
            val prev = byKey[entry.compositeKey]
            if (prev == null || entry.addedAt > prev.addedAt) {
                byKey[entry.compositeKey] = entry
            }
        }
        for (entry in incoming) {
            if (entry.targetId.isBlank()) continue
            val prev = byKey[entry.compositeKey]
            if (prev == null || entry.addedAt >= prev.addedAt) {
                byKey[entry.compositeKey] = entry
            }
        }
        return byKey.values.sortedByDescending { it.addedAt }
    }

    private fun mergeShelvesAndItems(
        localShelves: List<Shelf>,
        localItems: List<ShelfItem>,
        incomingShelves: List<Shelf>,
        incomingItems: List<ShelfItem>,
        now: Long,
    ): Pair<List<Shelf>, List<ShelfItem>> {
        val localShelfMap = localShelves.filter { it.id.isNotBlank() }.associateBy { it.id }
        val incomingShelfMap = incomingShelves.filter { it.id.isNotBlank() }.associateBy { it.id }
        val allShelfIds = (localShelfMap.keys + incomingShelfMap.keys + Shelf.WATCH_LATER_ID).distinct()

        val rawMergedShelves = linkedMapOf<String, Shelf>()
        for (id in allShelfIds) {
            val first = localShelfMap[id]
            val second = incomingShelfMap[id]
            val merged = when {
                first == null && second == null -> Shelf(
                    id = Shelf.WATCH_LATER_ID,
                    name = RavenDatabase.DEFAULT_WATCH_LATER_NAME,
                    isSystem = true,
                    createdAt = now,
                    modifiedAt = now,
                )

                first == null -> second!!.let { s ->
                    if (id == Shelf.WATCH_LATER_ID) s.copy(isSystem = true) else s
                }

                second == null -> first.let { f ->
                    if (id == Shelf.WATCH_LATER_ID) f.copy(isSystem = true) else f
                }

                else -> {
                    val winner = if (second.modifiedAt > first.modifiedAt) second else first
                    val earliestCreated = minPositive(first.createdAt, second.createdAt)
                    val latestModified = maxOf(first.modifiedAt, second.modifiedAt)
                    val isWatchLater = id == Shelf.WATCH_LATER_ID
                    winner.copy(
                        name = if (isWatchLater) first.name.ifBlank { RavenDatabase.DEFAULT_WATCH_LATER_NAME } else winner.name,
                        isSystem = isWatchLater || first.isSystem || second.isSystem,
                        createdAt = earliestCreated,
                        modifiedAt = latestModified,
                    )
                }
            }
            rawMergedShelves[id] = merged
        }

        val localByShelf = localItems
            .filter { it.shelfId in rawMergedShelves && it.mediaId.isNotBlank() }
            .groupBy { it.shelfId }
        val incomingByShelf = incomingItems
            .filter { it.shelfId in rawMergedShelves && it.mediaId.isNotBlank() }
            .groupBy { it.shelfId }

        val mergedItems = mutableListOf<ShelfItem>()
        val finalShelves = mutableListOf<Shelf>()

        for ((shelfId, shelf) in rawMergedShelves) {
            val localOrdered = localByShelf[shelfId].orEmpty()
                .sortedWith(compareBy<ShelfItem> { it.position }.thenBy { it.addedAt })
            val incomingOrdered = incomingByShelf[shelfId].orEmpty()
                .sortedWith(compareBy<ShelfItem> { it.position }.thenBy { it.addedAt })

            val localShelfMod = localShelfMap[shelfId]?.modifiedAt ?: 0L
            val incomingShelfMod = incomingShelfMap[shelfId]?.modifiedAt ?: 0L

            val localByKey = linkedMapOf<String, ShelfItem>()
            for (item in localOrdered) {
                localByKey.putIfAbsent(item.compositeKey, item)
            }
            val incomingByKey = linkedMapOf<String, ShelfItem>()
            for (item in incomingOrdered) {
                incomingByKey.putIfAbsent(item.compositeKey, item)
            }

            val orderedKeys = (localByKey.keys + incomingByKey.keys).distinct()
            val shelfMergedItems = orderedKeys.mapIndexed { index, key ->
                val localItem = localByKey[key]
                val incomingItem = incomingByKey[key]
                val resolved = when {
                    localItem == null -> incomingItem!!
                    incomingItem == null -> localItem
                    else -> mergeCollidingShelfItem(
                        localItem = localItem,
                        incomingItem = incomingItem,
                        localShelfModifiedAt = localShelfMod,
                        incomingShelfModifiedAt = incomingShelfMod,
                    )
                }
                resolved.copy(shelfId = shelfId, position = index)
            }

            mergedItems += shelfMergedItems
            val validKeys = shelfMergedItems.mapTo(mutableSetOf()) { it.compositeKey }
            val cover = shelf.coverCompositeKey?.takeIf { it in validKeys }
            finalShelves += shelf.copy(coverCompositeKey = cover)
        }

        finalShelves.sortWith(
            compareByDescending<Shelf> { it.isPinned }.thenByDescending { it.modifiedAt },
        )
        return finalShelves to mergedItems
    }

    private fun mergeCollidingShelfItem(
        localItem: ShelfItem,
        incomingItem: ShelfItem,
        localShelfModifiedAt: Long,
        incomingShelfModifiedAt: Long,
    ): ShelfItem {
        val earliestAddedAt = minPositive(localItem.addedAt, incomingItem.addedAt)
        val latestState = when {
            incomingShelfModifiedAt > localShelfModifiedAt -> incomingItem
            localShelfModifiedAt > incomingShelfModifiedAt -> localItem
            incomingItem.addedAt > localItem.addedAt -> incomingItem
            localItem.addedAt > incomingItem.addedAt -> localItem
            else -> incomingItem.copy(completed = localItem.completed || incomingItem.completed)
        }
        return latestState.copy(
            addedAt = earliestAddedAt,
            coverUrl = latestState.coverUrl.ifBlank {
                if (latestState === incomingItem) localItem.coverUrl else incomingItem.coverUrl
            },
            durationSeconds = latestState.durationSeconds ?: localItem.durationSeconds ?: incomingItem.durationSeconds,
            pageCount = latestState.pageCount ?: localItem.pageCount ?: incomingItem.pageCount,
        )
    }

    private fun mergeSmartShelves(
        local: List<SmartShelf>,
        incoming: List<SmartShelf>,
    ): List<SmartShelf> {
        val byId = linkedMapOf<String, SmartShelf>()
        for (item in local) {
            if (item.id.isNotBlank()) byId[item.id] = item
        }
        for (item in incoming) {
            if (item.id.isNotBlank()) byId[item.id] = item
        }
        return byId.values.sortedWith(
            compareByDescending<SmartShelf> { it.isPinned }.thenByDescending { it.createdAt },
        )
    }

    private fun mergePageBookmarks(
        local: List<PageBookmark>,
        incoming: List<PageBookmark>,
    ): List<PageBookmark> {
        val byComposite = linkedMapOf<String, PageBookmark>()
        val usedIds = mutableSetOf<String>()

        fun accept(bookmark: PageBookmark, preferOnTie: Boolean) {
            if (bookmark.mediaId.isBlank() || bookmark.page < 1) return
            val key = "${bookmark.siteId.value}:${bookmark.mediaId}:${bookmark.page}"
            val prev = byComposite[key]
            if (prev == null) {
                byComposite[key] = bookmark
            } else {
                val winner = when {
                    bookmark.createdAt > prev.createdAt -> bookmark
                    prev.createdAt > bookmark.createdAt -> prev
                    preferOnTie -> bookmark
                    else -> prev
                }
                val other = if (winner === bookmark) prev else bookmark
                byComposite[key] = winner.copy(
                    note = winner.note.ifBlank { other.note },
                    coverUrl = winner.coverUrl.ifBlank { other.coverUrl },
                    totalPages = maxOf(winner.totalPages, other.totalPages),
                )
            }
        }

        local.forEach { accept(it, preferOnTie = false) }
        incoming.forEach { accept(it, preferOnTie = true) }

        return byComposite.values
            .sortedByDescending { it.createdAt }
            .map { mark ->
                val safeId = mark.id.takeIf { it.isNotBlank() && usedIds.add(it) }
                    ?: UUID.randomUUID().toString().also(usedIds::add)
                if (safeId == mark.id) mark else mark.copy(id = safeId)
            }
    }

    private fun mergeTimeBookmarks(
        local: List<TimeBookmark>,
        incoming: List<TimeBookmark>,
    ): List<TimeBookmark> {
        val byComposite = linkedMapOf<String, TimeBookmark>()
        val usedIds = mutableSetOf<String>()

        fun accept(bookmark: TimeBookmark, preferOnTie: Boolean) {
            if (bookmark.mediaId.isBlank() || bookmark.positionMs < 0L) return
            val key = "${bookmark.siteId.value}:${bookmark.mediaId}:${bookmark.positionMs}"
            val prev = byComposite[key]
            if (prev == null) {
                byComposite[key] = bookmark
            } else {
                val winner = when {
                    bookmark.createdAt > prev.createdAt -> bookmark
                    prev.createdAt > bookmark.createdAt -> prev
                    preferOnTie -> bookmark
                    else -> prev
                }
                val other = if (winner === bookmark) prev else bookmark
                byComposite[key] = winner.copy(
                    label = winner.label.ifBlank { other.label },
                    groupName = winner.groupName.ifBlank { other.groupName },
                    coverUrl = winner.coverUrl.ifBlank { other.coverUrl },
                    durationMs = maxOf(winner.durationMs, other.durationMs),
                )
            }
        }

        local.forEach { accept(it, preferOnTie = false) }
        incoming.forEach { accept(it, preferOnTie = true) }

        return byComposite.values
            .sortedByDescending { it.createdAt }
            .map { mark ->
                val safeId = mark.id.takeIf { it.isNotBlank() && usedIds.add(it) }
                    ?: UUID.randomUUID().toString().also(usedIds::add)
                if (safeId == mark.id) mark else mark.copy(id = safeId)
            }
    }

    private fun mergeImageFavorites(
        local: List<ImageFavorite>,
        incoming: List<ImageFavorite>,
    ): List<ImageFavorite> {
        val byComposite = linkedMapOf<String, ImageFavorite>()
        val usedIds = mutableSetOf<String>()

        fun accept(fav: ImageFavorite, preferOnTie: Boolean) {
            if (fav.mediaId.isBlank() || fav.pageIndex < 0) return
            val key = "${fav.siteId.value}:${fav.mediaId}:${fav.pageIndex}"
            val prev = byComposite[key]
            if (prev == null) {
                byComposite[key] = fav
            } else {
                val winner = when {
                    fav.addedAt > prev.addedAt -> fav
                    prev.addedAt > fav.addedAt -> prev
                    preferOnTie -> fav
                    else -> prev
                }
                val other = if (winner === fav) prev else fav
                byComposite[key] = winner.copy(
                    mediaTitle = winner.mediaTitle.ifBlank { other.mediaTitle },
                    pageUrl = winner.pageUrl.ifBlank { other.pageUrl },
                    imageUrl = winner.imageUrl.ifBlank { other.imageUrl },
                )
            }
        }

        local.forEach { accept(it, preferOnTie = false) }
        incoming.forEach { accept(it, preferOnTie = true) }

        return byComposite.values
            .sortedByDescending { it.addedAt }
            .take(ImageFavoriteDao.MAX_IMAGE_FAVORITES)
            .map { fav ->
                val safeId = fav.id.takeIf { it.isNotBlank() && usedIds.add(it) }
                    ?: UUID.randomUUID().toString().also(usedIds::add)
                if (safeId == fav.id) fav else fav.copy(id = safeId)
            }
    }

    private fun mergeTagSubscriptions(
        local: List<TagSubscription>,
        incoming: List<TagSubscription>,
    ): List<TagSubscription> {
        val bySite = linkedMapOf<String, LinkedHashMap<String, TagSubscription>>()

        fun siteMap(siteId: String) = bySite.getOrPut(siteId) { linkedMapOf() }

        for (sub in local.sortedWith(compareBy<TagSubscription> { it.siteId.value }.thenBy { it.sortOrder }.thenBy { it.createdAt })) {
            if (sub.slug.isBlank()) continue
            val key = "${sub.slug}:${sub.scope.name}"
            siteMap(sub.siteId.value).putIfAbsent(key, sub)
        }
        for (sub in incoming.sortedWith(compareBy<TagSubscription> { it.siteId.value }.thenBy { it.sortOrder }.thenBy { it.createdAt })) {
            if (sub.slug.isBlank()) continue
            val key = "${sub.slug}:${sub.scope.name}"
            val map = siteMap(sub.siteId.value)
            val existing = map[key]
            if (existing == null) {
                map[key] = sub
            } else {
                val preferredLabel = when {
                    existing.label.isBlank() || existing.label == existing.slug ->
                        sub.label.ifBlank { existing.slug }
                    else -> existing.label
                }
                map[key] = existing.copy(
                    label = preferredLabel,
                    url = existing.url.ifBlank { sub.url },
                    createdAt = minPositive(existing.createdAt, sub.createdAt),
                )
            }
        }

        return bySite.values.flatMap { siteSubs ->
            siteSubs.values.mapIndexed { index, sub -> sub.copy(sortOrder = index) }
        }
    }

    private fun mergeSavedSearches(
        local: List<SavedSearch>,
        incoming: List<SavedSearch>,
    ): List<SavedSearch> {
        val byId = linkedMapOf<String, SavedSearch>()
        for (search in local) {
            if (search.id.isNotBlank()) byId[search.id] = search
        }
        for (search in incoming) {
            if (search.id.isBlank()) continue
            val prev = byId[search.id]
            if (prev == null) {
                byId[search.id] = search
            } else {
                val prevStamp = prev.lastCheckedAt ?: prev.createdAt
                val nextStamp = search.lastCheckedAt ?: search.createdAt
                val winner = if (nextStamp >= prevStamp) search else prev
                val latestCheck = listOfNotNull(prev.lastCheckedAt, search.lastCheckedAt).maxOrNull()
                byId[search.id] = winner.copy(
                    createdAt = minPositive(prev.createdAt, search.createdAt),
                    lastCheckedAt = latestCheck,
                )
            }
        }
        return byId.values.sortedByDescending { it.createdAt }
    }

    private fun mergeUpdateInbox(
        local: List<UpdateInboxItem>,
        incoming: List<UpdateInboxItem>,
    ): List<UpdateInboxItem> {
        val byComposite = linkedMapOf<String, UpdateInboxItem>()
        val usedIds = mutableSetOf<String>()

        fun accept(entry: UpdateInboxItem, preferOnTie: Boolean) {
            if (entry.sourceId.isBlank() || entry.item.id.isBlank()) return
            val key = "${entry.sourceKind.name}:${entry.sourceId}:${entry.item.compositeKey}"
            val prev = byComposite[key]
            if (prev == null) {
                byComposite[key] = entry
            } else {
                val winner = when {
                    entry.discoveredAt > prev.discoveredAt -> entry
                    prev.discoveredAt > entry.discoveredAt -> prev
                    preferOnTie -> entry
                    else -> prev
                }
                byComposite[key] = winner.copy(
                    isRead = prev.isRead || entry.isRead,
                )
            }
        }

        local.forEach { accept(it, preferOnTie = false) }
        incoming.forEach { accept(it, preferOnTie = true) }

        return byComposite.values
            .sortedByDescending { it.discoveredAt }
            .map { entry ->
                val safeId = entry.id.takeIf { it.isNotBlank() && usedIds.add(it) }
                    ?: UUID.randomUUID().toString().also(usedIds::add)
                if (safeId == entry.id) entry else entry.copy(id = safeId)
            }
    }

    private fun mergeBlockedTerms(
        local: List<BlockedTerm>,
        incoming: List<BlockedTerm>,
    ): List<BlockedTerm> {
        val byKey = linkedMapOf<String, BlockedTerm>()
        fun accept(entry: BlockedTerm) {
            val normalized = ContentFilter.normalizeTerm(entry.term)
            if (normalized.isEmpty()) return
            val key = "${entry.siteId.value}:$normalized"
            val prev = byKey[key]
            if (prev == null) {
                byKey[key] = entry.copy(term = normalized)
            } else {
                byKey[key] = prev.copy(createdAt = minPositive(prev.createdAt, entry.createdAt))
            }
        }
        local.forEach(::accept)
        incoming.forEach(::accept)
        return byKey.values.sortedWith(
            compareBy<BlockedTerm> { it.siteId.value }.thenByDescending { it.createdAt },
        )
    }

    private fun mergeSearchHistory(
        local: List<SearchHistoryEntry>,
        incoming: List<SearchHistoryEntry>,
    ): List<SearchHistoryEntry> {
        val byKey = linkedMapOf<String, SearchHistoryEntry>()
        fun accept(entry: SearchHistoryEntry, preferOnTie: Boolean) {
            val query = entry.query.trim()
            if (query.isEmpty()) return
            val normalized = entry.copy(query = query)
            val key = "${normalized.siteId.value}:${normalized.scope.name}:$query"
            val prev = byKey[key]
            if (prev == null || normalized.searchedAt > prev.searchedAt ||
                (preferOnTie && normalized.searchedAt == prev.searchedAt)
            ) {
                byKey[key] = normalized
            }
        }
        local.forEach { accept(it, preferOnTie = false) }
        incoming.forEach { accept(it, preferOnTie = true) }

        return byKey.values
            .groupBy { it.siteId }
            .values
            .flatMap { entries ->
                entries.sortedByDescending { it.searchedAt }.take(SearchHistoryDao.MAX_PER_SITE)
            }
            .sortedByDescending { it.searchedAt }
    }

    private fun mergePlaybackPreferences(
        local: List<PlaybackPreferences>,
        incoming: List<PlaybackPreferences>,
    ): List<PlaybackPreferences> {
        val bySite = linkedMapOf<String, PlaybackPreferences>()
        for (pref in local) {
            val clean = pref.sanitized()
            bySite[clean.siteId?.value ?: "*"] = clean
        }
        for (pref in incoming) {
            val clean = pref.sanitized()
            bySite[clean.siteId?.value ?: "*"] = clean
        }
        return bySite.values.toList()
    }

    private fun mergeReaderPreferences(
        local: List<ReaderPreferences>,
        incoming: List<ReaderPreferences>,
    ): List<ReaderPreferences> {
        val byKey = linkedMapOf<String, ReaderPreferences>()
        for (pref in local) {
            val clean = pref.sanitized()
            val key = "${clean.siteId?.value ?: "*"}:${clean.mediaId.orEmpty()}"
            byKey[key] = clean
        }
        for (pref in incoming) {
            val clean = pref.sanitized()
            val key = "${clean.siteId?.value ?: "*"}:${clean.mediaId.orEmpty()}"
            byKey[key] = clean
        }
        return byKey.values.toList()
    }

    private fun mergeCustomTagTranslations(
        local: List<TagTranslationEntry>,
        incoming: List<TagTranslationEntry>,
    ): List<TagTranslationEntry> {
        val byKey = linkedMapOf<Pair<String, String>, TagTranslationEntry>()
        fun accept(entry: TagTranslationEntry, preferOnTie: Boolean) {
            val (embeddedNs, barePart) = TagDictionary.splitNamespace(entry.rawSlug)
            val slug = TagDictionary.normalizeBareSlug(barePart)
            val ns = TagDictionary.normalizeNamespace(entry.namespace).ifEmpty { embeddedNs }
            val zh = entry.chineseName.trim()
            if (slug.isEmpty() || zh.isEmpty()) return
            val normalized = entry.copy(
                namespace = ns,
                rawSlug = slug,
                chineseName = zh,
                intro = entry.intro.trim(),
                source = TagTranslationSource.USER_CUSTOM,
                updatedAt = entry.updatedAt.coerceAtLeast(0L),
            )
            val key = ns to slug
            val prev = byKey[key]
            if (prev == null || normalized.updatedAt > prev.updatedAt ||
                (preferOnTie && normalized.updatedAt == prev.updatedAt)
            ) {
                byKey[key] = normalized
            }
        }
        local.forEach { accept(it, preferOnTie = false) }
        incoming.forEach { accept(it, preferOnTie = true) }
        return byKey.values.sortedWith(
            compareByDescending<TagTranslationEntry> { it.updatedAt }
                .thenBy { it.namespace }
                .thenBy { it.rawSlug },
        )
    }

    private fun filterPortableSettings(settings: Map<String, String>): Map<String, String> =
        settings.filterKeys { it in SettingsDataStore.PORTABLE_BACKUP_KEYS }

    private fun minPositive(a: Long, b: Long): Long = when {
        a > 0L && b > 0L -> minOf(a, b)
        a > 0L -> a
        b > 0L -> b
        else -> 0L
    }
}
