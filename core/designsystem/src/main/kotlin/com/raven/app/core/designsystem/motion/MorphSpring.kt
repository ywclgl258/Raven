package com.raven.app.core.designsystem.motion

import kotlin.math.abs
import kotlin.math.min

/** 图标形变弹簧：预设参数与可中断半隐式欧拉积分。 */

/**
 * Spring tuning presets calibrated for icon morph transitions.
 */
enum class MorphSpringPreset(
    val stiffness: Float,
    val damping: Float,
) {
    SMOOTH(stiffness = 170f, damping = 26f),
    SNAPPY(stiffness = 420f, damping = 30f),
    BOUNCY(stiffness = 300f, damping = 14f),
}

/**
 * Interruptible semi-implicit Euler spring operating in fixed `1 / 240s` substeps.
 */
class MorphSpring(
    var preset: MorphSpringPreset = MorphSpringPreset.SNAPPY,
) {
    var value: Float = 1f
        private set
    var velocity: Float = 0f
        private set
    var target: Float = 1f
        private set
    var isRunning: Boolean = false
        private set

    /**
     * Starts a new `[fromValue -> toValue]` transition. When invoked mid-flight with
     * [preserveVelocity] = true, incoming velocity is preserved and clamped to `[-14f, 14f]`.
     */
    fun start(
        fromValue: Float = 0f,
        toValue: Float = 1f,
        preserveVelocity: Boolean = true,
    ) {
        val safeFrom = if (fromValue.isFinite()) fromValue else 0f
        val safeTo = if (toValue.isFinite()) toValue else 1f
        velocity = if (preserveVelocity && velocity.isFinite()) {
            velocity.coerceIn(-MAX_START_VELOCITY, MAX_START_VELOCITY)
        } else {
            0f
        }
        value = safeFrom
        target = safeTo
        isRunning = true
    }

    /**
     * Snaps immediately to [targetValue] and stops active simulation.
     */
    fun snapTo(targetValue: Float = 1f) {
        val safeTarget = if (targetValue.isFinite()) targetValue else 1f
        value = safeTarget
        target = safeTarget
        velocity = 0f
        isRunning = false
    }

    /**
     * Advances simulation by [dtSeconds] using `1 / 240s` semi-implicit Euler substeps.
     * Returns `true` while still moving, `false` once settled.
     */
    fun step(dtSeconds: Float): Boolean {
        if (!isRunning) return false
        if (!dtSeconds.isFinite() || dtSeconds <= 0f) return isRunning

        var remaining = dtSeconds.coerceAtMost(MAX_FRAME_DT)
        val stiffness = preset.stiffness
        val damping = preset.damping

        while (remaining > 0f) {
            val h = min(remaining, SUBSTEP_DT)
            val accel = stiffness * (target - value) - damping * velocity
            velocity += accel * h
            value += velocity * h
            remaining -= h
        }

        if (!value.isFinite() || !velocity.isFinite()) {
            snapTo(target)
            return false
        }

        if (abs(target - value) < POSITION_EPSILON && abs(velocity) < VELOCITY_EPSILON) {
            value = target
            velocity = 0f
            isRunning = false
        }
        return isRunning
    }

    companion object {
        const val SUBSTEP_DT: Float = 1f / 240f
        const val MAX_START_VELOCITY: Float = 14f
        private const val MAX_FRAME_DT: Float = 0.05f
        private const val POSITION_EPSILON: Float = 1e-3f
        private const val VELOCITY_EPSILON: Float = 1e-3f
    }
}
