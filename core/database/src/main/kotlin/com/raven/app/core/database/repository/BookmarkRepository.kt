package com.raven.app.core.database.repository

import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.ImageFavoriteDao
import com.raven.app.core.database.dao.PageBookmarkDao
import com.raven.app.core.database.dao.TimeBookmarkDao
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TimeBookmark
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages comic/photo page bookmarks, video time bookmarks, and single-page image favorites
 * (capped at 20,000 globally).
 */
class BookmarkRepository(
    private val pageBookmarkDao: PageBookmarkDao,
    private val timeBookmarkDao: TimeBookmarkDao,
    private val imageFavoriteDao: ImageFavoriteDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val maxImageFavorites: Int = ImageFavoriteDao.MAX_IMAGE_FAVORITES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ---- Page bookmarks ----

    fun observePageBookmarks(): Flow<List<PageBookmark>> =
        pageBookmarkDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observePageBookmarks(siteId: SiteId, mediaId: String): Flow<List<PageBookmark>> =
        pageBookmarkDao.observeByMedia(siteId.value, mediaId).map { rows -> rows.map { it.toDomain() } }

    suspend fun savePageBookmark(bookmark: PageBookmark): PageBookmark {
        require(bookmark.mediaId.isNotBlank()) { "PageBookmark.mediaId must not be blank" }
        require(bookmark.page >= 1) { "PageBookmark.page must be >= 1" }
        return transactionRunner {
            val existing = pageBookmarkDao.getByMediaAndPage(
                bookmark.siteId.value,
                bookmark.mediaId,
                bookmark.page,
            )
            val resolvedId = existing?.id
                ?: bookmark.id.trim().ifBlank { UUID.randomUUID().toString() }
            val resolved = bookmark.copy(
                id = resolvedId,
                createdAt = bookmark.createdAt.takeIf { it > 0L } ?: clock(),
            )
            pageBookmarkDao.upsert(resolved.toEntity())
            resolved
        }
    }

    /**
     * Toggles a bookmark on `(siteId, mediaId, page)`. Returns `true` if added, `false` if removed.
     */
    suspend fun togglePageBookmark(bookmark: PageBookmark): Boolean {
        require(bookmark.mediaId.isNotBlank()) { "PageBookmark.mediaId must not be blank" }
        require(bookmark.page >= 1) { "PageBookmark.page must be >= 1" }
        return transactionRunner {
            val existing = pageBookmarkDao.getByMediaAndPage(
                bookmark.siteId.value,
                bookmark.mediaId,
                bookmark.page,
            )
            if (existing != null) {
                pageBookmarkDao.deleteById(existing.id)
                false
            } else {
                val prepared = bookmark.copy(
                    id = bookmark.id.trim().ifBlank { UUID.randomUUID().toString() },
                    createdAt = bookmark.createdAt.takeIf { it > 0L } ?: clock(),
                )
                pageBookmarkDao.upsert(prepared.toEntity())
                true
            }
        }
    }

    suspend fun deletePageBookmark(id: String) {
        pageBookmarkDao.deleteById(id)
    }

    suspend fun deletePageBookmarksForMedia(siteId: SiteId, mediaId: String) {
        pageBookmarkDao.deleteByMedia(siteId.value, mediaId)
    }

    // ---- Time bookmarks ----

    fun observeTimeBookmarks(): Flow<List<TimeBookmark>> =
        timeBookmarkDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeTimeBookmarks(siteId: SiteId, mediaId: String): Flow<List<TimeBookmark>> =
        timeBookmarkDao.observeByMedia(siteId.value, mediaId).map { rows -> rows.map { it.toDomain() } }

    suspend fun saveTimeBookmark(bookmark: TimeBookmark): TimeBookmark {
        require(bookmark.mediaId.isNotBlank()) { "TimeBookmark.mediaId must not be blank" }
        require(bookmark.positionMs >= 0L) { "TimeBookmark.positionMs must be >= 0" }
        require(bookmark.label.length <= MAX_TIME_BOOKMARK_LABEL_LENGTH) {
            "TimeBookmark.label exceeds $MAX_TIME_BOOKMARK_LABEL_LENGTH characters"
        }
        require(bookmark.groupName.length <= MAX_TIME_BOOKMARK_GROUP_LENGTH) {
            "TimeBookmark.groupName exceeds $MAX_TIME_BOOKMARK_GROUP_LENGTH characters"
        }
        val cleanId = bookmark.id.trim()
        return transactionRunner {
            val existing = if (cleanId.isNotEmpty()) timeBookmarkDao.getById(cleanId) else null
            // A label edit on an existing row refreshes `createdAt`: the merge uses it as the
            // edit ordering, so a re-exported backup carries the edited copy's timestamp and wins
            // over an older backup of the same bookmark.
            val isLabelEdit = existing != null &&
                (existing.label != bookmark.label || existing.groupName != bookmark.groupName)
            val prepared = bookmark.copy(
                id = cleanId.ifBlank { UUID.randomUUID().toString() },
                createdAt = when {
                    isLabelEdit -> clock()
                    bookmark.createdAt > 0L -> bookmark.createdAt
                    else -> clock()
                },
            )
            timeBookmarkDao.upsert(prepared.toEntity())
            prepared
        }
    }

    suspend fun deleteTimeBookmark(id: String) {
        timeBookmarkDao.deleteById(id)
    }

    suspend fun deleteTimeBookmarksForMedia(siteId: SiteId, mediaId: String) {
        timeBookmarkDao.deleteByMedia(siteId.value, mediaId)
    }

    // ---- Image favorites (capped at 20,000) ----

    fun observeImageFavorites(): Flow<List<ImageFavorite>> =
        imageFavoriteDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeImageFavoritesBySite(siteId: SiteId): Flow<List<ImageFavorite>> =
        imageFavoriteDao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }

    fun observeImageFavoritesForMedia(siteId: SiteId, mediaId: String): Flow<List<ImageFavorite>> =
        imageFavoriteDao.observeByMedia(siteId.value, mediaId).map { rows -> rows.map { it.toDomain() } }

    fun observeIsImageFavorite(siteId: SiteId, mediaId: String, pageIndex: Int): Flow<Boolean> =
        imageFavoriteDao.observeExists(siteId.value, mediaId, pageIndex)

    suspend fun saveImageFavorite(favorite: ImageFavorite): ImageFavorite {
        require(favorite.mediaId.isNotBlank()) { "ImageFavorite.mediaId must not be blank" }
        require(favorite.pageIndex >= 0) { "ImageFavorite.pageIndex must be >= 0" }
        val normalizedPage = favorite.pageIndex.coerceAtLeast(1)
        return transactionRunner {
            val existing = imageFavoriteDao.getByMediaAndPage(
                favorite.siteId.value,
                favorite.mediaId,
                normalizedPage,
            )
            val prepared = favorite.copy(
                id = existing?.id ?: favorite.id.trim().ifBlank { UUID.randomUUID().toString() },
                pageIndex = normalizedPage,
                addedAt = favorite.addedAt.takeIf { it > 0L } ?: clock(),
            )
            imageFavoriteDao.upsert(prepared.toEntity())
            imageFavoriteDao.prune(maxImageFavorites)
            prepared
        }
    }

    /**
     * Toggles a single-page image favorite by `(siteId, mediaId, pageIndex)`.
     * Returns `true` if added, `false` if removed.
     */
    suspend fun toggleImageFavorite(favorite: ImageFavorite): Boolean {
        require(favorite.mediaId.isNotBlank()) { "ImageFavorite.mediaId must not be blank" }
        require(favorite.pageIndex >= 0) { "ImageFavorite.pageIndex must be >= 0" }
        val normalizedPage = favorite.pageIndex.coerceAtLeast(1)
        return transactionRunner {
            val existing = imageFavoriteDao.getByMediaAndPage(
                favorite.siteId.value,
                favorite.mediaId,
                normalizedPage,
            )
            if (existing != null) {
                imageFavoriteDao.deleteById(existing.id)
                false
            } else {
                val prepared = favorite.copy(
                    id = favorite.id.trim().ifBlank { UUID.randomUUID().toString() },
                    pageIndex = normalizedPage,
                    addedAt = favorite.addedAt.takeIf { it > 0L } ?: clock(),
                )
                imageFavoriteDao.upsert(prepared.toEntity())
                imageFavoriteDao.prune(maxImageFavorites)
                true
            }
        }
    }

    suspend fun deleteImageFavorite(id: String) {
        imageFavoriteDao.deleteById(id)
    }

    suspend fun clearImageFavorites() {
        imageFavoriteDao.deleteAll()
    }

    companion object {
        const val MAX_TIME_BOOKMARK_LABEL_LENGTH: Int = 500
        const val MAX_TIME_BOOKMARK_GROUP_LENGTH: Int = 80
    }
}
