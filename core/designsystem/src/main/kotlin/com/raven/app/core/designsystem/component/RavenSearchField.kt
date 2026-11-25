package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes

/**
 * The SINGLE unified search input pill used across all screens in Raven
 * (Search, Cross-Site Search, Favorites, Downloads, Shelves, Subscriptions, Backup).
 *
 * Uses `Surface2` (`#1B1B22`) pill geometry with crisp focus hairline, `>= 48dp` interactive height,
 * and 200% font-scale resilience via [BasicTextField].
 */
@Composable
fun RavenSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索作品、标签、作者…",
    onSearch: ((String) -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    focusRequester: FocusRequester? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var textFieldValueState by remember {
        mutableStateOf(
            TextFieldValue(
                text = query,
                selection = TextRange(query.length),
            ),
        )
    }
    val pendingDispatched = remember { ArrayDeque<String>() }
    var lastExternalQuery by remember { mutableStateOf(query) }

    if (query != lastExternalQuery) {
        lastExternalQuery = query
        val matchedIndex = pendingDispatched.indexOf(query)
        if (matchedIndex >= 0) {
            repeat(matchedIndex + 1) { pendingDispatched.removeFirst() }
        } else if (query != textFieldValueState.text) {
            pendingDispatched.clear()
            textFieldValueState = TextFieldValue(
                text = query,
                selection = TextRange(query.length),
            )
        }
    } else if (!focused && query != textFieldValueState.text && pendingDispatched.isEmpty()) {
        textFieldValueState = TextFieldValue(
            text = query,
            selection = TextRange(query.length),
        )
    }

    val currentText = textFieldValueState.text

    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.crimson.copy(alpha = 0.85f) else colors.lightRimTop.copy(alpha = 0.14f),
        animationSpec = if (motionReduced) {
            snap()
        } else {
            tween(
                durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            )
        },
        label = "ravenSearchBorderColor",
    )
    val searchIconTint by animateColorAsState(
        targetValue = if (focused) colors.crimson else colors.inkSecondary,
        animationSpec = if (motionReduced) {
            snap()
        } else {
            tween(
                durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            )
        },
        label = "ravenSearchIconTint",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RavenShapes.PillShape)
            .background(if (focused) colors.surface2 else colors.surface2.copy(alpha = 0.92f))
            .border(
                width = if (focused) 1.5.dp else 0.5.dp,
                color = borderColor,
                shape = RavenShapes.PillShape,
            )
            .padding(start = 14.dp, end = 4.dp)
            .testTag("raven_search_field"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = searchIconTint,
            modifier = Modifier.size(20.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (currentText.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = textFieldValueState,
                onValueChange = { updated ->
                    val textChanged = updated.text != textFieldValueState.text
                    textFieldValueState = updated
                    if (textChanged) {
                        if (pendingDispatched.size >= 32) {
                            pendingDispatched.removeFirst()
                        }
                        pendingDispatched.addLast(updated.text)
                        onQueryChange(updated.text)
                    }
                },
                enabled = enabled,
                readOnly = readOnly,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.inkPrimary),
                cursorBrush = SolidColor(colors.crimson),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { onSearch?.invoke(textFieldValueState.text) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { state -> focused = state.isFocused }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key == Key.Escape) {
                            if (textFieldValueState.text.isNotEmpty()) {
                                textFieldValueState = TextFieldValue(text = "", selection = TextRange.Zero)
                                pendingDispatched.clear()
                                if (onClear != null) onClear() else onQueryChange("")
                            } else {
                                focusManager.clearFocus()
                            }
                            true
                        } else {
                            false
                        }
                    },
            )
        }

        AnimatedVisibility(
            visible = currentText.isNotEmpty() && enabled && !readOnly,
            enter = fadeIn(
                animationSpec = if (motionReduced) {
                    snap()
                } else {
                    tween(
                        durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                        easing = RavenMotion.HarmonyDecelerationEasing,
                    )
                },
            ),
            exit = fadeOut(
                animationSpec = if (motionReduced) {
                    snap()
                } else {
                    tween(
                        durationMillis = 120,
                        easing = RavenMotion.HarmonyDecelerationEasing,
                    )
                },
            ),
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    textFieldValueState = TextFieldValue(text = "", selection = TextRange.Zero)
                    pendingDispatched.clear()
                    if (lastExternalQuery.isNotEmpty()) {
                        pendingDispatched.addLast("")
                    }
                    if (onClear != null) {
                        onClear()
                    } else {
                        onQueryChange("")
                    }
                },
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("raven_search_clear"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "清除搜索内容",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (trailingContent != null) {
            trailingContent()
        }
    }
}
