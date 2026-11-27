package com.raven.app.core.common.log

/**
 * Conservative redaction for anything that could be a credential.
 *
 * Applied twice on purpose: once before a line reaches a sink (so secrets never land on disk) and
 * again when assembling the upload report (defense in depth against older rotated files).
 */
object LogRedactor {
    private const val REPLACEMENT = "[redacted]"
    private const val MAX_LINE_CHARS = 4_000

    private val secretKeyValue = Regex(
        "(?i)\\b(password|passwd|pwd|cookie|set-cookie|authorization|auth|token|access_token|refresh_token|secret|api[_-]?key|signature|sig)\\b" +
            "(\\s*[:=]\\s*)(\"?)((?:(?:bearer|basic|digest)\\s+)?[^\\s\"&,;]+)",
    )
    private val bearerToken = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val urlSecretQuery = Regex(
        "(?i)([?&](?:token|key|sig|signature|password|passwd|pwd|auth|session|code)=)[^&#\\s]+",
    )
    private val sessionCookieValue = Regex("(?i)\\b([A-Za-z0-9_-]{2,24})=(?:[A-Za-z0-9%._-]{20,})")
    private val privateKeyBlock = Regex(
        "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----",
    )

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var text = input
        if (text.length > MAX_LINE_CHARS) {
            text = text.take(MAX_LINE_CHARS) + "…(已截断)"
        }
        text = privateKeyBlock.replace(text, "$REPLACEMENT(private key)")
        text = secretKeyValue.replace(text) { match ->
            val key = match.groupValues[1]
            val separator = match.groupValues[2]
            val quote = match.groupValues[3]
            "$key$separator$quote$REPLACEMENT"
        }
        text = bearerToken.replace(text) { match -> "${match.groupValues[1]} $REPLACEMENT" }
        text = urlSecretQuery.replace(text) { match -> match.groupValues[1] + REPLACEMENT }
        text = sessionCookieValue.replace(text) { match -> "${match.groupValues[1]}=$REPLACEMENT" }
        return text
    }

    /** Convenience for messages assembled with a URL that may carry a signed query string. */
    fun redactUrl(url: String): String = urlSecretQuery.replace(url) { match -> match.groupValues[1] + REPLACEMENT }

    /** Strips a site cookie jar dump to key names only, so logs stay useful without leaking values. */
    fun cookieNames(raw: String): String = raw.split(';', '\n')
        .mapNotNull { part -> part.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
        .joinToString(", ")
}
