package com.raven.app.feature.library

import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalRavenColors

/** Shared presentation mode for subscription updates, downloads and favorites. */
enum class LibraryCollectionLayout(val label: String) {
    WATERFALL("瀑布流"),
    LIST("列表"),
}

@Composable
internal fun LibraryCollectionLayoutButton(
    layout: LibraryCollectionLayout,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val nextLabel = if (layout == LibraryCollectionLayout.WATERFALL) {
        LibraryCollectionLayout.LIST.label
    } else {
        LibraryCollectionLayout.WATERFALL.label
    }
    IconButton(
        onClick = onToggle,
        modifier = modifier.testTag("library_collection_layout_toggle"),
    ) {
        RavenMorphIcon(
            glyph = if (layout == LibraryCollectionLayout.WATERFALL) {
                RavenMorphIcons.ListView
            } else {
                RavenMorphIcons.GridView
            },
            contentDescription = "切换为$nextLabel 展示",
            tint = colors.inkPrimary,
            size = 22.dp,
            springPreset = MorphSpringPreset.SNAPPY,
        )
    }
}
