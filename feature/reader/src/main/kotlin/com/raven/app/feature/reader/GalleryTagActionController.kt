package com.raven.app.feature.reader

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tag / author long-press action sheet: opens the sheet with the current favorite, subscription
 * and block state, and toggles those three relationships plus custom Chinese translations.
 *
 * Extracted from [GalleryDetailViewModel]; the ViewModel keeps thin delegating entry points so its
 * public API (and Compose call sites) stay unchanged.
 */
internal class GalleryTagActionController(
    private val siteId: SiteId,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long,
    private val favorites: FavoriteRepository,
    private val subscriptions: SubscriptionRepository,
    private val preferences: PreferencesRepository,
    private val blockedTerms: BlockedTermsRepository,
    private val tagDictionaryRepository: TagDictionaryRepository?,
    private val dictionary: TagDictionary,
    private val state: () -> GalleryDetailUiState,
    private val update: ((GalleryDetailUiState) -> GalleryDetailUiState) -> Unit,
    private val snackbar: (String) -> Unit,
    private val label: (TagRef) -> String,
    private val translateLabel: (TagRef, Boolean) -> String,
) {
    fun open(tag: TagRef) {
        val snapshot = state()
        val normSlug = tagSubscriptionSlug(tag).lowercase()
        update {
            it.copy(
                tagAction = TagActionState(
                    tag = tag,
                    translatedLabel = label(tag),
                    isFavorited = normSlug in snapshot.favoritedTagSlugs,
                    isSubscribed = normSlug in snapshot.subscribedTagSlugs,
                    isBlocked = tagMatchesBlockedTerms(tag, snapshot.blockedTerms, dictionary),
                ),
                authorAction = null,
            )
        }
    }

    fun dismissTag() = update { it.copy(tagAction = null) }

    fun openAuthor(author: AuthorRef) = update { it.copy(authorAction = author, tagAction = null) }

    fun dismissAuthor() = update { it.copy(authorAction = null) }

    fun toggleFavorite(tag: TagRef) {
        scope.launch(dispatchers.io) {
            val display = label(tag)
            val added = favorites.toggle(
                FavoriteItem(
                    siteId = siteId,
                    targetId = tag.slug,
                    kind = FavoriteKind.TAG,
                    title = display,
                    url = tag.url,
                    addedAt = clock(),
                    extra = buildMap {
                        if (tag.namespace.isNotBlank()) put("namespace", tag.namespace)
                    },
                ),
            )
            snackbar(if (added) "已收藏标签「$display」" else "已取消收藏标签「$display」")
        }
    }

    fun toggleSubscription(tag: TagRef) {
        scope.launch(dispatchers.io) {
            val display = label(tag)
            val slug = tagSubscriptionSlug(tag)
            if (slug.isEmpty()) {
                snackbar("无法订阅标签「$display」：缺少标签标识")
                return@launch
            }
            val subscribed = subscriptions.toggleSubscription(
                TagSubscription(
                    siteId = siteId,
                    slug = slug,
                    label = display,
                    url = tag.url,
                    scope = SearchScope.TAG,
                    createdAt = clock(),
                ),
            )
            snackbar(if (subscribed) "已订阅标签「$display」" else "已取消订阅标签「$display」")
        }
    }

    fun toggleBlocked(tag: TagRef) {
        scope.launch(dispatchers.io) {
            val display = label(tag)
            val term = tag.slug.trim().ifBlank { tag.label.trim() }
            if (term.isEmpty()) return@launch
            val settings = preferences.currentSettings()
            val primaryScope = if (settings.blockTagsGlobally) SiteId.GLOBAL else siteId
            val normalized = ContentFilter.normalizeTerm(term)
            val globalTerms = blockedTerms.observeGlobalTerms().first()
            val siteTerms = blockedTerms.observeTermsBySite(siteId).first()
            val matchingGlobal = globalTerms.filter { tagMatchesBlockedTerm(tag, it, dictionary) }
            val matchingSite = siteTerms.filter { tagMatchesBlockedTerm(tag, it, dictionary) }
            val nowBlocked = if (matchingGlobal.isNotEmpty() || matchingSite.isNotEmpty()) {
                matchingGlobal.forEach { blockedTerms.removeTerm(SiteId.GLOBAL, it) }
                matchingSite.forEach { blockedTerms.removeTerm(siteId, it) }
                false
            } else {
                blockedTerms.addTerm(primaryScope, normalized)
                true
            }
            val message = when {
                !nowBlocked -> "已取消屏蔽「$display」"
                settings.blockTagsGlobally -> "已全局屏蔽「$display」"
                else -> "已在当前站点屏蔽「$display」"
            }
            snackbar(message)
        }
    }

    fun saveCustomTranslation(tag: TagRef, chineseName: String) {
        val repository = tagDictionaryRepository ?: return
        val slug = tag.slug.trim().ifBlank { tag.label.trim() }
        val cleanZh = chineseName.trim()
        if (slug.isEmpty() || cleanZh.isEmpty()) {
            snackbar("请输入有效的中文译名")
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
                update { snapshot ->
                    val updatedAction = snapshot.tagAction?.let { action ->
                        if (action.tag.slug == tag.slug && action.tag.namespace == tag.namespace) {
                            action.copy(
                                translatedLabel = translateLabel(action.tag, snapshot.translateTags),
                            )
                        } else {
                            action
                        }
                    }
                    snapshot.copy(tagAction = updatedAction)
                }
                snackbar("已更新中文译名：$cleanZh")
            }
        }
    }
}
