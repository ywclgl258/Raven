package com.raven.app.feature.settings

import java.io.File

/**
 * Contract between the settings feedback screen and the platform diagnostic log installed by `:app`.
 *
 * Implemented by `RavenDiagnostics` in `:app` so `:feature:settings` stays free of Build fields,
 * logcat and file-layout knowledge.
 */
interface FeedbackDiagnosticSource {
    /** Writes one upload-ready report file and returns it (null when logging is unavailable). */
    fun snapshot(): File?

    /** Bytes currently held by the rolling log, shown in the toggle subtitle. */
    fun bytes(): Long

    /** Stable per-install identifier, used to correlate repeated reports without an account. */
    fun installId(): String

    fun isVerbose(): Boolean

    /** Enables DEBUG-level capture for the current process only (never persisted). */
    fun setVerbose(enabled: Boolean)

    /** Latest recorded error, so the user can see what will be attached. */
    fun lastErrorSummary(): String?

    /** True when a crash record from a previous run exists. */
    fun hasCrashRecord(): Boolean
}

/** Fallback used in JVM tests and previews where the Android log is not installed. */
class EmptyFeedbackDiagnosticSource : FeedbackDiagnosticSource {
    override fun snapshot(): File? = null
    override fun bytes(): Long = 0L
    override fun installId(): String = "test-install"
    override fun isVerbose(): Boolean = false
    override fun setVerbose(enabled: Boolean) = Unit
    override fun lastErrorSummary(): String? = null
    override fun hasCrashRecord(): Boolean = false
}
