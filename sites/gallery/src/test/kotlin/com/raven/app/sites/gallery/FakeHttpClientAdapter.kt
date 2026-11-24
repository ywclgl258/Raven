package com.raven.app.sites.gallery

import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse

internal class FakeHttpClientAdapter(
    private val responder: (SiteHttpRequest) -> SiteHttpResponse = { req ->
        SiteHttpResponse(code = 404, finalUrl = req.url)
    },
) : HttpClientAdapter {
    private val routes = LinkedHashMap<String, (SiteHttpRequest) -> SiteHttpResponse>()
    val recordedRequests: MutableList<SiteHttpRequest> = mutableListOf()

    fun stubHtml(
        url: String,
        html: String,
        code: Int = 200,
        headers: Map<String, List<String>> = emptyMap(),
        finalUrl: String = url,
        fromCache: Boolean = false,
    ) {
        routes[url] = {
            SiteHttpResponse(
                code = code,
                finalUrl = finalUrl,
                headers = headers,
                body = html.toByteArray(Charsets.UTF_8),
                fromCache = fromCache,
            )
        }
    }

    fun stubDynamic(url: String, handler: (SiteHttpRequest) -> SiteHttpResponse) {
        routes[url] = handler
    }

    override suspend fun execute(request: SiteHttpRequest): SiteHttpResponse {
        recordedRequests += request
        val matched = routes[request.url]
        return matched?.invoke(request) ?: responder(request)
    }
}
