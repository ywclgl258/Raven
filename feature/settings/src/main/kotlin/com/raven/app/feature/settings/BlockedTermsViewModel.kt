package com.raven.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.common.TagSuggestion
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BlockedTermDisplayItem(
    val term: String,
    val localizedHint: String?,
    val createdAt: Long,
)

data class BlockedTermsUiState(
    val siteId: SiteId,
    val site: SiteDescriptor? = null,
    val inputQuery: String = "",
    val terms: List<BlockedTermDisplayItem> = emptyList(),
    val suggestions: List<TagSuggestion> = emptyList(),
    val feedbackMessage: String? = null,
)

/**
 * Per-site blocked terms CRUD ViewModel with live [TagDictionary] autocomplete suggestions.
 */
class BlockedTermsViewModel(
    val route: RavenRoute.BlockedTerms,
    private val siteRegistry: SiteRegistry,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val tagDictionary: TagDictionary,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    val siteId: SiteId = SiteId.of(route.siteId)
    private val descriptor: SiteDescriptor? = siteRegistry.get(siteId)?.descriptor

    private val inputQueryFlow = MutableStateFlow("")
    private val feedbackFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<BlockedTermsUiState> = combine(
        blockedTermsRepository.observeBySite(siteId),
        inputQueryFlow,
        feedbackFlow,
    ) { rawTerms: List<BlockedTerm>, query: String, feedback: String? ->
        val existingSet = rawTerms.mapTo(HashSet(rawTerms.size)) { it.term }
        val displayTerms = rawTerms.map { item ->
            BlockedTermDisplayItem(
                term = item.term,
                localizedHint = tagDictionary.translate(item.term),
                createdAt = item.createdAt,
            )
        }
        val suggestions = if (query.isBlank()) {
            emptyList()
        } else {
            tagDictionary.suggest(query, limit = SUGGESTION_LIMIT)
                .filterNot { ContentFilter.normalizeTerm(it.slug) in existingSet }
        }
        BlockedTermsUiState(
            siteId = siteId,
            site = descriptor,
            inputQuery = query,
            terms = displayTerms,
            suggestions = suggestions,
            feedbackMessage = feedback,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BlockedTermsUiState(siteId = siteId, site = descriptor),
    )

    fun updateQuery(query: String) {
        inputQueryFlow.value = query
        if (feedbackFlow.value != null) feedbackFlow.value = null
    }

    fun addCurrentInput() {
        addTerm(inputQueryFlow.value)
    }

    fun addTerm(rawTerm: String) {
        val normalized = ContentFilter.normalizeTerm(rawTerm)
        if (normalized.isEmpty()) {
            feedbackFlow.value = "请输入要屏蔽的标签或关键词"
            return
        }
        viewModelScope.launch {
            val added = withContext(dispatchers.io) {
                blockedTermsRepository.addTerm(siteId, normalized)
            }
            if (added) {
                inputQueryFlow.value = ""
                feedbackFlow.value = "已添加屏蔽词：$normalized"
            }
        }
    }

    fun addSuggestion(suggestion: TagSuggestion) {
        addTerm(suggestion.slug)
    }

    fun removeTerm(term: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                blockedTermsRepository.removeTerm(siteId, term)
            }
            feedbackFlow.update { "已移除屏蔽词：$term" }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                blockedTermsRepository.clearSite(siteId)
            }
            feedbackFlow.value = "已清空本站全部屏蔽词"
        }
    }

    fun clearFeedback() {
        feedbackFlow.value = null
    }

    companion object {
        private const val SUGGESTION_LIMIT: Int = 8
    }
}
