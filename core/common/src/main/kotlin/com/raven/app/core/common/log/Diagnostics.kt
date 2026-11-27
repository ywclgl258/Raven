package com.raven.app.core.common.log

import java.io.File

/**
 * Wires the diagnostic log for a process: rolling timeline, crash record, optional logcat tail.
 *
 * Platform-free on purpose — `:app` supplies the environment header (Build fields, ABI, memory) and
 * the logcat dump, while tests can pass fixed values.
 */
class Diagnostics(
    private val timelineFile: File,
    private val crashFile: File,
    private val header: () -> Map<String, String>,
    private val logcat: () -> String? = { null },
    private val verboseByDefault: Boolean = false,
    private val maxTimelineBytes: Long = 384 * 1024,
) {
    val timelineSink: LogFileSink = LogFileSink(timelineFile, maxBytes = maxTimelineBytes)
    val crashSink: CrashFileSink = CrashFileSink(crashFile)

    private var installed = false

    /** Idempotent; safe to call from `Application.onCreate`. */
    fun install() {
        if (installed) return
        installed = true
        RavenLog.addSink(timelineSink)
        RavenLog.addSink(crashSink)
        RavenLog.setVerbose(verboseByDefault)
        CrashRecorder.install()
    }

    fun bytes(): Long = timelineSink.totalBytes() + crashSink.tail(1).length

    /** Writes one upload-ready report and returns it. Empty log is still a valid report. */
    fun writeSnapshot(target: File): File {
        target.parentFile?.mkdirs()
        val headerValues = buildMap {
            put("生成时间", LogClock.iso(LogClock.now()))
            putAll(header())
            put("详细日志", if (RavenLog.isVerbose()) "开启" else "关闭")
            put("事件条数", RavenLog.snapshot().size.toString())
        }
        val crashTail = crashSink.tail()
        val report = RavenLog.report(
            header = headerValues,
            logcat = runCatching { logcat() }.getOrNull(),
            crashTail = crashTail,
        ) + "\n\n== 日志文件尾部（滚动文件）==\n" + timelineSink.tail(120_000)
        target.writeText(report)
        return target
    }

    fun clear() {
        timelineSink.clear()
        crashSink.clear()
    }

    /** Test/preview helper: message shown in the feedback screen. */
    fun lastErrorSummary(): String? = RavenLog.lastError()?.let { entry ->
        "${entry.tag}: ${entry.message}"
    }
}
