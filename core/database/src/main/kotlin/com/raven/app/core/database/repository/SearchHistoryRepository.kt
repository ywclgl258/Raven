package com.raven.app.core.database.repository

import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.SearchHistoryDao
import com.raven.app.core.database.entity.SearchHistoryEntity
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages per-site recent search history, automatically capped at 30 entries per site.
 */
class SearchHistoryRepository(
    private val searchHistoryDao: SearchHistoryDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val maxEntriesPerSite: Int = SearchHistoryDao.MAX_PER_SITE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeBySite(
        siteId: SiteId,
        limit: Int = maxEntriesPerSite,
    ): Flow<List<SearchHistoryEntry>> =
        searchHistoryDao.observeBySite(siteId.value, limit)
            .map { rows -> rows.map { it.toDomain() } }

    fun observeAll(limit: Int = maxEntriesPerSite): Flow<List<SearchHistoryEntry>> =
        searchHistoryDao.observeAll(limit)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun recordSearch(
        siteId: SiteId,
        query: String,
        scope: SearchScope = SearchScope.ALL,
    ): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        transactionRunner {
            searchHistoryDao.upsert(
                SearchHistoryEntity(
                    siteId = siteId.value,
                    query = trimmed,
                    scope = scope,
                    searchedAt = clock(),
                ),
            )
            searchHistoryDao.pruneSite(siteId.value, maxEntriesPerSite)
        }
        return true
    }

    suspend fun removeEntry(siteId: SiteId, query: String, scope: SearchScope) {
        searchHistoryDao.delete(siteId.value, query.trim(), scope)
    }

    suspend fun clearSite(siteId: SiteId) {
        searchHistoryDao.deleteBySite(siteId.value)
    }

    suspend fun clearAll() {
        searchHistoryDao.deleteAll()
    }
}
