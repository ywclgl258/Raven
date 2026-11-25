package com.raven.app.feature.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.common.MediaDateParser
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.displayLabel
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.SiteDescriptor

/**
 * Single-screen Apple HIG grouped [RavenBottomSheet] for advanced search filters.
 * Every section is driven strictly by [supportedFilters] and [supportsExcludeTags] from the active
 * site's `SearchSource` — zero hardcoded site checks, zero chained dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedSearchFilterSheet(
    site: SiteDescriptor,
    supportedFilters: Set<AdvancedFilterField>,
    supportsExcludeTags: Boolean,
    supportsLanguageFilter: Boolean,
    initialFilters: AdvancedSearchFilters,
    initialLanguage: ComicLanguage,
    onApply: (AdvancedSearchFilters, ComicLanguage) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    tagDictionary: TagDictionary = TagDictionary.default,
) {
    val colors = LocalRavenColors.current

    var includeTags by remember(initialFilters) { mutableStateOf(initialFilters.includeTags) }
    var includeInput by remember { mutableStateOf("") }
    var excludeTags by remember(initialFilters) { mutableStateOf(initialFilters.excludeTags) }
    var excludeInput by remember { mutableStateOf("") }
    var selectedCategory by remember(initialFilters) { mutableStateOf(initialFilters.category) }
    var brandInput by remember(initialFilters) { mutableStateOf(initialFilters.brand.orEmpty()) }
    var yearInput by remember(initialFilters) { mutableStateOf(initialFilters.year?.toString().orEmpty()) }
    var monthInput by remember(initialFilters) { mutableStateOf(initialFilters.month?.toString().orEmpty()) }
    var minDurInput by remember(initialFilters) { mutableStateOf(initialFilters.minDurationMinutes?.toString().orEmpty()) }
    var maxDurInput by remember(initialFilters) { mutableStateOf(initialFilters.maxDurationMinutes?.toString().orEmpty()) }
    var minPagesInput by remember(initialFilters) { mutableStateOf(initialFilters.minPages?.toString().orEmpty()) }
    var maxPagesInput by remember(initialFilters) { mutableStateOf(initialFilters.maxPages?.toString().orEmpty()) }
    var minRating by remember(initialFilters) { mutableStateOf(initialFilters.minRating) }
    var publishedFromInput by remember(initialFilters) { mutableStateOf(initialFilters.publishedFrom.orEmpty()) }
    var publishedToInput by remember(initialFilters) { mutableStateOf(initialFilters.publishedTo.orEmpty()) }
    var selectedLanguage by remember(initialLanguage) { mutableStateOf(initialLanguage) }

    val categoryOptions = remember(site) {
        site.sections.filter { !it.isDirectory && it.category.isNotBlank() }
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "高级筛选",
        subtitle = "${site.displayName} 支持的筛选条件",
        trailingAction = {
            TextButton(
                onClick = {
                    includeTags = emptyList()
                    includeInput = ""
                    excludeTags = emptyList()
                    excludeInput = ""
                    selectedCategory = null
                    brandInput = ""
                    yearInput = ""
                    monthInput = ""
                    minDurInput = ""
                    maxDurInput = ""
                    minPagesInput = ""
                    maxPagesInput = ""
                    minRating = null
                    publishedFromInput = ""
                    publishedToInput = ""
                    selectedLanguage = ComicLanguage.ALL
                    onReset()
                },
                modifier = Modifier.testTag("advanced_filter_reset_button"),
            ) {
                Text(
                    text = "重置",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.crimson,
                )
            }
        },
        modifier = Modifier.testTag("advanced_search_filter_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (AdvancedFilterField.INCLUDE_TAGS in supportedFilters) {
                RavenGroupedSection(title = "包含标签") {
                    TagChipsEditorBlock(
                        tags = includeTags,
                        inputValue = includeInput,
                        placeholder = "输入标签并添加（支持中文联想）",
                        onInputChange = { includeInput = it },
                        onAddTag = { tag ->
                            val clean = tag.trim()
                            if (clean.isNotEmpty() && clean !in includeTags) {
                                includeTags = includeTags + clean
                            }
                            includeInput = ""
                        },
                        onRemoveTag = { includeTags = includeTags - it },
                        tagDictionary = tagDictionary,
                    )
                }
            }

            if (AdvancedFilterField.EXCLUDE_TAGS in supportedFilters || supportsExcludeTags) {
                RavenGroupedSection(title = "排除标签") {
                    TagChipsEditorBlock(
                        tags = excludeTags,
                        inputValue = excludeInput,
                        placeholder = "输入要排除的标签",
                        onInputChange = { excludeInput = it },
                        onAddTag = { tag ->
                            val clean = tag.trim().removePrefix("-")
                            if (clean.isNotEmpty() && clean !in excludeTags) {
                                excludeTags = excludeTags + clean
                            }
                            excludeInput = ""
                        },
                        onRemoveTag = { excludeTags = excludeTags - it },
                        tagDictionary = tagDictionary,
                    )
                }
            }

            if (AdvancedFilterField.CATEGORY in supportedFilters && categoryOptions.isNotEmpty()) {
                RavenGroupedSection(title = "分类") {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RavenFilterChip(
                            label = "全部",
                            selected = selectedCategory.isNullOrBlank(),
                            onClick = { selectedCategory = null },
                        )
                        categoryOptions.forEach { spec ->
                            RavenFilterChip(
                                label = spec.title,
                                selected = selectedCategory == spec.category,
                                onClick = {
                                    selectedCategory = if (selectedCategory == spec.category) null else spec.category
                                },
                            )
                        }
                    }
                }
            }

            if (AdvancedFilterField.LANGUAGE in supportedFilters || supportsLanguageFilter) {
                RavenGroupedSection(title = "语言") {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ComicLanguage.entries.forEach { lang ->
                            RavenFilterChip(
                                label = lang.displayLabel(),
                                selected = selectedLanguage == lang,
                                onClick = { selectedLanguage = lang },
                            )
                        }
                    }
                }
            }

            if (AdvancedFilterField.BRAND in supportedFilters) {
                RavenGroupedSection(title = "厂商 / 品牌") {
                    CompactSheetTextField(
                        value = brandInput,
                        onValueChange = { brandInput = it },
                        placeholder = "例如社团或制作社名称",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    )
                }
            }

            if (AdvancedFilterField.YEAR in supportedFilters || AdvancedFilterField.MONTH in supportedFilters) {
                RavenGroupedSection(title = "发行时间") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (AdvancedFilterField.YEAR in supportedFilters) {
                            CompactSheetTextField(
                                value = yearInput,
                                onValueChange = { raw -> yearInput = raw.filter(Char::isDigit).take(4) },
                                placeholder = "年份（如 2025）",
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (AdvancedFilterField.MONTH in supportedFilters) {
                            CompactSheetTextField(
                                value = monthInput,
                                onValueChange = { raw -> monthInput = raw.filter(Char::isDigit).take(2) },
                                placeholder = "月份（1-12）",
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (AdvancedFilterField.DURATION in supportedFilters) {
                RavenGroupedSection(title = "视频时长（分钟）") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CompactSheetTextField(
                                value = minDurInput,
                                onValueChange = { raw -> minDurInput = raw.filter(Char::isDigit).take(4) },
                                placeholder = "最短（分）",
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                            CompactSheetTextField(
                                value = maxDurInput,
                                onValueChange = { raw -> maxDurInput = raw.filter(Char::isDigit).take(4) },
                                placeholder = "最长（分）",
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 15, 30, 60).forEach { mins ->
                                RavenFilterChip(
                                    label = "≥ ${mins} 分",
                                    selected = minDurInput == mins.toString() && maxDurInput.isEmpty(),
                                    onClick = {
                                        minDurInput = if (minDurInput == mins.toString()) "" else mins.toString()
                                        maxDurInput = ""
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (AdvancedFilterField.PAGES in supportedFilters) {
                RavenGroupedSection(title = "页数范围") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CompactSheetTextField(
                                value = minPagesInput,
                                onValueChange = { raw -> minPagesInput = raw.filter(Char::isDigit).take(5) },
                                placeholder = "最少页数",
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                            CompactSheetTextField(
                                value = maxPagesInput,
                                onValueChange = { raw -> maxPagesInput = raw.filter(Char::isDigit).take(5) },
                                placeholder = "最多页数",
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(20, 50, 100).forEach { pages ->
                                RavenFilterChip(
                                    label = "≥ ${pages}P",
                                    selected = minPagesInput == pages.toString() && maxPagesInput.isEmpty(),
                                    onClick = {
                                        minPagesInput = if (minPagesInput == pages.toString()) "" else pages.toString()
                                        maxPagesInput = ""
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (AdvancedFilterField.RATING in supportedFilters) {
                RavenGroupedSection(title = "最低评分") {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val options = listOf(null to "不限", 2 to "2★+", 3 to "3★+", 4 to "4★+", 5 to "5★")
                        options.forEach { (value, label) ->
                            RavenFilterChip(
                                label = label,
                                selected = minRating == value,
                                onClick = { minRating = value },
                            )
                        }
                    }
                }
            }

            RavenGroupedSection(title = "发布日期范围") {
                DateRangeFilterEditorBlock(
                    fromInput = publishedFromInput,
                    toInput = publishedToInput,
                    onFromChange = { publishedFromInput = it },
                    onToChange = { publishedToInput = it },
                    onPreset = { from, to ->
                        publishedFromInput = from
                        publishedToInput = to
                    },
                )
            }

            RavenGroupedDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface1,
                        contentColor = colors.inkPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(text = "取消", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        val finalInclude = if (includeInput.isNotBlank()) {
                            (includeTags + includeInput.trim()).distinct()
                        } else {
                            includeTags
                        }
                        val finalExclude = if (excludeInput.isNotBlank()) {
                            (excludeTags + excludeInput.trim().removePrefix("-")).distinct()
                        } else {
                            excludeTags
                        }
                        val cleanFrom = publishedFromInput.trim().takeIf {
                            MediaDateParser.parseIsoDateToEpochDay(it) != null
                        }
                        val cleanTo = publishedToInput.trim().takeIf {
                            MediaDateParser.parseIsoDateToEpochDay(it) != null
                        }
                        val filters = AdvancedSearchFilters(
                            includeTags = finalInclude,
                            excludeTags = finalExclude,
                            category = selectedCategory?.takeIf { it.isNotBlank() },
                            brand = brandInput.trim().takeIf { it.isNotBlank() },
                            year = yearInput.toIntOrNull()?.takeIf { it in 1900..2200 },
                            month = monthInput.toIntOrNull()?.takeIf { it in 1..12 },
                            minDurationMinutes = minDurInput.toIntOrNull()?.takeIf { it > 0 },
                            maxDurationMinutes = maxDurInput.toIntOrNull()?.takeIf { it > 0 },
                            minPages = minPagesInput.toIntOrNull()?.takeIf { it > 0 },
                            maxPages = maxPagesInput.toIntOrNull()?.takeIf { it > 0 },
                            minRating = minRating?.takeIf { it in 1..5 },
                            publishedFrom = cleanFrom,
                            publishedTo = cleanTo,
                        )
                        onApply(filters, selectedLanguage)
                        onDismiss()
                    },
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1.4f)
                        .heightIn(min = 48.dp)
                        .ravenSpringPress()
                        .testTag("advanced_filter_apply_button"),
                ) {
                    Text(
                        text = "应用筛选",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChipsEditorBlock(
    tags: List<String>,
    inputValue: String,
    placeholder: String,
    onInputChange: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    tagDictionary: TagDictionary,
) {
    val colors = LocalRavenColors.current
    val suggestions = remember(inputValue) {
        if (inputValue.isBlank()) emptyList() else tagDictionary.suggest(inputValue, limit = 5)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactSheetTextField(
                value = inputValue,
                onValueChange = onInputChange,
                placeholder = placeholder,
                onDone = { if (inputValue.isNotBlank()) onAddTag(inputValue) },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { if (inputValue.isNotBlank()) onAddTag(inputValue) },
                enabled = inputValue.isNotBlank(),
                modifier = Modifier
                    .size(44.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(if (inputValue.isNotBlank()) colors.primaryCtaContainer else colors.surface2),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "添加标签",
                    tint = if (inputValue.isNotBlank()) colors.primaryCtaInk else colors.inkTertiary,
                )
            }
        }

        if (suggestions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.forEach { sug ->
                    Row(
                        modifier = Modifier
                            .clip(RavenShapes.PillShape)
                            .background(colors.surface2)
                            .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                            .clickable { onAddTag(sug.slug) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${sug.localizedName} (${sug.slug})",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { tag ->
                    val label = tagDictionary.displayLabel(tag, tag)
                    Row(
                        modifier = Modifier
                            .clip(RavenShapes.PillShape)
                            .background(colors.crimson.copy(alpha = 0.14f))
                            .border(0.5.dp, colors.crimson.copy(alpha = 0.35f), RavenShapes.PillShape)
                            .clickable { onRemoveTag(tag) }
                            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (label != tag) "$label · $tag" else tag,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.inkPrimary,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "移除 $tag",
                            tint = colors.crimson,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactSheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RavenShapes.ControlShape)
            .background(colors.surface2)
            .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.tabular().copy(color = colors.inkPrimary),
            cursorBrush = SolidColor(colors.crimson),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Single-screen [RavenBottomSheet] to save the current query + filters as a named [SavedSearch].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveSearchSheet(
    suggestedName: String,
    summary: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    var name by remember(suggestedName) { mutableStateOf(suggestedName) }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "保存搜索方案",
        subtitle = summary,
        modifier = Modifier.testTag("save_search_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RavenGroupedSection(title = "名称") {
                CompactSheetTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "输入保存的搜索名称",
                    onDone = {
                        onSave(name)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface1,
                        contentColor = colors.inkPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(text = "取消", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        onSave(name)
                        onDismiss()
                    },
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .heightIn(min = 48.dp)
                        .ravenSpringPress()
                        .testTag("save_search_confirm_button"),
                ) {
                    Text(
                        text = "保存",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

/**
 * Shared published date-range editor (`不限 / 近7天 / 近30天 / 近半年 / 近一年` + `YYYY-MM-DD` bounds).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DateRangeFilterEditorBlock(
    fromInput: String,
    toInput: String,
    onFromChange: (String) -> Unit,
    onToChange: (String) -> Unit,
    onPreset: (from: String, to: String) -> Unit,
) {
    val colors = LocalRavenColors.current
    val todayDay = remember { MediaDateParser.currentUtcEpochDay() }
    val todayIso = remember(todayDay) { MediaDateParser.formatEpochDayToIso(todayDay) }
    val presets = remember(todayDay, todayIso) {
        listOf(
            Triple("不限", "", ""),
            Triple("近 7 天", MediaDateParser.formatEpochDayToIso(todayDay - 6L), todayIso),
            Triple("近 30 天", MediaDateParser.formatEpochDayToIso(todayDay - 29L), todayIso),
            Triple("近半年", MediaDateParser.formatEpochDayToIso(todayDay - 182L), todayIso),
            Triple("近一年", MediaDateParser.formatEpochDayToIso(todayDay - 364L), todayIso),
        )
    }

    val fromValid = fromInput.isBlank() || MediaDateParser.parseIsoDateToEpochDay(fromInput) != null
    val toValid = toInput.isBlank() || MediaDateParser.parseIsoDateToEpochDay(toInput) != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presets.forEach { (label, presetFrom, presetTo) ->
                val selected = fromInput.trim() == presetFrom && toInput.trim() == presetTo
                RavenFilterChip(
                    label = label,
                    selected = selected,
                    onClick = { onPreset(presetFrom, presetTo) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CompactSheetTextField(
                value = fromInput,
                onValueChange = { raw -> onFromChange(raw.filter { it.isDigit() || it == '-' }.take(10)) },
                placeholder = "开始 YYYY-MM-DD",
                keyboardType = KeyboardType.Number,
                modifier = Modifier
                    .weight(1f)
                    .testTag("date_range_from_input"),
            )
            CompactSheetTextField(
                value = toInput,
                onValueChange = { raw -> onToChange(raw.filter { it.isDigit() || it == '-' }.take(10)) },
                placeholder = "结束 YYYY-MM-DD",
                keyboardType = KeyboardType.Number,
                modifier = Modifier
                    .weight(1f)
                    .testTag("date_range_to_input"),
            )
        }

        val hintText = when {
            !fromValid || !toValid -> "请按 YYYY-MM-DD 格式填写有效日期（例如 $todayIso）"
            fromInput.isBlank() && toInput.isBlank() -> "设置后仅展示发布日期在选定范围内的作品"
            else -> "仅展示 ${fromInput.ifBlank { "最早" }} 至 ${toInput.ifBlank { "今天" }} 之间的作品"
        }
        Text(
            text = hintText,
            style = MaterialTheme.typography.bodySmall,
            color = if (!fromValid || !toValid) colors.error else colors.inkTertiary,
        )
    }
}

/**
 * Lightweight standalone [RavenBottomSheet] to filter search results by published date range.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchDateRangeSheet(
    initialFrom: String?,
    initialTo: String?,
    onApply: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    var fromInput by remember(initialFrom) { mutableStateOf(initialFrom.orEmpty()) }
    var toInput by remember(initialTo) { mutableStateOf(initialTo.orEmpty()) }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "按发布日期筛选",
        subtitle = "仅展示发布日期落在选定区间内的作品",
        trailingAction = {
            TextButton(
                onClick = {
                    fromInput = ""
                    toInput = ""
                    onApply(null, null)
                    onDismiss()
                },
                modifier = Modifier.testTag("date_range_reset_button"),
            ) {
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.crimson,
                )
            }
        },
        modifier = Modifier.testTag("search_date_range_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RavenGroupedSection(title = "选择发布时间段") {
                DateRangeFilterEditorBlock(
                    fromInput = fromInput,
                    toInput = toInput,
                    onFromChange = { fromInput = it },
                    onToChange = { toInput = it },
                    onPreset = { presetFrom, presetTo ->
                        fromInput = presetFrom
                        toInput = presetTo
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface1,
                        contentColor = colors.inkPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(text = "取消", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        val cleanFrom = fromInput.trim().takeIf {
                            MediaDateParser.parseIsoDateToEpochDay(it) != null
                        }
                        val cleanTo = toInput.trim().takeIf {
                            MediaDateParser.parseIsoDateToEpochDay(it) != null
                        }
                        onApply(cleanFrom, cleanTo)
                        onDismiss()
                    },
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1.4f)
                        .heightIn(min = 48.dp)
                        .ravenSpringPress()
                        .testTag("date_range_apply_button"),
                ) {
                    Text(
                        text = "仅看该日期内作品",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}
