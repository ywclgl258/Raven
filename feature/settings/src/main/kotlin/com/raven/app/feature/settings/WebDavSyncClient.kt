package com.raven.app.feature.settings

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.database.model.BackupSummary as DatabaseBackupSummary
import com.raven.app.core.database.repository.BackupDataRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.security.BackupCodec
import com.raven.app.core.security.BackupSummary as SecurityBackupSummary
import com.raven.app.core.security.BackupValidator
import com.raven.app.core.security.WebDavConfig
import com.raven.app.core.security.WebDavCredentialStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed interface WebDavSyncOutcome {
    data class Connected(val message: String) : WebDavSyncOutcome

    data class PushCompleted(
        val uploadedBytes: Int,
        val summary: SecurityBackupSummary,
        val syncedAtMs: Long,
    ) : WebDavSyncOutcome

    data class PullCompleted(
        val downloadedBytes: Int,
        val mergedSummary: DatabaseBackupSummary,
        val syncedAtMs: Long,
    ) : WebDavSyncOutcome

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : WebDavSyncOutcome
}

/**
 * End-to-end encrypted WebDAV sync client (`PROPFIND` / `PUT` / `GET`) backed by
 * [BackupCodec] (`RVNBK01\0` AES-256-GCM) and [BackupDataRepository].
 *
 * Security invariants:
 * - Strictly requires HTTPS endpoints validated via [WebDavCredentialStore.validateAndNormalize].
 * - Never sends site cookies or exposes credentials/passphrases in logs or error messages.
 * - Reuses Koin's single `NetworkQualifiers.API` [OkHttpClient] (zero per-request client builds).
 */
