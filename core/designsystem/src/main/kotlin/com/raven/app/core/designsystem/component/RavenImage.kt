package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.model.SiteId

/**
 * Hook provided at app root so `:core:network` can attach per-site Coil 3 request extras
 * (e.g. `SiteId` key, Referer/User-Agent headers, partitioned cache keys) without creating
 * a circular module dependency from `:core:designsystem` to `:core:network`.
 */
val LocalRavenImageRequestDecorator =
    staticCompositionLocalOf<(ImageRequest.Builder, SiteId?) -> ImageRequest.Builder> {
        { builder, _ -> builder }
    }

/**
 * Thin Coil 3 [AsyncImage] wrapper with Obsidian matte placeholder, reduced-motion aware crossfade,
 * per-site [LocalRavenImageRequestDecorator] decoration, and optional aspect-ratio callback.
 */
@Composable
fun RavenImage(
    url: String,
    modifier: Modifier = Modifier,
    siteId: SiteId? = null,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    crossfadeMillis: Int = RavenMotion.IMAGE_CROSSFADE_MILLIS,
    placeholderTopColor: Color = LocalRavenColors.current.surface2,
    placeholderBottomColor: Color = LocalRavenColors.current.surface1,
    showErrorIcon: Boolean = true,
    onAspectRatioResolved: ((Float) -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val platformContext = LocalPlatformContext.current
    val decorator = LocalRavenImageRequestDecorator.current
    val motionReduced = LocalMotionReduced.current
    val currentRatioCallback by rememberUpdatedState(onAspectRatioResolved)

    var loadState by remember(url, siteId) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }

    val effectiveCrossfade = if (motionReduced) 0 else crossfadeMillis.coerceAtLeast(0)
    val request = remember(platformContext, url, siteId, effectiveCrossfade, decorator) {
        val builder = ImageRequest.Builder(platformContext)
            .data(url.ifBlank { null })
            .crossfade(effectiveCrossfade)
        decorator(builder, siteId).build()
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .drawWithCache {
                val placeholderBrush = Brush.verticalGradient(
                    colors = listOf(placeholderTopColor, placeholderBottomColor),
                )
                onDrawBehind {
                    drawRect(brush = placeholderBrush)
                }
            },
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onState = { state ->
                    loadState = state
                    if (state is AsyncImagePainter.State.Success) {
                        val image = state.result.image
                        if (image.width > 0 && image.height > 0) {
                            currentRatioCallback?.invoke(image.width.toFloat() / image.height.toFloat())
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (loadState is AsyncImagePainter.State.Success) {
                            Modifier.testTag("raven_image_ready")
                        } else {
                            Modifier
                        },
                    ),
            )
        }

        AnimatedVisibility(
            visible = showErrorIcon && (url.isBlank() || loadState is AsyncImagePainter.State.Error),
            enter = fadeIn(if (motionReduced) snap() else tween(durationMillis = 160)),
            exit = fadeOut(if (motionReduced) snap() else tween(durationMillis = 100)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                tint = colors.inkTertiary,
                modifier = Modifier
                    .size(22.dp)
                    .testTag("raven_image_error"),
            )
        }
    }
}
