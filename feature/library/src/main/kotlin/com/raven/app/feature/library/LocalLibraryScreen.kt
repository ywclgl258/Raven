package com.raven.app.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.NaturalSortComparator
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.media.storage.LocalBook
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

enum class LocalLibraryKindFilter(val label: String, val kind: MediaKind?) {
    ALL("全部", null),
    COMIC("漫画 / 图集", MediaKind.COMIC),
    VIDEO("视频", MediaKind.VIDEO),
}

enum class LocalLibrarySort(val label: String) {
    RECENT("最新导入"),
    TITLE("标题 A-Z"),
    SIZE("体积最大"),
}

data class LocalLibraryUiState(
    val searchQuery: String = "",
    val kindFilter: LocalLibraryKindFilter = LocalLibraryKindFilter.ALL,
    val sort: LocalLibrarySort = LocalLibrarySort.RECENT,
    val totalCount: Int = 0,
    val totalBytes: Long = 0L,
    val books: List<LocalBook> = emptyList(),
    val mediaItems: Map<String, MediaItem> = emptyMap(),
    val shelves: List<ShelfWithSummary> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val importState: LocalImportState = LocalImportState(),
    val message: String? = null,
)

private data class LocalLibraryControls(
    val searchQuery: String = "",
    val kindFilter: LocalLibraryKindFilter = LocalLibraryKindFilter.ALL,
    val sort: LocalLibrarySort = LocalLibrarySort.RECENT,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val importState: LocalImportState = LocalImportState(),
    val message: String? = null,
)

