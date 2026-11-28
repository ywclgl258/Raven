package com.raven.app

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import kotlinx.serialization.json.Json
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.feature.player.PlayerLaunchArgs
import java.io.File

/** 路由契约辅助：作品/连载 ID 解析、`ReaderParent` 恢复、搜索路由构造与阅读器导航规划。从 `RavenNavHost.kt` 拆出，包名与语义不变。 */

/**
 * Supplies the missing detail parent for readers opened from resume cards, history or deep links.
 * A matching detail already on top is reused so normal detail -> reader navigation stays unchanged.
 */
internal fun readerWorkId(mediaId: String): String = mediaId.substringBefore(':')

internal fun readerSeriesId(reader: RavenRoute.Reader): String =
    reader.parent?.seriesId?.takeIf(String::isNotBlank) ?: readerWorkId(reader.mediaId)

internal fun readerParentDetail(
    currentDetail: RavenRoute.GalleryDetail?,
    reader: RavenRoute.Reader,
): RavenRoute.GalleryDetail? {
    val workId = readerSeriesId(reader)
    // If the current detail is already the work root, reuse it (no parent inserted).
    if (currentDetail != null &&
        currentDetail.siteId == reader.siteId &&
        currentDetail.mediaId == workId &&
        currentDetail.offlineOnly == reader.offlineOnly
    ) {
        return null
    }
    // Otherwise insert the work-level detail so Back returns to the whole work's
    // detail page instead of a single chapter's detail page (avoids nesting).
    return RavenRoute.GalleryDetail(
        siteId = reader.siteId,
        mediaId = workId,
        url = reader.parent?.url?.takeIf(String::isNotBlank) ?: reader.url,
        offlineOnly = reader.offlineOnly,
    )
}

internal val ReaderParentNavType: NavType<RavenRoute.ReaderParent?> =
    object : NavType<RavenRoute.ReaderParent?>(isNullableAllowed = true) {
        override fun get(bundle: Bundle, key: String): RavenRoute.ReaderParent? =
            bundle.getString(key)?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { Json.decodeFromString<RavenRoute.ReaderParent>(it) }

        override fun put(bundle: Bundle, key: String, value: RavenRoute.ReaderParent?) {
            if (value == null) bundle.remove(key) else bundle.putString(key, Json.encodeToString(value))
        }

        override fun parseValue(value: String): RavenRoute.ReaderParent? =
            value.takeIf { it.isNotBlank() && it != "null" }
                ?.let { raw ->
                    runCatching { Json.decodeFromString<RavenRoute.ReaderParent>(raw) }
                        .getOrElse {
                            val decoded = Uri.decode(raw)
                            runCatching { Json.decodeFromString<RavenRoute.ReaderParent>(decoded) }.getOrNull()
                        }
                }

        override fun serializeAsValue(value: RavenRoute.ReaderParent?): String =
            value?.let { Uri.encode(Json.encodeToString(it)) } ?: "null"
    }

/** Search destination used by Gallery Detail tag actions; canonical slug is preferred over translations. */
internal fun tagSearchRoute(tag: TagRef, crossSite: Boolean, sourceSiteId: SiteId? = null): RavenRoute.Search =
    RavenRoute.Search(
        initialQuery = tag.slug.trim().ifBlank { tag.label.trim() }.takeIf { it.isNotEmpty() },
        crossSite = crossSite,
        sourceSiteId = sourceSiteId?.value,
    )

/** Search destination used by Gallery Detail author taps and long-press actions. */
internal fun authorSearchRoute(author: AuthorRef, crossSite: Boolean, sourceSiteId: SiteId? = null): RavenRoute.Search =
    RavenRoute.Search(
        initialQuery = author.name.trim().ifBlank { author.id.trim() }.takeIf { it.isNotEmpty() },
        crossSite = crossSite,
        sourceSiteId = sourceSiteId?.value,
    )

internal data class ReaderNavigationPlan(
    val popCurrentReader: Boolean,
    val popStaleParentDetail: Boolean,
    val insertParentDetail: RavenRoute.GalleryDetail?,
)

/**
 * Plans backstack adjustment when entering or advancing inside [RavenRoute.Reader] so pressing Back
 * always returns to the active work's [RavenRoute.GalleryDetail] (never a previous queue item's detail).
 */
internal fun planReaderNavigation(
    currentReader: RavenRoute.Reader?,
    currentDetail: RavenRoute.GalleryDetail?,
    previousDetail: RavenRoute.GalleryDetail?,
    targetReader: RavenRoute.Reader,
): ReaderNavigationPlan {
    if (currentReader == null) {
        return ReaderNavigationPlan(
            popCurrentReader = false,
            popStaleParentDetail = false,
            insertParentDetail = readerParentDetail(currentDetail, targetReader),
        )
    }
    val targetWorkId = readerSeriesId(targetReader)
    val matchesPrevious = previousDetail != null &&
        previousDetail.siteId == targetReader.siteId &&
        (previousDetail.parent?.seriesId?.takeIf(String::isNotBlank) ?: readerWorkId(previousDetail.mediaId)) == targetWorkId &&
        previousDetail.offlineOnly == targetReader.offlineOnly
    if (matchesPrevious) {
        return ReaderNavigationPlan(
            popCurrentReader = true,
            popStaleParentDetail = false,
            insertParentDetail = null,
        )
    }
    return ReaderNavigationPlan(
        popCurrentReader = true,
        popStaleParentDetail = previousDetail != null,
        insertParentDetail = RavenRoute.GalleryDetail(
            siteId = targetReader.siteId,
            mediaId = targetWorkId,
            url = targetReader.parent?.url?.takeIf(String::isNotBlank) ?: targetReader.url,
            offlineOnly = targetReader.offlineOnly,
        ),
    )
}

/**
 * Host platform callbacks supplied by [MainActivity] for features that interact with Android
 * Activities, SAF document pickers, FileProvider sharing, window chrome, and hardware keys.
 */
interface MainActivityHostActions {
    val fileProviderAuthority: String
    fun launchPlayer(args: PlayerLaunchArgs)
    fun launchWebViewAuth(siteId: SiteId)
    fun setSystemBarsVisible(visible: Boolean)
    fun setReaderBrightness(value: Float)
    fun setKeepScreenOn(enabled: Boolean)
    fun registerVolumeKeyHandler(handler: ((up: Boolean, isLongPress: Boolean) -> Boolean)?)
    fun shareFiles(uris: List<Uri>, mimeType: String, subject: String)
    fun openAppUpdateInstaller(apkFile: File)
}

/**
 * Pending incoming `ACTION_SEND` shared text resolved via [SiteRegistry.resolveLinkFromText].
 */
data class SharedLinkPrompt(
    val rawText: String,
    val plugin: SitePlugin?,
    val resolved: ResolvedLink?,
)
