package com.raven.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.log.RavenLog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val FEEDBACK_TITLE_LIMIT = 120
internal const val FEEDBACK_BODY_LIMIT = 8_000
internal const val FEEDBACK_MIN_TITLE_LENGTH = 4
internal const val FEEDBACK_MIN_BODY_LENGTH = 8

enum class FeedbackCategory(val wire: String, val label: String) {
    BUG("bug", "缺陷"),
    PLAYBACK("playback", "播放"),
    DOWNLOAD("download", "下载"),
    ACCOUNT("account", "账号"),
    SUGGESTION("suggestion", "建议"),
    OTHER("other", "其他"),
}

data class FeedbackDeviceSnapshot(
    val model: String = "unknown",
    val androidRelease: String = "unknown",
    val sdkInt: Int = 0,
    val locale: String = "und",
)

data class FeedbackUiState(
    val category: FeedbackCategory = FeedbackCategory.BUG,
    val title: String = "",
    val body: String = "",
    val contact: String = "",
    val includeLog: Boolean = true,
    val logBytes: Long = 0L,
    val verboseLogging: Boolean = false,
    val hasCrashRecord: Boolean = false,
    val lastErrorSummary: String? = null,
    val isSubmitting: Boolean = false,
    val acceptedId: String? = null,
    val errorMessage: String? = null,
) {
    val hasValidDescription: Boolean
        get() = title.trim().length >= FEEDBACK_MIN_TITLE_LENGTH && body.trim().length >= FEEDBACK_MIN_BODY_LENGTH
}

class FeedbackViewModel(
    private val feedbackClient: FeedbackClient,
    private val appInfo: RavenAppInfo,
    private val diagnostics: FeedbackDiagnosticSource,
    private val deviceSnapshot: FeedbackDeviceSnapshot = FeedbackDeviceSnapshot(),
    private val dispatchers: AppDispatchers,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FeedbackUiState())
    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()

    fun selectCategory(category: FeedbackCategory) {
        _uiState.update { it.copy(category = category, acceptedId = null, errorMessage = null) }
    }

    fun updateTitle(value: String) = _uiState.update {
        it.copy(title = value.take(FEEDBACK_TITLE_LIMIT), acceptedId = null, errorMessage = null)
    }

    fun updateBody(value: String) = _uiState.update {
        it.copy(body = value.take(FEEDBACK_BODY_LIMIT), acceptedId = null, errorMessage = null)
    }
    fun updateContact(value: String) = _uiState.update { it.copy(contact = value.take(160)) }
    fun setIncludeLog(enabled: Boolean) = _uiState.update { it.copy(includeLog = enabled) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    /** Refreshes log size, verbose flag and the last captured error when the screen opens. */
    fun refreshDiagnostics() {
        _uiState.update {
            it.copy(
                logBytes = diagnostics.bytes().coerceAtLeast(0L),
                verboseLogging = diagnostics.isVerbose(),
                hasCrashRecord = diagnostics.hasCrashRecord(),
                lastErrorSummary = diagnostics.lastErrorSummary(),
            )
        }
    }

    fun setVerboseLogging(enabled: Boolean) {
        diagnostics.setVerbose(enabled)
        refreshDiagnostics()
    }

    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting || current.acceptedId != null) return
        val title = current.title.trim()
        val body = current.body.trim()
        if (!current.hasValidDescription) {
            _uiState.update {
                it.copy(errorMessage = "标题至少写 $FEEDBACK_MIN_TITLE_LENGTH 字，描述至少写 $FEEDBACK_MIN_BODY_LENGTH 字。")
            }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null, acceptedId = null) }
        viewModelScope.launch(dispatchers.io) {
            try {
                val log: File? = if (current.includeLog) diagnostics.snapshot() else null
                val submission = FeedbackSubmission(
                    category = current.category.wire,
                    title = title,
                    body = body,
                    contact = current.contact.trim(),
                    appVersion = appInfo.versionName,
                    versionCode = appInfo.versionCode,
                    packageName = appInfo.packageName,
                    deviceModel = deviceSnapshot.model,
                    androidRelease = deviceSnapshot.androidRelease,
                    sdkInt = deviceSnapshot.sdkInt,
                    locale = deviceSnapshot.locale,
                    installId = diagnostics.installId(),
                )
                RavenLog.i(TAG, "feedback submit category=${submission.category} log=${log?.length() ?: 0}B")
                val accepted = feedbackClient.submit(submission, log)
                RavenLog.i(TAG, "feedback accepted id=${accepted.id}")
                _uiState.update { it.copy(isSubmitting = false, acceptedId = accepted.id, title = "", body = "") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RavenLog.w(TAG, "feedback submit failed", error)
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = (error as? FeedbackException)?.message ?: "发送失败，请检查网络后重试。",
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "Feedback"
    }
}
