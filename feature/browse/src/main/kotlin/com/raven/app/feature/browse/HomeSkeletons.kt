package com.raven.app.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenGalleryCardSkeleton
import com.raven.app.core.designsystem.component.RavenSkeletonBox
import com.raven.app.core.designsystem.component.RavenVideoCardSkeleton
import com.raven.app.core.designsystem.component.rememberRavenShimmerProgress
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.model.MediaItem

/** 首页加载骨架屏：影院式与瀑布流两套骨架。 */

@Composable
internal fun CinemaHomeSkeleton() {
    val shimmerProgress = rememberRavenShimmerProgress()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("cinema_home_skeleton"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RavenSkeletonBox(
            shape = RavenShapes.CardShape,
            progress = shimmerProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RavenSkeletonBox(
                progress = shimmerProgress,
                modifier = Modifier
                    .width(120.dp)
                    .height(18.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RavenVideoCardSkeleton(progress = shimmerProgress, modifier = Modifier.weight(1f))
                RavenVideoCardSkeleton(progress = shimmerProgress, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun GalleryMasonrySkeleton() {
    val shimmerProgress = rememberRavenShimmerProgress()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("gallery_masonry_skeleton"),
    ) {
        val columns = computeGalleryGridColumns(maxWidth.value.toInt())
        val ratios = listOf(0.70f, 0.85f, 0.65f, 0.78f, 0.72f, 0.82f)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) { rowIdx ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(columns) { colIdx ->
                        RavenGalleryCardSkeleton(
                            aspectRatio = ratios[(rowIdx * columns + colIdx) % ratios.size],
                            progress = shimmerProgress,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
