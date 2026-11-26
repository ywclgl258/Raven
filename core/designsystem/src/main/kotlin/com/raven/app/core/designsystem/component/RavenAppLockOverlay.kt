package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.designsystem.theme.ravenSpringPress
import kotlinx.coroutines.launch

/**
 * Full-screen Dark Obsidian PIN lock overlay preventing underlying app content from appearing
 * until verified. Supports 4–8 digit PINs, failure/lockout feedback, and optional biometric trigger.
 */
@Composable
fun RavenAppLockOverlay(
    onSubmitPin: (String) -> Boolean = { false },
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    lockoutSecondsRemaining: Int = 0,
    onBiometricClick: (() -> Unit)? = null,
    maxPinLength: Int = 8,
    minPinLength: Int = 4,
    onSubmitPinAsync: (suspend (String) -> Boolean)? = null,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val motionReduced = LocalMotionReduced.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorShakeTrigger by remember { mutableIntStateOf(0) }
    val shakeOffsetPx = remember { Animatable(0f) }
    val lockedOut = lockoutSecondsRemaining > 0
    val displayError = when {
        lockedOut -> "尝试次数过多，请 ${lockoutSecondsRemaining} 秒后再试"
        !errorMessage.isNullOrBlank() -> errorMessage
        else -> localError
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            errorShakeTrigger++
        }
    }

    LaunchedEffect(errorShakeTrigger) {
        if (errorShakeTrigger <= 0) return@LaunchedEffect
        if (motionReduced) {
            shakeOffsetPx.snapTo(0f)
            return@LaunchedEffect
        }
        val amp = with(density) { 12.dp.toPx() }
        val steps = listOf(amp, -amp, amp * 0.72f, -amp * 0.56f, amp * 0.28f, 0f)
        for (target in steps) {
            shakeOffsetPx.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 42),
            )
        }
    }

    fun attemptUnlock(candidate: String) {
        if (lockedOut || isSubmitting || candidate.length < minPinLength) return
        if (onSubmitPinAsync != null) {
            isSubmitting = true
            scope.launch {
                val ok = try {
                    onSubmitPinAsync(candidate)
                } finally {
                    isSubmitting = false
                }
                if (!ok) {
                    feedback.perform(RavenFeedbackEffect.ERROR)
                    errorShakeTrigger++
                    localError = "PIN 密码不正确"
                    pin = ""
                } else {
                    localError = null
                    pin = ""
                }
            }
        } else {
            val ok = onSubmitPin(candidate)
            if (!ok) {
                feedback.perform(RavenFeedbackEffect.ERROR)
                errorShakeTrigger++
                localError = "PIN 密码不正确"
                pin = ""
            } else {
                localError = null
                pin = ""
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("raven_app_lock_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer { translationX = shakeOffsetPx.value }
                    .clip(CircleShape)
                    .background(colors.surface2)
                    .border(
                        width = 0.5.dp,
                        color = if (displayError != null) colors.error.copy(alpha = 0.65f) else colors.rimHighlight,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = if (displayError != null) colors.error else colors.crimson,
                    modifier = Modifier.size(26.dp),
                )
            }

            Text(
                text = "应用已锁定",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.inkPrimary,
                textAlign = TextAlign.Center,
            )

            Text(
                text = displayError ?: "输入 $minPinLength–$maxPinLength 位 PIN 解锁渡鸦",
                style = MaterialTheme.typography.bodyMedium,
                color = if (displayError != null) colors.error else colors.inkSecondary,
                textAlign = TextAlign.Center,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .graphicsLayer { translationX = shakeOffsetPx.value }
                    .animateContentSize(
                        animationSpec = if (motionReduced) snap() else RavenMotion.interactiveSpringSpec(),
                    ),
            ) {
                val dotSlots = maxOf(minPinLength, pin.length).coerceAtMost(maxPinLength)
                repeat(dotSlots) { idx ->
                    val filled = idx < pin.length
                    val dotScaleState = animateFloatAsState(
                        targetValue = if (filled) 1.15f else 0.92f,
                        animationSpec = if (motionReduced) snap() else RavenMotion.interactiveSpringSpec(),
                        label = "lockPinDotScale",
                    )
                    val dotFill by animateColorAsState(
                        targetValue = when {
                            displayError != null && pin.isEmpty() -> colors.error.copy(alpha = 0.35f)
                            filled -> colors.crimson
                            else -> colors.surface2
                        },
                        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
                        label = "lockPinDotFill",
                    )
                    val dotBorder by animateColorAsState(
                        targetValue = when {
                            displayError != null && pin.isEmpty() -> colors.error.copy(alpha = 0.75f)
                            filled -> colors.crimson
                            else -> colors.hairline
                        },
                        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
                        label = "lockPinDotBorder",
                    )
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer {
                                val s = dotScaleState.value
                                scaleX = s
                                scaleY = s
                            }
                            .clip(CircleShape)
                            .background(dotFill)
                            .border(0.5.dp, dotBorder, CircleShape),
                    )
                }
            }

            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("DEL", "0", "OK"),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        row.forEach { key ->
                            val enabledKey = !lockedOut && !isSubmitting && when (key) {
                                "DEL" -> pin.isNotEmpty()
                                "OK" -> pin.length >= minPinLength
                                else -> pin.length < maxPinLength
                            }
                            PinPadButton(
                                label = key,
                                enabled = enabledKey,
                                accent = key == "OK",
                                onClick = {
                                    feedback.perform(RavenFeedbackEffect.TAP)
                                    localError = null
                                    when (key) {
                                        "DEL" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                        "OK" -> attemptUnlock(pin)
                                        else -> {
                                            if (pin.length < maxPinLength) {
                                                pin += key
                                                if (minPinLength == maxPinLength && pin.length == maxPinLength) {
                                                    attemptUnlock(pin)
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = pin.length >= minPinLength,
                enter = if (motionReduced) {
                    fadeIn(snap())
                } else {
                    fadeIn(
                        tween(
                            durationMillis = 160,
                            easing = RavenMotion.HarmonyDecelerationEasing,
                        ),
                    ) + expandVertically(
                        animationSpec = tween(
                            durationMillis = 180,
                            easing = RavenMotion.HarmonyDecelerationEasing,
                        ),
                    )
                },
                exit = if (motionReduced) {
                    fadeOut(snap())
                } else {
                    fadeOut(tween(durationMillis = 120)) + shrinkVertically(tween(durationMillis = 140))
                },
            ) {
                Button(
                    onClick = { attemptUnlock(pin) },
                    enabled = !lockedOut && !isSubmitting,
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("确认解锁", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (onBiometricClick != null) {
                OutlinedButton(
                    onClick = onBiometricClick,
                    enabled = !lockedOut,
                    shape = RavenShapes.PillShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("生物识别解锁", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PinPadButton(
    label: String,
    enabled: Boolean,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val interactionSource = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surface1
            accent -> colors.crimson.copy(alpha = 0.16f)
            else -> colors.surface2
        },
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
        label = "pinPadBg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            !enabled -> colors.inkTertiary
            accent -> colors.crimson
            else -> colors.inkPrimary
        },
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
        label = "pinPadFg",
    )
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 64.dp)
            .size(68.dp)
            .ravenSpringPress(interactionSource = interactionSource, enabled = enabled)
            .clip(CircleShape)
            .background(bg)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = CircleShape,
                enabled = enabled,
                glowColor = colors.interactiveGlow,
            )
            .border(0.5.dp, colors.hairline, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = when (label) {
                    "DEL" -> "退格"
                    "OK" -> "确认解锁"
                    else -> "数字 $label"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (label) {
            "DEL" -> Icon(
                imageVector = Icons.AutoMirrored.Rounded.Backspace,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp),
            )
            "OK" -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(24.dp),
            )
            else -> Text(
                text = label,
                style = RavenTabularTextStyle.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = fg,
            )
        }
    }
}
