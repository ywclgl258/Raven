package com.raven.app.feature.settings

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.TagTranslationEntry
import com.raven.app.core.model.TagTranslationSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface TagSyncResult {
    data class Updated(
        val releaseTag: String,
        val entryCount: Int,
        val etag: String,
    ) : TagSyncResult

    data class NotModified(
        val releaseTag: String,
        val entryCount: Int,
    ) : TagSyncResult

    data class Skipped(
        val reason: String,
    ) : TagSyncResult

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : TagSyncResult
}

@Serializable
internal data class EhTagDatabaseDto(
    val version: Int = 0,
    val data: List<EhTagNamespaceBlockDto> = emptyList(),
)

@Serializable
internal data class EhTagNamespaceBlockDto(
    val namespace: String = "",
    val data: Map<String, EhTagItemDto> = emptyMap(),
)

@Serializable
internal data class EhTagItemDto(
    val name: String = "",
    val intro: String = "",
)

/**
 * Automatic & on-demand EhTagTranslation community Chinese tag pack synchronizer.
 *
 * Security & networking guarantees:
 * - Reuses Koin's `NetworkQualifiers.API` [OkHttpClient] (follows system proxy, never sends site
 *   cookies or Bearer tokens to GitHub).
 * - Uses `If-None-Match` conditional requests (`304 Not Modified`) and 7-day automatic throttling.
 * - Streams & bounds gzip decompression safely before bulk-saving into Room and hydrating
 *   [TagDictionary] runtime dynamic layers.
 */
