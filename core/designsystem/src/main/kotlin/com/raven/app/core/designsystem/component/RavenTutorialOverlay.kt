package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes

/**
 * Single instructional step inside a first-use feature tutorial.
 */
@Immutable
data class RavenTutorialStep(
    val title: String,
    val description: String,
    val icon: ImageVector = Icons.Outlined.AutoAwesome,
    val badge: String? = null,
)

/**
 * First-use feature onboarding dialog with step dots, spring entry, and a one-tap dismiss flow.
 */
@Composable
fun RavenFeatureTutorialDialog(
    featureTitle: String,
    steps: List<RavenTutorialStep>,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    onSkipAll: (() -> Unit)? = null,
) {
    if (steps.isEmpty()) return
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    var currentIndex by remember(featureTitle, steps) { mutableIntStateOf(0) }
    val step = steps[currentIndex.coerceIn(0, steps.lastIndex)]
    val isLastStep = currentIndex >= steps.lastIndex

    Dialog(
        onDismissRequest = onComplete,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = if (motionReduced) {
                fadeIn(tween(0))
            } else {
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 460f),
                ) + fadeIn(tween(180))
            },
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.96f),
        ) {
            Column(
                modifier = modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .testTag("raven_feature_tutorial_dialog")
                    .clip(RavenShapes.SheetShape)
                    .background(colors.surface2)
                    .border(
                        width = 1.dp,
                        color = colors.crimson.copy(alpha = 0.30f),
                        shape = RavenShapes.SheetShape,
                    )
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RavenShapes.PillShape)
                                .background(colors.crimson.copy(alpha = 0.16f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "功能导览",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.crimson,
                            )
                        }
                        Text(
                            text = featureTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.inkPrimary,
                        )
                    }
                    IconButton(
                        onClick = onComplete,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭引导",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (motionReduced) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            fadeIn(tween(160)) togetherWith fadeOut(tween(110))
                        }
                    },
                    label = "ravenTutorialStep",
                ) { activeStep ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RavenShapes.CardShape)
                            .background(colors.surface1)
                            .border(1.dp, colors.outlineSubtle, RavenShapes.CardShape)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(colors.crimson.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = activeStep.icon,
                                    contentDescription = null,
                                    tint = colors.crimson,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                if (!activeStep.badge.isNullOrBlank()) {
                                    Text(
                                        text = activeStep.badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.crimson,
                                    )
                                }
                                Text(
                                    text = activeStep.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.inkPrimary,
                                )
                            }
                        }
                        Text(
                            text = activeStep.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                        )
                    }
                }

                if (steps.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        steps.indices.forEach { idx ->
                            val active = idx == currentIndex
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (active) colors.crimson else colors.outlineSubtle,
                                    ),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onSkipAll != null) {
                        TextButton(onClick = onSkipAll) {
                            Text(
                                text = "跳过全部引导",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.inkTertiary,
                            )
                        }
                    } else {
                        Spacer(Modifier.height(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (currentIndex > 0) {
                            RavenSecondaryButton(
                                text = "上一步",
                                onClick = { currentIndex -= 1 },
                            )
                        }
                        RavenPrimaryButton(
                            text = if (isLastStep) "知道了" else "下一步",
                            onClick = {
                                if (isLastStep) {
                                    onComplete()
                                } else {
                                    currentIndex += 1
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
