package com.raven.app.feature.browse

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState

/** 首页跨视口共享的流阶段与「恢复到上次作品」加载助手。从 `HomeScreen.kt` 拆分时抽出，供影院式与瀑布流首页共用。 */

internal enum class HomeStreamStage {
    AUTH_GATE,
    LOADING,
    ERROR,
    EMPTY,
    CONTENT,
}

/**
 * Loads pages until the saved work is visible instead of silently jumping to the top when the
 * anchor has not arrived yet. The caller is responsible for scrolling after a hit.
 */
internal suspend fun jumpToSavedWork(
    targetId: String,
    readItems: () -> List<com.raven.app.core.model.MediaItem>,
    hasMore: () -> Boolean,
    onLoadNextPage: () -> Unit,
    onFound: (Int) -> Unit,
    host: SnackbarHostState,
) {
    for (attempt in 0 until MAX_RESTORE_PAGE_ATTEMPTS) {
        val index = readItems().indexOfFirst { it.id == targetId }
        if (index >= 0) {
            onFound(index)
            return
        }
        if (!hasMore()) break
        onLoadNextPage()
        kotlinx.coroutines.delay(250)
    }
    host.showSnackbar("当前已加载的内容里没有该位置，请继续向下浏览或重试", duration = SnackbarDuration.Short)
}

internal const val MAX_RESTORE_PAGE_ATTEMPTS: Int = 10
