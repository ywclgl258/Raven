package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalRavenColors
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
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    var focused by remember { mutableStateOf(false) }

    val borderColor = when {
        focused -> colors.crimson.copy(alpha = 0.75f)
        else -> colors.hairline
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RavenShapes.PillShape)
            .background(colors.surface2)
            .border(
                width = if (focused) 1.dp else 0.5.dp,
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
            tint = if (focused) colors.inkPrimary else colors.inkSecondary,
            modifier = Modifier.size(20.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                readOnly = readOnly,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.inkPrimary),
                cursorBrush = SolidColor(colors.crimson),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { onSearch?.invoke(query) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state -> focused = state.isFocused },
            )
        }

        AnimatedVisibility(
            visible = query.isNotEmpty() && enabled && !readOnly,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            IconButton(
                onClick = {
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
