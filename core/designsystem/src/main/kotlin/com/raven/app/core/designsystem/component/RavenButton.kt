package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenLayout
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.designsystem.theme.ravenSpringPress

/**
 * Unified HarmonyOS button size tiers (`Small = 40dp`, `Medium = 48dp` default, `Large = 52dp`).
 * Touch targets are always guaranteed to be `>= 48dp` via [minimumInteractiveComponentSize].
 */
enum class RavenButtonSize(
    val minHeight: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val iconSize: Dp,
) {
    Small(minHeight = 40.dp, horizontalPadding = 16.dp, verticalPadding = 8.dp, iconSize = 16.dp),
    Medium(minHeight = 48.dp, horizontalPadding = 20.dp, verticalPadding = 12.dp, iconSize = 18.dp),
    Large(minHeight = 52.dp, horizontalPadding = 24.dp, verticalPadding = 14.dp, iconSize = 20.dp),
}

/**
 * Visual variant for [RavenPrimaryButton].
 * - [Crimson]: Brand primary action (play, confirm, save, install).
 * - [Cta]: High-contrast cinema CTA (`PrimaryCtaContainer` white-on-obsidian) for hero/media banners.
 */
enum class RavenPrimaryButtonVariant {
    Crimson,
    Cta,
}

/**
 * Unified HarmonyOS primary button (`RavenShapes.ButtonShape` 20dp, max width `448dp`,
 * spring press + pointer-origin interactive light + tactile haptic + inline loading/success states).
 */
@Composable
fun RavenPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    success: Boolean = false,
    size: RavenButtonSize = RavenButtonSize.Medium,
    variant: RavenPrimaryButtonVariant = RavenPrimaryButtonVariant.Crimson,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    failure: Boolean = false,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val motionReduced = LocalMotionReduced.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val interactive = enabled && !loading
    val colorSpec = if (motionReduced) {
        snap<Color>()
    } else {
        tween(
            durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        )
    }

    val baseContainer = when {
        success -> colors.success
        variant == RavenPrimaryButtonVariant.Cta -> colors.primaryCtaContainer
        else -> colors.crimson
    }
    val targetContainerColor = if (interactive && (isHovered || isFocused)) {
        baseContainer.copy(alpha = 0.92f)
    } else {
        baseContainer
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = colorSpec,
        label = "ravenPrimaryButtonContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            success -> colors.canvas
            variant == RavenPrimaryButtonVariant.Cta -> colors.primaryCtaInk
            else -> colors.inkPrimary
        },
        animationSpec = colorSpec,
        label = "ravenPrimaryButtonContent",
    )
    val focusBorder = if (isFocused && interactive) {
        BorderStroke(1.5.dp, colors.inkPrimary.copy(alpha = 0.85f))
    } else {
        null
    }

    Button(
        onClick = {
            feedback.perform(RavenFeedbackEffect.TAP)
            onClick()
        },
        enabled = interactive,
        interactionSource = interactionSource,
        shape = RavenShapes.ButtonShape,
        border = focusBorder,
        contentPadding = PaddingValues(
            horizontal = size.horizontalPadding,
            vertical = size.verticalPadding,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = colors.surface3,
            disabledContentColor = colors.inkTertiary,
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .widthIn(max = RavenLayout.ButtonMaxWidth)
            .heightIn(min = size.minHeight)
            .semantics {
                if (loading) {
                    contentDescription = "$text，加载中"
                }
            }
            .ravenSpringPress(interactionSource = interactionSource, enabled = interactive)
            .ravenBurstFeedback(trigger = success, burstColor = colors.success)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = RavenShapes.ButtonShape,
                enabled = interactive,
                glowColor = colors.interactiveGlow,
            ),
    ) {
        RavenButtonContent(
            text = text,
            loading = loading,
            success = success,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            iconSize = size.iconSize,
            indicatorColor = contentColor,
            fontWeight = FontWeight.SemiBold,
            failure = failure,
        )
    }
}

/**
 * Unified HarmonyOS secondary button (`Surface2/3` container + crisp Hairline/Rim border).
 */
