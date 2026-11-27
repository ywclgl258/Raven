package com.raven.app.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runtime application identity consumed by `:feature:settings` (Settings Hub, Backup export,
 * and [RavenUpdater]) so library modules stay decoupled from `:app`'s generated `BuildConfig`.
 */
data class RavenAppInfo(
    val versionName: String,
    val versionCode: Long,
    val isDebug: Boolean,
    val packageName: String,
    val githubOwner: String = DEFAULT_GITHUB_OWNER,
    val githubRepo: String = DEFAULT_GITHUB_REPO,
    val mirrorReleasesApiBaseUrl: String? = DEFAULT_MIRROR_RELEASES_API_BASE_URL,
    val releaseCertSha256: String? = null,
) {
    val releasesApiBaseUrl: String
        get() = "https://api.github.com/repos/$githubOwner/$githubRepo/releases"

    companion object {
        const val DEFAULT_GITHUB_OWNER: String = "ywclgl258"
        const val DEFAULT_GITHUB_REPO: String = "Raven"
        const val DEFAULT_MIRROR_RELEASES_API_BASE_URL: String =
            "https://ywclgl258.us.ci:8443/raven/releases"
        const val DEFAULT_PACKAGE_NAME: String = "com.raven.app"

        @Suppress("DEPRECATION")
        fun fromContext(
            context: Context,
            githubOwner: String = DEFAULT_GITHUB_OWNER,
            githubRepo: String = DEFAULT_GITHUB_REPO,
            releaseCertSha256: String? = null,
        ): RavenAppInfo {
            val appCtx = context.applicationContext
            val pkg = appCtx.packageName.orEmpty().ifBlank { DEFAULT_PACKAGE_NAME }
            val debuggable = runCatching {
                (appCtx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            }.getOrDefault(false)
            val pkgInfo = runCatching {
                appCtx.packageManager.getPackageInfo(pkg, 0)
            }.getOrNull()
            val versionName = pkgInfo?.versionName?.takeIf { it.isNotBlank() } ?: "0.1.0"
            val versionCode = when {
                pkgInfo == null -> 1L
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> pkgInfo.longVersionCode
                else -> pkgInfo.versionCode.toLong()
            }
            return RavenAppInfo(
                versionName = versionName,
                versionCode = versionCode,
                isDebug = debuggable,
                packageName = pkg,
                githubOwner = githubOwner,
                githubRepo = githubRepo,
                releaseCertSha256 = releaseCertSha256,
            )
        }
    }
}

@Serializable
data class GitHubReleaseDto(
    val id: Long = 0L,
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAssetDto> = emptyList(),
)

@Serializable
data class GitHubReleaseAssetDto(
    val id: Long = 0L,
    val name: String = "",
    val size: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val digest: String? = null,
)

data class RavenReleaseAsset(
    val releaseId: Long,
    val assetId: Long,
    val fileName: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val expectedSha256: String?,
) {
    init {
        require(releaseId > 0L && assetId > 0L && sizeBytes > 0L) { "更新资产元数据无效" }
        require(expectedSha256 == null || SHA256_REGEX.matches(expectedSha256)) { "更新资产 SHA-256 摘要格式无效" }
    }

    fun cacheDirectory(updatesRoot: File): File = File(updatesRoot, "apk/$releaseId-$assetId")

    companion object {
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

        fun fromDto(releaseId: Long, dto: GitHubReleaseAssetDto): RavenReleaseAsset {
            val rawDigest = dto.digest?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            val sha256 = when {
                rawDigest == null -> null
                rawDigest.startsWith("sha256:") -> rawDigest.removePrefix("sha256:")
                else -> throw IllegalArgumentException("不受支持的资产摘要算法：$rawDigest")
            }
            return RavenReleaseAsset(
                releaseId = releaseId,
                assetId = dto.id,
                fileName = dto.name,
                sizeBytes = dto.size,
                downloadUrl = dto.browserDownloadUrl,
                expectedSha256 = sha256,
            )
        }
    }
}

data class RavenReleaseInfo(
    val releaseId: Long,
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val asset: RavenReleaseAsset,
    val cachedApk: File? = null,
    /** The endpoint that supplied this metadata; downloads must stay on the same working source. */
    val sourceBaseUrl: String? = null,
)

sealed interface UpdateCheckOutcome {
    data class UpdateAvailable(val release: RavenReleaseInfo) : UpdateCheckOutcome
    data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateCheckOutcome
    data class DebugBuildSkipped(val message: String) : UpdateCheckOutcome
    data object Throttled : UpdateCheckOutcome
    data class Error(val message: String, val cause: Throwable? = null) : UpdateCheckOutcome
}

data class UpdateDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

class UpdateVerificationException(
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)

/**
 * Verifies a downloaded APK's package name, version monotonicity, and signing certificate lineage
 * before any installer handoff. Abstracted for deterministic JVM tests.
 */
interface ApkSignatureVerifier {
    fun needsCompatibilityPackage(): Boolean = false

    @Throws(UpdateVerificationException::class)
    fun verify(apkFile: File, releaseTag: String)
}

/**
 * Production Android verifier matching R34V's hardened signing lineage rules:
 * - Package name must equal the currently installed app.
 * - `candidateVersionCode > installedVersionCode`, and the APK version name must match the release tag.
 * - API 28+: every active signer in `installed.signingInfo.apkContentsSigners` must be present in
 *   `candidate.signingInfo.signingCertificateHistory`.
 * - API 24-27: candidate signature SHA-256 digests must match installed signatures.
 */
class AndroidApkSignatureVerifier(
    private val context: Context,
    private val appInfo: RavenAppInfo,
) : ApkSignatureVerifier {

    @Suppress("DEPRECATION")
    override fun needsCompatibilityPackage(): Boolean {
        val certHash = appInfo.releaseCertSha256?.takeIf { it.isNotBlank() } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return false
        return runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
            info.signatures?.any { sha256Hex(it.toByteArray()).equals(certHash, ignoreCase = true) } == true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    override fun verify(apkFile: File, releaseTag: String) {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            throw UpdateVerificationException("安装包文件不存在或为空")
        }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val candidate = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw UpdateVerificationException("安装包无法解析或已损坏")
        val installed = runCatching { pm.getPackageInfo(appInfo.packageName, flags) }.getOrNull()
            ?: throw UpdateVerificationException("无法读取当前应用签名信息")

        if (candidate.packageName != appInfo.packageName) {
            throw UpdateVerificationException("安装包包名 (${candidate.packageName}) 与当前应用不符")
        }
        val candidateVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            candidate.longVersionCode
        } else {
            candidate.versionCode.toLong()
        }
        verifyReleaseVersion(candidateVersion, appInfo.versionCode, candidate.versionName, releaseTag)

        val signatureAccepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val candidateHistory = candidate.signingInfo
                ?.signingCertificateHistory
                ?.map { sha256Hex(it.toByteArray()) }
                ?.toSet()
                .orEmpty()
            val candidateSigners = candidate.signingInfo
                ?.apkContentsSigners
                ?.map { sha256Hex(it.toByteArray()) }
                ?.toSet()
                .orEmpty()
            val validCandidateCerts = candidateHistory + candidateSigners
            val installedSigners = installed.signingInfo
                ?.apkContentsSigners
                ?.map { sha256Hex(it.toByteArray()) }
                ?.toSet()
                .orEmpty()
            isCandidateSignatureAccepted(
                isDebug = appInfo.isDebug,
                expectedReleaseCertSha256 = appInfo.releaseCertSha256,
                candidateCerts = validCandidateCerts,
                installedCerts = installedSigners,
                exactMatchLegacy = false,
            )
        } else {
            val candidateSigs = candidate.signatures?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
            val installedSigs = installed.signatures?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
            isCandidateSignatureAccepted(
                isDebug = appInfo.isDebug,
                expectedReleaseCertSha256 = appInfo.releaseCertSha256,
                candidateCerts = candidateSigs,
                installedCerts = installedSigs,
                exactMatchLegacy = true,
            )
        }

        if (!signatureAccepted) {
            throw UpdateVerificationException("安装包签名校验未通过，无法覆盖当前版本，已停止安装")
        }
    }

    internal companion object {
        internal fun verifyReleaseVersion(code: Long, installedCode: Long, name: String?, releaseTag: String) {
            if (code <= installedCode) throw UpdateVerificationException("安装包版本号 $code 必须高于当前版本 $installedCode")
            val expected = releaseTag.trim().removePrefix("v").removePrefix("V")
            if (name.isNullOrBlank() || name != expected) {
                throw UpdateVerificationException("安装包版本名与发布标签 $releaseTag 不一致")
            }
        }

        internal fun isCandidateSignatureAccepted(
            isDebug: Boolean,
            expectedReleaseCertSha256: String?,
            candidateCerts: Set<String>,
            installedCerts: Set<String>,
            exactMatchLegacy: Boolean,
        ): Boolean {
            if (candidateCerts.isEmpty() || installedCerts.isEmpty()) return false
            val matchesInstalled = if (exactMatchLegacy) {
                candidateCerts.map { it.lowercase(Locale.ROOT) }.toSet() ==
                    installedCerts.map { it.lowercase(Locale.ROOT) }.toSet()
            } else {
                installedCerts.all { inst -> candidateCerts.any { it.equals(inst, ignoreCase = true) } }
            }
            if (!matchesInstalled) return false

            val pinned = expectedReleaseCertSha256?.trim()?.takeIf { it.isNotEmpty() }
            if (!isDebug && pinned != null) {
                return candidateCerts.any { it.equals(pinned, ignoreCase = true) }
            }
            return true
        }
    }
}

