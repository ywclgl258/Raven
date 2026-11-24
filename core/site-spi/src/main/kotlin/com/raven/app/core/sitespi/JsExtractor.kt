package com.raven.app.core.sitespi

/**
 * Pure-Kotlin JavaScript literal & object extractors for SSR/HTML site parsers
 * (e.g., KVS `flashvars`, Jable `player_aaaa`, WNACG `document.writeln`, Next.js RSC Flight).
 */
object JsExtractor {
    private val nextFlightRegex = Regex("""self\.__next_f\.push\(\[\s*1\s*,\s*"((?:\\.|[^"\\])*)"\s*]\)""")

    /**
     * Locates [marker] (e.g., `"player_aaaa"`, `"flashvars"`) and extracts the following string-aware
     * brace-matched `{ ... }` object literal without depending on trailing semicolons.
     */
    fun extractBraceObject(source: String, marker: String, fromIndex: Int = 0): String? {
        val markerIdx = source.indexOf(marker, fromIndex)
        if (markerIdx < 0) return null
        val braceStart = source.indexOf('{', markerIdx + marker.length)
        if (braceStart < 0) return null
        return extractBalanced(source, braceStart, '{', '}')
    }

    /**
     * Extracts a balanced delimiter region starting at [startIndex], ignoring delimiters inside
     * single-quoted, double-quoted, or backtick JS strings and honoring backslash escapes.
     */
    fun extractBalanced(
        source: String,
        startIndex: Int,
        openChar: Char = '{',
        closeChar: Char = '}',
    ): String? {
        if (startIndex !in source.indices || source[startIndex] != openChar) return null
        var depth = 0
        var quote: Char? = null
        var escaped = false

        for (i in startIndex until source.length) {
            val ch = source[i]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    quote = null
                }
                continue
            }
            when (ch) {
                '\'', '"', '`' -> quote = ch
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return source.substring(startIndex, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Extracts a simple JS string variable assignment such as `const source = '...'` or `var aid = "123"`.
     */
    fun extractJsStringVar(source: String, varName: String): String? {
        val escapedName = Regex.escape(varName)
        val regex = Regex("""(?:var|let|const)?\s*\b$escapedName\b\s*=\s*(['"])((?:\\.|(?!\1)[^\\])*)\1""")
        val raw = regex.find(source)?.groupValues?.get(2) ?: return null
        return unescapeJsString(raw)
    }

    /**
     * Unescapes JS/JSON string escape sequences (`\/`, `\"`, `\'`, `\\`, `\n`, `\r`, `\t`, `\uXXXX`, `\xXX`).
     */
    fun unescapeJsString(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '\\' -> { out.append('\\'); i += 2 }
                    '"' -> { out.append('"'); i += 2 }
                    '\'' -> { out.append('\''); i += 2 }
                    '/' -> { out.append('/'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'b' -> { out.append('\b'); i += 2 }
                    'f' -> { out.append('\u000C'); i += 2 }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append(ch)
                        i++
                    }
                    'x' -> {
                        if (i + 3 < raw.length) {
                            val code = raw.substring(i + 2, i + 4).toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 4
                                continue
                            }
                        }
                        out.append(ch)
                        i++
                    }
                    else -> {
                        out.append(next)
                        i += 2
                    }
                }
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Concatenates and unescapes Next.js App Router RSC Flight `self.__next_f.push([1,"..."])` chunks.
     */
    fun extractNextRscFlightChunks(html: String): String {
        val sb = StringBuilder()
        for (match in nextFlightRegex.findAll(html)) {
            sb.append(unescapeJsString(match.groupValues[1]))
        }
        return sb.toString()
    }
}
