package com.raven.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import com.raven.app.core.common.log.Diagnostics
import com.raven.app.core.common.log.LogClock
import com.raven.app.core.common.log.RavenLog
import com.raven.app.feature.settings.FeedbackDiagnosticSource
import com.raven.app.feature.settings.RavenAppInfo
import java.io.File
import java.util.Locale

/**
 * Android side of the diagnostic log: environment header, logcat mirror, and the upload snapshot.
 *
 * Install order matters. [install] must run in `Application.onCreate` before anything else logs so a
 * crash during startup is still recorded.
 */
object RavenDiagnostics : FeedbackDiagnosticSource {
    private const val PREFS = "raven_diagnostics"
    private const val KEY_INSTALL_ID = "install_id"
    private const val LOGCAT_MAX_LINES = "600"

    @Volatile
    private var diagnostics: Diagnostics? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var appInfo: RavenAppInfo? = null

    fun install(context: Context, info: RavenAppInfo, debuggable: Boolean) {
        if (diagnostics != null) return
        val appContext = context.applicationContext
        val dir = File(appContext.filesDir, "diagnostics").apply { mkdirs() }
        val engine = Diagnostics(
            timelineFile = File(dir, "raven.log"),
            crashFile = File(dir, "crash.log"),
            header = { environmentHeader(appContext, info) },
            logcat = { dumpLogcat() },
            verboseByDefault = debuggable,
        )
        engine.install()
        // Mirror into logcat so `adb logcat` still works while developing, without a second call site.
        RavenLog.addSink(LogcatSink)
        this.appContext = appContext
        this.appInfo = info
        this.diagnostics = engine
        RavenLog.i(TAG, "diagnostics installed version=${info.versionName} (${info.versionCode})")
    }

    fun isInstalled(): Boolean = diagnostics != null

    override fun snapshot(): File? {
        val engine = diagnostics ?: return null
        val context = appContext ?: return null
        val target = File(File(context.cacheDir, "feedback").apply { mkdirs() }, "raven-diagnostics.txt")
        return runCatching { engine.writeSnapshot(target) }
            .onFailure { RavenLog.w(TAG, "写入诊断报告失败", it) }
            .getOrNull()
    }

    override fun bytes(): Long = diagnostics?.bytes() ?: 0L

    override fun installId(): String {
        val context = appContext ?: return "unavailable"
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val created = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, created).apply()
        return created
    }

    override fun isVerbose(): Boolean = RavenLog.isVerbose()

    override fun setVerbose(enabled: Boolean) {
        RavenLog.setVerbose(enabled)
        RavenLog.i(TAG, if (enabled) "verbose logging enabled" else "verbose logging disabled")
    }

    override fun lastErrorSummary(): String? = diagnostics?.lastErrorSummary()

    override fun hasCrashRecord(): Boolean {
        val context = appContext ?: return false
        return File(context.filesDir, "diagnostics/crash.log").let { it.isFile && it.length() > 0L }
    }

    /** Wipes both the timeline and the crash record; used after the user confirms a report is sent. */
    fun clear() {
        diagnostics?.clear()
        RavenLog.i(TAG, "diagnostics cleared")
    }

    private fun environmentHeader(context: Context, info: RavenAppInfo): Map<String, String> = buildMap {
        put("应用版本", "${info.versionName} (${info.versionCode})")
        put("包名", info.packageName)
        put("构建类型", if (info.isDebug) "debug" else "release")
        put("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("Android", "${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        put("ABI", Build.SUPPORTED_ABIS?.joinToString(",").orEmpty())
        put("语言", Locale.getDefault().toLanguageTag())
        put("时区", java.util.TimeZone.getDefault().id)
        put("屏幕", context.resources.displayMetrics.let { "${it.widthPixels}x${it.heightPixels} @${it.density}" })
        put("安装ID", installId())
        put("进程ID", Process.myPid().toString())
        put("运行时长", runtimeSinceStart())
        put("内存", memorySnapshot(context))
    }

    private fun runtimeSinceStart(): String {
        val uptimeMs = android.os.SystemClock.elapsedRealtime()
        return "${uptimeMs / 1000}s"
    }

    private fun memorySnapshot(context: Context): String = runCatching {
        val runtime = Runtime.getRuntime()
        val heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val lowMb = memoryInfo.availMem / (1024 * 1024)
        "heap=${heapMb}MB/${maxMb}MB 可用=${lowMb}MB 低内存=${memoryInfo.lowMemory}"
    }.getOrDefault("unknown")

    /**
     * Best-effort logcat tail for this process only. On Android 10+ without READ_LOGS this returns
     * often-empty output; the timeline above is the primary source, so an empty tail is harmless.
     */
    private fun dumpLogcat(): String = runCatching {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-t",
            LOGCAT_MAX_LINES,
            "--pid=${Process.myPid()}",
            "-v",
            "threadtime",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        text
    }.getOrDefault("")

    /** Mirrors every captured entry into logcat with the original tag, so `adb logcat` still works. */
    private object LogcatSink : RavenLog.Sink {
        override fun write(entry: RavenLog.Entry) {
            val priority = when (entry.level) {
                RavenLog.Level.VERBOSE -> Log.VERBOSE
                RavenLog.Level.DEBUG -> Log.DEBUG
                RavenLog.Level.INFO -> Log.INFO
                RavenLog.Level.WARN -> Log.WARN
                RavenLog.Level.ERROR -> Log.ERROR
            }
            if (!Log.isLoggable(entry.tag, priority)) return
            Log.println(priority, entry.tag, entry.message + (entry.throwable?.let { "\n$it" } ?: ""))
        }
    }

    private const val TAG = "RavenDiagnostics"

    /** Exposed for tests/diagnostics UI: local ISO timestamp of the last entry. */
    fun lastEntryTimestamp(): String? = RavenLog.snapshot(1).lastOrNull()?.let { LogClock.iso(it.timestampMs) }
}