@Serializable
data class PersistedUpdateState(
    val lastCheckedAtMs: Long = 0L,
    val pendingTag: String? = null,
    val pendingReleaseId: Long = 0L,
    val pendingAssetId: Long = 0L,
    val pendingSizeBytes: Long = 0L,
    val pendingSha256: String? = null,
)

/**
 * Atomic, non-blocking metadata store for update check throttling and verified pending APK tracking.
 * Uses atomic temp-file rename rather than synchronous blocking writes.
 */
interface UpdateStateStore {
    suspend fun read(): PersistedUpdateState
    suspend fun write(state: PersistedUpdateState)
}

class FileUpdateStateStore(
    private val stateFile: File,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
) : UpdateStateStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun read(): PersistedUpdateState = withContext(dispatchers.io) {
        runCatching {
            if (!stateFile.isFile || stateFile.length() !in 1L..MAX_STATE_BYTES) {
                PersistedUpdateState()
            } else {
                json.decodeFromString(PersistedUpdateState.serializer(), stateFile.readText())
            }
        }.getOrDefault(PersistedUpdateState())
    }

    override suspend fun write(state: PersistedUpdateState) = withContext(dispatchers.io) {
        val parent = stateFile.parentFile ?: return@withContext
        if (!parent.isDirectory) parent.mkdirs()
        val temp = File(parent, "${stateFile.name}.${System.nanoTime()}.part")
        try {
            temp.writeText(json.encodeToString(PersistedUpdateState.serializer(), state))
            if (!temp.renameTo(stateFile)) {
                if (stateFile.exists()) stateFile.delete()
                temp.renameTo(stateFile)
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val MAX_STATE_BYTES: Long = 64L * 1024L
    }
}

/**
 * GitHub Release updater for Raven (`渡鸦`).
 *
 * Architectural guarantees:
 * - Reuses pre-built [OkHttpClient] instances (`metadataClient` for `/releases/latest` JSON and
 *   `downloadClient` with `callTimeout = 0` + 30s read stall timeout for APK streaming) — zero
 *   per-request OkHttp client construction and no 25s whole-file timeout.
 * - Never sends site cookies or Bearer tokens (GitHub is never a registered site host).
 * - 12-hour startup check throttle and debug-build guard.
 * - Verifies byte length, optional SHA-256 digest, package name, version monotonicity, and signing
 *   certificate lineage via [ApkSignatureVerifier] before publishing or handing off an APK.
 */
class RavenUpdater(
    private val appInfo: RavenAppInfo,
    private val metadataClient: OkHttpClient,
    private val downloadClient: OkHttpClient,
    private val updatesDir: File,
    private val signatureVerifier: ApkSignatureVerifier,
    private val stateStore: UpdateStateStore = FileUpdateStateStore(File(updatesDir, "updater-state.json")),
    private val supportedAbisProvider: () -> List<String> = {
        runCatching { Build.SUPPORTED_ABIS?.toList().orEmpty() }.getOrDefault(emptyList())
    },
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val releasesApiBaseUrlOverride: String? = null,
) {
    private val operationMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val releasesBaseUrl: String
        get() = (releasesApiBaseUrlOverride ?: appInfo.releasesApiBaseUrl).trimEnd('/')

    suspend fun shouldRunAutoCheck(autoUpdateEnabled: Boolean): Boolean {
        if (!autoUpdateEnabled || appInfo.isDebug) return false
        val state = stateStore.read()
        val elapsed = clockMs() - state.lastCheckedAtMs
        return state.lastCheckedAtMs <= 0L || elapsed >= AUTO_CHECK_INTERVAL_MS
    }

    suspend fun checkForUpdate(manual: Boolean): UpdateCheckOutcome {
        if (appInfo.isDebug) {
            return UpdateCheckOutcome.DebugBuildSkipped(
                message = "当前为开发版 (${appInfo.versionName})，仅正式版支持在线检查更新",
            )
        }
        if (!manual) {
            val state = stateStore.read()
            val elapsed = clockMs() - state.lastCheckedAtMs
            if (state.lastCheckedAtMs > 0L && elapsed < AUTO_CHECK_INTERVAL_MS) {
                return UpdateCheckOutcome.Throttled
            }
        }
        if (!operationMutex.tryLock()) {
            return UpdateCheckOutcome.Error("正在处理更新检查，请稍候")
        }
        return try {
            withContext(dispatchers.io) {
                var releaseDto: GitHubReleaseDto? = null
                var releaseBaseUrl: String? = null
                val failures = mutableListOf<String>()
                for (endpoint in updateEndpoints()) {
                    try {
                        releaseDto = fetchLatestRelease(endpoint)
                        releaseBaseUrl = endpoint.baseUrl
                        break
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        failures += "${endpoint.label}：${e.message ?: "连接失败"}"
                    }
                }
                val resolvedRelease = releaseDto ?: return@withContext UpdateCheckOutcome.Error(
                    message = failures.joinToString("；").ifBlank { "所有更新通道均不可用" },
                )
                val resolvedBaseUrl = releaseBaseUrl ?: releasesBaseUrl

                val prevState = stateStore.read()
                stateStore.write(prevState.copy(lastCheckedAtMs = clockMs()))

                val remoteTag = resolvedRelease.tagName.trim()
                if (remoteTag.isEmpty() || resolvedRelease.draft) {
                    return@withContext UpdateCheckOutcome.Error("未获取到有效的正式版本发布信息")
                }
                if (!isRemoteVersionNewer(remoteTag, appInfo.versionName)) {
                    pruneStaleApks(keepFile = null)
                    return@withContext UpdateCheckOutcome.UpToDate(
                        currentVersion = appInfo.versionName,
                        latestTag = remoteTag,
                    )
                }

                val selectedDto = selectReleaseAsset(
                    assets = resolvedRelease.assets,
                    compatibilityMode = signatureVerifier.needsCompatibilityPackage(),
                    supportedAbis = supportedAbisProvider(),
                ) ?: return@withContext UpdateCheckOutcome.Error("此版本暂未提供适配当前设备的正式 APK，请稍后重试")

                val asset = RavenReleaseAsset.fromDto(resolvedRelease.id, selectedDto)
                val cachedFile = findVerifiedCachedApk(asset, remoteTag)
                val releaseInfo = RavenReleaseInfo(
                    releaseId = resolvedRelease.id,
                    tagName = remoteTag,
                    title = resolvedRelease.name.ifBlank { remoteTag },
                    releaseNotes = resolvedRelease.body.orEmpty().trim().take(MAX_RELEASE_NOTES_CHARS),
                    htmlUrl = resolvedRelease.htmlUrl,
                    asset = asset,
                    cachedApk = cachedFile,
                    sourceBaseUrl = resolvedBaseUrl,
                )
                UpdateCheckOutcome.UpdateAvailable(releaseInfo)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            UpdateCheckOutcome.Error(
                message = e.message ?: "更新检查失败，请检查网络连接",
                cause = e,
            )
        } finally {
            operationMutex.unlock()
        }
    }

    private data class UpdateEndpoint(val label: String, val baseUrl: String)

    private fun updateEndpoints(): List<UpdateEndpoint> = buildList {
        add(UpdateEndpoint("GitHub", releasesBaseUrl))
        val mirror = appInfo.mirrorReleasesApiBaseUrl?.trim()?.trimEnd('/').orEmpty()
        if (mirror.isNotEmpty() && mirror != releasesBaseUrl) {
            add(UpdateEndpoint("VPS 备用通道", mirror))
        }
    }

    private suspend fun fetchLatestRelease(endpoint: UpdateEndpoint): GitHubReleaseDto {
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/latest")
            .header("Accept", "application/vnd.github+json, application/json")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Raven/${appInfo.versionName} (Android)")
            .build()
        return executeCancellable(metadataClient.newCall(request)).use { response ->
            if (response.code == 403 || response.code == 429) {
                throw IOException("${endpoint.label} 请求受限 (HTTP ${response.code})")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("发布信息为空")
            val source = body.source()
            if (body.contentLength() > MAX_METADATA_BYTES || source.request(MAX_METADATA_BYTES + 1L)) {
                throw IOException("发布清单超过大小上限")
            }
            json.decodeFromString(GitHubReleaseDto.serializer(), source.readUtf8())
        }
    }

    /**
     * Downloads [release]'s selected APK asset via [downloadClient] (read stall timeout, zero
     * whole-file timeout), verifies exact byte length + SHA-256 + [ApkSignatureVerifier], and
     * atomically publishes the verified file for installer handoff.
     */
    suspend fun downloadAndVerify(
        release: RavenReleaseInfo,
        onProgress: (UpdateDownloadProgress) -> Unit = {},
    ): File {
        if (!operationMutex.tryLock()) {
            throw IllegalStateException("另一个更新任务正在执行，请稍候")
        }
        return try {
            withContext(dispatchers.io) {
                val asset = release.asset
                findVerifiedCachedApk(asset, release.tagName)?.let { cached ->
                    onProgress(UpdateDownloadProgress(asset.sizeBytes, asset.sizeBytes))
                    return@withContext cached
                }

                val downloadBaseUrl = release.sourceBaseUrl?.trim()?.trimEnd('/')
                    ?.takeIf { it.isNotEmpty() } ?: releasesBaseUrl
                val targetUrl = "$downloadBaseUrl/assets/${asset.assetId}"
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("Accept", "application/octet-stream")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "Raven/${appInfo.versionName} (Android)")
                    .build()

                val targetDir = asset.cacheDirectory(updatesDir)
                check(targetDir.isDirectory || targetDir.mkdirs() || targetDir.isDirectory) {
                    "无法创建更新缓存目录"
                }

                val verifiedFile = executeCancellable(downloadClient.newCall(request)).use { response ->
                    check(response.isSuccessful) { "更新下载失败：HTTP ${response.code}" }
                    val body = response.body ?: throw IOException("更新响应内容为空")
                    val tempFile = File.createTempFile("download-", ".apk", targetDir)
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        var received = 0L
                        val buffer = ByteArray(64 * 1024)
                        val coroutineCtx = currentCoroutineContext()
                        body.byteStream().use { input ->
                            tempFile.outputStream().use { output ->
                                while (true) {
                                    coroutineCtx.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    received += read
                                    if (received > asset.sizeBytes) {
                                        throw UpdateVerificationException("安装包大小超出发布声明，已中止下载")
                                    }
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    onProgress(UpdateDownloadProgress(received, asset.sizeBytes))
                                }
                            }
                        }
                        coroutineCtx.ensureActive()
                        if (received != asset.sizeBytes) {
                            throw IOException("安装包下载不完整（期望 ${asset.sizeBytes} 字节，实际 $received 字节）")
                        }
                        val actualHash = bytesToHex(digest.digest())
                        val expectedHash = asset.expectedSha256
                        if (expectedHash != null && !actualHash.equals(expectedHash, ignoreCase = true)) {
                            throw UpdateVerificationException("安装包 SHA-256 摘要校验不一致，已中止安装")
                        }

                        signatureVerifier.verify(tempFile, release.tagName)
                        coroutineCtx.ensureActive()

                        val finalFile = File(targetDir, "$actualHash.apk")
                        if (!tempFile.renameTo(finalFile)) {
                            if (finalFile.exists()) finalFile.delete()
                            check(tempFile.renameTo(finalFile)) { "无法保存已校验的安装包" }
                        }
                        finalFile
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }

                val currentState = stateStore.read()
                stateStore.write(
                    currentState.copy(
                        pendingTag = release.tagName,
                        pendingReleaseId = asset.releaseId,
                        pendingAssetId = asset.assetId,
                        pendingSizeBytes = asset.sizeBytes,
                        pendingSha256 = verifiedFile.nameWithoutExtension,
                    ),
                )
                pruneStaleApks(keepFile = verifiedFile)
                verifiedFile
            }
        } finally {
            operationMutex.unlock()
        }
    }

    private suspend fun findVerifiedCachedApk(asset: RavenReleaseAsset, releaseTag: String): File? {
        val dir = asset.cacheDirectory(updatesDir)
        if (!dir.isDirectory) return null
        val candidates = asset.expectedSha256?.let { listOf(File(dir, "$it.apk")) }
            ?: dir.listFiles().orEmpty().toList()
        val coroutineCtx = currentCoroutineContext()
        return candidates.firstOrNull { file ->
            val hashName = file.nameWithoutExtension
            if (!file.isFile || file.extension != "apk" || !SHA256_REGEX.matches(hashName)) {
                return@firstOrNull false
            }
            if (file.length() != asset.sizeBytes) return@firstOrNull false
            runCatching {
                val actual = sha256File(file) { coroutineCtx.ensureActive() }
                if (!actual.equals(hashName, ignoreCase = true)) return@firstOrNull false
                signatureVerifier.verify(file, releaseTag)
                true
            }.getOrDefault(false)
        }
    }

    private suspend fun pruneStaleApks(keepFile: File?) {
        val apkRoot = File(updatesDir, "apk")
        if (!apkRoot.isDirectory) return
        val keepCanonical = keepFile?.let { runCatching { it.canonicalFile }.getOrNull() }
        for (dir in apkRoot.listFiles().orEmpty()) {
            if (!dir.isDirectory) continue
            for (file in dir.listFiles().orEmpty()) {
                if (!file.isFile) continue
                val sameAsKeep = keepCanonical != null &&
                    runCatching { file.canonicalFile == keepCanonical }.getOrDefault(false)
                if (sameAsKeep) continue
                repeat(3) { attempt ->
                    if (!file.exists() || file.delete()) return@repeat
                    if (attempt < 2) delay(20L)
                }
            }
            if (dir.list().isNullOrEmpty()) {
                dir.delete()
            }
        }
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
        const val AUTO_CHECK_INTERVAL_MS: Long = 12L * 60L * 60L * 1_000L
        internal const val MAX_METADATA_BYTES = 1024L * 1024L
        private const val MAX_RELEASE_NOTES_CHARS: Int = 2_000
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        /**
         * Semantic version comparison deciding whether [remoteTag] is strictly newer than [currentVersion].
         * Strips leading `v`/`V`, compares numeric segments left-to-right, and treats stable releases
         * as newer than same-base prereleases (`1.2.0` > `1.2.0-beta.1`, `1.2.0-rc.2` > `1.2.0-rc.1`).
         */
        fun isRemoteVersionNewer(remoteTag: String, currentVersion: String): Boolean {
            val remote = parseSemVer(remoteTag) ?: return false
            val current = parseSemVer(currentVersion) ?: return false

            val maxLen = maxOf(remote.numbers.size, current.numbers.size)
            for (i in 0 until maxLen) {
                val r = remote.numbers.getOrElse(i) { 0 }
                val c = current.numbers.getOrElse(i) { 0 }
                if (r != c) return r > c
            }

            // Equal numeric segments: stable > prerelease
            val rPre = remote.prerelease
            val cPre = current.prerelease
            if (rPre == null && cPre == null) return false
            if (rPre == null) return true
            if (cPre == null) return false
            return comparePrerelease(rPre, cPre) > 0
        }

        /**
         * Selects the best matching release APK asset from [assets]:
         * - Excludes non-`.apk`, empty (`size <= 0`), and `debug` APKs.
         * - Prefers a compatible legacy variant when [compatibilityMode] is true (and excludes
         *   `-compat` packages when false).
         * - Prefers device ABI match in [supportedAbis] priority order, then `universal` or generic
         *   architecture-neutral release APKs, avoiding incompatible ABI builds.
         */
        fun selectReleaseAsset(
            assets: List<GitHubReleaseAssetDto>,
            compatibilityMode: Boolean = false,
            supportedAbis: List<String> = emptyList(),
        ): GitHubReleaseAssetDto? {
            val apkCandidates = assets.filter { asset ->
                val lower = asset.name.lowercase(Locale.ROOT)
                asset.id > 0L &&
                    asset.size > 0L &&
                    lower.endsWith(".apk") &&
                    !lower.contains("debug")
            }
            if (apkCandidates.isEmpty()) return null

            val compatible = if (compatibilityMode) apkCandidates.filter { isCompatPackageName(it.name) } else emptyList()
            val regular = apkCandidates.filterNot { isCompatPackageName(it.name) }
            return bestAbiAsset(compatible, supportedAbis) ?: bestAbiAsset(regular, supportedAbis)
        }

        private fun bestAbiAsset(candidates: List<GitHubReleaseAssetDto>, supportedAbis: List<String>): GitHubReleaseAssetDto? {

            val normalizedAbis = supportedAbis.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }

            // 1. Prefer highest-priority matching ABI asset.
            for (abi in normalizedAbis) {
                candidates.firstOrNull { asset ->
                    val name = asset.name.lowercase(Locale.ROOT)
                    KNOWN_ABIS.firstOrNull { name.contains(it) } == abi
                }?.let { return it }
            }

            // 2. Prefer explicit universal APK or ABI-neutral APK (no known ABI token in name).
            candidates.firstOrNull { it.name.lowercase(Locale.ROOT).contains("universal") }?.let { return it }
            candidates.firstOrNull { asset ->
                val lower = asset.name.lowercase(Locale.ROOT)
                KNOWN_ABIS.none { abi -> lower.contains(abi) }
            }?.let { return it }

            // 3. If no ABI hint was supplied (e.g., generic check), return first compat-matched APK.
            return if (normalizedAbis.isEmpty()) candidates.firstOrNull() else null
        }

        private fun isCompatPackageName(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            return lower.contains("-compat") || lower.contains("_compat")
        }

        private data class ParsedVersion(
            val numbers: List<Int>,
            val prerelease: String?,
        )

        private fun parseSemVer(raw: String): ParsedVersion? {
            val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            if (cleaned.isEmpty()) return null
            val dashIdx = cleaned.indexOf('-')
            val numericPart = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
            val prePart = if (dashIdx >= 0) cleaned.substring(dashIdx + 1).trim().takeIf { it.isNotEmpty() } else null
            val segments = numericPart.split('.').map { seg ->
                seg.takeWhile(Char::isDigit).toIntOrNull()
            }
            if (segments.isEmpty() || segments.all { it == null }) return null
            return ParsedVersion(
                numbers = segments.map { it ?: 0 },
                prerelease = prePart,
            )
        }

        private fun comparePrerelease(a: String, b: String): Int {
            val partsA = a.split('.', '-', '_').filter { it.isNotEmpty() }
            val partsB = b.split('.', '-', '_').filter { it.isNotEmpty() }
            val max = maxOf(partsA.size, partsB.size)
            for (i in 0 until max) {
                val pa = partsA.getOrNull(i) ?: return -1
                val pb = partsB.getOrNull(i) ?: return 1
                val na = pa.toIntOrNull()
                val nb = pb.toIntOrNull()
                val cmp = when {
                    na != null && nb != null -> na.compareTo(nb)
                    else -> pa.lowercase(Locale.ROOT).compareTo(pb.lowercase(Locale.ROOT))
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

/**
 * Helper for `:app`'s `SettingsNavigator.openAppUpdateInstaller` to construct the FileProvider
 * package installer Intent and Android 8+ unknown-sources permission Intent cleanly.
 */
object RavenUpdateInstaller {
    fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    fun unknownSourcesPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    fun createInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

private fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { b -> String.format(Locale.ROOT, "%02x", b) }

private fun sha256Hex(bytes: ByteArray): String =
    bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun sha256File(file: File, checkActive: () -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    file.inputStream().use { input ->
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return bytesToHex(digest.digest())
}