@Composable
fun RavenSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    size: RavenButtonSize = RavenButtonSize.Medium,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val motionReduced = LocalMotionReduced.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val interactive = enabled && !loading
    val colorSpec = if (motionReduced) {
        snap<Color>()
    } else {
        tween(
            durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        )
    }

    val containerColor by animateColorAsState(
        targetValue = if (interactive && (isHovered || isFocused)) colors.surface3 else colors.surface2,
        animationSpec = colorSpec,
        label = "ravenSecondaryButtonContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !interactive -> colors.hairline
            isFocused -> colors.crimson.copy(alpha = 0.85f)
            isHovered -> colors.rimHighlight
            else -> colors.outlineSubtle
        },
        animationSpec = colorSpec,
        label = "ravenSecondaryButtonBorder",
    )

    OutlinedButton(
        onClick = {
            feedback.perform(RavenFeedbackEffect.TAP)
            onClick()
        },
        enabled = interactive,
        interactionSource = interactionSource,
        shape = RavenShapes.ButtonShape,
        border = BorderStroke(if (isFocused && interactive) 1.25.dp else 0.75.dp, borderColor),
        contentPadding = PaddingValues(
            horizontal = size.horizontalPadding,
            vertical = size.verticalPadding,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = colors.inkPrimary,
            disabledContainerColor = colors.surface1,
            disabledContentColor = colors.inkTertiary,
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .widthIn(max = RavenLayout.ButtonMaxWidth)
            .heightIn(min = size.minHeight)
            .semantics {
                if (loading) {
                    contentDescription = "$text，加载中"
                }
            }
            .ravenSpringPress(interactionSource = interactionSource, enabled = interactive)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = RavenShapes.ButtonShape,
                enabled = interactive,
                glowColor = colors.interactiveGlow,
            ),
    ) {
        RavenButtonContent(
            text = text,
            loading = loading,
            success = false,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            iconSize = size.iconSize,
            indicatorColor = colors.inkPrimary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Unified HarmonyOS low-emphasis text button (no filled container; Crimson or InkSecondary text).
 */
@Composable
fun RavenTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    accent: Boolean = true,
    size: RavenButtonSize = RavenButtonSize.Medium,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val motionReduced = LocalMotionReduced.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val interactive = enabled && !loading
    val contentColor = if (accent) colors.crimson else colors.inkSecondary
    val hoverContainer by animateColorAsState(
        targetValue = if (interactive && (isHovered || isFocused)) {
            colors.surface2.copy(alpha = 0.72f)
        } else {
            Color.Transparent
        },
        animationSpec = if (motionReduced) {
            snap()
        } else {
            tween(
                durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            )
        },
        label = "ravenTextButtonContainer",
    )

    TextButton(
        onClick = {
            feedback.perform(RavenFeedbackEffect.TAP)
            onClick()
        },
        enabled = interactive,
        interactionSource = interactionSource,
        shape = RavenShapes.ButtonShape,
        contentPadding = PaddingValues(
            horizontal = (size.horizontalPadding - 4.dp).coerceAtLeast(12.dp),
            vertical = size.verticalPadding,
        ),
        colors = ButtonDefaults.textButtonColors(
            containerColor = hoverContainer,
            contentColor = contentColor,
            disabledContentColor = colors.inkTertiary,
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .widthIn(max = RavenLayout.ButtonMaxWidth)
            .heightIn(min = size.minHeight)
            .semantics {
                if (loading) {
                    contentDescription = "$text，加载中"
                }
            }
            .ravenSpringPress(interactionSource = interactionSource, enabled = interactive)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = RavenShapes.ButtonShape,
                enabled = interactive,
                glowColor = colors.interactiveGlow,
            ),
    ) {
        RavenButtonContent(
            text = text,
            loading = loading,
            success = false,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            iconSize = size.iconSize,
            indicatorColor = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Unified HarmonyOS destructive button (`Error` palette). Irreversible actions should pair with
 * [RavenAlertDialog] or [RavenConfirmDialog].
 */
@Composable
fun RavenDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    outlined: Boolean = false,
    size: RavenButtonSize = RavenButtonSize.Medium,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val motionReduced = LocalMotionReduced.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val interactive = enabled && !loading
    val colorSpec = if (motionReduced) {
        snap<Color>()
    } else {
        tween(
            durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        )
    }

    val commonModifier = modifier
        .minimumInteractiveComponentSize()
        .widthIn(max = RavenLayout.ButtonMaxWidth)
        .heightIn(min = size.minHeight)
        .semantics {
            if (loading) {
                contentDescription = "$text，加载中"
            }
        }
        .ravenSpringPress(interactionSource = interactionSource, enabled = interactive)
        .ravenInteractiveLight(
            interactionSource = interactionSource,
            shape = RavenShapes.ButtonShape,
            enabled = interactive,
            glowColor = colors.interactiveGlow,
        )

    if (outlined) {
        val borderColor by animateColorAsState(
            targetValue = when {
                !interactive -> colors.hairline
                isFocused -> colors.error
                isHovered -> colors.error.copy(alpha = 0.72f)
                else -> colors.error.copy(alpha = 0.45f)
            },
            animationSpec = colorSpec,
            label = "ravenDangerOutlinedBorder",
        )
        val containerColor by animateColorAsState(
            targetValue = if (interactive && (isHovered || isFocused)) {
                colors.error.copy(alpha = 0.14f)
            } else {
                colors.surface2
            },
            animationSpec = colorSpec,
            label = "ravenDangerOutlinedContainer",
        )
        OutlinedButton(
            onClick = {
                feedback.perform(RavenFeedbackEffect.TAP)
                onClick()
            },
            enabled = interactive,
            interactionSource = interactionSource,
            shape = RavenShapes.ButtonShape,
            border = BorderStroke(if (isFocused && interactive) 1.25.dp else 0.75.dp, borderColor),
            contentPadding = PaddingValues(
                horizontal = size.horizontalPadding,
                vertical = size.verticalPadding,
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor,
                contentColor = colors.error,
                disabledContainerColor = colors.surface1,
                disabledContentColor = colors.inkTertiary,
            ),
            modifier = commonModifier,
        ) {
            RavenButtonContent(
                text = text,
                loading = loading,
                success = false,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                iconSize = size.iconSize,
                indicatorColor = colors.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
    } else {
        Button(
            onClick = {
                feedback.perform(RavenFeedbackEffect.TAP)
                onClick()
            },
            enabled = interactive,
            interactionSource = interactionSource,
            shape = RavenShapes.ButtonShape,
            border = if (isFocused && interactive) {
                BorderStroke(1.5.dp, colors.inkPrimary.copy(alpha = 0.85f))
            } else {
                null
            },
            contentPadding = PaddingValues(
                horizontal = size.horizontalPadding,
                vertical = size.verticalPadding,
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (interactive && (isHovered || isFocused)) {
                    colors.error.copy(alpha = 0.90f)
                } else {
                    colors.error
                },
                contentColor = colors.inkPrimary,
                disabledContainerColor = colors.surface3,
                disabledContentColor = colors.inkTertiary,
            ),
            modifier = commonModifier,
        ) {
            RavenButtonContent(
                text = text,
                loading = loading,
                success = false,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                iconSize = size.iconSize,
                indicatorColor = colors.inkPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Unified HarmonyOS icon button with guaranteed `>= 48dp` touch target, circular/squircle shape,
 * pointer-origin interactive light, gravity spring press, and desktop hover/focus feedback.
 */
@Composable
fun RavenIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalRavenColors.current.inkPrimary,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val motionReduced = LocalMotionReduced.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    val resolvedContainer by animateColorAsState(
        targetValue = when {
            !enabled -> if (containerColor == Color.Transparent) Color.Transparent else colors.surface2.copy(alpha = 0.5f)
            isHovered || isFocused -> if (containerColor == Color.Transparent) {
                colors.surface2.copy(alpha = 0.85f)
            } else {
                containerColor.copy(alpha = (containerColor.alpha + 0.12f).coerceAtMost(1f))
            }
            else -> containerColor
        },
        animationSpec = if (motionReduced) {
            snap()
        } else {
            tween(
                durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            )
        },
        label = "ravenIconButtonContainer",
    )
    val borderModifier = if (enabled && isFocused) {
        Modifier.border(1.25.dp, colors.crimson.copy(alpha = 0.85f), shape)
    } else if (enabled && isHovered) {
        Modifier.border(0.75.dp, colors.hairline, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape)
            .background(resolvedContainer, shape)
            .then(borderModifier)
            .ravenSpringPress(interactionSource = interactionSource, enabled = enabled)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = shape,
                enabled = enabled,
                glowColor = colors.interactiveGlow,
            )
            .semantics {
                if (!contentDescription.isNullOrBlank()) {
                    this.contentDescription = contentDescription
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    feedback.perform(RavenFeedbackEffect.TAP)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (enabled) contentColor else colors.inkTertiary,
        ) {
            content()
        }
    }
}

/**
 * Convenience overload for [RavenIconButton] taking an [ImageVector] directly.
 */
@Composable
fun RavenIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    containerColor: Color = Color.Transparent,
    tint: Color = LocalRavenColors.current.inkPrimary,
    iconSize: Dp = 22.dp,
) {
    RavenIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        containerColor = containerColor,
        contentColor = tint,
        contentDescription = contentDescription,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * HarmonyOS button group layout helper enforcing consistent geometry across sheets and dialogs:
 * - 1 button: single full-width button capped at `448dp`.
 * - 2 buttons: equal-width horizontal row with `12dp` gap.
 * - 3+ buttons: vertical stack with `12dp` gap, each capped at `448dp`.
 */
@Composable
fun RavenButtonRow(
    buttons: List<@Composable (Modifier) -> Unit>,
    modifier: Modifier = Modifier,
) {
    when (buttons.size) {
        0 -> Unit
        1 -> {
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                buttons[0](
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = RavenLayout.ButtonMaxWidth),
                )
            }
        }
        2 -> {
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = RavenLayout.ButtonMaxWidth),
                    horizontalArrangement = Arrangement.spacedBy(RavenLayout.ButtonRowSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    buttons[0](Modifier.weight(1f))
                    buttons[1](Modifier.weight(1f))
                }
            }
        }
        else -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(RavenLayout.ButtonRowSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                buttons.forEach { button ->
                    button(
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = RavenLayout.ButtonMaxWidth),
                    )
                }
            }
        }
    }
}

/**
 * Convenience 1- or 2-button overload for [RavenButtonRow].
 * When [secondary] is provided, renders `[secondary | primary]` in an equal-width row (`12dp` gap).
 */
@Composable
fun RavenButtonRow(
    primary: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    secondary: (@Composable (Modifier) -> Unit)? = null,
) {
    val items = if (secondary != null) listOf(secondary, primary) else listOf(primary)
    RavenButtonRow(buttons = items, modifier = modifier)
}

private enum class ButtonIconState { IDLE, LOADING, SUCCESS, FAILURE }

@Composable
private fun RavenButtonContent(
    text: String,
    loading: Boolean,
    success: Boolean,
    leadingIcon: ImageVector?,
    trailingIcon: ImageVector?,
    iconSize: Dp,
    indicatorColor: Color,
    fontWeight: FontWeight,
    failure: Boolean = false,
) {
    val motionReduced = LocalMotionReduced.current
    val slotState = when {
        loading -> ButtonIconState.LOADING
        failure -> ButtonIconState.FAILURE
        success -> ButtonIconState.SUCCESS
        else -> ButtonIconState.IDLE
    }
    AnimatedContent(
        targetState = slotState,
        transitionSpec = {
            val duration = if (motionReduced) 0 else RavenMotion.CHIP_TRANSITION_DURATION_MS
            val fade = tween<Float>(duration, easing = RavenMotion.HarmonyDecelerationEasing)
            (fadeIn(fade) togetherWith fadeOut(fade)).using(
                SizeTransform { _, _ -> tween(duration, easing = RavenMotion.HarmonyDecelerationEasing) },
            )
        },
        label = "ravenButtonLeadingSlot",
    ) { state ->
        if (state != ButtonIconState.IDLE || leadingIcon != null) {
            Box(
                modifier = Modifier.padding(end = 6.dp).size(iconSize),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    ButtonIconState.LOADING -> if (motionReduced) {
                        RavenMorphIcon(RavenMorphIcons.Refresh, tint = indicatorColor, size = iconSize)
                    } else {
                        CircularProgressIndicator(
                            color = indicatorColor,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(iconSize - 2.dp),
                        )
                    }
                    ButtonIconState.SUCCESS -> RavenMorphIcon(RavenMorphIcons.Check, tint = indicatorColor, size = iconSize)
                    ButtonIconState.FAILURE -> RavenMorphIcon(RavenMorphIcons.Close, tint = indicatorColor, size = iconSize)
                    ButtonIconState.IDLE -> if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(iconSize))
                    }
                }
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = fontWeight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (slotState == ButtonIconState.IDLE && trailingIcon != null) {
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
        )
    }
}
