package com.raven.app.feature.settings

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class FeedbackSubmission(
    val source: String = "app",
    val category: String,
    val title: String,
    val body: String,
    val contact: String = "",
    val appVersion: String,
    val versionCode: Long,
    val packageName: String,
    val deviceModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val locale: String,
    val installId: String,
)

@Serializable
data class FeedbackAccepted(
    val ok: Boolean = false,
    val id: String = "",
    val status: String = "",
)

class FeedbackException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Uploads one feedback ticket and an optional diagnostic log to the official website backend.
 * Uses the shared external client so site cookies and credentials are never attached.
 */
open class FeedbackClient(
    private val httpClient: OkHttpClient,
    private val appInfo: RavenAppInfo,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val endpointOverride: String? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    open suspend fun submit(submission: FeedbackSubmission, logFile: File?): FeedbackAccepted {
        val endpoint = (endpointOverride ?: DEFAULT_ENDPOINT).toHttpUrlOrNull()
            ?: throw FeedbackException("反馈地址无效")
        val payload = json.encodeToString(FeedbackSubmission.serializer(), submission)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("payload", payload)
            .apply {
                if (logFile != null && logFile.isFile && logFile.length() > 0L) {
                    addFormDataPart("log", "raven-log.txt", logFile.asRequestBody(TEXT))
                }
            }
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", "Raven/${appInfo.versionName} (Android)")
            .post(body)
            .build()
        return withContext(dispatchers.io) {
            execute(httpClient.newCall(request)).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw FeedbackException(errorMessage(response.code, text))
                }
                val accepted = runCatching {
                    json.decodeFromString(FeedbackAccepted.serializer(), text)
                }.getOrElse { throw FeedbackException("反馈服务返回了无法识别的结果", it) }
                if (!accepted.ok || accepted.id.isBlank()) {
                    throw FeedbackException("反馈服务没有返回编号")
                }
                accepted
            }
        }
    }

    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun errorMessage(code: Int, body: String): String = when (code) {
        413 -> "日志太大，请缩短后重试"
        429 -> "提交过于频繁，请稍后再试"
        else -> "提交失败（HTTP $code）"
    }.let { message ->
        if (body.isBlank()) message else "$message：${body.take(160)}"
    }

    companion object {
        const val DEFAULT_ENDPOINT: String = "https://ywclgl258.us.ci/api/feedback"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val TEXT = "text/plain; charset=utf-8".toMediaType()

        fun diagnosticClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun newInstallId(): String = UUID.randomUUID().toString()
    }
}
