package com.raven.app.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.designsystem.component.RavenAnimatedCounter
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.RavenMorphToggleIcon
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.component.ravenSquashPress
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphGlyph
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule

/**
 * MediaKind filter option supporting `全部 / 视频 / 漫画 / 连载漫画 / 写真`.
 */
enum class LibraryMediaFilter(val label: String, val mediaKind: MediaKind?) {
    ALL("全部", null),
    VIDEO("视频", MediaKind.VIDEO),
    COMIC("漫画", MediaKind.COMIC),
    SERIAL_COMIC("连载漫画", MediaKind.SERIAL_COMIC),
    PHOTO("写真", MediaKind.PHOTO);

    companion object {
        fun fromKind(kind: MediaKind?): LibraryMediaFilter = when (kind) {
            null -> ALL
            MediaKind.VIDEO -> VIDEO
            MediaKind.COMIC -> COMIC
            MediaKind.SERIAL_COMIC -> SERIAL_COMIC
            MediaKind.PHOTO -> PHOTO
        }
    }
}

@Composable
internal fun LibraryMediaFilterChips(
    selected: LibraryMediaFilter,
    onSelect: (LibraryMediaFilter) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    RavenFilterChipRow(
        items = LibraryMediaFilter.entries,
        selectedItem = selected,
        labelProvider = { it.label },
        onItemSelected = onSelect,
        keyProvider = { it.name },
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

@Composable
internal fun LibrarySiteFilterChips(
    options: List<SiteFilterOption>,
    selectedSiteId: SiteId?,
    onSelectSite: (SiteId?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    if (options.isEmpty()) return
    val allEntries: List<SiteFilterOption?> = remember(options) { listOf(null) + options }
    val selectedOption = remember(options, selectedSiteId) {
        if (selectedSiteId == null) null else options.firstOrNull { it.siteId == selectedSiteId }
    }
    RavenFilterChipRow(
        items = allEntries,
        selectedItem = selectedOption,
        labelProvider = { it?.label ?: "全部站点" },
        onItemSelected = { onSelectSite(it?.siteId) },
        keyProvider = { it?.siteId?.value ?: "_all_sites" },
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

/**
 * Obsidian Apple HIG text field matching [com.raven.app.core.designsystem.component.RavenSearchField]
 * geometry with zero hardcoded colors.
 */
@Composable
internal fun LibraryTextInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 4,
    imeAction: ImeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    val colors = LocalRavenColors.current
    var focused by remember { mutableStateOf(false) }

    val borderColor = when {
        isError -> colors.error
        focused -> colors.crimson.copy(alpha = 0.78f)
        else -> colors.hairline
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkSecondary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 48.dp else 88.dp)
                .clip(RavenShapes.ControlShape)
                .background(if (enabled) colors.surface2 else colors.surface1)
                .border(
                    width = if (focused || isError) 1.dp else 0.5.dp,
                    color = borderColor,
                    shape = RavenShapes.ControlShape,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkTertiary,
                    maxLines = if (singleLine) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) colors.inkPrimary else colors.inkTertiary,
                ),
                cursorBrush = SolidColor(colors.crimson),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction?.invoke() },
                    onGo = { onImeAction?.invoke() },
                    onSearch = { onImeAction?.invoke() },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state -> focused = state.isFocused },
            )
        }
        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) colors.error else colors.inkTertiary,
            )
        }
    }
}

/**
 * Compact Obsidian banner used for transient action results, import status, or network alerts.
 */
@Composable
internal fun LibraryFeedbackBanner(
    message: String,
    modifier: Modifier = Modifier,
    tone: RavenStatusTone = RavenStatusTone.NEUTRAL,
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
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
        else -> accent.copy(alpha = 0.14f)
    }
    val borderColor = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.hairline
        else -> accent.copy(alpha = 0.30f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.ControlShape)
            .background(container)
            .border(0.5.dp, borderColor, RavenShapes.ControlShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
        }
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭提示",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
