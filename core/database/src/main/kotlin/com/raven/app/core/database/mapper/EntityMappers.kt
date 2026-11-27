package com.raven.app.core.database.mapper

import java.util.Locale
import com.raven.app.core.database.converter.RavenTypeConverters
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.entity.BrowsePositionEntity
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.ImageFavoriteEntity
import com.raven.app.core.database.entity.MediaLikeEntity
import com.raven.app.core.database.entity.PageBookmarkEntity
import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.database.entity.ReadingProgressEntity
import com.raven.app.core.database.entity.SavedSearchEntity
import com.raven.app.core.database.entity.SearchHistoryEntity
import com.raven.app.core.database.entity.ShelfEntity
import com.raven.app.core.database.entity.ShelfItemEntity
import com.raven.app.core.database.entity.SmartShelfEntity
import com.raven.app.core.database.entity.TagSubscriptionEntity
import com.raven.app.core.database.entity.TagTranslationEntity
import com.raven.app.core.database.entity.TimeBookmarkEntity
import com.raven.app.core.database.entity.UpdateInboxEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.database.model.BrowsePosition
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.LikedMedia
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.PlaybackPreferences
import com.raven.app.core.model.ReaderPreferences
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.TagTranslationEntry
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.model.workId

fun WatchProgress.toEntity(): WatchProgressEntity = WatchProgressEntity(
    siteId = siteId.value,
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    positionMs = positionMs.coerceAtLeast(0L),
    durationMs = durationMs.coerceAtLeast(0L),
    updatedAt = updatedAt,
    completed = completed,
    quality = quality,
)

fun WatchProgressEntity.toDomain(): WatchProgress = WatchProgress(
    siteId = SiteId(siteId),
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    completed = completed,
    quality = quality,
)

fun ReadingProgress.toEntity(): ReadingProgressEntity = ReadingProgressEntity(
    siteId = siteId.value,
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    kind = kind,
    currentPage = currentPage.coerceAtLeast(0),
    totalPages = totalPages.coerceAtLeast(0),
    scrollOffsetPx = scrollOffsetPx,
    updatedAt = updatedAt,
    completed = completed,
    seriesId = seriesId ?: workId.takeIf { it != mediaId },
    seriesTitle = seriesTitle,
    seriesUrl = seriesUrl,
)

fun ReadingProgressEntity.toDomain(): ReadingProgress = ReadingProgress(
    siteId = SiteId(siteId),
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    kind = kind,
    currentPage = currentPage,
    totalPages = totalPages,
    scrollOffsetPx = scrollOffsetPx,
    updatedAt = updatedAt,
    completed = completed,
    seriesId = seriesId,
    seriesTitle = seriesTitle,
    seriesUrl = seriesUrl,
)

fun FavoriteItem.toEntity(): FavoriteEntity = FavoriteEntity(
    siteId = siteId.value,
    kind = kind,
    targetId = targetId,
    title = title,
    url = url,
    coverUrl = coverUrl,
    addedAt = addedAt,
    extra = extra,
)

fun FavoriteEntity.toDomain(): FavoriteItem = FavoriteItem(
    siteId = SiteId(siteId),
    targetId = targetId,
    kind = kind,
    title = title,
    url = url,
    coverUrl = coverUrl,
    addedAt = addedAt,
    extra = extra,
)

fun LikedMedia.toEntity(): MediaLikeEntity = MediaLikeEntity(
    siteId = siteId.value,
    mediaId = mediaId,
    kind = kind,
    title = title,
    url = url,
    coverUrl = coverUrl,
    authorName = authorName,
    tagsJson = RavenTypeConverters.encodeTagRefList(tags),
    likedAt = likedAt,
)

fun MediaLikeEntity.toDomain(): LikedMedia = LikedMedia(
    siteId = SiteId(siteId),
    mediaId = mediaId,
    kind = kind,
    title = title,
    url = url,
    coverUrl = coverUrl,
    authorName = authorName,
    tags = RavenTypeConverters.decodeTagRefList(tagsJson),
    likedAt = likedAt,
)

fun Shelf.toEntity(): ShelfEntity = ShelfEntity(
    id = id,
    name = name,
    note = note,
    isPinned = isPinned,
    isSystem = isSystem,
    coverCompositeKey = coverCompositeKey?.takeIf(String::isNotBlank),
    createdAt = createdAt,
    modifiedAt = modifiedAt,
)

