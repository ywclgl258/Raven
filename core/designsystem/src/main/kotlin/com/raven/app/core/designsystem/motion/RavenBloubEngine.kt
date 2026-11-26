package com.raven.app.core.designsystem.motion

import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Fixed radial sample count around every [BloubSilhouette] contour. */
const val PROFILE_SAMPLES: Int = 64

/** Spring overshoot scale applied to [RavenPopBadge] when unread/item counts increase. */
const val NOTIF_POP_SCALE: Float = 1.14f

private const val TWO_PI: Float = (2.0 * PI).toFloat()
private const val PI_F: Float = PI.toFloat()

/**
 * Semantic presence moods for [RavenBloubPresence] and [RavenBloubLoadingIndicator].
 */
enum class BloubMood {
    IDLE,
    ORBIT,
    SEARCHING,
    EMPTY,
    ERROR,
    SUCCESS,
}

/**
 * Radial silhouette descriptor sampled at [PROFILE_SAMPLES] uniform polar angles with affine
 * transform parameters ([rot], [cx], [cy], [sx], [sy]).
 */
class BloubSilhouette(
    val radii: FloatArray = FloatArray(PROFILE_SAMPLES) { 1f },
    var rot: Float = 0f,
    var cx: Float = 0f,
    var cy: Float = 0f,
    var sx: Float = 1f,
    var sy: Float = 1f,
) {
    /** Copies all radial samples and transform parameters from [other] without allocation. */
    fun copyFrom(other: BloubSilhouette): BloubSilhouette {
        val count = minOf(radii.size, other.radii.size)
        for (i in 0 until count) {
            radii[i] = sanitizeFinite(other.radii[i], 1f)
        }
        for (i in count until radii.size) {
            radii[i] = 1f
        }
        rot = sanitizeFinite(other.rot, 0f)
        cx = sanitizeFinite(other.cx, 0f)
        cy = sanitizeFinite(other.cy, 0f)
        sx = sanitizeFinite(other.sx, 1f)
        sy = sanitizeFinite(other.sy, 1f)
        return this
    }
}

/**
 * Precomputed smooth radial silhouettes aligned to Raven's Dark Obsidian × Raven Crimson visual tone.
 */
object BloubSilhouettes {
    val Squircle: BloubSilhouette = buildSuperellipse(
        exponent = 3.2f,
        baseRadius = 0.90f,
    )

    val SoftBlob: BloubSilhouette = buildHarmonicBlob(
        baseRadius = 0.88f,
        amp2 = 0.045f,
        amp3 = 0.065f,
        amp4 = 0.020f,
        phase = 0.45f,
        sx = 1.02f,
        sy = 0.98f,
    )

    val DropletPetal: BloubSilhouette = buildDropletPetal()

    val AlertDiamondSquircle: BloubSilhouette = buildSuperellipse(
        exponent = 1.48f,
        baseRadius = 0.92f,
        rot = 0.14f,
        sx = 1.04f,
        sy = 0.96f,
    )

    val CompactCore: BloubSilhouette = buildSuperellipse(
        exponent = 2.4f,
        baseRadius = 0.68f,
    )

    val CuriousEmpty: BloubSilhouette = buildHarmonicBlob(
        baseRadius = 0.86f,
        amp2 = 0.03f,
        amp3 = 0.035f,
        amp4 = 0.04f,
        phase = 1.1f,
        cy = -0.02f,
        sx = 0.96f,
        sy = 1.04f,
    )

    /** Returns the canonical resting silhouette for a given [BloubMood]. */
    fun forMood(mood: BloubMood): BloubSilhouette = when (mood) {
        BloubMood.IDLE -> Squircle
        BloubMood.ORBIT -> CompactCore
        BloubMood.SEARCHING -> SoftBlob
        BloubMood.EMPTY -> CuriousEmpty
        BloubMood.ERROR -> AlertDiamondSquircle
        BloubMood.SUCCESS -> DropletPetal
    }