class WebDavSyncClient(
    private val apiClient: OkHttpClient,
    private val backupDataRepository: BackupDataRepository,
    private val backupCodec: BackupCodec,
    private val backupValidator: BackupValidator,
    private val preferencesRepository: PreferencesRepository,
    private val appInfo: RavenAppInfo,
    private val dispatchers: AppDispatchers,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun testConnection(rawConfig: WebDavConfig): WebDavSyncOutcome = withContext(dispatchers.io) {
        try {
            val config = WebDavCredentialStore.validateAndNormalize(rawConfig)
            val request = Request.Builder()
                .url("${config.endpointUrl}/")
                .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0")
                .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
                .header("User-Agent", userAgent())
                .build()

            executeCancellable(apiClient.newCall(request)).use { response ->
                val targetHost = UrlSanitizer.extractHost(config.endpointUrl).orEmpty()
                val actualHost = response.request.url.host
                if (targetHost.isNotEmpty() && !actualHost.equals(targetHost, ignoreCase = true)) {
                    return@withContext WebDavSyncOutcome.Error("WebDAV 响应跳转到非目标域名，已拒绝连接")
                }
                when {
                    response.code in 200..299 ->
                        WebDavSyncOutcome.Connected("WebDAV 服务连接正常 (HTTP ${response.code})")
                    response.code == 401 || response.code == 403 ->
                        WebDavSyncOutcome.Error("WebDAV 账号或密码认证失败 (HTTP ${response.code})")
                    response.code == 405 -> {
                        // Some lightweight servers reject PROPFIND with 405; verify file endpoint with HEAD
                        probeRemoteFileWithHead(config)
                    }
                    else ->
                        WebDavSyncOutcome.Error("WebDAV 服务返回 HTTP ${response.code}")
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            WebDavSyncOutcome.Error(e.message ?: "无法连接 WebDAV 服务器", cause = e)
        }
    }

    suspend fun pushBackup(rawConfig: WebDavConfig): WebDavSyncOutcome = withContext(dispatchers.io) {
        val config = try {
            WebDavCredentialStore.validateAndNormalize(rawConfig)
        } catch (e: Exception) {
            return@withContext WebDavSyncOutcome.Error(e.message ?: "WebDAV 配置无效", cause = e)
        }

        val passphraseChars = config.backupPassphrase.toCharArray()
        var encrypted: ByteArray? = null
        try {
            val payload = backupDataRepository.exportPayload(appVersion = appInfo.versionName)
            val validatedSummary = backupValidator.validate(payload)
            val encoded = backupCodec.encode(payload, passphraseChars)
            encrypted = encoded

            val targetUrl = "${config.endpointUrl}/${config.remoteFileName}"
            val request = Request.Builder()
                .url(targetUrl)
                .put(encoded.toRequestBody(OCTET_STREAM))
                .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
                .header("User-Agent", userAgent())
                .build()

            executeCancellable(apiClient.newCall(request)).use { response ->
                val targetHost = UrlSanitizer.extractHost(config.endpointUrl).orEmpty()
                val actualHost = response.request.url.host
                if (targetHost.isNotEmpty() && !actualHost.equals(targetHost, ignoreCase = true)) {
                    return@withContext WebDavSyncOutcome.Error("WebDAV 上传跳转到了非目标域名，已中止操作")
                }
                if (response.code == 401 || response.code == 403) {
                    return@withContext WebDavSyncOutcome.Error("WebDAV 认证失败或无写入权限 (HTTP ${response.code})")
                }
                if (!response.isSuccessful) {
                    return@withContext WebDavSyncOutcome.Error("WebDAV 上传失败：HTTP ${response.code}")
                }
            }

            val syncedAt = clockMs()
            preferencesRepository.setWebDavLastSyncAt(syncedAt)
            WebDavSyncOutcome.PushCompleted(
                uploadedBytes = encoded.size,
                summary = validatedSummary,
                syncedAtMs = syncedAt,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            WebDavSyncOutcome.Error(e.message ?: "上传云端加密备份失败", cause = e)
        } finally {
            passphraseChars.fill('\u0000')
            encrypted?.fill(0)
        }
    }

    suspend fun pullAndRestore(rawConfig: WebDavConfig): WebDavSyncOutcome = withContext(dispatchers.io) {
        val config = try {
            WebDavCredentialStore.validateAndNormalize(rawConfig)
        } catch (e: Exception) {
            return@withContext WebDavSyncOutcome.Error(e.message ?: "WebDAV 配置无效", cause = e)
        }

        val passphraseChars = config.backupPassphrase.toCharArray()
        var rawBytes: ByteArray? = null
        try {
            val targetUrl = "${config.endpointUrl}/${config.remoteFileName}"
            val request = Request.Builder()
                .url(targetUrl)
                .get()
                .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
                .header("Cache-Control", "no-cache")
                .header("User-Agent", userAgent())
                .build()

            val downloaded = executeCancellable(apiClient.newCall(request)).use { response ->
                val targetHost = UrlSanitizer.extractHost(config.endpointUrl).orEmpty()
                val actualHost = response.request.url.host
                if (targetHost.isNotEmpty() && !actualHost.equals(targetHost, ignoreCase = true)) {
                    return@withContext WebDavSyncOutcome.Error("WebDAV 下载跳转到了非目标域名，已中止恢复")
                }
                if (response.code == 404) {
                    return@withContext WebDavSyncOutcome.Error("远端尚无备份文件 (${config.remoteFileName})")
                }
                if (response.code == 401 || response.code == 403) {
                    return@withContext WebDavSyncOutcome.Error("WebDAV 认证失败或无读取权限 (HTTP ${response.code})")
                }
                if (!response.isSuccessful) {
                    return@withContext WebDavSyncOutcome.Error("下载云端备份失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("WebDAV 返回空响应")
                readBoundedBytes(body.byteStream(), BackupRestoreViewModel.MAX_BACKUP_WIRE_BYTES)
            }
            rawBytes = downloaded

            val decoded = backupCodec.decode(downloaded, passphraseChars)
            backupValidator.validate(decoded.payload)
            val merged = backupDataRepository.applyBackup(
                incoming = decoded.payload,
                appVersion = appInfo.versionName,
            )

            val syncedAt = clockMs()
            preferencesRepository.setWebDavLastSyncAt(syncedAt)
            WebDavSyncOutcome.PullCompleted(
                downloadedBytes = downloaded.size,
                mergedSummary = merged,
                syncedAtMs = syncedAt,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            WebDavSyncOutcome.Error(e.message ?: "下载并解密云端备份失败", cause = e)
        } finally {
            passphraseChars.fill('\u0000')
            rawBytes?.fill(0)
        }
    }

    private suspend fun probeRemoteFileWithHead(config: WebDavConfig): WebDavSyncOutcome {
        val request = Request.Builder()
            .url("${config.endpointUrl}/${config.remoteFileName}")
            .head()
            .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
            .header("User-Agent", userAgent())
            .build()
        return executeCancellable(apiClient.newCall(request)).use { headResp ->
            when (headResp.code) {
                in 200..299 -> WebDavSyncOutcome.Connected("WebDAV 服务连接正常，远端已有备份")
                404 -> WebDavSyncOutcome.Connected("WebDAV 服务认证正常（远端尚未创建备份）")
                401, 403 -> WebDavSyncOutcome.Error("WebDAV 账号或密码认证失败 (HTTP ${headResp.code})")
                else -> WebDavSyncOutcome.Error("WebDAV 服务返回 HTTP ${headResp.code}")
            }
        }
    }

    private fun userAgent(): String = "Raven/${appInfo.versionName} (Android)"

    private suspend fun readBoundedBytes(
        input: java.io.InputStream,
        maxBytes: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        val coroutineCtx = currentCoroutineContext()
        input.use { stream ->
            while (true) {
                coroutineCtx.ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                if (out.size() + read > maxBytes) {
                    throw IOException("远端备份超过安全体积上限，已停止下载")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    private suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                    } else {
                        cont.resume(response)
                    }
                }
            })
        }

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
