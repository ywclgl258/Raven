package com.raven.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display

/**
 * Pure Kotlin representation of an Android [Display.Mode] for unit-testable high-refresh-rate
 * selection without Robolectric/Android framework mocks.
 */
data class DisplayModeCandidate(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float,
)

/**
 * High-refresh-rate (90Hz / 120Hz / 144Hz) & LTPO adaptive display coordinator.
 *
 * - For UI windows (`allowVideoFrameRateMatching = false`), requests the highest mode that keeps
 *   the current physical resolution and stays inside the system peak-refresh cap.
 * - Battery saver and a user peak at or below 60Hz release the window preference instead of locking 120Hz.
 * - Enables Android 15 (API 35+) touch frame-rate boost when available.
 * - For pure video playback (`allowVideoFrameRateMatching = true`), releases the forced mode lock
 *   so LTPO panels + Media3 seamless frame-rate matching can conserve battery.
 */
object DisplayRefreshRateController {

    internal const val HIGH_REFRESH_THRESHOLD_HZ: Float = 89.5f
    internal const val DEFAULT_MAX_PREFERRED_HZ: Float = 144f
    internal const val SYSTEM_PEAK_REFRESH_RATE = "peak_refresh_rate"

    fun isHighRefreshDisplay(refreshRateHz: Float): Boolean =
        refreshRateHz.isFinite() && refreshRateHz >= HIGH_REFRESH_THRESHOLD_HZ

    /**
     * Highest same-resolution mode at or under [maxPreferredHz]. Never changes width/height.
     * A non-positive or non-finite cap means the caller must not force a mode.
     */
    fun selectOptimalHighRefreshMode(
        current: DisplayModeCandidate,
        supported: List<DisplayModeCandidate>,
        maxPreferredHz: Float = DEFAULT_MAX_PREFERRED_HZ,
    ): DisplayModeCandidate? {
        if (!maxPreferredHz.isFinite() || maxPreferredHz < 59f) return null
        val sameResolution = supported.filter { candidate ->
            candidate.physicalWidth == current.physicalWidth &&
                candidate.physicalHeight == current.physicalHeight &&
                candidate.refreshRate.isFinite() &&
                candidate.refreshRate >= 59f &&
                candidate.refreshRate <= maxPreferredHz + 0.5f
        }
        return sameResolution.maxWithOrNull(
            compareBy<DisplayModeCandidate> { it.refreshRate }
                .thenBy { it.modeId == current.modeId },
        )
    }

    /**
     * System peak is an upper bound, never a reason to raise the app default.
     * Battery saver or a standard-refresh peak leaves mode selection to the system.
     */
    fun resolvePreferredRefreshCapHz(
        batterySaver: Boolean,
        systemPeakHz: Float,
        appMaxHz: Float = DEFAULT_MAX_PREFERRED_HZ,
    ): Float {
        if (batterySaver) return 0f
        val userCappedToStandard = systemPeakHz.isFinite() &&
            systemPeakHz > 0f &&
            systemPeakHz <= 60.5f
        if (userCappedToStandard) return 0f
        val peakBound = if (systemPeakHz.isFinite() && systemPeakHz >= 59f) systemPeakHz else appMaxHz
        return minOf(appMaxHz, peakBound)
    }

    fun applyHighRefreshRate(
        activity: Activity,
        allowVideoFrameRateMatching: Boolean = false,
    ) {
        val window = activity.window ?: return
        val attrs = window.attributes
        var changed = false

        val capHz = if (allowVideoFrameRateMatching) 0f else readSystemRefreshCapHz(activity)
        if (capHz < 59f) {
            if (attrs.preferredDisplayModeId != 0 || attrs.preferredRefreshRate != 0f) {
                attrs.preferredDisplayModeId = 0
                attrs.preferredRefreshRate = 0f
                changed = true
            }
        } else {
            val display = resolveActivityDisplay(activity)
            if (display != null) {
                val current = display.mode.toCandidate()
                val supported = display.supportedModes.map { it.toCandidate() }
                val best = selectOptimalHighRefreshMode(
                    current = current,
                    supported = supported,
                    maxPreferredHz = capHz,
                )
                val nextModeId = best?.modeId ?: 0
                val nextRate = best?.refreshRate ?: 0f
                if (attrs.preferredDisplayModeId != nextModeId ||
                    attrs.preferredRefreshRate != nextRate
                ) {
                    attrs.preferredDisplayModeId = nextModeId
                    attrs.preferredRefreshRate = nextRate
                    changed = true
                }
            }
        }

        if (changed) {
            window.attributes = attrs
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            if (!window.frameRateBoostOnTouchEnabled) {
                window.setFrameRateBoostOnTouchEnabled(true)
            }
        }
    }

    fun isSystemMotionReduced(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    private fun readSystemRefreshCapHz(context: Context): Float =
        resolvePreferredRefreshCapHz(
            batterySaver = isBatterySaverEnabled(context),
            systemPeakHz = readFloatSetting(context, SYSTEM_PEAK_REFRESH_RATE),
        )

    private fun isBatterySaverEnabled(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }.getOrDefault(false)

    private fun readFloatSetting(context: Context, key: String): Float = runCatching {
        Settings.System.getFloat(context.contentResolver, key)
    }.getOrDefault(Float.NaN)

    private fun resolveActivityDisplay(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager?.defaultDisplay
        }

    private fun Display.Mode.toCandidate(): DisplayModeCandidate = DisplayModeCandidate(
        modeId = modeId,
        physicalWidth = physicalWidth,
        physicalHeight = physicalHeight,
        refreshRate = refreshRate,
    )
}
