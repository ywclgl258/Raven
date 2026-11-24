package com.raven.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress

/**
 * Obsidian Apple HIG filter pill chip with guaranteed `>= 48dp` interactive touch bounds,
 * tactile spring press feedback, and crisp high-contrast selected styling.
 */
@Composable
fun RavenFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = LocalRavenColors.current
    val interactionSource = remember { MutableInteractionSource() }

    val containerColor = when {
        !enabled -> colors.surface1.copy(alpha = 0.5f)
        selected -> colors.primaryCtaContainer
        else -> colors.surface1
    }
    val contentColor = when {
        !enabled -> colors.inkTertiary
        selected -> colors.primaryCtaInk
        else -> colors.inkPrimary
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .ravenSpringPress(interactionSource = interactionSource, enabled = enabled)
            .selectable(
                selected = selected,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 34.dp)
                .clip(RavenShapes.PillShape)
                .background(containerColor)
                .then(
                    if (!selected) {
                        Modifier.border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Horizontally scrollable row of [RavenFilterChip] options.
 */
@Composable
fun <T> RavenFilterChipRow(
    items: List<T>,
    selectedItem: T?,
    labelProvider: (T) -> String,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    keyProvider: ((T) -> Any)? = null,
    iconProvider: ((T) -> ImageVector?)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = items,
            key = keyProvider,
        ) { item ->
            RavenFilterChip(
                label = labelProvider(item),
                selected = item == selectedItem,
                leadingIcon = iconProvider?.invoke(item),
                onClick = { onItemSelected(item) },
            )
        }
    }
}
