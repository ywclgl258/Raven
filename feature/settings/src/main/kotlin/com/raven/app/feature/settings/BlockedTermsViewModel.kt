package com.raven.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.common.TagSuggestion
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BlockedTermsScope {
    GLOBAL,
    CURRENT_SITE,
}

data class BlockedTermDisplayItem(
    val term: String,
    val localizedHint: String?,
    val createdAt: Long,
)

data class BlockedTermsUiState(
    val siteId: SiteId,
    val site: SiteDescriptor? = null,
    val selectedScope: BlockedTermsScope = BlockedTermsScope.CURRENT_SITE,
    val blockTagsGlobally: Boolean = true,
    val globalTermsCount: Int = 0,
    val siteTermsCount: Int = 0,
    val inputQuery: String = "",
    val terms: List<BlockedTermDisplayItem> = emptyList(),
    val suggestions: List<TagSuggestion> = emptyList(),
    val feedbackMessage: String? = null,
) {
    val activeTargetSiteId: SiteId
        get() = if (selectedScope == BlockedTermsScope.GLOBAL) SiteId.GLOBAL else siteId
}

private data class BlockedTermsInputControls(
    val selectedScope: BlockedTermsScope,
    val inputQuery: String = "",
    val feedbackMessage: String? = null,
)

/**
 * Global (`SiteId.GLOBAL`) and per-site blocked terms CRUD ViewModel with live [TagDictionary]
 * bilingual autocomplete suggestions and `blockTagsGlobally` preference control.
 */
