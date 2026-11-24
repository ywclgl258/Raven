package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.model.ComicLanguage

/** Localized display label for [ComicLanguage] shared across language bar, explore, and settings. */
fun ComicLanguage.displayLabel(): String = when (this) {
    ComicLanguage.ALL -> "全部"
    ComicLanguage.CHINESE -> "中文"
    ComicLanguage.JAPANESE -> "日文"
    ComicLanguage.CHINESE_AND_JAPANESE -> "中＋日"
}

/**
 * Animated collapsible single-row comic language filter pill strip (`全部 / 中文 / 日文 / 中＋日`)
 * for sites declaring `SiteCapability.LANGUAGE_FILTER`.
 */
@Composable
fun RavenLanguageBar(
    selected: ComicLanguage,
    onSelected: (ComicLanguage) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    languages: List<ComicLanguage> = ComicLanguage.entries,
    showBottomHairline: Boolean = true,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current

    AnimatedVisibility(
        visible = visible,
        enter = if (motionReduced) {
            EnterTransition.None
        } else {
            expandVertically(
                animationSpec = tween(
                    durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
                    easing = RavenMotion.AppleDecelerationEasing,
                ),
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                    easing = RavenMotion.AppleDecelerationEasing,
                ),
            )
        },
        exit = if (motionReduced) {
            ExitTransition.None
        } else {
            shrinkVertically(
                animationSpec = tween(
                    durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                    easing = RavenMotion.AppleDecelerationEasing,
                ),
            ) + fadeOut(
                animationSpec = tween(
                    durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS - 30,
                    easing = RavenMotion.AppleDecelerationEasing,
                ),
            )
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.canvas)
                .testTag("raven_language_bar"),
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(vertical = 2.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(languages, key = { it.name }) { language ->
                    RavenFilterChip(
                        label = language.displayLabel(),
                        selected = language == selected,
                        onClick = { onSelected(language) },
                        modifier = Modifier.testTag("lang_pill_${language.name.lowercase()}"),
                    )
                }
            }
            if (showBottomHairline) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = colors.hairline,
                )
            }
        }
    }
}
