package com.raven.app.core.sitespi

/**
 * Pure-Kotlin Dean Edwards Packer decoder (`eval(function(p,a,c,k,e,...)` -> unpacked JS).
 * Supports bases 2..62 and performs single-pass token substitution so expanded dictionary values
 * are never re-substituted.
 */
object Base62Packer {
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val EVAL_MARKER = "eval(function(p,a,c,k,e,"
    private val tokenRegex = Regex("\\b[0-9A-Za-z]+\\b")

    /**
     * Unpacks a single Dean Edwards packed block; returns [source] unchanged if not standard packed form.
     */
    fun unpack(source: String): String {
        val open = source.indexOf("}(")
        if (open < 0) return source
        var cursor = open + 2

        fun readQuoted(): String? {
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            if (cursor >= source.length) return null
            val quote = source[cursor]
            if (quote != '\'' && quote != '"') return null
            cursor++
            val out = StringBuilder()
            while (cursor < source.length) {
                val ch = source[cursor]
                if (ch == '\\') {
                    cursor++
                    if (cursor >= source.length) return null
                    out.append(source[cursor])
                    cursor++
                    continue
                }
                if (ch == quote) {
                    cursor++
                    return out.toString()
                }
                out.append(ch)
                cursor++
            }
            return null
        }

        fun readNumber(): Int? {
            while (cursor < source.length && !source[cursor].isDigit()) {
                if (source[cursor] == '\'' || source[cursor] == '"') return null
                cursor++
            }
            val start = cursor
            while (cursor < source.length && source[cursor].isDigit()) cursor++
            return source.substring(start, cursor).toIntOrNull()
        }

        val payload = readQuoted() ?: return source
        val base = readNumber() ?: return source
        val count = readNumber() ?: return source

        while (cursor < source.length && source[cursor] != '\'' && source[cursor] != '"') {
            cursor++
        }
        val words = readQuoted()?.split('|') ?: return source
        if (base !in 2..62 || count < 0 || words.size < count) return source

        val replacements = HashMap<String, String>(count)
        for (index in 0 until count) {
            val word = words[index]
            if (word.isNotEmpty()) {
                val token = encodeBaseToken(index, base) ?: continue
                replacements[token] = word
            }
        }
        return tokenRegex.replace(payload) { match -> replacements[match.value] ?: match.value }
    }

    /**
     * Finds and unpacks every `eval(function(p,a,c,k,e,` block in an HTML/JS document.
     */
    fun unpackAll(html: String): List<String> {
        val results = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = html.indexOf(EVAL_MARKER, from)
            if (start < 0) break
            val slice = html.substring(start)
            val unpacked = unpack(slice)
            if (unpacked !== slice) {
                results.add(unpacked)
            }
            from = start + EVAL_MARKER.length
        }
        return results
    }

    fun encodeBaseToken(value: Int, base: Int): String? {
        if (base !in 2..62 || value < 0) return null
        if (value == 0) return "0"
        var remaining = value
        val out = StringBuilder()
        while (remaining > 0) {
            out.append(DIGITS[remaining % base])
            remaining /= base
        }
        return out.reverse().toString()
    }
}
