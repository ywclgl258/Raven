package com.raven.app.sites.comic

import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse

/**
 * Pure-JVM fake [HttpClientAdapter] supporting exact/predicate routing, HTTP 206 Range slicing,
 * mutable route keys (for [com.raven.app.core.sitespi.MirrorLeaseRouter] tests), and request auditing.
 */
internal class FakeHttpClientAdapter(
    private val fallback: suspend (SiteHttpRequest) -> SiteHttpResponse = { req ->
        SiteHttpResponse(code = 404, finalUrl = req.url, body = "Not Found: ${req.url}".toByteArray())
    },
) : HttpClientAdapter {

    val recordedRequests: MutableList<SiteHttpRequest> = mutableListOf()
    var routeKey: String = "route-default"

    private val handlers = mutableListOf<Pair<(SiteHttpRequest) -> Boolean, suspend (SiteHttpRequest) -> SiteHttpResponse>>()

    override fun currentRouteKey(urls: List<String>): String = routeKey

    fun onUrl(url: String, response: SiteHttpResponse) {
        onMatch({ req -> req.url == url }) { response }
    }

    fun onUrlText(
        url: String,
        body: String,
        code: Int = 200,
        headers: Map<String, List<String>> = emptyMap(),
        finalUrl: String = url,
    ) {
        onUrl(
            url,
            SiteHttpResponse(
                code = code,
                finalUrl = finalUrl,
                headers = headers,
                body = body.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    fun onBinaryWithRange(url: String, fullBytes: ByteArray) {
        onMatch({ req -> req.url == url }) { req ->
            val range = req.rangeHeader
            if (range == null) {
                SiteHttpResponse(
                    code = 200,
                    finalUrl = req.url,
                    headers = mapOf("Content-Length" to listOf(fullBytes.size.toString())),
                    body = fullBytes,
                )
            } else {
                val m = RANGE_REGEX.matchEntire(range.trim())
                    ?: error("Invalid Range header in test: $range")
                val start = m.groupValues[1].toInt()
                val endReq = m.groupValues[2].toInt()
                if (start >= fullBytes.size) {
                    SiteHttpResponse(code = 416, finalUrl = req.url)
                } else {
                    val actualEnd = minOf(endReq, fullBytes.size - 1)
                    val slice = fullBytes.copyOfRange(start, actualEnd + 1)
                    SiteHttpResponse(
                        code = 206,
                        finalUrl = req.url,
                        headers = mapOf(
                            "Content-Range" to listOf("bytes $start-$actualEnd/${fullBytes.size}"),
                        ),
                        body = slice,
                    )
                }
            }
        }
    }

    fun onMatch(
        predicate: (SiteHttpRequest) -> Boolean,
        handler: suspend (SiteHttpRequest) -> SiteHttpResponse,
    ) {
        handlers.add(predicate to handler)
    }

    override suspend fun execute(request: SiteHttpRequest): SiteHttpResponse {
        recordedRequests.add(request)
        for ((predicate, handler) in handlers.asReversed()) {
            if (predicate(request)) return handler(request)
        }
        return fallback(request)
    }

    companion object {
        private val RANGE_REGEX = Regex("""bytes=(\d+)-(\d+)""")

        fun htmlResponse(
            url: String,
            html: String,
            code: Int = 200,
            headers: Map<String, List<String>> = emptyMap(),
        ): SiteHttpResponse = SiteHttpResponse(
            code = code,
            finalUrl = url,
            headers = headers,
            body = html.toByteArray(Charsets.UTF_8),
        )
    }
}
