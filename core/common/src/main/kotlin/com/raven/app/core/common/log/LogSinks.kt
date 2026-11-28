package com.raven.app.core.common.log

import java.io.File
import java.io.IOException

/**
 * Rolling diagnostic log on disk.
 *
 * Two files only (`raven.log` + `raven.log.1`), each capped at [maxBytes]. Writes are serialized and
 * appended, so a crash mid-write loses at most one line. Rotation is a rename, never a truncate, so
 * the previous session survives the next launch.
 */
class LogFileSink(
    private val file: File,
    private val maxBytes: Long = 384 * 1024,
    private val flushEveryWrite: Boolean = true,
) : RavenLog.Sink {

    init {
        file.parentFile?.mkdirs()
    }

    override fun write(entry: RavenLog.Entry) {
        val line = entry.format() + "\n"
        synchronized(this) {
            runCatching {
                rotateIfNeeded(line.length)
                file.appendText(line)
                if (flushEveryWrite) {
                    // appendText closes the handle; this keeps the tail durable across process death.
                }
            }.onFailure { if (it is IOException) return }
        }
    }

    fun tail(maxChars: Int = 200_000): String = synchronized(this) {
        runCatching {
            val current = if (file.isFile) file.readText() else ""
            val previous = rotatedFile().takeIf { it.isFile }?.readText().orEmpty()
            (previous + current).takeLast(maxChars)
        }.getOrDefault("")
    }

    fun totalBytes(): Long = synchronized(this) {
        val current = if (file.isFile) file.length() else 0L
        val previous = rotatedFile().takeIf { it.isFile }?.length() ?: 0L
        current + previous
    }

    fun clear() = synchronized(this) {
        runCatching { file.delete() }
        runCatching { rotatedFile().delete() }
        Unit
    }

    private fun rotateIfNeeded(incoming: Int) {
        if (!file.isFile) return
        if (file.length() + incoming <= maxBytes) return
        val rotated = rotatedFile()
        runCatching { rotated.delete() }
        runCatching { file.renameTo(rotated) }
    }

    private fun rotatedFile(): File = File(file.parentFile, file.name + ".1")
}

/**
 * Append-only crash record. Kept separate from the timeline so an OOM-killed process still leaves
 * a readable stack on the next launch, even if the rolling log rotated past it.
 */
class CrashFileSink(
    private val file: File,
    private val maxBytes: Long = 96 * 1024,
) : RavenLog.Sink {

    init {
        file.parentFile?.mkdirs()
    }

    override fun write(entry: RavenLog.Entry) {
        if (entry.level != RavenLog.Level.ERROR || entry.throwable.isNullOrBlank()) return
        synchronized(this) {
            runCatching {
                if (file.isFile && file.length() > maxBytes) {
                    file.writeText(file.readText().takeLast((maxBytes / 2).toInt()))
                }
                file.appendText(entry.format() + "\n\n")
            }
        }
    }

    fun tail(maxChars: Int = 60_000): String = synchronized(this) {
        runCatching { if (file.isFile) file.readText().takeLast(maxChars) else "" }.getOrDefault("")
    }

    fun totalBytes(): Long = synchronized(this) {
        if (file.isFile) file.length() else 0L
    }

    fun clear() = synchronized(this) { runCatching { file.delete() }; Unit }
}

/**
 * Captures the JVM default uncaught-exception handler chain: records once, then delegates so the
 * platform still shows/collects the crash as before.
 */
object CrashRecorder {
    fun install(onCrash: (RavenLog.Entry?) -> Unit = {}): Boolean {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recording) return false
        Thread.setDefaultUncaughtExceptionHandler(Recording(previous, onCrash))
        return true
    }

    fun isInstalled(): Boolean = Thread.getDefaultUncaughtExceptionHandler() is Recording

    private class Recording(
        private val previous: Thread.UncaughtExceptionHandler?,
        private val onCrash: (RavenLog.Entry?) -> Unit,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            val entry = runCatching {
                RavenLog.e(TAG, "未捕获异常 thread=${thread.name}", error)
            }.getOrNull()
            runCatching { onCrash(entry) }
            previous?.uncaughtException(thread, error)
        }

        private companion object {
            const val TAG = "Crash"
        }
    }
}