    private fun buildSuperellipse(
        exponent: Float,
        baseRadius: Float,
        rot: Float = 0f,
        cx: Float = 0f,
        cy: Float = 0f,
        sx: Float = 1f,
        sy: Float = 1f,
    ): BloubSilhouette {
        val radii = FloatArray(PROFILE_SAMPLES)
        val invExp = 1f / exponent
        for (i in 0 until PROFILE_SAMPLES) {
            val theta = TWO_PI * i / PROFILE_SAMPLES
            val c = abs(cos(theta)).pow(exponent)
            val s = abs(sin(theta)).pow(exponent)
            val superR = (c + s).coerceAtLeast(1e-4f).pow(-invExp)
            radii[i] = baseRadius * superR
        }
        return BloubSilhouette(
            radii = radii,
            rot = rot,
            cx = cx,
            cy = cy,
            sx = sx,
            sy = sy,
        )
    }

    private fun buildHarmonicBlob(
        baseRadius: Float,
        amp2: Float,
        amp3: Float,
        amp4: Float,
        phase: Float,
        rot: Float = 0f,
        cx: Float = 0f,
        cy: Float = 0f,
        sx: Float = 1f,
        sy: Float = 1f,
    ): BloubSilhouette {
        val radii = FloatArray(PROFILE_SAMPLES)
        for (i in 0 until PROFILE_SAMPLES) {
            val theta = TWO_PI * i / PROFILE_SAMPLES
            radii[i] = baseRadius +
                amp2 * cos(2f * theta - phase) +
                amp3 * sin(3f * theta + phase * 0.7f) +
                amp4 * cos(4f * theta)
        }
        return BloubSilhouette(
            radii = radii,
            rot = rot,
            cx = cx,
            cy = cy,
            sx = sx,
            sy = sy,
        )
    }

    private fun buildDropletPetal(): BloubSilhouette {
        val radii = FloatArray(PROFILE_SAMPLES)
        for (i in 0 until PROFILE_SAMPLES) {
            val theta = TWO_PI * i / PROFILE_SAMPLES
            // Gentle upward-pointing rounded petal / droplet curve
            val upward = -sin(theta)
            radii[i] = 0.87f +
                0.08f * upward +
                0.045f * cos(2f * theta) +
                0.03f * cos(3f * theta - 0.5f)
        }
        return BloubSilhouette(
            radii = radii,
            rot = 0f,
            cx = 0f,
            cy = -0.03f,
            sx = 1.04f,
            sy = 0.97f,
        )
    }
}

/**
 * Zero-allocation interpolation between silhouettes [a] and [b] at fraction [t] (`0f..1f`),
 * writing into [out] and using shortest-angle wrap across `±PI` for rotation.
 */
fun blendSilhouettes(
    a: BloubSilhouette,
    b: BloubSilhouette,
    t: Float,
    out: BloubSilhouette,
): BloubSilhouette {
    val u = if (t.isFinite()) t.coerceIn(0f, 1f) else 0f
    val count = minOf(a.radii.size, b.radii.size, out.radii.size)
    for (i in 0 until count) {
        val ra = sanitizeFinite(a.radii[i], 1f)
        val rb = sanitizeFinite(b.radii[i], 1f)
        out.radii[i] = ra + (rb - ra) * u
    }
    for (i in count until out.radii.size) {
        out.radii[i] = 1f
    }
    val rotA = sanitizeFinite(a.rot, 0f)
    val rotB = sanitizeFinite(b.rot, 0f)
    val deltaRot = shortestAngleDelta(rotA, rotB)
    out.rot = when {
        u <= 0f -> rotA
        u >= 1f -> rotB
        else -> wrapAngle(rotA + deltaRot * u)
    }
    val cxA = sanitizeFinite(a.cx, 0f)
    val cxB = sanitizeFinite(b.cx, 0f)
    val cyA = sanitizeFinite(a.cy, 0f)
    val cyB = sanitizeFinite(b.cy, 0f)
    val sxA = sanitizeFinite(a.sx, 1f)
    val sxB = sanitizeFinite(b.sx, 1f)
    val syA = sanitizeFinite(a.sy, 1f)
    val syB = sanitizeFinite(b.sy, 1f)
    out.cx = cxA + (cxB - cxA) * u
    out.cy = cyA + (cyB - cyA) * u
    out.sx = sxA + (sxB - sxA) * u
    out.sy = syA + (syB - syA) * u
    return out
}