fun ShelfEntity.toDomain(): Shelf = Shelf(
    id = id,
    name = name,
    note = note,
    isPinned = isPinned,
    isSystem = isSystem,
    coverCompositeKey = coverCompositeKey,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
)

fun ShelfItem.toEntity(): ShelfItemEntity = ShelfItemEntity(
    shelfId = shelfId,
    siteId = siteId.value,
    mediaId = mediaId,
    url = url,
    kind = kind,
    title = title,
    coverUrl = coverUrl,
    position = position,
    addedAt = addedAt,
    completed = completed,
    completionChangedAt = completionChangedAt,
    durationSeconds = durationSeconds,
    pageCount = pageCount,
)

fun ShelfItemEntity.toDomain(): ShelfItem = ShelfItem(
    shelfId = shelfId,
    siteId = SiteId(siteId),
    mediaId = mediaId,
    url = url,
    kind = kind,
    title = title,
    coverUrl = coverUrl,
    position = position,
    addedAt = addedAt,
    completed = completed,
    completionChangedAt = completionChangedAt,
    durationSeconds = durationSeconds,
    pageCount = pageCount,
)

fun SmartShelf.toEntity(): SmartShelfEntity = SmartShelfEntity(
    id = id,
    name = name,
    rule = rule,
    mediaKindFilter = mediaKindFilter,
    siteIdFilter = siteIdFilter?.value,
    isPinned = isPinned,
    createdAt = createdAt,
)

fun SmartShelfEntity.toDomain(): SmartShelf = SmartShelf(
    id = id,
    name = name,
    rule = rule,
    mediaKindFilter = mediaKindFilter,
    siteIdFilter = siteIdFilter?.takeIf(String::isNotBlank)?.let(::SiteId),
    isPinned = isPinned,
    createdAt = createdAt,
)

fun PageBookmark.toEntity(): PageBookmarkEntity = PageBookmarkEntity(
    id = id,
    siteId = siteId.value,
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    page = page,
    totalPages = totalPages,
    scrollOffsetPx = scrollOffsetPx,
    note = note,
    createdAt = createdAt,
)

fun PageBookmarkEntity.toDomain(): PageBookmark = PageBookmark(
    id = id,
    siteId = SiteId(siteId),
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    page = page,
    totalPages = totalPages,
    scrollOffsetPx = scrollOffsetPx,
    note = note,
    createdAt = createdAt,
)

fun TimeBookmark.toEntity(): TimeBookmarkEntity = TimeBookmarkEntity(
    id = id,
    siteId = siteId.value,
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    positionMs = positionMs.coerceAtLeast(0L),
    durationMs = durationMs.coerceAtLeast(0L),
    label = label,
    groupName = groupName,
    createdAt = createdAt,
)

fun TimeBookmarkEntity.toDomain(): TimeBookmark = TimeBookmark(
    id = id,
    siteId = SiteId(siteId),
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    positionMs = positionMs,
    durationMs = durationMs,
    label = label,
    groupName = groupName,
    createdAt = createdAt,
)

fun ImageFavorite.toEntity(): ImageFavoriteEntity = ImageFavoriteEntity(
    id = id,
    siteId = siteId.value,
    mediaId = mediaId,
    mediaTitle = mediaTitle,
    pageUrl = pageUrl,
    imageUrl = imageUrl,
    pageIndex = pageIndex,
    addedAt = addedAt,
)

fun ImageFavoriteEntity.toDomain(): ImageFavorite = ImageFavorite(
    id = id,
    siteId = SiteId(siteId),
    mediaId = mediaId,
    mediaTitle = mediaTitle,
    pageUrl = pageUrl,
    imageUrl = imageUrl,
    pageIndex = pageIndex,
    addedAt = addedAt,
)

fun TagSubscription.toEntity(): TagSubscriptionEntity = TagSubscriptionEntity(
    siteId = siteId.value,
    slug = slug,
    label = label.ifBlank { slug },
    url = url,
    scope = scope,
    sortOrder = sortOrder,
    createdAt = createdAt,
)

fun TagSubscriptionEntity.toDomain(): TagSubscription = TagSubscription(
    siteId = SiteId(siteId),
    slug = slug,
    label = label,
    url = url,
    scope = scope,
    sortOrder = sortOrder,
    createdAt = createdAt,
)

