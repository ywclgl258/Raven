package com.raven.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class ImageFavoritesUiState(
    val searchQuery: String = "",
    val siteFilter: SiteId? = null,
    val totalCount: Int = 0,
    val items: List<ImageFavorite> = emptyList(),
    val siteOptions: List<SiteFilterOption> = emptyList(),
    val siteLabels: Map<SiteId, String> = emptyMap(),
    val message: String? = null,
)

private data class ImageFavoritesControls(
    val searchQuery: String = "",
    val siteFilter: SiteId? = null,
    val message: String? = null,
)

class ImageFavoritesViewModel(
    private val bookmarkRepository: BookmarkRepository,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val controls = MutableStateFlow(ImageFavoritesControls())

    val uiState: StateFlow<ImageFavoritesUiState> = combine(
        bookmarkRepository.observeImageFavorites(),
        controls,
    ) { allImages, ctrl ->
        val query = ctrl.searchQuery.trim().lowercase()
        val filtered = allImages.filter { fav ->
            val siteLabel = siteRegistry.displayNameFor(fav.siteId)
            val matchesQuery = query.isEmpty() ||
                fav.mediaTitle.lowercase().contains(query) ||
                siteLabel.lowercase().contains(query) ||
                fav.siteId.value.lowercase().contains(query)
            val matchesSite = ctrl.siteFilter == null || fav.siteId == ctrl.siteFilter
            matchesQuery && matchesSite
        }
        ImageFavoritesUiState(
            searchQuery = ctrl.searchQuery,
            siteFilter = ctrl.siteFilter,
            totalCount = allImages.size,
            items = filtered,
            siteOptions = siteRegistry.buildSiteFilterOptions(allImages.map { it.siteId }),
            siteLabels = allImages.associate { it.siteId to siteRegistry.displayNameFor(it.siteId) },
            message = ctrl.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ImageFavoritesUiState(),
    )

    fun updateSearchQuery(query: String) {
        controls.update { it.copy(searchQuery = query) }
    }

    fun selectSiteFilter(siteId: SiteId?) {
        controls.update { it.copy(siteFilter = siteId) }
    }

    fun deleteImageFavorite(id: String) {
        viewModelScope.launch(dispatchers.io) {
            bookmarkRepository.deleteImageFavorite(id)
            controls.update { it.copy(message = "已取消单图收藏") }
        }
    }

    fun clearAll() {
        viewModelScope.launch(dispatchers.io) {
            bookmarkRepository.clearImageFavorites()
            controls.update { it.copy(message = "已清空全部单图收藏") }
        }
    }

    fun clearMessage() {
        controls.update { it.copy(message = null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageFavoritesScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    viewModel: ImageFavoritesViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current

    var previewTarget by remember { mutableStateOf<ImageFavorite?>(null) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("image_favorites_screen"),
    ) {
        RavenTopBar(
            title = "单图收藏",
            subtitle = "共 ${uiState.items.size} / ${uiState.totalCount} 张",
            navigationIcon = {
                IconButton(onClick = navigator::navigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
            actions = {
                if (uiState.totalCount > 0) {
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.testTag("image_favorites_clear_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "清空单图收藏",
                            tint = colors.inkSecondary,
                        )
                    }
                }
            },
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = computeLibraryGridColumns(maxWidth.value.toInt())
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            if (!uiState.message.isNullOrBlank()) {
                item(key = "img_fav_msg") {
                    LibraryFeedbackBanner(
                        message = uiState.message.orEmpty(),
                        tone = RavenStatusTone.ACCENT,
                        onDismiss = viewModel::clearMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "img_fav_search") {
                RavenSearchField(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    placeholder = "按作品标题或站点筛选单图…",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (uiState.siteOptions.isNotEmpty()) {
                item(key = "img_fav_sites") {
                    LibrarySiteFilterChips(
                        options = uiState.siteOptions,
                        selectedSiteId = uiState.siteFilter,
                        onSelectSite = viewModel::selectSiteFilter,
                    )
                }
            }

            if (uiState.items.isEmpty()) {
                item(key = "img_fav_empty") {
                    RavenEmptyState(
                        title = if (uiState.totalCount == 0) "暂无单图收藏" else "没有匹配的单图",
                        message = if (uiState.totalCount == 0) {
                            "在漫画或写真阅读器中收藏喜欢的单页图片后，会集中展示在这里并支持一键跳转原页。"
                        } else {
                            "尝试清除关键词或切换站点筛选。"
                        },
                        icon = Icons.Outlined.Collections,
                    )
                }
            } else {
                val rows = uiState.items.chunked(columns)
                itemsIndexed(
                    items = rows,
                    key = { idx, row -> "img_fav_row_${idx}_${row.first().id}" },
                ) { _, rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { fav ->
                            val siteLabel = uiState.siteLabels[fav.siteId] ?: fav.siteId.value
                            val displayPage = fav.pageIndex.coerceAtLeast(1)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RavenShapes.CardShape)
                                    .background(colors.surface1)
                                    .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                                    .ravenSpringPress()
                                    .clickable(role = Role.Button) { previewTarget = fav }
                                    .testTag("image_favorite_card_${fav.id}"),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.74f)
                                        .background(colors.surface2),
                                ) {
                                    RavenImage(
                                        url = fav.imageUrl.ifBlank { fav.pageUrl },
                                        siteId = fav.siteId,
                                        contentDescription = fav.mediaTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .clip(RavenShapes.BadgeShape)
                                            .background(colors.badgeBackdrop)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = "P$displayPage",
                                            style = MaterialTheme.typography.labelSmall.tabular(),
                                            color = colors.inkPrimary,
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = fav.mediaTitle.ifBlank { fav.mediaId },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = siteLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.inkSecondary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        repeat(columns - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        }
    }

    previewTarget?.let { fav ->
        val displayPage = fav.pageIndex.coerceAtLeast(1)
        val siteLabel = uiState.siteLabels[fav.siteId] ?: fav.siteId.value
        RavenBottomSheet(
            onDismissRequest = { previewTarget = null },
            title = fav.mediaTitle.ifBlank { fav.mediaId },
            subtitle = "$siteLabel · 第 $displayPage 页 · ${formatRelativeTime(fav.addedAt)}",
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.85f)
                        .clip(RavenShapes.CardShape)
                        .background(colors.surface1)
                        .border(0.5.dp, colors.hairline, RavenShapes.CardShape),
                ) {
                    RavenImage(
                        url = fav.imageUrl.ifBlank { fav.pageUrl },
                        siteId = fav.siteId,
                        contentDescription = fav.mediaTitle,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.deleteImageFavorite(fav.id)
                            previewTarget = null
                        },
                        shape = RavenShapes.PillShape,
                        border = BorderStroke(0.5.dp, colors.error.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("取消收藏")
                    }
                    Button(
                        onClick = {
                            previewTarget = null
                            navigator.openReader(
                                siteId = fav.siteId,
                                mediaId = fav.mediaId,
                                url = fav.pageUrl,
                                startPage = displayPage,
                                offlineOnly = fav.siteId.isLocal,
                            )
                        },
                        shape = RavenShapes.PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primaryCtaContainer,
                            contentColor = colors.primaryCtaInk,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .ravenSpringPress(),
                    ) {
                        Text(
                            text = "打开第 $displayPage 页",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        LibraryConfirmBottomSheet(
            title = "清空全部单图收藏？",
            message = "共 ${uiState.totalCount} 张单图收藏记录将被清除，原作品与下载文件不受影响。",
            confirmLabel = "确认清空",
            onConfirm = viewModel::clearAll,
            onDismiss = { showClearConfirm = false },
        )
    }
}
