package com.raven.app.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenDangerButton
import com.raven.app.core.designsystem.component.RavenIconButton
import com.raven.app.core.designsystem.component.RavenPrimaryButton
import com.raven.app.core.designsystem.component.RavenPrimaryButtonVariant
import com.raven.app.core.designsystem.component.RavenSecondaryButton
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shared pure-Kotlin display formatters for `:feature:settings`.
 */
internal object SettingsFormatters {
    val QUALITY_OPTIONS: List<Pair<String, String>> = listOf(
        "auto" to "自动（按网络状况自适应）",
        "1080p" to "1080p 全高清",
        "720p" to "720p 高清",
        "480p" to "480p 流畅省流",
    )

    fun qualityLabel(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
        "auto", "" -> "自动"
        "1080p" -> "1080p"
        "720p" -> "720p"
        "480p" -> "480p"
        else -> raw
    }

    fun formatSpeed(speed: Float): String {
        val snapped = snapPlaybackSpeed(speed)
        return String.format(Locale.US, "%.2f×", snapped)
    }

    /** Snaps playback speed onto clean 0.25× steps within `[0.25, 3.0]`. */
    fun snapPlaybackSpeed(speed: Float): Float {
        val clamped = speed.coerceIn(0.25f, 3.0f)
        return (clamped * 4f).roundToInt() / 4f
    }

    fun formatTimestamp(epochMs: Long): String {
        if (epochMs <= 0L) return "未知时间"
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return formatter.format(Date(epochMs))
    }
}

/**
 * Temporarily applies [WindowManager.LayoutParams.FLAG_SECURE] while sensitive credential or
 * backup-password input surfaces are on screen, restoring the prior window flag state on exit.
 */
@Composable
internal fun SecureWindowEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        if (!enabled) {
            onDispose { }
        } else {
            val activity = context.findActivity()
            val window = activity?.window
            val hadFlag = window != null &&
                (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
            if (window != null && !hadFlag) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose {
                if (window != null && !hadFlag) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Obsidian semantic callout banner (info / success / warning / error) for non-blocking status
 * feedback, security reminders, and deferred cold-start notices.
 */
@Composable
internal fun SettingsNoticeBanner(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: RavenStatusTone = RavenStatusTone.NEUTRAL,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val accent = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.inkSecondary
        RavenStatusTone.ACCENT -> colors.crimson
        RavenStatusTone.SUCCESS -> colors.success
        RavenStatusTone.WARNING -> colors.warning
        RavenStatusTone.ERROR -> colors.error
    }
    val container = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.surface2
        else -> accent.copy(alpha = 0.12f)
    }
    val borderColor = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.hairline
        else -> accent.copy(alpha = 0.30f)
    }
    val resolvedIcon = icon ?: when (tone) {
        RavenStatusTone.SUCCESS -> Icons.Outlined.CheckCircle
        RavenStatusTone.WARNING -> Icons.Outlined.WarningAmber
        RavenStatusTone.ERROR -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Info
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(container)
            .border(0.5.dp, borderColor, RavenShapes.CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = resolvedIcon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.inkPrimary,
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
            }
        }
        if (action != null) {
            Box(modifier = Modifier.fillMaxWidth()) {
                action()
            }
        }
    }
}

/**
 * Settings primary button delegating to the unified [RavenPrimaryButton] / [RavenDangerButton] family.
 */
@Composable
internal fun SettingsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    if (destructive) {
        RavenDangerButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            loading = loading,
            leadingIcon = icon,
        )
    } else {
        RavenPrimaryButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            loading = loading,
            variant = RavenPrimaryButtonVariant.Cta,
            leadingIcon = icon,
        )
    }
}

/**
 * Settings secondary button delegating to the unified [RavenSecondaryButton] / [RavenDangerButton] family.
 */
@Composable
internal fun SettingsSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    if (destructive) {
        RavenDangerButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            outlined = true,
            leadingIcon = icon,
        )
    } else {
        RavenSecondaryButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            leadingIcon = icon,
        )
    }
}

/**
 * Unified Obsidian text field for credentials, passwords, and cookie strings.
 */
@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = LocalRavenColors.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        placeholder = placeholder?.let { hint ->
            {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkTertiary,
                )
            }
        },
        supportingText = supportingText?.let { support ->
            {
                Text(
                    text = support,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) colors.error else colors.inkTertiary,
                )
            }
        },
        visualTransformation = if (isPassword && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (isPassword && onTogglePasswordVisibility != null) {
            {
                RavenIconButton(
                    icon = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    onClick = onTogglePasswordVisibility,
                    tint = colors.inkSecondary,
                )
            }
        } else {
            null
        },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RavenShapes.ControlShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.inkPrimary,
            unfocusedTextColor = colors.inkPrimary,
            disabledTextColor = colors.inkTertiary,
            focusedContainerColor = colors.surface2,
            unfocusedContainerColor = colors.surface2,
            disabledContainerColor = colors.surface1,
            cursorColor = colors.crimson,
            focusedBorderColor = colors.crimson,
            unfocusedBorderColor = colors.outlineSubtle,
            errorBorderColor = colors.error,
            focusedLabelColor = colors.inkPrimary,
            unfocusedLabelColor = colors.inkSecondary,
            errorLabelColor = colors.error,
        ),
    )
}
