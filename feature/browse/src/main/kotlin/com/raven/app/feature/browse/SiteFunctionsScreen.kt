package com.raven.app.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.displayLabel
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderPreferences
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.sitespi.AuthState
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data class SiteFunctionsUiState(
    val site: SiteDescriptor? = null,
    val isActiveSite: Boolean = false,
    val supportsLanguageFilter: Boolean = false,
    val comicLanguage: ComicLanguage = ComicLanguage.ALL,
    val supportsGalleryReader: Boolean = false,
    val readerMode: ReaderMode = ReaderMode.STRIP,
    val translateTags: Boolean = true,
    val supportsAuth: Boolean = false,
    val authState: AuthState = AuthState(isAuthenticated = false),
    val favoriteCount: Int = 0,
    val downloadCount: Int = 0,
    val subscriptionCount: Int = 0,
    val blockedTermCount: Int = 0,
    val feedSections: List<SiteSectionSpec> = emptyList(),
    val directorySections: List<SiteSectionSpec> = emptyList(),
)

private enum class SiteFunctionsSheetKind {
    NONE,
    LANGUAGE,
    READER_MODE,
}

private data class SiteCounts(
    val favorites: Int,
    val downloads: Int,
    val subscriptions: Int,
    val blockedTerms: Int,
)

class SiteFunctionsViewModel(
    val route: RavenRoute.SiteFunctions,
    private val siteRegistry: SiteRegistry,
    private val preferencesRepository: PreferencesRepository,
    private val favoriteRepository: FavoriteRepository,
    private val downloadStateRepository: DownloadStateRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    val siteId: SiteId? = runCatching { SiteId.of(route.siteId) }.getOrNull()
    private val plugin: SitePlugin? = siteId?.let(siteRegistry::get)
    private val authStateFlow = MutableStateFlow(AuthState(isAuthenticated = false))

    private val countsFlow = if (siteId != null) {
        combine(
            favoriteRepository.observeBySite(siteId),
            downloadStateRepository.observeBySite(siteId),
            subscriptionRepository.observeSubscriptions(siteId),
            blockedTermsRepository.observeEffectiveTermsBySite(siteId),
        ) { favs, dls, subs, blocked ->
            SiteCounts(
                favorites = favs.size,
                downloads = dls.size,
                subscriptions = subs.size,
                blockedTerms = blocked.size,
            )
        }
    } else {
        flowOf(SiteCounts(favorites = 0, downloads = 0, subscriptions = 0, blockedTerms = 0))
    }

    private val readerPrefsFlow = if (siteId != null) {
        combine(
            preferencesRepository.observeSiteReaderPreferences(siteId),
            preferencesRepository.observeGlobalReaderPreferences(),
        ) { siteOverride, globalDefault ->
            siteOverride ?: globalDefault
        }
    } else {
        preferencesRepository.observeGlobalReaderPreferences()
    }

    val uiState: StateFlow<SiteFunctionsUiState> = combine(
        preferencesRepository.settings,
        readerPrefsFlow,
        countsFlow,
        authStateFlow,
    ) { settings, readerPrefs, counts, auth ->
        val descriptor = plugin?.descriptor
        val supportsLang = siteId != null && descriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true
        val supportsGallery = descriptor?.supportedKinds?.any { it.isGallery } == true
        val supportsAuth = descriptor?.hasCapability(SiteCapability.AUTH) == true ||
            descriptor?.hasCapability(SiteCapability.COOKIE_IMPORT) == true

        val feedSections = descriptor?.sections?.filterNot { it.isDirectory }.orEmpty()
        val directorySections = buildList {
            plugin?.directorySource?.directorySections()?.let(::addAll)
            descriptor?.sections?.filter { it.isDirectory }?.forEach { spec ->
                if (none { it.id == spec.id }) add(spec)
            }
        }

        SiteFunctionsUiState(
            site = descriptor,
            isActiveSite = siteId != null && settings.activeSiteId == siteId,
            supportsLanguageFilter = supportsLang,
            comicLanguage = if (supportsLang && siteId != null) settings.languageFor(siteId) else ComicLanguage.ALL,
            supportsGalleryReader = supportsGallery,
            readerMode = readerPrefs.mode,
            translateTags = settings.translateTags,
            supportsAuth = supportsAuth,
            authState = auth,
            favoriteCount = counts.favorites,
            downloadCount = counts.downloads,
            subscriptionCount = counts.subscriptions,
            blockedTermCount = counts.blockedTerms,
            feedSections = feedSections,
            directorySections = directorySections,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SiteFunctionsUiState(site = plugin?.descriptor),
    )

    init {
        refreshAuthState()
    }

    fun refreshAuthState() {
        val targetPlugin = plugin ?: return
        viewModelScope.launch {
            val state = runCatching {
                withContext(dispatchers.io) { targetPlugin.authProvider.currentAuthState() }
            }.getOrDefault(AuthState(isAuthenticated = false))
            authStateFlow.value = state
        }
    }

    fun activateSite() {
        val id = siteId ?: return
        if (plugin == null) return
        viewModelScope.launch {
            preferencesRepository.setActiveSiteId(id)
        }
    }

    fun setComicLanguage(language: ComicLanguage) {
        val id = siteId ?: return
        viewModelScope.launch {
            preferencesRepository.setSiteLanguage(id, language)
        }
    }

    fun setReaderMode(mode: ReaderMode) {
        val id = siteId ?: return
        viewModelScope.launch {
            val existing = preferencesRepository.observeSiteReaderPreferences(id).first()
                ?: preferencesRepository.observeGlobalReaderPreferences().first().copy(siteId = id, mediaId = null)
            preferencesRepository.saveReaderPreferences(existing.copy(siteId = id, mediaId = null, mode = mode))
        }
    }

    fun setTranslateTags(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTranslateTags(enabled)
        }
    }
}

