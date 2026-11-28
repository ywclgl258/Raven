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
    isLoadingMore: () -> Boolean = { false },
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
        val prevCount = readItems().size
        onLoadNextPage()
        kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            androidx.compose.runtime.snapshotFlow {
                readItems().size > prevCount || !isLoadingMore() || !hasMore()
            }
            // Wait a brief moment first so `isLoadingMore` can flip true if triggered asynchronously.
            kotlinx.coroutines.delay(50)
            while (isLoadingMore() && readItems().size == prevCount && hasMore()) {
                kotlinx.coroutines.delay(100)
            }
        }
    }
    host.showSnackbar("当前已加载的内容里没有该位置，请继续向下浏览或重试", duration = SnackbarDuration.Short)
}

internal const val MAX_RESTORE_PAGE_ATTEMPTS: Int = 10