class BlockedTermsViewModel(
    val route: RavenRoute.BlockedTerms,
    private val siteRegistry: SiteRegistry,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val tagDictionary: TagDictionary,
    private val dispatchers: AppDispatchers,
    private val preferencesRepository: PreferencesRepository? = null,
    private val tagDictionaryRepository: TagDictionaryRepository? = null,
) : ViewModel() {

    val siteId: SiteId = SiteId.of(route.siteId)
    private val descriptor: SiteDescriptor? =
        if (siteId.isGlobal) null else siteRegistry.get(siteId)?.descriptor

    private val initialScope: BlockedTermsScope =
        if (siteId.isGlobal) BlockedTermsScope.GLOBAL else BlockedTermsScope.CURRENT_SITE

    private val controls = MutableStateFlow(BlockedTermsInputControls(selectedScope = initialScope))

    private val settingsFlow = preferencesRepository?.settings ?: flowOf(AppSettings())
    private val dictRevisionFlow = tagDictionaryRepository?.observeRevision() ?: flowOf(0L)

    private val siteTermsFlow = if (siteId.isGlobal) {
        flowOf(emptyList<BlockedTerm>())
    } else {
        blockedTermsRepository.observeBySite(siteId)
    }

    val uiState: StateFlow<BlockedTermsUiState> = combine(
        blockedTermsRepository.observeBySite(SiteId.GLOBAL),
        siteTermsFlow,
        settingsFlow,
        dictRevisionFlow,
        controls,
    ) { globalTerms: List<BlockedTerm>, siteTerms: List<BlockedTerm>, settings: AppSettings, _: Long, ctrl: BlockedTermsInputControls ->
        val activeRawTerms = if (ctrl.selectedScope == BlockedTermsScope.GLOBAL) globalTerms else siteTerms
        val existingSet = activeRawTerms.mapTo(HashSet(activeRawTerms.size)) { it.term }
        val displayTerms = activeRawTerms.map { item ->
            BlockedTermDisplayItem(
                term = item.term,
                localizedHint = tagDictionary.translate(item.term),
                createdAt = item.createdAt,
            )
        }
        val query = ctrl.inputQuery
        val suggestions = if (query.isBlank()) {
            emptyList()
        } else {
            tagDictionary.suggest(query, limit = SUGGESTION_LIMIT)
                .filterNot { suggestion ->
                    val bareNorm = ContentFilter.normalizeTerm(suggestion.slug)
                    val qualNorm = if (suggestion.namespace.isNotBlank()) {
                        ContentFilter.normalizeTerm("${suggestion.namespace}:${suggestion.slug}")
                    } else {
                        bareNorm
                    }
                    bareNorm in existingSet || qualNorm in existingSet
                }
        }
        BlockedTermsUiState(
            siteId = siteId,
            site = descriptor,
            selectedScope = ctrl.selectedScope,
            blockTagsGlobally = settings.blockTagsGlobally,
            globalTermsCount = globalTerms.size,
            siteTermsCount = siteTerms.size,
            inputQuery = query,
            terms = displayTerms,
            suggestions = suggestions,
            feedbackMessage = ctrl.feedbackMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BlockedTermsUiState(
            siteId = siteId,
            site = descriptor,
            selectedScope = initialScope,
        ),
    )

    fun selectScope(scope: BlockedTermsScope) {
        if (siteId.isGlobal && scope == BlockedTermsScope.CURRENT_SITE) return
        controls.update { it.copy(selectedScope = scope, feedbackMessage = null) }
    }

    fun setBlockTagsGlobally(enabled: Boolean) {
        val repo = preferencesRepository ?: return
        viewModelScope.launch {
            repo.setBlockTagsGlobally(enabled)
        }
    }

    fun updateQuery(query: String) {
        controls.update { it.copy(inputQuery = query, feedbackMessage = null) }
    }

    fun addCurrentInput() {
        addTerm(controls.value.inputQuery)
    }

    fun addTerm(rawTerm: String) {
        val normalized = ContentFilter.normalizeTerm(rawTerm)
        if (normalized.isEmpty()) {
            controls.update { it.copy(feedbackMessage = "请输入要屏蔽的标签或关键词") }
            return
        }
        val targetScope = controls.value.selectedScope
        val targetSiteId = if (targetScope == BlockedTermsScope.GLOBAL) SiteId.GLOBAL else siteId
        viewModelScope.launch {
            val added = withContext(dispatchers.io) {
                blockedTermsRepository.addTerm(targetSiteId, normalized)
            }
            if (added) {
                val label = if (targetScope == BlockedTermsScope.GLOBAL) "全局屏蔽词" else "屏蔽词"
                controls.update {
                    it.copy(
                        inputQuery = "",
                        feedbackMessage = "已添加$label：$normalized",
                    )
                }
            }
        }
    }

    fun addSuggestion(suggestion: TagSuggestion) {
        addTerm(suggestion.slug)
    }

    fun removeTerm(term: String) {
        val targetScope = controls.value.selectedScope
        val targetSiteId = if (targetScope == BlockedTermsScope.GLOBAL) SiteId.GLOBAL else siteId
        viewModelScope.launch {
            withContext(dispatchers.io) {
                blockedTermsRepository.removeTerm(targetSiteId, term)
            }
            val label = if (targetScope == BlockedTermsScope.GLOBAL) "全局屏蔽词" else "屏蔽词"
            controls.update { it.copy(feedbackMessage = "已移除$label：$term") }
        }
    }

    fun clearAll() {
        val targetScope = controls.value.selectedScope
        val targetSiteId = if (targetScope == BlockedTermsScope.GLOBAL) SiteId.GLOBAL else siteId
        viewModelScope.launch {
            withContext(dispatchers.io) {
                blockedTermsRepository.clearSite(targetSiteId)
            }
            val msg = if (targetScope == BlockedTermsScope.GLOBAL) {
                "已清空全部全局屏蔽词"
            } else {
                "已清空本站全部屏蔽词"
            }
            controls.update { it.copy(feedbackMessage = msg) }
        }
    }

    fun clearFeedback() {
        controls.update { it.copy(feedbackMessage = null) }
    }

    companion object {
        private const val SUGGESTION_LIMIT: Int = 10
    }
}
