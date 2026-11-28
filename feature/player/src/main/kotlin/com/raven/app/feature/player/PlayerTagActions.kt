package com.raven.app.feature.player

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Player tag chip actions: subscribe, block (global or per-site) and store a custom Chinese
 * translation. Extracted from [PlayerViewModel] so the tag rules live next to the same matching
 * helper the detail sheet uses; the ViewModel keeps thin delegates for its public API.
 */
internal class PlayerTagActions(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long,
    private val activeSiteId: () -> SiteId?,
    private val subscriptions: SubscriptionRepository?,
    private val preferences: PreferencesRepository,
    private val blockedTerms: BlockedTermsRepository,
    private val tagDictionaryRepository: TagDictionaryRepository?,
    private val dictionary: TagDictionary,
    private val matchesBlockedTerm: (TagRef, String) -> Boolean,
    private val message: (String) -> Unit,
) {
    fun toggleSubscription(tag: TagRef) {
        val repository = subscriptions ?: return
        val siteId = activeSiteId() ?: return
        val slug = tag.slug.trim().ifBlank { tag.label.trim() }
        if (slug.isEmpty()) return
        val translated = dictionary.translate(tag.slug, tag.namespace)
            ?: dictionary.translate(tag.label, tag.namespace)
        val label = translated ?: tag.label.ifBlank { slug }
        scope.launch {
            val subscribed = repository.toggleSubscription(
                TagSubscription(
                    siteId = siteId,
                    slug = slug,
                    label = label,
                    url = tag.url,
                    scope = SearchScope.TAG,
                    createdAt = clock(),
                ),
            )
            message(if (subscribed) "已订阅标签「$label」" else "已取消订阅标签「$label」")
        }
    }

    fun toggleFavorite(tag: TagRef) = toggleSubscription(tag)

    fun toggleBlocked(tag: TagRef) {
        val siteId = activeSiteId() ?: return
        val term = tag.slug.trim().ifBlank { tag.label.trim() }
        if (term.isEmpty()) return
        scope.launch {
            val settings = preferences.currentSettings()
            val primaryScope = if (settings.blockTagsGlobally) SiteId.GLOBAL else siteId
            val normalized = ContentFilter.normalizeTerm(term)
            val globalTerms = blockedTerms.observeGlobalTerms().first()
            val siteTerms = blockedTerms.observeTermsBySite(siteId).first()
            val matchingGlobal = globalTerms.filter { matchesBlockedTerm(tag, it) }
            val matchingSite = siteTerms.filter { matchesBlockedTerm(tag, it) }
            val nowBlocked = if (matchingGlobal.isNotEmpty() || matchingSite.isNotEmpty()) {
                matchingGlobal.forEach { blockedTerms.removeTerm(SiteId.GLOBAL, it) }
                matchingSite.forEach { blockedTerms.removeTerm(siteId, it) }
                false
            } else {
                blockedTerms.addTerm(primaryScope, normalized)
                true
            }
            val scopePrefix = if (settings.blockTagsGlobally) "已全局屏蔽标签" else "已在当前站点屏蔽标签"
            message(if (nowBlocked) "$scopePrefix：$term" else "已取消屏蔽：$term")
        }
    }

    fun saveCustomTranslation(tag: TagRef, chineseName: String) {
        val repository = tagDictionaryRepository ?: return
        val slug = tag.slug.trim().ifBlank { tag.label.trim() }
        val cleanZh = chineseName.trim()
        if (slug.isEmpty() || cleanZh.isEmpty()) {
            message("请输入有效的中文译名")
            return
        }
        scope.launch(dispatchers.io) {
            val saved = repository.upsertCustomTranslation(
                rawSlug = slug,
                chineseName = cleanZh,
                namespace = tag.namespace,
            )
            if (saved) {
                repository.hydrateRuntimeDictionary()
                message("已更新中文译名：$cleanZh")
            }
        }
    }
}
