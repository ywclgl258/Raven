package com.raven.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenLayout
import com.raven.app.core.designsystem.theme.RavenShapes

/**
 * Base HarmonyOS modal dialog shell:
 * - Width: `min(screenWidth - 32dp, 400dp)`, centered across phone/tablet/desktop.
 * - Max height: `90%` of viewport height with internal vertical scroll for tall content.
 * - Shape: [RavenShapes.DialogShape] (`28dp`), `Surface2` container + top specular rim highlight.
 * - Supports Escape key dismissal when [dismissOnBackPress] is true.
 */
@Composable
fun RavenDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnScrimClick: Boolean = true,
    dismissOnBackPress: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val reduceTransparency = LocalReduceTransparency.current
    val configuration = LocalConfiguration.current
    val dialogWidth = RavenLayout.computeDialogWidth(configuration.screenWidthDp)
    val maxHeight = (configuration.screenHeightDp * RavenLayout.DIALOG_MAX_HEIGHT_FRACTION).dp
    val borderColor = if (reduceTransparency) colors.outlineSubtle else colors.hairline

    LaunchedEffect(Unit) {
        feedback.perform(RavenFeedbackEffect.SHEET_OPEN)
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnScrimClick,
            dismissOnBackPress = dismissOnBackPress,
        ),
    ) {
        Surface(
            modifier = modifier
                .width(dialogWidth)
                .heightIn(max = maxHeight)
                .imePadding()
                .border(0.75.dp, borderColor, RavenShapes.DialogShape)
                .onPreviewKeyEvent { keyEvent ->
                    if (dismissOnBackPress &&
                        keyEvent.type == KeyEventType.KeyUp &&
                        keyEvent.key == Key.Escape
                    ) {
                        onDismissRequest()
                        true
                    } else {
                        false
                    }
                }
                .testTag("raven_dialog"),
            shape = RavenShapes.DialogShape,
            color = colors.surface2,
            contentColor = colors.inkPrimary,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!reduceTransparency) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, colors.lightRimTop, Color.Transparent),
                                ),
                            ),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * HarmonyOS strong-interruption alert dialog for critical notices, prerequisite warnings,
 * and high-risk confirmations. Destructive dialogs disable scrim dismissal by default.
 */
@Composable
fun RavenAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    confirmLoading: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissOnScrimClick: Boolean = !destructive,
    icon: ImageVector? = null,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val scrollState = rememberScrollState()

    RavenDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnScrimClick = dismissOnScrimClick && !confirmLoading,
        dismissOnBackPress = !confirmLoading,
    ) {
        if (icon != null) {
            val iconTint = if (destructive) colors.error else colors.crimson
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.14f))
                    .border(0.75.dp, iconTint.copy(alpha = 0.30f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.inkPrimary,
                textAlign = if (icon != null) TextAlign.Center else TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() },
            )
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    textAlign = if (icon != null) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (extraContent != null) {
                extraContent()
            }
        }

        val buttons: List<@Composable (Modifier) -> Unit> = buildList {
            if (!dismissLabel.isNullOrBlank()) {
                add { buttonModifier ->
                    RavenSecondaryButton(
                        text = dismissLabel,
                        onClick = { (onDismiss ?: onDismissRequest)() },
                        enabled = !confirmLoading,
                        modifier = buttonModifier,
                    )
                }
            }
            add { buttonModifier ->
                if (destructive) {
                    RavenDangerButton(
                        text = confirmLabel,
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        loading = confirmLoading,
                        modifier = buttonModifier,
                    )
                } else {
                    RavenPrimaryButton(
                        text = confirmLabel,
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        loading = confirmLoading,
                        modifier = buttonModifier,
                    )
                }
            }
        }

        RavenButtonRow(buttons = buttons)
    }
}

/**
 * Two-button HarmonyOS confirmation dialog (`cancel` + `confirm`).
 * For destructive operations (`destructive = true`), scrim dismissal is disabled by default
 * and the confirm action renders with [RavenDangerButton].
 */
@Composable
fun RavenConfirmDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "取消",
    onCancel: () -> Unit = onDismissRequest,
    destructive: Boolean = false,
    confirmLoading: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissOnScrimClick: Boolean = !destructive,
    icon: ImageVector? = null,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    RavenAlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        modifier = modifier,
        dismissLabel = cancelLabel,
        onDismiss = onCancel,
        destructive = destructive,
        confirmLoading = confirmLoading,
        confirmEnabled = confirmEnabled,
        dismissOnScrimClick = dismissOnScrimClick,
        icon = icon,
        extraContent = extraContent,
    )
}

/**
 * HarmonyOS input dialog for focused single-field tasks (rename, create shelf, quick tag input).
 * Automatically requests focus on the text field for keyboard/desktop friendliness.
 */
@Composable
fun RavenInputDialog(
    onDismissRequest: () -> Unit,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    cancelLabel: String = "取消",
    confirmEnabled: Boolean = value.isNotBlank(),
    confirmLoading: Boolean = false,
    singleLine: Boolean = true,
) {
    val colors = LocalRavenColors.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    RavenDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnScrimClick = !confirmLoading,
        dismissOnBackPress = !confirmLoading,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.inkPrimary,
                modifier = Modifier.semantics { heading() },
            )
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            enabled = !confirmLoading,
            singleLine = singleLine,
            isError = isError,
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (confirmEnabled && !confirmLoading) {
                        onConfirm()
                    }
                },
            ),
            shape = RavenShapes.ControlShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.inkPrimary,
                unfocusedTextColor = colors.inkPrimary,
                disabledTextColor = colors.inkTertiary,
                focusedContainerColor = colors.surface1,
                unfocusedContainerColor = colors.surface1,
                cursorColor = colors.crimson,
                focusedBorderColor = colors.crimson,
                unfocusedBorderColor = colors.outlineSubtle,
                errorBorderColor = colors.error,
            ),
        )

        RavenButtonRow(
            secondary = { buttonModifier ->
                RavenSecondaryButton(
                    text = cancelLabel,
                    onClick = onDismissRequest,
                    enabled = !confirmLoading,
                    modifier = buttonModifier,
                )
            },
            primary = { buttonModifier ->
                RavenPrimaryButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    loading = confirmLoading,
                    modifier = buttonModifier,
                )
            },
        )
    }
}

/**
 * Compact blocking loading dialog. Prefer inline button `loading = true` whenever possible;
 * reserve [RavenLoadingDialog] strictly for unavoidable modal waits.
 */
@Composable
fun RavenLoadingDialog(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    onDismissRequest: () -> Unit = {},
    dismissable: Boolean = false,
) {
    val colors = LocalRavenColors.current

    RavenDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnScrimClick = dismissable,
        dismissOnBackPress = dismissable,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                color = colors.crimson,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}