class EhTagTranslationSyncer(
    private val apiClient: OkHttpClient,
    private val tagDictionaryRepository: TagDictionaryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val appInfo: RavenAppInfo = RavenAppInfo(
        versionName = "0.1.0",
        versionCode = 1L,
        isDebug = false,
        packageName = RavenAppInfo.DEFAULT_PACKAGE_NAME,
    ),
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val releasesApiBaseUrlOverride: String? = null,
) {
    private val syncMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val releasesBaseUrl: String
        get() = (releasesApiBaseUrlOverride ?: DEFAULT_RELEASES_API_BASE_URL).trimEnd('/')

    /**
     * Non-blocking, exception-safe 7-day throttled background sync invoked on app startup.
     */
    suspend fun syncIfDue(nowMs: Long = clockMs()): TagSyncResult = try {
        val settings = preferencesRepository.currentSettings()
        if (!settings.tagDictAutoSync) {
            TagSyncResult.Skipped("已关闭每周自动更新")
        } else if (
            settings.tagDictUpdatedAt > 0L &&
            nowMs >= settings.tagDictUpdatedAt &&
            (nowMs - settings.tagDictUpdatedAt) < AUTO_SYNC_INTERVAL_MS
        ) {
            TagSyncResult.Skipped("距离上次同步未满 7 天")
        } else {
            syncNow(nowMs = nowMs)
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Throwable) {
        TagSyncResult.Error(
            message = e.message?.takeIf(String::isNotBlank) ?: "自动同步词库失败",
            cause = e,
        )
    }

    /**
     * Fetches the latest EhTagTranslation release (`db.text.json.gz`, fallback `db.text.json`),
     * parses all entries, replaces Room's `COMMUNITY_EHTAG` table, and hydrates runtime [TagDictionary].
     */
    suspend fun syncNow(nowMs: Long = clockMs()): TagSyncResult {
        if (!syncMutex.tryLock()) {
            return TagSyncResult.Skipped("正在同步社区词库，请稍候")
        }
        return try {
            withContext(dispatchers.io) {
                val settings = preferencesRepository.currentSettings()
                val storedEtag = settings.tagDictEtag.trim()
                val storedReleaseTag = settings.tagDictReleaseTag.trim()
                val existingStats = runCatching { tagDictionaryRepository.observeStats().first() }.getOrNull()
                val existingCommunityCount = existingStats?.communityCount ?: 0

                val latestUrl = "$releasesBaseUrl/latest"
                val metadataRequestBuilder = Request.Builder()
                    .url(latestUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", userAgent())
                if (storedEtag.isNotBlank()) {
                    metadataRequestBuilder.header("If-None-Match", storedEtag)
                }

                var metadataEtag = ""
                var fallbackDirectAsset = false
                val releaseDto: GitHubReleaseDto? = executeCancellable(
                    apiClient.newCall(metadataRequestBuilder.build()),
                ).use { response ->
                    if (response.code == 304) {
                        preferencesRepository.setTagDictUpdatedAt(nowMs)
                        return@withContext TagSyncResult.NotModified(
                            releaseTag = storedReleaseTag,
                            entryCount = existingCommunityCount,
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    if ((response.code == 403 || response.code == 404 || response.code == 429) &&
                        releasesApiBaseUrlOverride == null
                    ) {
                        fallbackDirectAsset = true
                        return@use null
                    }
                    if (response.code == 403 || response.code == 429) {
                        val msg = if (body.contains("rate limit", ignoreCase = true)) {
                            "GitHub 请求过于频繁，请稍后重试"
                        } else {
                            "GitHub 拒绝访问 (HTTP ${response.code})，请检查网络或代理设置"
                        }
                        return@withContext TagSyncResult.Error(msg)
                    }
                    if (!response.isSuccessful) {
                        return@withContext TagSyncResult.Error("获取社区词库发布信息失败：HTTP ${response.code}")
                    }
                    metadataEtag = response.header("ETag").orEmpty().trim()
                    json.decodeFromString(GitHubReleaseDto.serializer(), body)
                }

                val remoteTag: String
                val assetName: String
                val downloadUrl: String
                if (fallbackDirectAsset) {
                    remoteTag = "latest"
                    assetName = ASSET_GZIP_NAME
                    downloadUrl = FALLBACK_LATEST_GZIP_URL
                } else {
                    val release = releaseDto
                        ?: return@withContext TagSyncResult.Error("未获取到有效的词库发布元数据")
                    remoteTag = release.tagName.trim().ifBlank { release.name.trim() }

                    if (existingCommunityCount > 0 && remoteTag.isNotEmpty() && remoteTag == storedReleaseTag) {
                        val effectiveEtag = metadataEtag.ifBlank { storedEtag }
                        preferencesRepository.setTagDictSyncState(
                            etag = effectiveEtag,
                            releaseTag = remoteTag,
                            updatedAt = nowMs,
                        )
                        return@withContext TagSyncResult.NotModified(
                            releaseTag = remoteTag,
                            entryCount = existingCommunityCount,
                        )
                    }

                    val selectedAsset = selectDatabaseAsset(release.assets)
                        ?: return@withContext TagSyncResult.Error("当前发布未包含 db.text.json.gz 或 db.text.json 词库文件")

                    assetName = selectedAsset.name
                    downloadUrl = selectedAsset.browserDownloadUrl.trim().ifBlank {
                        if (selectedAsset.id > 0L) "$releasesBaseUrl/assets/${selectedAsset.id}" else ""
                    }
                }
                if (downloadUrl.isBlank()) {
                    return@withContext TagSyncResult.Error("社区词库下载地址无效")
                }

                val assetRequestBuilder = Request.Builder()
                    .url(downloadUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", userAgent())
                if (storedEtag.isNotBlank()) {
                    assetRequestBuilder.header("If-None-Match", storedEtag)
                }

                var assetEtag = ""
                val rawBytes: ByteArray = executeCancellable(
                    apiClient.newCall(assetRequestBuilder.build()),
                ).use { response ->
                    if (response.code == 304) {
                        preferencesRepository.setTagDictUpdatedAt(nowMs)
                        return@withContext TagSyncResult.NotModified(
                            releaseTag = remoteTag.ifBlank { storedReleaseTag },
                            entryCount = existingCommunityCount,
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext TagSyncResult.Error("下载社区词库失败：HTTP ${response.code}")
                    }
                    assetEtag = response.header("ETag").orEmpty().trim()
                    val body = response.body ?: throw IOException("词库响应内容为空")
                    readBoundedBytes(body.byteStream(), MAX_DOWNLOAD_BYTES)
                }

                val jsonText = decodePayloadText(
                    assetName = assetName,
                    rawBytes = rawBytes,
                )
                val entries = parseEhTagJson(
                    jsonText = jsonText,
                    updatedAt = nowMs,
                    json = json,
                )
                if (entries.isEmpty()) {
                    return@withContext TagSyncResult.Error("解析社区词库结果为空，已保留原有词典")
                }

                val savedCount = tagDictionaryRepository.replaceCommunityTranslations(entries)
                tagDictionaryRepository.hydrateRuntimeDictionary()

                val finalEtag = assetEtag.ifBlank { metadataEtag }.ifBlank { storedEtag }
                preferencesRepository.setTagDictSyncState(
                    etag = finalEtag,
                    releaseTag = remoteTag,
                    updatedAt = nowMs,
                )

                TagSyncResult.Updated(
                    releaseTag = remoteTag,
                    entryCount = savedCount,
                    etag = finalEtag,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            TagSyncResult.Error(
                message = e.message?.takeIf(String::isNotBlank) ?: "同步社区词库失败，请检查网络连接",
                cause = e,
            )
        } finally {
            syncMutex.unlock()
        }
    }

    private fun userAgent(): String = "Raven/${appInfo.versionName} (Android)"

    private suspend fun readBoundedBytes(
        input: java.io.InputStream,
        maxBytes: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        val coroutineCtx = currentCoroutineContext()
        input.use { stream ->
            while (true) {
                coroutineCtx.ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IOException("社区词库体积超出安全上限（$maxBytes 字节）")
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
        const val DEFAULT_RELEASES_API_BASE_URL: String =
            "https://api.github.com/repos/EhTagTranslation/Database/releases"
        const val FALLBACK_LATEST_GZIP_URL: String =
            "https://github.com/EhTagTranslation/Database/releases/latest/download/db.text.json.gz"
        const val AUTO_SYNC_INTERVAL_MS: Long = 7L * 24L * 60L * 60L * 1_000L

        private const val ASSET_GZIP_NAME: String = "db.text.json.gz"
        private const val ASSET_PLAIN_NAME: String = "db.text.json"
        private const val MAX_DOWNLOAD_BYTES: Int = 24 * 1024 * 1024
        private const val MAX_DECOMPRESSED_BYTES: Int = 48 * 1024 * 1024
        private const val MAX_INTRO_CHARS: Int = 240

        private val MARKDOWN_IMAGE_REGEX = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
        private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val MULTI_SPACE_REGEX = Regex("\\s+")

        internal fun selectDatabaseAsset(assets: List<GitHubReleaseAssetDto>): GitHubReleaseAssetDto? {
            if (assets.isEmpty()) return null
            assets.firstOrNull { it.name.trim().equals(ASSET_GZIP_NAME, ignoreCase = true) }?.let { return it }
            return assets.firstOrNull { it.name.trim().equals(ASSET_PLAIN_NAME, ignoreCase = true) }
        }

        internal fun decodePayloadText(assetName: String, rawBytes: ByteArray): String {
            val isGzip = assetName.trim().lowercase(Locale.ROOT).endsWith(".gz") ||
                (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte())
            if (!isGzip) {
                return rawBytes.toString(Charsets.UTF_8)
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0
            GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gzip ->
                while (true) {
                    val read = gzip.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DECOMPRESSED_BYTES) {
                        throw IOException("解压后的词库体积超出安全上限")
                    }
                    out.write(buffer, 0, read)
                }
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }

        internal fun cleanMarkdownText(raw: String): String {
            if (raw.isBlank()) return ""
            return raw
                .replace(MARKDOWN_IMAGE_REGEX, "")
                .replace(MARKDOWN_LINK_REGEX, "$1")
                .replace(HTML_TAG_REGEX, "")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }

        internal fun parseEhTagJson(
            jsonText: String,
            updatedAt: Long,
            json: Json = Json { ignoreUnknownKeys = true },
        ): List<TagTranslationEntry> {
            val parsed = json.decodeFromString(EhTagDatabaseDto.serializer(), jsonText)
            val result = ArrayList<TagTranslationEntry>(4_096)
            for (block in parsed.data) {
                val ns = TagDictionary.normalizeNamespace(block.namespace)
                if (ns == "rows") continue
                for ((rawSlug, item) in block.data) {
                    val cleanSlug = TagDictionary.normalizeBareSlug(rawSlug)
                    val cleanName = cleanMarkdownText(item.name)
                    if (cleanSlug.isEmpty() || cleanName.isEmpty()) continue
                    val cleanIntro = cleanMarkdownText(item.intro).take(MAX_INTRO_CHARS)
                    result.add(
                        TagTranslationEntry(
                            namespace = ns,
                            rawSlug = cleanSlug,
                            chineseName = cleanName,
                            intro = cleanIntro,
                            source = TagTranslationSource.COMMUNITY_EHTAG,
                            updatedAt = updatedAt,
                        ),
                    )
                }
            }
            return result
        }
    }
}
