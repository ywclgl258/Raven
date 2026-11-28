package com.raven.app.core.designsystem.motion

import androidx.compose.runtime.Immutable

/** 采样与对齐模型：字形、等距采样描边、对齐候选对与插值计划。 */

/**
 * Named vector-stroke glyph defined in canonical `24f x 24f` coordinates.
 */
@Immutable
data class RavenMorphGlyph(
    val id: String,
    val strokes: List<MorphStroke>,
)

/**
 * Uniformly arc-length resampled stroke (`pts` stores interleaved `[x0, y0, x1, y1, ...]`).
 */
class SampledStroke(
    val pts: FloatArray,
    val closed: Boolean,
    val fill: Boolean,
) {
    val sampleCount: Int get() = pts.size / 2

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SampledStroke) return false
        return closed == other.closed && fill == other.fill && pts.contentEquals(other.pts)
    }

    override fun hashCode(): Int {
        var result = pts.contentHashCode()
        result = 31 * result + closed.hashCode()
        result = 31 * result + fill.hashCode()
        return result
    }
}

/**
 * Pre-aligned source/target subpath pair for polar similarity morphing.
 */
class AlignedMorphPair(
    val sampleCount: Int,
    val caX: Float,
    val caY: Float,
    val cbX: Float,
    val cbY: Float,
    val theta: Float,
    val lnSigma: Float,
    val aC: FloatArray,
    val bT: FloatArray,
    val fromClosed: Boolean,
    val toClosed: Boolean,
    val fromFill: Boolean,
    val toFill: Boolean,
)

/**
 * Compiled morph plan between two [RavenMorphGlyph]s ready for zero-allocation frame interpolation.
 */
class MorphPlan(
    val pairs: List<AlignedMorphPair>,
) {
    fun allocateBuffers(): List<FloatArray> =
        pairs.map { FloatArray(it.sampleCount * 2) }
}
