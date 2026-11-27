package com.raven.app.feature.settings

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.model.BackupSummary as DatabaseBackupSummary
import com.raven.app.core.database.repository.BackupDataRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.security.BackupCodec
import com.raven.app.core.security.BackupSummary as SecurityBackupSummary
import com.raven.app.core.security.BackupValidator
import com.raven.app.core.security.DecodedBackup
import com.raven.app.core.security.WebDavConfig
import com.raven.app.core.security.WebDavCredentialStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        /** False when the remote upload succeeded but the local "last sync" write failed. */
        val syncTimestampSaved: Boolean,
        val timestampWarning: String?,
    ) : WebDavSyncOutcome

    /**
     * Downloaded + decrypted + validated cloud snapshot. Nothing has been written to Room yet;
     * the caller must render this preview and wait for an explicit confirm, mirroring the
     * local-file import contract.
     */
    data class PullPreviewReady(
        val downloadedBytes: Int,
        val decoded: DecodedBackup,
        val summary: SecurityBackupSummary,
        val remoteFileName: String,
        val endpointLabel: String,
        val downloadedAtMs: Long,
    ) : WebDavSyncOutcome

    data class PullCompleted(
        val mergedSummary: DatabaseBackupSummary,
        val syncedAtMs: Long,
        /** False when the merge succeeded but the local "last sync" write failed. */
        val syncTimestampSaved: Boolean,
        val timestampWarning: String?,
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
 * - References are resolved by URL **path segments** ([remoteFileUrl]); a query string or fragment
 *   in the configured directory can never absorb the file name.
 * - Redirects are followed manually, one hop at a time. Every hop is checked for the exact
 *   `https` scheme + host + effective port of the configured endpoint **before** the request
 *   (including the PUT body) is sent, so credentials or backup ciphertext are never forwarded
 *   to a foreign origin or a downgraded scheme.
 * - Uses its own pre-built [OkHttpClient] derived once from the shared API client: `NO_COOKIES`
 *   (site session cookies never leak to a third-party DAV host) and no automatic redirects.
 * - Never exposes credentials/passphrases in logs or error messages.
 */
class WebDavSyncClient(
    apiClient: OkHttpClient,
    private val backupDataRepository: BackupDataRepository,
    private val backupCodec: BackupCodec,
    private val backupValidator: BackupValidator,
    private val preferencesRepository: PreferencesRepository,
    private val appInfo: RavenAppInfo,
    private val dispatchers: AppDispatchers,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Built exactly once (never per request) from Koin's shared API client. Cookies are disabled
     * because this client talks to a user-supplied third-party host that must not receive any
     * site session; redirects are handled manually by [executeFollowingSafeRedirects].
     */
    private val syncClient: OkHttpClient = apiClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(rawConfig: WebDavConfig): WebDavSyncOutcome = withContext(dispatchers.io) {
        try {
            val config = assertNormalized(rawConfig)
            val origin = originOf(config)
            val request = Request.Builder()
                .url(directoryUrl(config))
                .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0")
                .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
                .header("User-Agent", userAgent())
                .build()

            executeFollowingSafeRedirects(request, origin).use { response ->
                when {
                    response.code in 200..299 ->
                        WebDavSyncOutcome.Connected("WebDAV 服务连接正常 (HTTP ${response.code})")
                    response.code == 401 || response.code == 403 ->
                        WebDavSyncOutcome.Error("WebDAV 账号或密码认证失败 (HTTP ${response.code})")
                    response.code == 405 -> {
                        // Some lightweight servers reject PROPFIND with 405; verify file endpoint with HEAD
                        probeRemoteFileWithHead(config, origin)
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
            assertNormalized(rawConfig)
        } catch (e: Exception) {
            return@withContext WebDavSyncOutcome.Error(e.message ?: "WebDAV 配置无效", cause = e)
        }

        val passphraseChars = config.backupPassphrase.toCharArray()
        var encrypted: ByteArray? = null
        try {
            val origin = originOf(config)
            val payload = backupDataRepository.exportPayload(appVersion = appInfo.versionName)
            val validatedSummary = backupValidator.validate(payload)
            val encoded = backupCodec.encode(payload, passphraseChars)
            encrypted = encoded

            val request = Request.Builder()
                .url(remoteFileUrl(config))
                .put(encoded.toRequestBody(OCTET_STREAM))
                .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
                .header("User-Agent", userAgent())
                .build()

            executeFollowingSafeRedirects(request, origin).use { response ->
                if (response.code == 401 || response.code == 403) {
                    return@withContext WebDavSyncOutcome.Error("WebDAV 认证失败或无写入权限 (HTTP ${response.code})")
                }
                if (!response.isSuccessful) {
                    return@withContext WebDavSyncOutcome.Error("WebDAV 上传失败：HTTP ${response.code}")
                }
            }

            // Upload already succeeded; a failed clock/persistence write must not turn it into a
            // "failed sync" that invites the user to upload again.
            val timestamp = persistSyncTimestamp()
            WebDavSyncOutcome.PushCompleted(
                uploadedBytes = encoded.size,
                summary = validatedSummary,
                syncedAtMs = timestamp.epochMs,
                syncTimestampSaved = timestamp.saved,
                timestampWarning = timestamp.warning,
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

    /**
     * Downloads, decrypts and validates the cloud snapshot **without touching Room**, so the UI
     * can show the same full preview + explicit confirm flow that local file restore offers.
     */
    suspend fun pullPreview(rawConfig: WebDavConfig): WebDavSyncOutcome = withContext(dispatchers.io) {
        val config = try {
            assertNormalized(rawConfig)
        } catch (e: Exception) {
            return@withContext WebDavSyncOutcome.Error(e.message ?: "WebDAV 配置无效", cause = e)
        }

        val passphraseChars = config.backupPassphrase.toCharArray()
        var rawBytes: ByteArray? = null
        try {
            val origin = originOf(config)
            val request = Request.Builder()
                .url(remoteFileUrl(config))
                .get()
                .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
                .header("Cache-Control", "no-cache")
                .header("User-Agent", userAgent())
                .build()

            val downloaded = executeFollowingSafeRedirects(request, origin).use { response ->
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
            val validatedSummary = backupValidator.validate(decoded.payload)
            val downloadedAt = clockMs()
            WebDavSyncOutcome.PullPreviewReady(
                downloadedBytes = downloaded.size,
                decoded = decoded,
                summary = validatedSummary,
                remoteFileName = config.remoteFileName,
                endpointLabel = originLabel(config),
                downloadedAtMs = downloadedAt,
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

    /**
     * Persists a [WebDavSyncOutcome.PullPreviewReady] snapshot that the user explicitly confirmed.
     * The merge itself is atomic in [BackupDataRepository]; the timestamp write is reported separately.
     */
    suspend fun applyConfirmedPull(preview: WebDavSyncOutcome.PullPreviewReady): WebDavSyncOutcome =
        withContext(dispatchers.io) {
            try {
                val merged = backupDataRepository.applyBackup(
                    incoming = preview.decoded.payload,
                    appVersion = appInfo.versionName,
                )
                val timestamp = persistSyncTimestamp()
                WebDavSyncOutcome.PullCompleted(
                    mergedSummary = merged,
                    syncedAtMs = timestamp.epochMs,
                    syncTimestampSaved = timestamp.saved,
                    timestampWarning = timestamp.warning,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                WebDavSyncOutcome.Error(e.message ?: "合并云端备份失败，已完整回滚本地数据", cause = e)
            }
        }

    private suspend fun probeRemoteFileWithHead(config: WebDavConfig, origin: WebDavOrigin): WebDavSyncOutcome {
        val request = Request.Builder()
            .url(remoteFileUrl(config))
            .head()
            .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
            .header("User-Agent", userAgent())
            .build()
        return executeFollowingSafeRedirects(request, origin).use { headResp ->
            when (headResp.code) {
                in 200..299 -> WebDavSyncOutcome.Connected("WebDAV 服务连接正常，远端已有备份")
                404 -> WebDavSyncOutcome.Connected("WebDAV 服务认证正常（远端尚未创建备份）")
                401, 403 -> WebDavSyncOutcome.Error("WebDAV 账号或密码认证失败 (HTTP ${headResp.code})")
                else -> WebDavSyncOutcome.Error("WebDAV 服务返回 HTTP ${headResp.code}")
            }
        }
    }

    // ---- URL component handling ------------------------------------------------------------

    /**
     * Resolves the remote backup resource by appending [WebDavConfig.remoteFileName] as a URL
     * **path segment**. A query string or `#fragment` present on the configured directory can
     * therefore never swallow or rename the file (e.g. `…/dav?token=x` -> `…/dav/raven-sync.rvnbk?token=x`).
     */
    internal fun remoteFileUrl(config: WebDavConfig): HttpUrl {
        val base = config.endpointUrl.toHttpUrlOrNull()
            ?: throw IOException("WebDAV 服务器地址无效")
        val basePath = base.encodedPath.ifEmpty { "/" }.let { if (it.endsWith('/')) it else "$it/" }
        return base.newBuilder()
            .encodedPath(basePath)
            .addPathSegment(config.remoteFileName)
            .fragment(null)
            .build()
    }

    /**
     * The configured collection itself: same path with a trailing slash, query preserved,
     * fragment dropped so a literal `#` in the setting cannot truncate the request target.
     */
    private fun directoryUrl(config: WebDavConfig): HttpUrl {
        val base = config.endpointUrl.toHttpUrlOrNull()
            ?: throw IOException("WebDAV 服务器地址无效")
        val basePath = base.encodedPath.ifEmpty { "/" }.let { if (it.endsWith('/')) it else "$it/" }
        return base.newBuilder()
            .encodedPath(basePath)
            .fragment(null)
            .build()
    }

    private fun originOf(config: WebDavConfig): WebDavOrigin {
        val url = config.endpointUrl.toHttpUrlOrNull()
            ?: throw IOException("WebDAV 服务器地址无效")
        if (!url.isHttps) throw IOException("WebDAV 服务器地址必须使用 HTTPS")
        return WebDavOrigin(
            host = url.host.lowercase(Locale.ROOT),
            effectivePort = url.effectivePort(),
        )
    }

    private fun originLabel(config: WebDavConfig): String {
        val url = config.endpointUrl.toHttpUrlOrNull()
            ?: return config.endpointUrl
        return buildString {
            append(url.host)
            val port = url.effectivePort()
            if (port != 443) append(':').append(port)
            append(url.encodedPath.ifEmpty { "/" })
        }
    }

    /**
     * Exact-origin comparison: scheme must be `https`, host must match byte-for-byte
     * (case-insensitive), and the **effective** port must match (implicit 443 included).
     */
    private fun HttpUrl.isSameOriginAs(origin: WebDavOrigin): Boolean =
        isHttps && host.equals(origin.host, ignoreCase = true) && effectivePort() == origin.effectivePort

    /**
     * Effective port. OkHttp may report `-1` for an unspecified port, so the scheme default is
     * filled in explicitly; `https://host` and `https://host:443` must compare as equal.
     */
    private fun HttpUrl.effectivePort(): Int = when {
        port > 0 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    // ---- Redirect-safe execution ------------------------------------------------------------

    /**
     * Executes [request], following up to [MAX_REDIRECTS] redirects **manually** so that each hop
     * is origin-checked before anything is sent. Because automatic redirects are disabled on
     * [syncClient], OkHttp itself can never replay a PUT body or Authorization header to a
     * foreign host or an `http://` downgrade.
     *
     * 307 / 308 preserve the method and body and are followed after the origin check. 301 / 302 /
     * 303 cannot silently degrade a `PUT` into a body-less `GET` — that would make a rejected
     * upload look successful (and drop the ciphertext), so they abort the operation instead.
     */
    private suspend fun executeFollowingSafeRedirects(
        request: Request,
        origin: WebDavOrigin,
    ): Response {
        var current = request
        var hops = 0
        while (true) {
            val response = executeCancellable(syncClient.newCall(current))
            val code = response.code
            if (code !in REDIRECT_STATUS_CODES) {
                if (!response.request.url.isSameOriginAs(origin)) {
                    response.close()
                    throw WebDavOriginViolationException()
                }
                return response
            }

            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) throw IOException("WebDAV 返回了缺少 Location 的重定向 (HTTP $code)")
            if (hops >= MAX_REDIRECTS) throw IOException("WebDAV 重定向次数过多，已中止操作")

            val nextUrl = current.url.resolve(location)
                ?: throw WebDavOriginViolationException()
            if (!nextUrl.isSameOriginAs(origin)) throw WebDavOriginViolationException()

            if (code != 307 && code != 308) {
                // Only a body-less request may be re-issued as GET; a PUT must fail loudly.
                if (current.method != "GET" && current.method != "HEAD") {
                    throw IOException(
                        "WebDAV 以 HTTP $code 重定向写入请求，无法在保留备份内容的前提下继续，已中止上传",
                    )
                }
                current = current.newBuilder().url(nextUrl).get().build()
            } else {
                current = current.newBuilder().url(nextUrl).build()
            }
            hops++
        }
    }

    private data class WebDavOrigin(
        val host: String,
        val effectivePort: Int,
    )

    private class WebDavOriginViolationException :
        IOException("WebDAV 重定向跳转到非目标域名、非目标端口或降级为非 HTTPS，已拒绝转发请求与备份内容")

    // ---- Timestamp truthfulness -------------------------------------------------------------

    private class TimestampWrite(
        val epochMs: Long,
        val saved: Boolean,
        val warning: String?,
    )

    /**
     * Records "last sync" separately from the remote operation. A local DataStore failure after a
     * successful upload/download must surface as a warning, not as a failed sync.
     */
    private suspend fun persistSyncTimestamp(): TimestampWrite {
        val now = clockMs()
        return try {
            preferencesRepository.setWebDavLastSyncAt(now)
            TimestampWrite(now, saved = true, warning = null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            TimestampWrite(
                epochMs = now,
                saved = false,
                warning = "云端操作已成功，但本机“最近同步时间”写入失败（${e.message ?: "未知错误"}），下次打开页面可能显示旧时间。",
            )
        }
    }

    // ---- HTTP plumbing ----------------------------------------------------------------------

    private fun userAgent(): String = "Raven/${appInfo.versionName} (Android)"

    /** Normalizes the raw config and rejects values the client cannot safely address. */
    private fun assertNormalized(rawConfig: WebDavConfig): WebDavConfig {
        val normalized = WebDavCredentialStore.validateAndNormalize(rawConfig)
        if (normalized.endpointUrl.toHttpUrlOrNull() == null) {
            throw IOException("WebDAV 服务器地址无法解析")
        }
        return normalized
    }

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
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