@Composable
fun SiteFunctionsScreen(
    route: RavenRoute.SiteFunctions,
    navigator: BrowseNavigator,
    modifier: Modifier = Modifier,
    viewModel: SiteFunctionsViewModel = koinViewModel(
        key = "site_functions_${route.siteId}",
        parameters = { parametersOf(route) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SiteFunctionsScreenContent(
        uiState = uiState,
        navigator = navigator,
        onActivateSite = viewModel::activateSite,
        onSelectLanguage = viewModel::setComicLanguage,
        onSelectReaderMode = viewModel::setReaderMode,
        onToggleTranslateTags = viewModel::setTranslateTags,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SiteFunctionsScreenContent(
    uiState: SiteFunctionsUiState,
    navigator: BrowseNavigator,
    onActivateSite: () -> Unit,
    onSelectLanguage: (ComicLanguage) -> Unit,
    onSelectReaderMode: (ReaderMode) -> Unit,
    onToggleTranslateTags: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    var activeSheet by rememberSaveable { mutableStateOf(SiteFunctionsSheetKind.NONE) }
    val site = uiState.site

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("browse_site_functions_screen"),
    ) {
        RavenTopBar(
            title = site?.displayName ?: "站点中心",
            subtitle = "站点功能与分区",
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("site_functions_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
        )

        if (site == null) {
            RavenEmptyState(
                title = "站点不可用",
                message = "未找到对应站点配置。",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("site_functions_list"),
            ) {
                // Overview card with accessNote
                item(key = "site_overview_card") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RavenShapes.CardShape)
                            .background(colors.surface1)
                            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                tint = colors.crimson,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = site.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.inkPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (uiState.isActiveSite) {
                                RavenStatusPill(text = "当前站点", tone = RavenStatusTone.ACCENT)
                            }
                        }

                        Text(
                            text = site.defaultHost,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            site.supportedKinds.forEach { kind ->
                                RavenStatusPill(text = kind.displayLabel())
                            }
                            if (site.mirrorHosts.isNotEmpty()) {
                                RavenStatusPill(text = "${site.mirrorHosts.size + 1} 镜像节点")
                            }
                            if (uiState.supportsAuth) {
                                RavenStatusPill(
                                    text = if (uiState.authState.isAuthenticated) {
                                        uiState.authState.username?.let { "已登录 · $it" } ?: "已登录"
                                    } else {
                                        "未登录"
                                    },
                                    tone = if (uiState.authState.isAuthenticated) {
                                        RavenStatusTone.SUCCESS
                                    } else if (site.networkProfile.requiresAuthGate) {
                                        RavenStatusTone.WARNING
                                    } else {
                                        RavenStatusTone.NEUTRAL
                                    },
                                )
                            }
                        }

                        val accessNote = site.accessNote
                        if (!accessNote.isNullOrBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RavenShapes.ControlShape)
                                    .background(colors.surface2)
                                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = colors.warning,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = accessNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkSecondary,
                                )
                            }
                        }
                    }
                }

                // Quick entries
                item(key = "quick_entries_section") {
                    RavenGroupedSection(title = "快捷入口") {
                        if (!uiState.isActiveSite) {
                            RavenGroupedRow(
                                title = "切换为当前浏览站点",
                                subtitle = "首页与发现页将切换到 ${site.displayName}",
                                icon = Icons.Outlined.CheckCircle,
                                iconTint = colors.crimson,
                                onClick = onActivateSite,
                            )
                            RavenGroupedDivider(insetWithIcon = true)
                        }
                        if (site.hasCapability(SiteCapability.SEARCH)) {
                            RavenGroupedRow(
                                title = "站内搜索",
                                subtitle = "在 ${site.shortName} 中搜索作品、标签或作者",
                                icon = Icons.Outlined.Search,
                                showChevron = true,
                                onClick = {
                                    if (!uiState.isActiveSite) onActivateSite()
                                    navigator.openSearch(crossSite = false)
                                },
                            )
                            RavenGroupedDivider(insetWithIcon = true)
                        }
                        RavenGroupedRow(
                            title = "跨站聚合搜索",
                            subtitle = "同时检索多个站点",
                            icon = Icons.Outlined.TravelExplore,
                            showChevron = true,
                            onClick = { navigator.openSearch(crossSite = true) },
                        )
                    }
                }

                // Quick site preferences
                item(key = "site_prefs_section") {
                    RavenGroupedSection(title = "站点快捷偏好") {
                        var hasAbove = false
                        if (uiState.supportsLanguageFilter) {
                            RavenGroupedRow(
                                title = "漫画语言筛选",
                                value = uiState.comicLanguage.displayLabel(),
                                icon = Icons.Outlined.Translate,
                                showChevron = true,
                                onClick = { activeSheet = SiteFunctionsSheetKind.LANGUAGE },
                            )
                            hasAbove = true
                        }
                        if (uiState.supportsGalleryReader) {
                            if (hasAbove) RavenGroupedDivider(insetWithIcon = true)
                            RavenGroupedRow(
                                title = "默认阅读模式",
                                value = uiState.readerMode.displayLabel(),
                                icon = Icons.Outlined.MenuBook,
                                showChevron = true,
                                onClick = { activeSheet = SiteFunctionsSheetKind.READER_MODE },
                            )
                            hasAbove = true
                        }
                        if (hasAbove) RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedSwitchRow(
                            title = "全局标签中文翻译",
                            subtitle = "与主设置共用同一偏好，对所有站点生效",
                            icon = Icons.Outlined.Translate,
                            checked = uiState.translateTags,
                            onCheckedChange = onToggleTranslateTags,
                        )
                    }
                }

                // Site-scoped library & account hub
                item(key = "site_scoped_library_section") {
                    RavenGroupedSection(title = "本站资料与账号") {
                        RavenGroupedRow(
                            title = "本站收藏",
                            value = "${uiState.favoriteCount}",
                            icon = Icons.Outlined.FavoriteBorder,
                            showChevron = true,
                            onClick = { navigator.openFavorites(site.id) },
                        )
                        RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedRow(
                            title = "本站下载",
                            value = "${uiState.downloadCount}",
                            icon = Icons.Outlined.Download,
                            showChevron = true,
                            onClick = { navigator.openDownloads(site.id) },
                        )
                        RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedRow(
                            title = "本站订阅",
                            value = "${uiState.subscriptionCount}",
                            icon = Icons.Outlined.Notifications,
                            showChevron = true,
                            onClick = { navigator.openSubscriptions(site.id) },
                        )
                        RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedRow(
                            title = "本站屏蔽词",
                            value = "${uiState.blockedTermCount}",
                            icon = Icons.Outlined.Block,
                            showChevron = true,
                            onClick = { navigator.openBlockedTerms(site.id) },
                        )
                        if (uiState.supportsAuth) {
                            RavenGroupedDivider(insetWithIcon = true)
                            RavenGroupedRow(
                                title = "账号与会话管理",
                                subtitle = if (uiState.authState.isAuthenticated) {
                                    uiState.authState.username ?: "已保持加密会话"
                                } else {
                                    "登录账号或导入加密 Cookie"
                                },
                                value = if (uiState.authState.isAuthenticated) "已登录" else "未登录",
                                icon = Icons.Outlined.ManageAccounts,
                                iconTint = if (uiState.authState.isAuthenticated) colors.success else colors.crimson,
                                showChevron = true,
                                onClick = { navigator.openSiteAccount(site.id) },
                            )
                        }
                    }
                }

                // Feed sections
                if (uiState.feedSections.isNotEmpty()) {
                    item(key = "site_feed_sections") {
                        RavenGroupedSection(title = "站点分区") {
                            uiState.feedSections.forEachIndexed { idx, spec ->
                                if (idx > 0) RavenGroupedDivider(insetWithIcon = true)
                                RavenGroupedRow(
                                    title = spec.title,
                                    subtitle = spec.subtitle.ifBlank { null },
                                    icon = Icons.Outlined.BookmarkBorder,
                                    showChevron = true,
                                    onClick = {
                                        navigator.openSiteFeed(
                                            RavenRoute.SiteFeed(
                                                siteId = site.id.value,
                                                section = spec.id,
                                                category = spec.category.ifBlank { null },
                                                title = spec.title,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                // Directory sections
                if (uiState.directorySections.isNotEmpty()) {
                    item(key = "site_directory_sections") {
                        RavenGroupedSection(title = "目录索引") {
                            uiState.directorySections.forEachIndexed { idx, spec ->
                                if (idx > 0) RavenGroupedDivider(insetWithIcon = true)
                                RavenGroupedRow(
                                    title = spec.title,
                                    subtitle = spec.subtitle.ifBlank { null },
                                    icon = Icons.Outlined.FolderOpen,
                                    showChevron = true,
                                    onClick = { navigator.openSiteDirectory(site.id, spec.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (activeSheet) {
        SiteFunctionsSheetKind.LANGUAGE -> {
            RavenBottomSheet(
                onDismissRequest = { activeSheet = SiteFunctionsSheetKind.NONE },
                title = "漫画语言筛选",
            ) {
                RavenGroupedSection {
                    ComicLanguage.entries.forEachIndexed { idx, lang ->
                        if (idx > 0) RavenGroupedDivider()
                        RavenGroupedRow(
                            title = lang.displayLabel(),
                            trailingContent = if (uiState.comicLanguage == lang) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "已选中",
                                        tint = colors.crimson,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                onSelectLanguage(lang)
                                activeSheet = SiteFunctionsSheetKind.NONE
                            },
                        )
                    }
                }
            }
        }
        SiteFunctionsSheetKind.READER_MODE -> {
            RavenBottomSheet(
                onDismissRequest = { activeSheet = SiteFunctionsSheetKind.NONE },
                title = "站点默认阅读模式",
            ) {
                RavenGroupedSection {
                    ReaderMode.entries.forEachIndexed { idx, mode ->
                        if (idx > 0) RavenGroupedDivider()
                        RavenGroupedRow(
                            title = mode.displayLabel(),
                            trailingContent = if (uiState.readerMode == mode) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "已选中",
                                        tint = colors.crimson,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                onSelectReaderMode(mode)
                                activeSheet = SiteFunctionsSheetKind.NONE
                            },
                        )
                    }
                }
            }
        }
        SiteFunctionsSheetKind.NONE -> Unit
    }
}
