package com.raven.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenSpringPress

/**
 * Full-screen Dark Obsidian PIN lock overlay preventing underlying app content from appearing
 * until verified. Supports 4–8 digit PINs, failure/lockout feedback, and optional biometric trigger.
 */
@Composable
fun RavenAppLockOverlay(
    onSubmitPin: (String) -> Boolean,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    lockoutSecondsRemaining: Int = 0,
    onBiometricClick: (() -> Unit)? = null,
    maxPinLength: Int = 8,
    minPinLength: Int = 4,
) {
    val colors = LocalRavenColors.current
    val haptic = LocalHapticFeedback.current
    var pin by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val lockedOut = lockoutSecondsRemaining > 0
    val displayError = when {
        lockedOut -> "尝试次数过多，请 ${lockoutSecondsRemaining} 秒后再试"
        !errorMessage.isNullOrBlank() -> errorMessage
        else -> localError
    }

    fun attemptUnlock(candidate: String) {
        if (lockedOut || candidate.length < minPinLength) return
        val ok = onSubmitPin(candidate)
        if (!ok) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            localError = "PIN 密码不正确"
            pin = ""
        } else {
            localError = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
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
                    .clip(CircleShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.rimHighlight, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = colors.crimson,
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
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                val dotSlots = maxOf(minPinLength, pin.length).coerceAtMost(maxPinLength)
                repeat(dotSlots) { idx ->
                    val filled = idx < pin.length
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (filled) colors.crimson else colors.surface2)
                            .border(0.5.dp, if (filled) colors.crimson else colors.hairline, CircleShape),
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
                            val enabledKey = !lockedOut && when (key) {
                                "DEL" -> pin.isNotEmpty()
                                "OK" -> pin.length >= minPinLength
                                else -> pin.length < maxPinLength
                            }
                            PinPadButton(
                                label = key,
                                enabled = enabledKey,
                                accent = key == "OK",
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                    localError = null
                                    when (key) {
                                        "DEL" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                        "OK" -> attemptUnlock(pin)
                                        else -> {
                                            if (pin.length < maxPinLength) {
                                                pin += key
                                                if (pin.length == minPinLength) {
                                                    if (onSubmitPin(pin)) {
                                                        localError = null
                                                    }
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

            if (pin.length >= minPinLength) {
                Button(
                    onClick = { attemptUnlock(pin) },
                    enabled = !lockedOut,
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
    val bg = when {
        !enabled -> colors.surface1
        accent -> colors.crimson.copy(alpha = 0.16f)
        else -> colors.surface2
    }
    val fg = when {
        !enabled -> colors.inkTertiary
        accent -> colors.crimson
        else -> colors.inkPrimary
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 64.dp)
            .size(68.dp)
            .ravenSpringPress(enabled = enabled)
            .clip(CircleShape)
            .background(bg)
            .border(0.5.dp, colors.hairline, CircleShape)
            .clickable(
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