fun SavedSearch.toEntity(): SavedSearchEntity = SavedSearchEntity(
    id = id,
    name = name,
    query = query,
    scope = scope,
    siteIds = siteIds.map { it.value },
    mediaKind = mediaKind,
    sort = sort,
    language = language,
    filters = filters,
    createdAt = createdAt,
    lastCheckedAt = lastCheckedAt,
)

fun SavedSearchEntity.toDomain(): SavedSearch = SavedSearch(
    id = id,
    name = name,
    query = query,
    scope = scope,
    siteIds = siteIds.mapNotNull { token -> token.trim().takeIf(String::isNotEmpty)?.let(SiteId::of) },
    mediaKind = mediaKind,
    sort = sort,
    language = language,
    filters = filters,
    createdAt = createdAt,
    lastCheckedAt = lastCheckedAt,
)

fun UpdateInboxItem.toEntity(): UpdateInboxEntity = UpdateInboxEntity(
    id = id,
    sourceKind = sourceKind,
    sourceId = sourceId,
    sourceLabel = sourceLabel,
    siteId = item.siteId.value,
    mediaId = item.id,
    item = item,
    discoveredAt = discoveredAt,
    isRead = isRead,
)

fun UpdateInboxEntity.toDomain(): UpdateInboxItem = UpdateInboxItem(
    id = id,
    sourceKind = sourceKind,
    sourceId = sourceId,
    sourceLabel = sourceLabel,
    item = item,
    discoveredAt = discoveredAt,
    isRead = isRead,
)

fun BlockedTerm.toEntity(): BlockedTermEntity = BlockedTermEntity(
    siteId = siteId.value,
    term = term,
    createdAt = createdAt,
)

fun BlockedTermEntity.toDomain(): BlockedTerm = BlockedTerm(
    siteId = SiteId(siteId),
    term = term,
    createdAt = createdAt,
)

fun SearchHistoryEntry.toEntity(): SearchHistoryEntity = SearchHistoryEntity(
    siteId = siteId.value,
    query = query,
    scope = scope,
    searchedAt = searchedAt,
)

fun SearchHistoryEntity.toDomain(): SearchHistoryEntry = SearchHistoryEntry(
    siteId = SiteId(siteId),
    query = query,
    scope = scope,
    searchedAt = searchedAt,
)

