package com.raven.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.model.TagDictionaryStats
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.TagTranslationEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val TagTranslationEntry.qualifiedSlug: String
    get() = if (namespace.isNotBlank()) "$namespace:$rawSlug" else rawSlug

data class TagDictionaryUiState(
    val stats: TagDictionaryStats = TagDictionaryStats(
        builtInCount = TagDictionary.default.builtInSize,
        communityCount = 0,
        customCount = 0,
    ),
    val autoSyncEnabled: Boolean = true,
    val releaseTag: String = "",
    val lastUpdatedAt: Long = 0L,
    val isSyncing: Boolean = false,
    val syncStatusMessage: String? = null,
    val syncStatusIsError: Boolean = false,
    val searchQuery: String = "",
    val customEntries: List<TagTranslationEntry> = emptyList(),
    val totalCustomCount: Int = 0,
    val feedbackMessage: String? = null,
)

private data class TagDictionaryControlState(
    val isSyncing: Boolean = false,
    val syncStatusMessage: String? = null,
    val syncStatusIsError: Boolean = false,
    val searchQuery: String = "",
    val feedbackMessage: String? = null,
)

/**
 * ViewModel backing [TagDictionaryScreen]:
 * - EhTagTranslation community dictionary sync & cache lifecycle
 * - User custom tag Chinese translation CRUD with search filtering
 */
class TagDictionaryViewModel(
    private val tagDictionaryRepository: TagDictionaryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val syncer: EhTagTranslationSyncer,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val controls = MutableStateFlow(TagDictionaryControlState())

    val uiState: StateFlow<TagDictionaryUiState> = combine(
        tagDictionaryRepository.observeStats(),
        tagDictionaryRepository.observeCustomEntries(),
        preferencesRepository.settings,
        controls,
    ) { stats, customEntries, settings, ctrl ->
        val needle = ctrl.searchQuery.trim().lowercase()
        val filteredCustom = if (needle.isEmpty()) {
            customEntries
        } else {
            customEntries.filter { entry ->
                entry.rawSlug.lowercase().contains(needle) ||
                    entry.chineseName.lowercase().contains(needle) ||
                    entry.namespace.lowercase().contains(needle) ||
                    entry.qualifiedSlug.lowercase().contains(needle)
            }
        }
        TagDictionaryUiState(
            stats = stats,
            autoSyncEnabled = settings.tagDictAutoSync,
            releaseTag = settings.tagDictReleaseTag,
            lastUpdatedAt = settings.tagDictUpdatedAt,
            isSyncing = ctrl.isSyncing,
            syncStatusMessage = ctrl.syncStatusMessage,
            syncStatusIsError = ctrl.syncStatusIsError,
            searchQuery = ctrl.searchQuery,
            customEntries = filteredCustom,
            totalCustomCount = customEntries.size,
            feedbackMessage = ctrl.feedbackMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TagDictionaryUiState(),
    )

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTagDictAutoSync(enabled)
        }
    }

    fun syncCommunityNow() {
        if (controls.value.isSyncing) return
        viewModelScope.launch {
            controls.update {
                it.copy(
                    isSyncing = true,
                    syncStatusMessage = "正在连接 GitHub Releases 下载 EhTagTranslation 词库…",
                    syncStatusIsError = false,
                    feedbackMessage = null,
                )
            }
            when (val result = syncer.syncNow()) {
                is TagSyncResult.Updated -> {
                    val tagSuffix = result.releaseTag.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
                    val msg = "已同步 ${result.entryCount} 条社区中文译名$tagSuffix"
                    controls.update {
                        it.copy(
                            isSyncing = false,
                            syncStatusMessage = msg,
                            syncStatusIsError = false,
                            feedbackMessage = msg,
                        )
                    }
                }
                is TagSyncResult.NotModified -> {
                    val tagSuffix = result.releaseTag.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
                    val msg = "社区词库已是最新版本$tagSuffix（共 ${result.entryCount} 条）"
                    controls.update {
                        it.copy(
                            isSyncing = false,
                            syncStatusMessage = msg,
                            syncStatusIsError = false,
                            feedbackMessage = msg,
                        )
                    }
                }
                is TagSyncResult.Skipped -> {
                    controls.update {
                        it.copy(
                            isSyncing = false,
                            syncStatusMessage = result.reason,
                            syncStatusIsError = false,
                        )
                    }
                }
                is TagSyncResult.Error -> {
                    controls.update {
                        it.copy(
                            isSyncing = false,
                            syncStatusMessage = result.message,
                            syncStatusIsError = true,
                            feedbackMessage = result.message,
                        )
                    }
                }
            }
        }
    }

    fun clearCommunityCache() {
        if (controls.value.isSyncing) return
        viewModelScope.launch {
            withContext(dispatchers.io) {
                tagDictionaryRepository.clearCommunityEntries()
                preferencesRepository.setTagDictSyncState(
                    etag = "",
                    releaseTag = "",
                    updatedAt = 0L,
                )
            }
            controls.update {
                it.copy(
                    syncStatusMessage = "已清空社区词库缓存，已保留内置与自定义译名",
                    syncStatusIsError = false,
                    feedbackMessage = "已清空社区词库缓存",
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        controls.update { it.copy(searchQuery = query) }
    }

    fun saveCustomTranslation(
        rawSlug: String,
        chineseName: String,
        namespace: String = "",
        originalEntry: TagTranslationEntry? = null,
    ) {
        val trimmedSlug = rawSlug.trim()
        val trimmedZh = chineseName.trim()
        val trimmedNs = namespace.trim()
        if (trimmedSlug.isEmpty() || trimmedZh.isEmpty()) {
            controls.update { it.copy(feedbackMessage = "请填写英文标签与中文译名") }
            return
        }
        viewModelScope.launch {
            val saved = withContext(dispatchers.io) {
                val (embeddedNs, barePart) = TagDictionary.splitNamespace(trimmedSlug)
                val normalizedSlug = TagDictionary.normalizeBareSlug(barePart)
                val normalizedNs = TagDictionary.normalizeNamespace(trimmedNs).ifEmpty { embeddedNs }
                if (
                    originalEntry != null &&
                    (originalEntry.rawSlug != normalizedSlug || originalEntry.namespace != normalizedNs)
                ) {
                    tagDictionaryRepository.deleteCustomTranslation(
                        rawSlug = originalEntry.rawSlug,
                        namespace = originalEntry.namespace,
                    )
                }
                val ok = tagDictionaryRepository.upsertCustomTranslation(
                    rawSlug = trimmedSlug,
                    chineseName = trimmedZh,
                    namespace = trimmedNs,
                )
                if (ok) {
                    tagDictionaryRepository.hydrateRuntimeDictionary()
                }
                ok
            }
            if (saved) {
                controls.update { it.copy(feedbackMessage = "已保存自定义译名：$trimmedSlug → $trimmedZh") }
            } else {
                controls.update { it.copy(feedbackMessage = "译名格式无效，请检查英文标签") }
            }
        }
    }

    fun deleteCustomTranslation(entry: TagTranslationEntry) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                tagDictionaryRepository.deleteCustomTranslation(
                    rawSlug = entry.rawSlug,
                    namespace = entry.namespace,
                )
                tagDictionaryRepository.hydrateRuntimeDictionary()
            }
            controls.update { it.copy(feedbackMessage = "已删除自定义译名：${entry.qualifiedSlug}") }
        }
    }

    fun clearFeedback() {
        controls.update { it.copy(feedbackMessage = null) }
    }
}
