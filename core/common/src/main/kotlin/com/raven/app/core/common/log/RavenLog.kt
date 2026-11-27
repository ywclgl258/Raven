package com.raven.app.core.common.log

/**
 * Pure-JVM diagnostic log facade shared by every module.
 *
 * Design goals:
 * - One call site ([RavenLog.w] / [RavenLog.e]) fans out to the on-disk rolling file, the logcat
 *   mirror, and the in-memory ring buffer, so a bug report always has a matching timeline.
 * - Secrets never reach disk: [LogRedactor] runs before any sink sees the line.
 * - Cheap when idle: level filtering short-circuits before formatting, and the ring buffer is a
 *   fixed-size deque (no unbounded growth on long sessions).
 * - Testable without Android: no `android.util.Log`, no `Context`; the platform bits live in `:app`.
 */
object RavenLog {
    const val DEFAULT_RING_CAPACITY: Int = 1200
    private const val MAX_MESSAGE_CHARS: Int = 1000
    private const val MAX_THROWABLE_CHARS: Int = 1200

    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    data class Entry(
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: String? = null,
    ) {
        fun format(): String = buildString {
            append(LogClock.iso(timestampMs))
            append(' ')
            append(level.name.padEnd(5))
            append(' ')
            append(tag)
            append(": ")
            append(message)
            if (!throwable.isNullOrBlank()) {
                append('\n')
                append(throwable)
            }
        }
    }

    fun interface Sink {
        fun write(entry: Entry)
    }

    private val lock = Any()
    private val sinks = ArrayList<Sink>(4)
    private val ring = ArrayDeque<Entry>(128)
    private var lastError: Entry? = null
    private var verbose: Boolean = false

    /** Session-scoped verbose capture; DEBUG/VERBOSE lines are dropped unless enabled. */
    fun setVerbose(enabled: Boolean) {
        synchronized(lock) { verbose = enabled }
    }

    fun isVerbose(): Boolean = synchronized(lock) { verbose }

    fun addSink(sink: Sink) {
        synchronized(lock) { sinks.add(sink) }
    }

    fun removeSink(sink: Sink) {
        synchronized(lock) { sinks.remove(sink) }
    }

    fun resetForTest() {
        synchronized(lock) {
            sinks.clear()
            ring.clear()
            lastError = null
            verbose = false
        }
    }

    fun v(tag: String, message: String) = log(Level.VERBOSE, tag, message, null)

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, message, error)

    /** Short user-visible or navigation breadcrumb, kept at INFO so release builds retain it. */
    fun breadcrumb(tag: String, message: String) = log(Level.INFO, tag, message, null)

    fun log(level: Level, tag: String, message: String, error: Throwable?): Entry? {
        val enabledNow = synchronized(lock) {
            verbose || level.ordinal >= Level.INFO.ordinal
        }
        if (!enabledNow) return null
        val entry = Entry(
            timestampMs = LogClock.now(),
            level = level,
            tag = tag.take(48),
            message = LogRedactor.redact(message).take(MAX_MESSAGE_CHARS),
            throwable = error?.let { LogRedactor.redact(stackDigest(it)).take(MAX_THROWABLE_CHARS) },
        )
        synchronized(lock) {
            ring.addLast(entry)
            while (ring.size > DEFAULT_RING_CAPACITY) ring.removeFirst()
            if (level == Level.ERROR) lastError = entry
            for (index in sinks.indices) {
                runCatching { sinks[index].write(entry) }
            }
        }
        return entry
    }

    fun snapshot(limit: Int = DEFAULT_RING_CAPACITY): List<Entry> = synchronized(lock) {
        if (ring.size <= limit) ring.toList() else ring.toList().takeLast(limit)
    }

    fun lastError(): Entry? = synchronized(lock) { lastError }

    fun errors(limit: Int = 40): List<Entry> = snapshot().filter { it.level == Level.ERROR || it.level == Level.WARN }.takeLast(limit)

    /**
     * Renders the agent-readable report: identity header, highest-signal diagnostics first
     * (errors), then the recent timeline, then whatever the platform adds (logcat, crash file).
     */
    fun report(
        header: Map<String, String>,
        logcat: String? = null,
        crashTail: String? = null,
        timelineLimit: Int = 600,
    ): String = buildString {
        appendLine("== Raven 诊断报告 ==")
        header.forEach { (key, value) -> appendLine("$key: ${LogRedactor.redact(value)}") }
        appendLine()
        val errorEntries = errors()
        appendLine("== 错误与警告（最近 ${errorEntries.size} 条）==")
        if (errorEntries.isEmpty()) {
            appendLine("（本次运行没有记录到错误）")
        } else {
            errorEntries.forEach { appendLine(it.format()) }
        }
        appendLine()
        val last = lastError()
        if (last != null) {
            appendLine("== 最近一次错误 ==")
            appendLine(last.format())
            appendLine()
        }
        appendLine("== 事件时间线（最近 $timelineLimit 条）==")
        val timeline = snapshot(timelineLimit)
        if (timeline.isEmpty()) {
            appendLine("（没有事件）")
        } else {
            timeline.forEach { appendLine(it.format()) }
        }
        appendLine()
        if (!crashTail.isNullOrBlank()) {
            appendLine("== 上次崩溃 ==")
            appendLine(LogRedactor.redact(crashTail))
            appendLine()
        }
        if (!logcat.isNullOrBlank()) {
            appendLine("== logcat（应用进程最近日志，已过滤）==")
            appendLine(LogRedactor.redact(logcat).takeLast(500_000))
        }
    }

    /** Keeps the first frames of the app's own code plus the exception chain; drops SDK noise. */
    fun stackDigest(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 3) {
            if (depth > 0) append("Caused by: ")
            append(current.javaClass.name)
            current.message?.let { append(": ").append(it) }
            append('\n')
            current.stackTrace
                .filterNot { frame -> frame.className.startsWith("java.") || frame.className.startsWith("kotlinx.") }
                .take(14)
                .forEach { frame -> append("    at ").append(frame).append('\n') }
            val next = current.cause
            if (next === current) break
            current = next
            depth++
        }
    }.trimEnd()
}

/** Injectable clock so unit tests can assert stable formatting. */
object LogClock {
    @Volatile
    private var override: (() -> Long)? = null

    fun now(): Long = override?.invoke() ?: System.currentTimeMillis()

    fun setForTest(clock: (() -> Long)?) {
        override = clock
    }

    fun iso(timestampMs: Long): String = java.time.Instant.ofEpochMilli(timestampMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))
}
