package com.raven.app

import android.app.Activity
import android.content.Context
import android.os.Build
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
 * - For UI/Browse/Reader windows (`allowVideoFrameRateMatching = false`), locks the highest display
 *   refresh rate matching the current physical resolution so OEM ROMs (HyperOS/MIUI, ColorOS,
 *   OriginOS) do not throttle Compose rendering down to 60Hz.
 * - Enables Android 15 (API 35+) touch frame-rate boost when available.
 * - For pure video playback (`allowVideoFrameRateMatching = true`), releases forced mode lock
 *   so LTPO panels + Media3 seamless frame-rate matching can conserve battery cleanly.
 */
object DisplayRefreshRateController {

    internal const val HIGH_REFRESH_THRESHOLD_HZ: Float = 89.5f
    internal const val DEFAULT_MAX_PREFERRED_HZ: Float = 144f

    fun isHighRefreshDisplay(refreshRateHz: Float): Boolean =
        refreshRateHz.isFinite() && refreshRateHz >= HIGH_REFRESH_THRESHOLD_HZ

    fun selectOptimalHighRefreshMode(
        current: DisplayModeCandidate,
        supported: List<DisplayModeCandidate>,
        maxPreferredHz: Float = DEFAULT_MAX_PREFERRED_HZ,
    ): DisplayModeCandidate {
        if (!maxPreferredHz.isFinite() || maxPreferredHz < 59f) return current
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
        ) ?: current
    }

    fun applyHighRefreshRate(
        activity: Activity,
        allowVideoFrameRateMatching: Boolean = false,
    ) {
        val window = activity.window ?: return
        val attrs = window.attributes
        var changed = false

        if (allowVideoFrameRateMatching) {
            if (attrs.preferredDisplayModeId != 0 || attrs.preferredRefreshRate != 0f) {
                attrs.preferredDisplayModeId = 0
                attrs.preferredRefreshRate = 0f
                changed = true
            }
        } else {
            val display = resolveActivityDisplay(activity)
            if (display != null) {
                val activeMode = display.mode
                val current = activeMode.toCandidate()
                val supported = display.supportedModes.map { it.toCandidate() }
                val best = selectOptimalHighRefreshMode(current = current, supported = supported)
                if (attrs.preferredDisplayModeId != best.modeId ||
                    attrs.preferredRefreshRate != best.refreshRate
                ) {
                    attrs.preferredDisplayModeId = best.modeId
                    attrs.preferredRefreshRate = best.refreshRate
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