/**
 * Projects [silhouette] samples into interleaved `[x0, y0, x1, y1, ...]` screen coordinates inside
 * [outPoints], returning the number of projected vertices (`0` if inputs are invalid).
 */
fun projectSilhouettePoints(
    silhouette: BloubSilhouette,
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    outPoints: FloatArray,
): Int {
    if (!centerX.isFinite() || !centerY.isFinite() || !baseRadius.isFinite() || baseRadius <= 0f) {
        return 0
    }
    val count = minOf(silhouette.radii.size, outPoints.size / 2)
    if (count < 3) return 0

    val rot = sanitizeFinite(silhouette.rot, 0f)
    val cx = sanitizeFinite(silhouette.cx, 0f)
    val cy = sanitizeFinite(silhouette.cy, 0f)
    val sx = sanitizeFinite(silhouette.sx, 1f)
    val sy = sanitizeFinite(silhouette.sy, 1f)
    val cosR = cos(rot)
    val sinR = sin(rot)
    val originX = centerX + cx * baseRadius
    val originY = centerY + cy * baseRadius

    for (i in 0 until count) {
        val theta = TWO_PI * i / count
        val r = sanitizeFinite(silhouette.radii[i], 1f).coerceAtLeast(0.05f) * baseRadius
        val lx = r * cos(theta) * sx
        val ly = r * sin(theta) * sy
        outPoints[i * 2] = originX + (lx * cosR - ly * sinR)
        outPoints[i * 2 + 1] = originY + (lx * sinR + ly * cosR)
    }
    return count
}

/**
 * Writes a closed C1-continuous Catmull-Rom cubic Bézier curve from interleaved [pointsXY] into
 * [outPath] without allocating temporary point objects.
 */
fun writeCatmullRomClosedPath(
    pointsXY: FloatArray,
    pointCount: Int,
    outPath: Path,
    tension: Float = 1f,
) {
    outPath.reset()
    val safeCount = minOf(pointCount, pointsXY.size / 2)
    if (safeCount < 3) return
    val factor = (if (tension.isFinite()) tension.coerceIn(0f, 2f) else 1f) / 6f

    val startX = sanitizeFinite(pointsXY[0], 0f)
    val startY = sanitizeFinite(pointsXY[1], 0f)
    outPath.moveTo(startX, startY)

    for (i in 0 until safeCount) {
        val i0 = (i - 1 + safeCount) % safeCount
        val i1 = i
        val i2 = (i + 1) % safeCount
        val i3 = (i + 2) % safeCount

        val p0x = sanitizeFinite(pointsXY[i0 * 2], startX)
        val p0y = sanitizeFinite(pointsXY[i0 * 2 + 1], startY)
        val p1x = sanitizeFinite(pointsXY[i1 * 2], startX)
        val p1y = sanitizeFinite(pointsXY[i1 * 2 + 1], startY)
        val p2x = sanitizeFinite(pointsXY[i2 * 2], startX)
        val p2y = sanitizeFinite(pointsXY[i2 * 2 + 1], startY)
        val p3x = sanitizeFinite(pointsXY[i3 * 2], startX)
        val p3y = sanitizeFinite(pointsXY[i3 * 2 + 1], startY)

        val c1x = p1x + (p2x - p0x) * factor
        val c1y = p1y + (p2y - p0y) * factor
        val c2x = p2x - (p3x - p1x) * factor
        val c2y = p2y - (p3y - p1y) * factor

        outPath.cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)
    }
    outPath.close()
}

