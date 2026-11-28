package com.raven.app.core.database

import com.raven.app.core.database.dao.ImageFavoriteDao
import com.raven.app.core.database.dao.PageBookmarkDao
import com.raven.app.core.database.dao.TimeBookmarkDao
import com.raven.app.core.database.entity.ImageFavoriteEntity
import com.raven.app.core.database.entity.PageBookmarkEntity
import com.raven.app.core.database.entity.TimeBookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class FakePageBookmarkDao : PageBookmarkDao {
    val rows = MutableStateFlow<Map<String, PageBookmarkEntity>>(emptyMap())

    override fun observeAll(): Flow<List<PageBookmarkEntity>> =
        rows.map { it.values.sortedByDescending(PageBookmarkEntity::createdAt) }

    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<PageBookmarkEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.page } }

    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, page: Int): PageBookmarkEntity? =
        rows.value.values.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.page == page }

    override suspend fun getAll(): List<PageBookmarkEntity> =
        rows.value.values.sortedByDescending(PageBookmarkEntity::createdAt)

    override suspend fun upsert(entity: PageBookmarkEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<PageBookmarkEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.value = rows.value.filterValues { !(it.siteId == siteId && it.mediaId == mediaId) }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeTimeBookmarkDao : TimeBookmarkDao {
    val rows = MutableStateFlow<Map<String, TimeBookmarkEntity>>(emptyMap())

    override fun observeAll(): Flow<List<TimeBookmarkEntity>> =
        rows.map { it.values.sortedByDescending(TimeBookmarkEntity::createdAt) }

    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<TimeBookmarkEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.positionMs } }

    override suspend fun getAll(): List<TimeBookmarkEntity> =
        rows.value.values.sortedByDescending(TimeBookmarkEntity::createdAt)

    override suspend fun getById(id: String): TimeBookmarkEntity? = rows.value[id]

    override suspend fun upsert(entity: TimeBookmarkEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<TimeBookmarkEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.value = rows.value.filterValues { !(it.siteId == siteId && it.mediaId == mediaId) }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeImageFavoriteDao : ImageFavoriteDao {
    val rows = MutableStateFlow<Map<String, ImageFavoriteEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ImageFavoriteEntity>> =
        rows.map { it.values.sortedByDescending(ImageFavoriteEntity::addedAt) }

    override fun observeBySite(siteId: String): Flow<List<ImageFavoriteEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId }.sortedByDescending(ImageFavoriteEntity::addedAt) }

    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<ImageFavoriteEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.pageIndex } }

    override fun observeExists(siteId: String, mediaId: String, pageIndex: Int): Flow<Boolean> =
        rows.map { it.values.any { e -> e.siteId == siteId && e.mediaId == mediaId && e.pageIndex == pageIndex } }

    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, pageIndex: Int): ImageFavoriteEntity? =
        rows.value.values.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.pageIndex == pageIndex }

    override suspend fun getAll(): List<ImageFavoriteEntity> =
        rows.value.values.sortedByDescending(ImageFavoriteEntity::addedAt)

    override suspend fun upsert(entity: ImageFavoriteEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<ImageFavoriteEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override suspend fun prune(limit: Int) {
        val keep = rows.value.values.sortedByDescending { it.addedAt }.take(limit).mapTo(mutableSetOf()) { it.id }
        rows.value = rows.value.filterKeys { it in keep }
    }
}
