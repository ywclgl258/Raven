package com.raven.app.core.database.repository

import com.raven.app.core.common.ContentFilter
import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.BlockedTermDao
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Manages global (`SiteId.GLOBAL`) and per-site content blocklists normalized via
 * [ContentFilter.normalizeTerm].
 */
class BlockedTermsRepository(
    private val blockedTermDao: BlockedTermDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<BlockedTerm>> =
        blockedTermDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeBySite(siteId: SiteId): Flow<List<BlockedTerm>> =
        blockedTermDao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }

    fun observeTermsBySite(siteId: SiteId): Flow<List<String>> =
        observeBySite(siteId).map { list -> list.map { it.term } }

    fun observeGlobalTerms(): Flow<List<String>> =
        observeTermsBySite(SiteId.GLOBAL)

    /**
     * Reactive union of global (`SiteId.GLOBAL`) and site-specific blocked terms for [siteId].
     */
    fun observeEffectiveTermsBySite(siteId: SiteId): Flow<List<String>> =
        if (siteId.isGlobal) {
            observeGlobalTerms()
        } else {
            combine(
                observeGlobalTerms(),
                observeTermsBySite(siteId),
            ) { globalTerms, siteTerms ->
                (globalTerms + siteTerms).distinct()
            }
        }

    /**
     * Reactive map grouped by [SiteId] (including [SiteId.GLOBAL]) ready for
     * [ContentFilter.filterAllowed].
     */
    fun observeAllGrouped(): Flow<Map<SiteId, List<String>>> =
        observeAll().map { list -> list.groupBy(BlockedTerm::siteId) { it.term } }

    suspend fun addTerm(siteId: SiteId, rawTerm: String): Boolean {
        val term = ContentFilter.normalizeTerm(rawTerm)
        if (term.isEmpty()) return false
        blockedTermDao.upsert(
            BlockedTermEntity(
                siteId = siteId.value,
                term = term,
                createdAt = clock(),
            ),
        )
        return true
    }

    suspend fun removeTerm(siteId: SiteId, rawTerm: String) {
        val term = ContentFilter.normalizeTerm(rawTerm)
        if (term.isEmpty()) return
        blockedTermDao.delete(siteId.value, term)
    }

    /**
     * Atomically toggles a blocked term for [siteId]. Returns `true` if now blocked, `false` if unblocked.
     */
    suspend fun toggleTerm(siteId: SiteId, rawTerm: String): Boolean {
        val term = ContentFilter.normalizeTerm(rawTerm)
        if (term.isEmpty()) return false
        return transactionRunner {
            if (blockedTermDao.exists(siteId.value, term)) {
                blockedTermDao.delete(siteId.value, term)
                false
            } else {
                blockedTermDao.upsert(
                    BlockedTermEntity(
                        siteId = siteId.value,
                        term = term,
                        createdAt = clock(),
                    ),
                )
                true
            }
        }
    }

    suspend fun clearSite(siteId: SiteId) {
        blockedTermDao.deleteBySite(siteId.value)
    }
}