/**
 * Smooth periodic harmonic noise in `[-1f, 1f]` guaranteed to repeat seamlessly every [period]
 * units of [t] for living idle gaze drift and organic breathing.
 */
fun loopNoise(
    t: Float,
    period: Float = 1f,
    seed: Float = 0f,
): Float {
    if (!t.isFinite() || !period.isFinite() || period <= 0f || !seed.isFinite()) return 0f
    val wrapped = ((t / period) % 1f).let { if (it < 0f) it + 1f else it }
    val phase = wrapped * (2.0 * PI)
    val s = seed.toDouble() * 1.61803398875
    val n1 = sin(phase + s * 2.13)
    val n2 = 0.55 * cos(2.0 * phase - s * 3.71)
    val n3 = 0.28 * sin(3.0 * phase + s * 5.29)
    val n4 = 0.14 * cos(5.0 * phase - s * 1.47)
    return ((n1 + n2 + n3 + n4) / 1.97).toFloat().coerceIn(-1f, 1f)
}

/**
 * One depth-sorted polyline arc of a tilted 3D orbital ring, split at the `z = 0` plane so
 * back segments (`isFront = false`) draw behind the body and front segments (`isFront = true`)
 * draw cleanly in front of it.
 */
class BloubOrbitArc(
    val ringIndex: Int,
    val isFront: Boolean,
    val pointsXY: FloatArray,
    val avgDepth: Float,
    val alpha: Float,
    val strokeScale: Float,
) {
    val pointCount: Int get() = pointsXY.size / 2

    fun xAt(index: Int): Float = pointsXY[index * 2]

    fun yAt(index: Int): Float = pointsXY[index * 2 + 1]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BloubOrbitArc) return false
        return ringIndex == other.ringIndex &&
            isFront == other.isFront &&
            avgDepth == other.avgDepth &&
            alpha == other.alpha &&
            strokeScale == other.strokeScale &&
            pointsXY.contentEquals(other.pointsXY)
    }

    override fun hashCode(): Int {
        var result = ringIndex
        result = 31 * result + isFront.hashCode()
        result = 31 * result + pointsXY.contentHashCode()
        result = 31 * result + avgDepth.hashCode()
        result = 31 * result + alpha.hashCode()
        result = 31 * result + strokeScale.hashCode()
        return result
    }
}

private const val ORBIT_SEGMENTS: Int = 56

/**
 * Computes two tilted 3D orthographic orbital rings at time [t] and radius [scale], splitting
 * each ring at `z = 0` into back (`isFront = false`) and front (`isFront = true`) arcs sorted
 * ascending by [BloubOrbitArc.avgDepth].
 */
