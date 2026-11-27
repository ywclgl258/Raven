package com.raven.app.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion

/**
 * Bounded reveal fraction for [RavenTextReveal]. Non-finite progress resolves to fully revealed
 * so a corrupt animation value can never leave text permanently invisible.
 */
internal fun revealClipFraction(progress: Float): Float =
    if (!progress.isFinite()) 1f else progress.coerceIn(0f, 1f)

/**
 * Cinema-style headline reveal: the text is laid out at its final position and revealed by an
 * expanding horizontal clip, so there is zero layout shift while the title enters.
 *
 * Restarts whenever [revealKey] changes (e.g. hero page changed), snaps immediately when
 * [LocalMotionReduced] is active, and never exceeds the text bounds.
 */
@Composable
fun RavenTextReveal(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalRavenColors.current.inkPrimary,
    revealKey: Any? = text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign = TextAlign.Unspecified,
    durationMillis: Int = 320,
) {
    val animate = !LocalMotionReduced.current && durationMillis > 0
    val progress = remember(revealKey) { Animatable(if (animate) 0f else 1f) }

    LaunchedEffect(progress, animate, durationMillis) {
        if (animate) {
            progress.animateTo(1f, tween(durationMillis, easing = RavenMotion.HarmonyDecelerationEasing))
        } else {
            progress.snapTo(1f)
        }
    }

    Text(
        text = text,
        modifier = modifier.drawWithContent {
            val fraction = if (animate) revealClipFraction(progress.value) else 1f
            if (fraction >= 1f) {
                drawContent()
            } else {
                val width = size.width * fraction
                val rtl = layoutDirection == LayoutDirection.Rtl
                clipRect(left = if (rtl) size.width - width else 0f, right = if (rtl) size.width else width) {
                    this@drawWithContent.drawContent()
                }
            }
        },
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}
