package com.raven.app.core.network

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import kotlin.coroutines.CoroutineContext

/**
 * Named Koin qualifiers for the shared pre-built OkHttp client pool and application coroutine scope.
 */
object NetworkQualifiers {
    const val PAGE_NAME: String = "page"
    const val API_NAME: String = "api"
    const val IMAGE_NAME: String = "image"
    const val MEDIA_NAME: String = "media"
    const val PROBE_NAME: String = "probe"
    const val LAN_NAME: String = "lan"
    const val APPLICATION_SCOPE_NAME: String = "application_scope"

    val PAGE: Qualifier = named(PAGE_NAME)
    val API: Qualifier = named(API_NAME)
    val IMAGE: Qualifier = named(IMAGE_NAME)
    val MEDIA: Qualifier = named(MEDIA_NAME)
    val PROBE: Qualifier = named(PROBE_NAME)
    val LAN: Qualifier = named(LAN_NAME)
    val APPLICATION_SCOPE: Qualifier = named(APPLICATION_SCOPE_NAME)
}

/**
 * Process-lifetime structured [CoroutineScope] (`SupervisorJob() + Dispatchers.Default`)
 * backed by injectable [AppDispatchers].
 */
class ApplicationScope(
    val dispatchers: AppDispatchers = DefaultAppDispatchers(),
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + dispatchers.default
}