fun computeOrbitArcs(
    t: Float,
    scale: Float = 1f,
): List<BloubOrbitArc> {
    if (!scale.isFinite() || scale <= 0f) return emptyList()
    val safeT = if (t.isFinite()) t else 0f

    val arcs = ArrayList<BloubOrbitArc>(4)
    val ringSpecs = arrayOf(
        // Primary Raven Crimson orbit
        floatArrayOf(1.18f, 1.12f, -0.44f, 1.85f, 0.92f, 1.0f),
        // Secondary subtle counter-tilted ink/rim orbit
        floatArrayOf(1.34f, 1.04f, 0.58f, -1.35f, 0.54f, 0.78f),
    )

    for (ringIdx in ringSpecs.indices) {
        val spec = ringSpecs[ringIdx]
        val radius = spec[0] * scale
        val tiltX = spec[1] + 0.14f * sin(safeT * 1.1f + ringIdx * 1.7f)
        val tiltZ = spec[2] + 0.10f * cos(safeT * 0.85f + ringIdx)
        val spin = safeT * spec[3] + ringIdx * 1.1f
        val baseAlpha = spec[4]
        val strokeScale = spec[5]

        val cosTx = cos(tiltX)
        val sinTx = sin(tiltX)
        val cosTz = cos(tiltZ)
        val sinTz = sin(tiltZ)

        val xs = FloatArray(ORBIT_SEGMENTS)
        val ys = FloatArray(ORBIT_SEGMENTS)
        val zs = FloatArray(ORBIT_SEGMENTS)

        for (i in 0 until ORBIT_SEGMENTS) {
            val angle = spin + TWO_PI * i / ORBIT_SEGMENTS
            val cx = radius * cos(angle)
            val cy = radius * sin(angle)

            // Tilt around X so ring spans depth z, then rotate in screen plane by tiltZ
            val y1 = cy * cosTx
            val z1 = cy * sinTx
            val x2 = cx * cosTz - y1 * sinTz
            val y2 = cx * sinTz + y1 * cosTz

            xs[i] = x2
            ys[i] = y2
            zs[i] = z1
        }

        // Locate sign transitions along the closed ring so each front/back arc is continuous
        var firstCrossIdx = -1
        for (i in 0 until ORBIT_SEGMENTS) {
            val next = (i + 1) % ORBIT_SEGMENTS
            if ((zs[i] >= 0f) != (zs[next] >= 0f)) {
                firstCrossIdx = i
                break
            }
        }

        if (firstCrossIdx == -1) {
            val isFront = zs[0] >= 0f
            val coords = FloatArray((ORBIT_SEGMENTS + 1) * 2)
            var zSum = 0f
            for (i in 0..ORBIT_SEGMENTS) {
                val idx = i % ORBIT_SEGMENTS
                coords[i * 2] = xs[idx]
                coords[i * 2 + 1] = ys[idx]
                if (i < ORBIT_SEGMENTS) zSum += zs[idx]
            }
            val avgDepth = zSum / ORBIT_SEGMENTS
            arcs.add(
                BloubOrbitArc(
                    ringIndex = ringIdx,
                    isFront = isFront,
                    pointsXY = coords,
                    avgDepth = avgDepth,
                    alpha = (baseAlpha * if (isFront) 1f else 0.42f).coerceIn(0f, 1f),
                    strokeScale = strokeScale,
                ),
            )
            continue
        }

        // Walk starting immediately after firstCrossIdx and split into contiguous front/back runs
        val frontPts = ArrayList<Float>((ORBIT_SEGMENTS + 4) * 2)
        val backPts = ArrayList<Float>((ORBIT_SEGMENTS + 4) * 2)
        var frontZSum = 0f
        var frontZCount = 0
        var backZSum = 0f
        var backZCount = 0

        for (step in 0 until ORBIT_SEGMENTS) {
            val curr = (firstCrossIdx + 1 + step) % ORBIT_SEGMENTS
            val next = (curr + 1) % ORBIT_SEGMENTS
            val currFront = zs[curr] >= 0f
            val nextFront = zs[next] >= 0f

            if (step == 0) {
                val prev = (curr - 1 + ORBIT_SEGMENTS) % ORBIT_SEGMENTS
                val zPrev = zs[prev]
                val zCurr = zs[curr]
                val frac = (abs(zPrev) / abs(zCurr - zPrev).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
                val crossX = xs[prev] + (xs[curr] - xs[prev]) * frac
                val crossY = ys[prev] + (ys[curr] - ys[prev]) * frac
                val target = if (currFront) frontPts else backPts
                target.add(crossX)
                target.add(crossY)
            }

            if (currFront) {
                frontPts.add(xs[curr])
                frontPts.add(ys[curr])
                frontZSum += zs[curr]
                frontZCount++
            } else {
                backPts.add(xs[curr])
                backPts.add(ys[curr])
                backZSum += zs[curr]
                backZCount++
            }

            if (currFront != nextFront) {
                val zCurr = zs[curr]
                val zNext = zs[next]
                val frac = (abs(zCurr) / abs(zNext - zCurr).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
                val crossX = xs[curr] + (xs[next] - xs[curr]) * frac
                val crossY = ys[curr] + (ys[next] - ys[curr]) * frac
                if (currFront) {
                    frontPts.add(crossX)
                    frontPts.add(crossY)
                    if (step < ORBIT_SEGMENTS - 1) {
                        backPts.add(crossX)
                        backPts.add(crossY)
                    }
                } else {
                    backPts.add(crossX)
                    backPts.add(crossY)
                    if (step < ORBIT_SEGMENTS - 1) {
                        frontPts.add(crossX)
                        frontPts.add(crossY)
                    }
                }
            }
        }

        if (backPts.size >= 4) {
            arcs.add(
                BloubOrbitArc(
                    ringIndex = ringIdx,
                    isFront = false,
                    pointsXY = backPts.toFloatArray(),
                    avgDepth = if (backZCount > 0) backZSum / backZCount else -0.5f * radius,
                    alpha = (baseAlpha * 0.42f).coerceIn(0f, 1f),
                    strokeScale = strokeScale * 0.85f,
                ),
            )
        }
        if (frontPts.size >= 4) {
            arcs.add(
                BloubOrbitArc(
                    ringIndex = ringIdx,
                    isFront = true,
                    pointsXY = frontPts.toFloatArray(),
                    avgDepth = if (frontZCount > 0) frontZSum / frontZCount else 0.5f * radius,
                    alpha = baseAlpha.coerceIn(0f, 1f),
                    strokeScale = strokeScale,
                ),
            )
        }
    }

    arcs.sortBy { it.avgDepth }
    return arcs
}

/**
 * Single particle emitted along a spiral vortex path for [BloubMood.SEARCHING] or burst effects.
 */
data class BloubSpiralParticle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val life: Float,
    val index: Int,
)

/**
 * Computes [count] spiral vortex particles around `(0, 0)` scaled by [scale] at time [t].
 * Every particle has `life` in `[0f, 1f)` and `alpha` in `[0f, 1f]`.
 */
fun computeSpiralParticles(
    t: Float,
    scale: Float = 1f,
    count: Int = 10,
): List<BloubSpiralParticle> {
    if (!scale.isFinite() || scale <= 0f || count <= 0) return emptyList()
    val safeT = if (t.isFinite()) t else 0f
    val particles = ArrayList<BloubSpiralParticle>(count)

    for (i in 0 until count) {
        val rawLife = (safeT * 0.62f + i.toFloat() / count) % 1f
        val life = (if (rawLife < 0f) rawLife + 1f else rawLife).coerceIn(0f, 0.9999f)
        val r = (1.38f - 0.94f * life) * scale
        val angle = i * (TWO_PI / count) * 1.5f + life * 4.2f + safeT * 1.35f
        val envelope = sin(life * PI_F).coerceIn(0f, 1f)
        val radius = (0.022f + 0.032f * envelope) * scale
        val alpha = (envelope * 0.85f).coerceIn(0f, 1f)

        particles.add(
            BloubSpiralParticle(
                x = r * cos(angle),
                y = r * sin(angle),
                radius = radius,
                alpha = alpha,
                life = life,
                index = i,
            ),
        )
    }
    return particles
}

private fun shortestAngleDelta(from: Float, to: Float): Float {
    var diff = (to - from) % TWO_PI
    if (diff > PI_F) diff -= TWO_PI
    if (diff < -PI_F) diff += TWO_PI
    return diff
}

private fun wrapAngle(angle: Float): Float {
    var wrapped = angle % TWO_PI
    if (wrapped > PI_F) wrapped -= TWO_PI
    if (wrapped <= -PI_F) wrapped += TWO_PI
    return wrapped
}

private fun sanitizeFinite(value: Float, fallback: Float): Float =
    if (value.isFinite()) value else fallback