fun DownloadItem.toEntity(): DownloadItemEntity = DownloadItemEntity(
    id = id,
    siteId = siteId.value,
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    kind = kind,
    status = status,
    quality = quality,
    groupName = groupName,
    progressFraction = progressFraction.coerceIn(0f, 1f),
    completedUnits = completedUnits.coerceAtLeast(0),
    totalUnits = totalUnits.coerceAtLeast(0),
    downloadedBytes = downloadedBytes.coerceAtLeast(0L),
    totalBytes = totalBytes?.coerceAtLeast(0L),
    selectedPages = selectedPages,
    errorCategory = errorCategory,
    errorMessage = errorMessage,
    retryAtEpochMs = retryAtEpochMs,
    priority = priority,
    storageLocation = storageLocation,
    localPath = localPath,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun DownloadItemEntity.toDomain(): DownloadItem = DownloadItem(
    id = id,
    siteId = SiteId(siteId),
    mediaId = mediaId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    kind = kind,
    status = status,
    quality = quality,
    groupName = groupName,
    progressFraction = progressFraction,
    completedUnits = completedUnits,
    totalUnits = totalUnits,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    selectedPages = selectedPages,
    errorCategory = errorCategory,
    errorMessage = errorMessage,
    retryAtEpochMs = retryAtEpochMs,
    priority = priority,
    storageLocation = storageLocation,
    localPath = localPath,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun PlaybackPreferences.sanitized(): PlaybackPreferences = copy(
    defaultSpeed = defaultSpeed.coerceIn(0.25f, 3.0f),
    longPressSpeed = longPressSpeed.coerceIn(1.0f, 4.0f),
    seekStepSeconds = seekStepSeconds.coerceIn(5, 60),
    gestureSensitivitySeconds = gestureSensitivitySeconds.coerceIn(30, 600),
    preferredQuality = preferredQuality.trim().ifBlank { "auto" },
)

fun PlaybackPreferences.toEntity(): PlaybackPreferencesEntity {
    val clean = sanitized()
    return PlaybackPreferencesEntity(
        siteId = clean.siteId?.value ?: PlaybackPreferencesEntity.GLOBAL_SITE_ID,
        defaultSpeed = clean.defaultSpeed,
        longPressSpeed = clean.longPressSpeed,
        seekStepSeconds = clean.seekStepSeconds,
        gestureSensitivitySeconds = clean.gestureSensitivitySeconds,
        preferredQuality = clean.preferredQuality,
        autoFullscreen = clean.autoFullscreen,
        autoDownload = clean.autoDownload,
    )
}

fun PlaybackPreferencesEntity.toDomain(): PlaybackPreferences = PlaybackPreferences(
    siteId = siteId.takeUnless { it == PlaybackPreferencesEntity.GLOBAL_SITE_ID || it.isBlank() }
        ?.let(::SiteId),
    defaultSpeed = defaultSpeed,
    longPressSpeed = longPressSpeed,
    seekStepSeconds = seekStepSeconds,
    gestureSensitivitySeconds = gestureSensitivitySeconds,
    preferredQuality = preferredQuality,
    autoFullscreen = autoFullscreen,
    autoDownload = autoDownload,
)

fun ReaderPreferences.sanitized(): ReaderPreferences = copy(
    mediaId = mediaId?.takeIf(String::isNotBlank),
    cropPercent = cropPercent.coerceIn(0, 20),
    brightnessPercent = brightnessPercent.coerceIn(-1, 100),
)

fun ReaderPreferences.toEntity(): ReaderPreferencesEntity {
    val clean = sanitized()
    return ReaderPreferencesEntity(
        siteId = clean.siteId?.value ?: ReaderPreferencesEntity.GLOBAL_SITE_ID,
        mediaId = clean.mediaId ?: ReaderPreferencesEntity.SITE_DEFAULT_MEDIA_ID,
        mode = clean.mode,
        fit = clean.fit,
        startPosition = clean.startPosition,
        cropPercent = clean.cropPercent,
        combineCover = clean.combineCover,
        preferOriginalImages = clean.preferOriginalImages,
        volumeKeyPaging = clean.volumeKeyPaging,
        reverseVolumeKeys = clean.reverseVolumeKeys,
        keepScreenOn = clean.keepScreenOn,
        brightnessPercent = clean.brightnessPercent,
    )
}

fun ReaderPreferencesEntity.toDomain(): ReaderPreferences = ReaderPreferences(
    siteId = siteId.takeUnless { it == ReaderPreferencesEntity.GLOBAL_SITE_ID || it.isBlank() }
        ?.let(::SiteId),
    mediaId = mediaId.takeIf(String::isNotBlank),
    mode = mode,
    fit = fit,
    startPosition = startPosition,
    cropPercent = cropPercent,
    combineCover = combineCover,
    preferOriginalImages = preferOriginalImages,
    volumeKeyPaging = volumeKeyPaging,
    reverseVolumeKeys = reverseVolumeKeys,
    keepScreenOn = keepScreenOn,
    brightnessPercent = brightnessPercent,
)

fun BrowsePosition.toEntity(): BrowsePositionEntity = BrowsePositionEntity(
    positionKey = positionKey,
    siteId = siteId?.value.orEmpty(),
    workId = workId,
    throughPage = throughPage.coerceAtLeast(1),
    shelfId = shelfId,
    updatedAt = updatedAt,
)

fun BrowsePositionEntity.toDomain(): BrowsePosition = BrowsePosition(
    positionKey = positionKey,
    siteId = siteId.takeIf(String::isNotBlank)?.let(::SiteId),
    workId = workId,
    throughPage = throughPage,
    shelfId = shelfId,
    updatedAt = updatedAt,
)

fun TagTranslationEntry.toEntity(): TagTranslationEntity = TagTranslationEntity(
    namespace = namespace.trim().lowercase(Locale.ROOT),
    rawSlug = rawSlug.trim().lowercase(Locale.ROOT),
    source = source,
    chineseName = chineseName.trim(),
    intro = intro.trim(),
    updatedAt = updatedAt.coerceAtLeast(0L),
)

fun TagTranslationEntity.toDomain(): TagTranslationEntry = TagTranslationEntry(
    namespace = namespace,
    rawSlug = rawSlug,
    chineseName = chineseName,
    intro = intro,
    source = source,
    updatedAt = updatedAt,
)