class LocalLibraryViewModel(
    private val localLibraryImporter: LocalLibraryImporter,
    private val shelfRepository: ShelfRepository,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val controls = MutableStateFlow(LocalLibraryControls())

    val uiState: StateFlow<LocalLibraryUiState> = combine(
        localLibraryImporter.books,
        shelfRepository.observeShelvesWithSummary(),
        controls,
    ) { allBooks, shelves, ctrl ->
        val query = ctrl.searchQuery.trim().lowercase()
        val filtered = allBooks.filter { book ->
            val matchesQuery = query.isEmpty() || book.title.lowercase().contains(query)
            val matchesKind = when (ctrl.kindFilter) {
                LocalLibraryKindFilter.ALL -> true
                LocalLibraryKindFilter.COMIC -> book.kind != MediaKind.VIDEO
                LocalLibraryKindFilter.VIDEO -> book.kind == MediaKind.VIDEO
            }
            matchesQuery && matchesKind
        }
        val sorted = when (ctrl.sort) {
            LocalLibrarySort.RECENT -> filtered.sortedByDescending { it.importedAt }
            LocalLibrarySort.TITLE -> filtered.sortedWith { a, b ->
                NaturalSortComparator.compare(a.title, b.title)
            }
            LocalLibrarySort.SIZE -> filtered.sortedByDescending { it.bytes }
        }
        val mediaMap = sorted.associate { book -> book.id to localLibraryImporter.toMediaItem(book) }
        val validIds = sorted.mapTo(mutableSetOf()) { it.id }

        LocalLibraryUiState(
            searchQuery = ctrl.searchQuery,
            kindFilter = ctrl.kindFilter,
            sort = ctrl.sort,
            totalCount = allBooks.size,
            totalBytes = allBooks.sumOf { it.bytes },
            books = sorted,
            mediaItems = mediaMap,
            shelves = shelves,
            selectionMode = ctrl.selectionMode,
            selectedIds = ctrl.selectedIds.intersect(validIds),
            importState = ctrl.importState,
            message = ctrl.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LocalLibraryUiState(),
    )

    fun updateSearchQuery(query: String) {
        controls.update { it.copy(searchQuery = query) }
    }

    fun selectKindFilter(filter: LocalLibraryKindFilter) {
        controls.update { it.copy(kindFilter = filter) }
    }

    fun selectSort(sort: LocalLibrarySort) {
        controls.update { it.copy(sort = sort) }
    }

    fun toggleSelectionMode(enabled: Boolean = !controls.value.selectionMode) {
        controls.update {
            it.copy(
                selectionMode = enabled,
                selectedIds = if (enabled) it.selectedIds else emptySet(),
            )
        }
    }

    fun toggleSelectBook(bookId: String) {
        controls.update { current ->
            val next = if (bookId in current.selectedIds) {
                current.selectedIds - bookId
            } else {
                current.selectedIds + bookId
            }
            current.copy(selectionMode = true, selectedIds = next)
        }
    }

    fun selectAllVisible() {
        val visibleIds = uiState.value.books.mapTo(linkedSetOf()) { it.id }
        controls.update { it.copy(selectionMode = true, selectedIds = visibleIds) }
    }

    fun clearSelection() {
        controls.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    fun importArchive(uri: Uri) = runImport("正在导入 CBZ / ZIP 压缩包…") {
        localLibraryImporter.importArchive(uri) { progress ->
            controls.update { it.copy(importState = it.importState.copy(progressMessage = progress)) }
        }
    }

    fun importDirectory(treeUri: Uri) = runImport("正在扫描图片目录…") {
        localLibraryImporter.importDirectory(treeUri) { progress ->
            controls.update { it.copy(importState = it.importState.copy(progressMessage = progress)) }
        }
    }

    fun importVideo(uri: Uri) = runImport("正在导入本地视频…") {
        localLibraryImporter.importVideo(uri) { progress ->
            controls.update { it.copy(importState = it.importState.copy(progressMessage = progress)) }
        }
    }

    fun removeBook(bookId: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                localLibraryImporter.removeBook(bookId)
            }.onSuccess {
                controls.update { it.copy(message = "已删除本地内容") }
            }.onFailure { error ->
                controls.update { it.copy(message = error.message ?: "删除失败") }
            }
        }
    }

    fun removeSelectedBooks() {
        val ids = uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            var removed = 0
            for (id in ids) {
                if (runCatching { localLibraryImporter.removeBook(id) }.isSuccess) {
                    removed += 1
                }
            }
            controls.update {
                it.copy(
                    selectionMode = false,
                    selectedIds = emptySet(),
                    message = "已移除 $removed 项本地内容",
                )
            }
        }
    }

    fun addSelectedToShelf(shelfId: String) {
        val snapshot = uiState.value
        val selectedMedia = snapshot.books
            .filter { it.id in snapshot.selectedIds }
            .mapNotNull { snapshot.mediaItems[it.id] }
        if (selectedMedia.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            val added = shelfRepository.addItems(
                shelfId = shelfId,
                items = selectedMedia.map { it.toLibraryShelfItem(shelfId) },
            )
            controls.update {
                it.copy(
                    selectionMode = false,
                    selectedIds = emptySet(),
                    message = if (added > 0) "已将 $added 项本地内容加入片单" else "所选内容已在目标片单中",
                )
            }
        }
    }

    fun createShelfAndAddSelected(shelfName: String) {
        val snapshot = uiState.value
        val selectedMedia = snapshot.books
            .filter { it.id in snapshot.selectedIds }
            .mapNotNull { snapshot.mediaItems[it.id] }
        if (selectedMedia.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val created = shelfRepository.createShelf(name = shelfName)
                val added = shelfRepository.addItems(
                    shelfId = created.id,
                    items = selectedMedia.map { it.toLibraryShelfItem(created.id) },
                )
                created.name to added
            }.onSuccess { (name, added) ->
                controls.update {
                    it.copy(
                        selectionMode = false,
                        selectedIds = emptySet(),
                        message = "已创建「$name」并加入 $added 项内容",
                    )
                }
            }.onFailure { error ->
                controls.update { it.copy(message = error.message ?: "创建片单失败") }
            }
        }
    }

    fun clearMessage() {
        controls.update { it.copy(message = null) }
    }

    fun clearImportStatus() {
        controls.update { it.copy(importState = it.importState.copy(lastMessage = null, isError = false)) }
    }

    private fun runImport(
        startMessage: String,
        action: suspend () -> MediaItem,
    ) {
        if (controls.value.importState.isImporting) return
        viewModelScope.launch(dispatchers.io) {
            controls.update {
                it.copy(importState = LocalImportState(isImporting = true, progressMessage = startMessage))
            }
            try {
                val item = action()
                controls.update {
                    it.copy(
                        importState = LocalImportState(
                            isImporting = false,
                            lastMessage = "已导入「${item.title}」",
                            isError = false,
                        ),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                controls.update {
                    it.copy(
                        importState = LocalImportState(
                            isImporting = false,
                            lastMessage = error.message ?: "导入失败",
                            isError = true,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
fun LocalLibraryScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    viewModel: LocalLibraryViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current

    var removingBook by remember { mutableStateOf<LocalBook?>(null) }
    var showShelfPicker by remember { mutableStateOf(false) }

    val archiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importArchive(uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importDirectory(uri)
    }
    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importVideo(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("local_library_screen"),
    ) {
        RavenTopBar(
            title = "本地库管理",
            subtitle = "${uiState.totalCount} 项 · ${RavenFormatters.formatByteSize(uiState.totalBytes)}",
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
                IconButton(
                    onClick = { viewModel.toggleSelectionMode() },
                    modifier = Modifier.testTag("local_library_multiselect_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckBox,
                        contentDescription = "批量管理",
                        tint = if (uiState.selectionMode) colors.crimson else colors.inkPrimary,
                    )
                }
            },
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val columns = computeLibraryGridColumns(maxWidth.value.toInt())
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            if (!uiState.message.isNullOrBlank()) {
                item(key = "local_msg") {
                    LibraryFeedbackBanner(
                        message = uiState.message.orEmpty(),
                        tone = RavenStatusTone.ACCENT,
                        onDismiss = viewModel::clearMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (uiState.importState.isImporting) {
                item(key = "local_importing") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RavenShapes.ControlShape)
                            .background(colors.surface2)
                            .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.crimson,
                        )
                        Text(
                            text = uiState.importState.progressMessage ?: "正在导入…",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkPrimary,
                        )
                    }
                }
            } else if (!uiState.importState.lastMessage.isNullOrBlank()) {
                item(key = "local_import_done") {
                    LibraryFeedbackBanner(
                        message = uiState.importState.lastMessage.orEmpty(),
                        tone = if (uiState.importState.isError) RavenStatusTone.ERROR else RavenStatusTone.SUCCESS,
                        onDismiss = viewModel::clearImportStatus,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // SAF Import Triggers
            item(key = "local_saf_actions") {
                RavenGroupedSection(
                    title = "导入本地文件（SAF）",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    RavenGroupedRow(
                        title = "导入 CBZ / ZIP 压缩包",
                        subtitle = "自动校验图片完整性并按自然顺序排页",
                        icon = Icons.Outlined.FolderZip,
                        enabled = !uiState.importState.isImporting,
                        showChevron = true,
                        onClick = {
                            archiveLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-cbz",
                                    "application/vnd.comicbook+zip",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        modifier = Modifier.testTag("local_import_cbz_button"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "导入图片目录",
                        subtitle = "选择本机相册或漫画目录",
                        icon = Icons.Outlined.FolderOpen,
                        enabled = !uiState.importState.isImporting,
                        showChevron = true,
                        onClick = { folderLauncher.launch(null) },
                        modifier = Modifier.testTag("local_import_folder_button"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "导入本地视频",
                        subtitle = "支持 MP4 / MKV / WebM / MOV 等格式",
                        icon = Icons.Outlined.Movie,
                        enabled = !uiState.importState.isImporting,
                        showChevron = true,
                        onClick = { videoLauncher.launch(arrayOf("video/*")) },
                        modifier = Modifier.testTag("local_import_video_button"),
                    )
                }
            }

            item(key = "local_search") {
                RavenSearchField(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    placeholder = "搜索本地漫画或视频…",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item(key = "local_filters") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RavenFilterChipRow(
                        items = LocalLibraryKindFilter.entries,
                        selectedItem = uiState.kindFilter,
                        labelProvider = { it.label },
                        onItemSelected = viewModel::selectKindFilter,
                        keyProvider = { it.name },
                    )
                    RavenFilterChipRow(
                        items = LocalLibrarySort.entries,
                        selectedItem = uiState.sort,
                        labelProvider = { it.label },
                        onItemSelected = viewModel::selectSort,
                        keyProvider = { it.name },
                    )
                }
            }

            if (uiState.books.isEmpty()) {
                item(key = "local_empty") {
                    RavenEmptyState(
                        title = if (uiState.totalCount == 0) "本地库为空" else "没有匹配的本地内容",
                        message = "点击上方导入按钮，即可将 CBZ/ZIP、图片目录或视频文件安全导入本地库。",
                        icon = Icons.Outlined.FolderZip,
                    )
                }
            } else {
                val rows = uiState.books.chunked(columns)
                itemsIndexed(
                    items = rows,
                    key = { idx, row -> "local_row_${idx}_${row.first().id}" },
                ) { _, rowBooks ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowBooks.forEach { book ->
                            val mediaItem = uiState.mediaItems[book.id]
                            val isSelected = if (uiState.selectionMode) {
                                book.id in uiState.selectedIds
                            } else {
                                null
                            }
                            LocalBookGridCard(
                                book = book,
                                coverUrl = mediaItem?.coverUrl.orEmpty(),
                                selected = isSelected,
                                onClick = {
                                    if (uiState.selectionMode) {
                                        viewModel.toggleSelectBook(book.id)
                                    } else if (mediaItem != null) {
                                        navigator.openMedia(mediaItem, offlineOnly = true)
                                    }
                                },
                                onRemoveClick = { removingBook = book },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - rowBooks.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        }

        val motionReduced = com.raven.app.core.designsystem.theme.LocalMotionReduced.current
        AnimatedVisibility(
            visible = uiState.selectionMode,
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                expandVertically(tween(200, easing = com.raven.app.core.designsystem.theme.RavenMotion.HarmonyDecelerationEasing)) +
                    fadeIn(tween(160))
            },
            exit = if (motionReduced) {
                ExitTransition.None
            } else {
                shrinkVertically(tween(170, easing = com.raven.app.core.designsystem.theme.RavenMotion.HarmonyDecelerationEasing)) +
                    fadeOut(tween(120))
            },
        ) {
            LibrarySelectionActionBar(
                selectedCount = uiState.selectedIds.size,
                onSelectAll = viewModel::selectAllVisible,
                onClearSelection = viewModel::clearSelection,
                onAddToShelf = {
                    if (uiState.selectedIds.isNotEmpty()) showShelfPicker = true
                },
                onDeleteSelected = {
                    if (uiState.selectedIds.isNotEmpty()) viewModel.removeSelectedBooks()
                },
            )
        }
    }

    if (showShelfPicker) {
        ShelfPickerBottomSheet(
            shelves = uiState.shelves,
            itemCount = uiState.selectedIds.size,
            onSelectShelf = { shelfId -> viewModel.addSelectedToShelf(shelfId) },
            onCreateAndAdd = { name -> viewModel.createShelfAndAddSelected(name) },
            onDismiss = { showShelfPicker = false },
        )
    }

    removingBook?.let { book ->
        LibraryConfirmBottomSheet(
            title = "移除本地内容「${book.title}」？",
            message = "将从应用本地存储中删除 ${RavenFormatters.formatByteSize(book.bytes)}，原外部文件不受影响。",
            confirmLabel = "移除",
            onConfirm = { viewModel.removeBook(book.id) },
            onDismiss = { removingBook = null },
        )
    }
}
