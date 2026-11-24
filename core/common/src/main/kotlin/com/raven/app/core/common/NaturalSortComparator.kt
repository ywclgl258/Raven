package com.raven.app.core.common

/**
 * Natural alphanumeric comparator (e.g., `page2.jpg` < `page10.jpg`, `001.png` == numeric magnitude of `1.png`).
 * Case-insensitive primary pass with deterministic tie-breaking.
 */
object NaturalSortComparator : Comparator<String> {
    private val tokenRegex = Regex("\\d+|\\D+")

    override fun compare(a: String?, b: String?): Int {
        if (a === b) return 0
        if (a == null) return -1
        if (b == null) return 1

        val left = tokenRegex.findAll(a.lowercase()).map { it.value }.toList()
        val right = tokenRegex.findAll(b.lowercase()).map { it.value }.toList()
        val limit = minOf(left.size, right.size)

        for (i in 0 until limit) {
            val x = left[i]
            val y = right[i]
            val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0').ifEmpty { "0" }
                val ny = y.trimStart('0').ifEmpty { "0" }
                val lenCmp = nx.length.compareTo(ny.length)
                if (lenCmp != 0) lenCmp else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }

        val sizeCmp = left.size.compareTo(right.size)
        return if (sizeCmp != 0) sizeCmp else a.compareTo(b)
    }
}
