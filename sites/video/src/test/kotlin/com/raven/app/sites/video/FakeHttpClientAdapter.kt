package com.raven.app.sites.video

import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse

internal class FakeHttpClientAdapter(
    private val handler: suspend (SiteHttpRequest) -> SiteHttpResponse = { req ->
        SiteHttpResponse(code = 404, finalUrl = req.url, body = "Not found: ${req.url}".toByteArray())
    },
) : HttpClientAdapter {
    private val _requests = mutableListOf<SiteHttpRequest>()
    val requests: List<SiteHttpRequest> get() = _requests.toList()
    var routeKeyValue: String = "route-1"

    private val exactRoutes = LinkedHashMap<Pair<SiteHttpMethod, String>, suspend (SiteHttpRequest) -> SiteHttpResponse>()
    private val predicateRoutes = mutableListOf<Pair<(SiteHttpRequest) -> Boolean, suspend (SiteHttpRequest) -> SiteHttpResponse>>()

    fun onGet(
        url: String,
        code: Int = 200,
        body: String = "",
        headers: Map<String, List<String>> = emptyMap(),
        finalUrl: String = url,
    ) {
        exactRoutes[SiteHttpMethod.GET to url] = {
            SiteHttpResponse(
                code = code,
                finalUrl = finalUrl,
                headers = headers,
                body = body.toByteArray(Charsets.UTF_8),
            )
        }
    }

    fun onGetDynamic(
        url: String,
        responder: suspend (SiteHttpRequest) -> SiteHttpResponse,
    ) {
        exactRoutes[SiteHttpMethod.GET to url] = responder
    }

    fun onPost(
        url: String,
        responder: suspend (SiteHttpRequest) -> SiteHttpResponse,
    ) {
        exactRoutes[SiteHttpMethod.POST to url] = responder
    }

    fun onMatching(
        predicate: (SiteHttpRequest) -> Boolean,
        responder: suspend (SiteHttpRequest) -> SiteHttpResponse,
    ) {
        predicateRoutes.add(predicate to responder)
    }

    override fun currentRouteKey(urls: List<String>): String = routeKeyValue

    override suspend fun execute(request: SiteHttpRequest): SiteHttpResponse {
        _requests.add(request)
        exactRoutes[request.method to request.url]?.let { return it(request) }
        predicateRoutes.firstOrNull { (pred, _) -> pred(request) }?.let { (_, responder) ->
            return responder(request)
        }
        return handler(request)
    }
}
