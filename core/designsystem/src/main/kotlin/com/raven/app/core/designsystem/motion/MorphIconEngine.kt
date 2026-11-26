package com.raven.app.core.designsystem.motion

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Immutable path segment inside a canonical `24f x 24f` [MorphStroke].
 */
@Immutable
sealed interface MorphSegment {
    @Immutable
    data class MoveTo(val x: Float, val y: Float) : MorphSegment

    @Immutable
    data class LineTo(val x: Float, val y: Float) : MorphSegment

    @Immutable
    data class QuadTo(
        val cx: Float,
        val cy: Float,
        val x: Float,
        val y: Float,
    ) : MorphSegment

    @Immutable
    data class CubicTo(
        val c1x: Float,
        val c1y: Float,
        val c2x: Float,
        val c2y: Float,
        val x: Float,
        val y: Float,
    ) : MorphSegment
}

/**
 * Fluent builder for constructing a [MorphStroke] in canonical `24f x 24f` viewBox coordinates.
 */
class MorphPathBuilder {
    private val segments = mutableListOf<MorphSegment>()
    private var curX = 0f
    private var curY = 0f
    private var startX = 0f
    private var startY = 0f
    private var hasPoint = false

    fun moveTo(x: Float, y: Float): MorphPathBuilder = apply {
        if (!x.isFinite() || !y.isFinite()) return@apply
        segments.add(MorphSegment.MoveTo(x, y))
        curX = x
        curY = y
        startX = x
        startY = y
        hasPoint = true
    }

    fun lineTo(x: Float, y: Float): MorphPathBuilder = apply {
        if (!x.isFinite() || !y.isFinite()) return@apply
        ensureStart(x, y)
        segments.add(MorphSegment.LineTo(x, y))
        curX = x
        curY = y
    }

    fun quadTo(cx: Float, cy: Float, x: Float, y: Float): MorphPathBuilder = apply {
        if (!cx.isFinite() || !cy.isFinite() || !x.isFinite() || !y.isFinite()) return@apply
        ensureStart(x, y)
        segments.add(MorphSegment.QuadTo(cx, cy, x, y))
        curX = x
        curY = y
    }

    fun cubicTo(
        c1x: Float,
        c1y: Float,
        c2x: Float,
        c2y: Float,
        x: Float,
        y: Float,
    ): MorphPathBuilder = apply {
        if (
            !c1x.isFinite() || !c1y.isFinite() ||
            !c2x.isFinite() || !c2y.isFinite() ||
            !x.isFinite() || !y.isFinite()
        ) {
            return@apply
        }
        ensureStart(x, y)
        segments.add(MorphSegment.CubicTo(c1x, c1y, c2x, c2y, x, y))
        curX = x
        curY = y
    }

    /**
     * Appends an SVG-style elliptical arc from the current point to `(x, y)`,
     * subdivided into cubic Bezier curves.
     */
    fun arcTo(
        rx: Float,
        ry: Float,
        xAxisRotationDeg: Float,
        largeArc: Boolean,
        sweep: Boolean,
        x: Float,
        y: Float,
    ): MorphPathBuilder = apply {
        if (
            !rx.isFinite() || !ry.isFinite() ||
            !xAxisRotationDeg.isFinite() || !x.isFinite() || !y.isFinite()
        ) {
            return@apply
        }
        ensureStart(x, y)
        val x0 = curX.toDouble()
        val y0 = curY.toDouble()
        val x1 = x.toDouble()
        val y1 = y.toDouble()
        if (abs(x0 - x1) < 1e-6 && abs(y0 - y1) < 1e-6) return@apply

        var arx = abs(rx.toDouble())
        var ary = abs(ry.toDouble())
        if (arx < 1e-6 || ary < 1e-6) {
            return lineTo(x, y)
        }

        val phi = Math.toRadians(xAxisRotationDeg.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx2 = (x0 - x1) * 0.5
        val dy2 = (y0 - y1) * 0.5
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        val rxSq = arx * arx
        val rySq = ary * ary
        val x1pSq = x1p * x1p
        val y1pSq = y1p * y1p

        val lambda = x1pSq / rxSq + y1pSq / rySq
        if (lambda > 1.0) {
            val scale = sqrt(lambda)
            arx *= scale
            ary *= scale
        }

        val rx2 = arx * arx
        val ry2 = ary * ary
        val num = max(0.0, rx2 * ry2 - rx2 * y1pSq - ry2 * x1pSq)
        val den = rx2 * y1pSq + ry2 * x1pSq
        val factor = if (den < 1e-12) 0.0 else sqrt(num / den) * if (largeArc == sweep) -1.0 else 1.0

        val cxp = factor * (arx * y1p / ary)
        val cyp = factor * (-ary * x1p / arx)

        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) * 0.5
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) * 0.5

        fun vecAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            if (len < 1e-12) return 0.0
            val ang = acos((dot / len).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0.0) -ang else ang
        }

        val ux = (x1p - cxp) / arx
        val uy = (y1p - cyp) / ary
        val vx = (-x1p - cxp) / arx
        val vy = (-y1p - cyp) / ary

        val theta1 = vecAngle(1.0, 0.0, ux, uy)
        var dTheta = vecAngle(ux, uy, vx, vy)
        if (!sweep && dTheta > 0.0) dTheta -= 2.0 * PI
        if (sweep && dTheta < 0.0) dTheta += 2.0 * PI

        val segCount = max(1, kotlin.math.ceil(abs(dTheta) / (PI * 0.5)).toInt())
        val delta = dTheta / segCount
        val alpha = (4.0 / 3.0) * tan(delta * 0.25)

        var angle = theta1
        for (i in 0 until segCount) {
            val nextAngle = angle + delta
            val cos1 = cos(angle)
            val sin1 = sin(angle)
            val cos2 = cos(nextAngle)
            val sin2 = sin(nextAngle)

            val p1x = cos1 - alpha * sin1
            val p1y = sin1 + alpha * cos1
            val p2x = cos2 + alpha * sin2
            val p2y = sin2 - alpha * cos2

            val c1x = cx + cosPhi * (arx * p1x) - sinPhi * (ary * p1y)
            val c1y = cy + sinPhi * (arx * p1x) + cosPhi * (ary * p1y)
            val c2x = cx + cosPhi * (arx * p2x) - sinPhi * (ary * p2y)
            val c2y = cy + sinPhi * (arx * p2x) + cosPhi * (ary * p2y)
            val ex = if (i == segCount - 1) x1 else cx + cosPhi * (arx * cos2) - sinPhi * (ary * sin2)
            val ey = if (i == segCount - 1) y1 else cy + sinPhi * (arx * cos2) + cosPhi * (ary * sin2)

            cubicTo(
                c1x.toFloat(),
                c1y.toFloat(),
                c2x.toFloat(),
                c2y.toFloat(),
                ex.toFloat(),
                ey.toFloat(),
            )
            angle = nextAngle
        }
    }

    /**
     * Appends a circular arc centered at `(cx, cy)` with [radius], starting at [startAngleDeg]
     * (0° = +X, 90° = +Y) and sweeping [sweepAngleDeg].
     */
    fun arcCenter(
        cx: Float,
        cy: Float,
        radius: Float,
        startAngleDeg: Float,
        sweepAngleDeg: Float,
        moveToStart: Boolean = !hasPoint,
    ): MorphPathBuilder = apply {
        if (
            !cx.isFinite() || !cy.isFinite() || !radius.isFinite() ||
            !startAngleDeg.isFinite() || !sweepAngleDeg.isFinite() || radius <= 0f
        ) {
            return@apply
        }
        val startRad = Math.toRadians(startAngleDeg.toDouble())
        val sweepRad = Math.toRadians(sweepAngleDeg.toDouble())
        val sx = (cx + radius * cos(startRad)).toFloat()
        val sy = (cy + radius * sin(startRad)).toFloat()
        if (moveToStart) {
            moveTo(sx, sy)
        } else {
            lineTo(sx, sy)
        }

        val segCount = max(1, kotlin.math.ceil(abs(sweepRad) / (PI * 0.5)).toInt())
        val delta = sweepRad / segCount
        val alpha = (4.0 / 3.0) * tan(delta * 0.25)
        var angle = startRad
        for (i in 0 until segCount) {
            val next = angle + delta
            val cos1 = cos(angle)
            val sin1 = sin(angle)
            val cos2 = cos(next)
            val sin2 = sin(next)

            val c1x = cx + radius * (cos1 - alpha * sin1)
            val c1y = cy + radius * (sin1 + alpha * cos1)
            val c2x = cx + radius * (cos2 + alpha * sin2)
            val c2y = cy + radius * (sin2 - alpha * cos2)
            val ex = cx + radius * cos2
            val ey = cy + radius * sin2

            cubicTo(
                c1x.toFloat(),
                c1y.toFloat(),
                c2x.toFloat(),
                c2y.toFloat(),
                ex.toFloat(),
                ey.toFloat(),
            )
            angle = next
        }
    }

    /**
     * Appends a full circle centered at `(cx, cy)` with radius [r].
     */
    fun circle(cx: Float, cy: Float, r: Float): MorphPathBuilder = apply {
        if (!cx.isFinite() || !cy.isFinite() || !r.isFinite() || r <= 0f) return@apply
        val k = 0.5522847498f * r
        moveTo(cx + r, cy)
        cubicTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        cubicTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        cubicTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        cubicTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    }

    /**
     * Appends an axis-aligned rounded rectangle `[left, top, right, bottom]`.
     */
    fun roundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rx: Float,
        ry: Float = rx,
    ): MorphPathBuilder = apply {
        if (
            !left.isFinite() || !top.isFinite() || !right.isFinite() ||
            !bottom.isFinite() || !rx.isFinite() || !ry.isFinite()
        ) {
            return@apply
        }
        val l = min(left, right)
        val r = max(left, right)
        val t = min(top, bottom)
        val b = max(top, bottom)
        val crx = rx.coerceIn(0f, (r - l) * 0.5f)
        val cry = ry.coerceIn(0f, (b - t) * 0.5f)
        if (crx <= 1e-4f || cry <= 1e-4f) {
            moveTo(l, t)
            lineTo(r, t)
            lineTo(r, b)
            lineTo(l, b)
            lineTo(l, t)
            return@apply
        }
        val kx = 0.5522847498f * crx
        val ky = 0.5522847498f * cry
        moveTo(l + crx, t)
        lineTo(r - crx, t)
        cubicTo(r - crx + kx, t, r, t + cry - ky, r, t + cry)
        lineTo(r, b - cry)
        cubicTo(r, b - cry + ky, r - crx + kx, b, r - crx, b)
        lineTo(l + crx, b)
        cubicTo(l + crx - kx, b, l, b - cry + ky, l, b - cry)
        lineTo(l, t + cry)
        cubicTo(l, t + cry - ky, l + crx - kx, t, l + crx, t)
    }

    fun close(): MorphPathBuilder = apply {
        if (hasPoint && (abs(curX - startX) > 1e-4f || abs(curY - startY) > 1e-4f)) {
            lineTo(startX, startY)
        }
    }

    internal fun buildSegments(): List<MorphSegment> = segments.toList()

    private fun ensureStart(x: Float, y: Float) {
        if (!hasPoint) {
            moveTo(x, y)
        }
    }
}

/**
 * Single subpath stroke in canonical `24f x 24f` viewBox coordinates.
 */
@Immutable
data class MorphStroke(
    val segments: List<MorphSegment>,
    val closed: Boolean,
    val fill: Boolean = false,
) {
    companion object {
        fun build(
            closed: Boolean = false,
            fill: Boolean = false,
            block: MorphPathBuilder.() -> Unit,
        ): MorphStroke {
            val builder = MorphPathBuilder().apply(block)
            return MorphStroke(
                segments = builder.buildSegments(),
                closed = closed,
                fill = fill,
            )
        }

        fun line(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            fill: Boolean = false,
        ): MorphStroke = build(closed = false, fill = fill) {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }

        fun polyline(
            vararg points: Pair<Float, Float>,
            closed: Boolean = false,
            fill: Boolean = false,
        ): MorphStroke = build(closed = closed, fill = fill) {
            if (points.isNotEmpty()) {
                moveTo(points[0].first, points[0].second)
                for (i in 1 until points.size) {
                    lineTo(points[i].first, points[i].second)
                }
            }
        }

        fun circle(
            cx: Float,
            cy: Float,
            r: Float,
            fill: Boolean = false,
        ): MorphStroke = build(closed = true, fill = fill) {
            circle(cx, cy, r)
        }

        fun roundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            rx: Float,
            ry: Float = rx,
            fill: Boolean = false,
        ): MorphStroke = build(closed = true, fill = fill) {
            roundRect(left, top, right, bottom, rx, ry)
        }

        fun arc(
            cx: Float,
            cy: Float,
            r: Float,
            startAngleDeg: Float,
            sweepAngleDeg: Float,
            closed: Boolean = false,
            fill: Boolean = false,
        ): MorphStroke = build(closed = closed, fill = fill) {
            arcCenter(cx, cy, r, startAngleDeg, sweepAngleDeg, moveToStart = true)
        }
    }
}

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

/**
 * Zero-dependency stroke-icon morphing engine modeled after `guillermolg00/morphicons`:
 * arc-length resampling with corner anchoring, optimal subpath pairing, 2D Procrustes similarity
 * alignment, and zero-allocation polar interpolation.
 */
object MorphIconEngine {
    const val DEFAULT_SAMPLES: Int = 48
    const val CORNER_ANGLE_THRESHOLD_RAD: Float = (PI / 8.0).toFloat()
    const val MIN_SCALE: Float = 1e-4f
    private const val MAX_SCALE: Float = 1e4f
    private const val VIEWBOX_CENTER: Float = 12f
    private const val CURVE_STEPS: Int = 24
    private const val EPSILON: Float = 1e-6f

    private data class FlattenedPiece(
        val xs: FloatArray,
        val ys: FloatArray,
        val cumLen: FloatArray,
        val totalLen: Float,
        val startDx: Float,
        val startDy: Float,
        val endDx: Float,
        val endDy: Float,
    )

    private data class Span(
        val startS: Float,
        val endS: Float,
        val length: Float,
    )

    /**
     * Resamples a single [MorphStroke] to [samples] points using arc-length parameterization,
     * anchoring vertices whose tangent discontinuity exceeds `PI / 8`.
     */
    fun resampleStroke(
        stroke: MorphStroke,
        samples: Int = DEFAULT_SAMPLES,
    ): SampledStroke {
        val count = samples.coerceAtLeast(2)
        val pieces = flattenPieces(stroke)
        if (pieces.isEmpty()) {
            return degenerateSampledStroke(VIEWBOX_CENTER, VIEWBOX_CENTER, count, stroke.closed, stroke.fill)
        }

        val totalLen = pieces.sumOf { it.totalLen.toDouble() }.toFloat()
        if (!totalLen.isFinite() || totalLen <= EPSILON) {
            val fx = pieces[0].xs[0]
            val fy = pieces[0].ys[0]
            return degenerateSampledStroke(fx, fy, count, stroke.closed, stroke.fill)
        }

        // Cumulative arc length at start of each piece + total end.
        val pieceOffsets = FloatArray(pieces.size + 1)
        var acc = 0f
        for (i in pieces.indices) {
            pieceOffsets[i] = acc
            acc += pieces[i].totalLen
        }
        pieceOffsets[pieces.size] = acc

        // Detect corners where tangent discontinuity > PI / 8.
        val rawAnchors = mutableListOf<Float>()
        val rawAnchorAngles = mutableListOf<Float>()
        rawAnchors.add(0f)
        rawAnchorAngles.add(Float.POSITIVE_INFINITY) // start is always anchored

        for (i in 0 until pieces.size - 1) {
            val prev = pieces[i]
            val next = pieces[i + 1]
            val ang = tangentDiscontinuity(prev.endDx, prev.endDy, next.startDx, next.startDy)
            if (ang > CORNER_ANGLE_THRESHOLD_RAD) {
                rawAnchors.add(pieceOffsets[i + 1])
                rawAnchorAngles.add(ang)
            }
        }
        if (!stroke.closed) {
            rawAnchors.add(acc)
            rawAnchorAngles.add(Float.POSITIVE_INFINITY)
        }

        // Deduplicate nearly-coincident anchors.
        val anchors = mutableListOf<Float>()
        val anchorAngles = mutableListOf<Float>()
        for (i in rawAnchors.indices) {
            val s = rawAnchors[i]
            val ang = rawAnchorAngles[i]
            if (anchors.isEmpty() || abs(s - anchors.last()) > 1e-4f) {
                if (!stroke.closed || abs(s - acc) > 1e-4f || anchors.isEmpty()) {
                    anchors.add(s)
                    anchorAngles.add(ang)
                }
            } else if (ang > anchorAngles.last()) {
                anchorAngles[anchorAngles.lastIndex] = ang
            }
        }

        // Cap anchors so every span can receive at least 1 interval.
        val maxAnchors = if (stroke.closed) count else count
        if (anchors.size > maxAnchors) {
            trimAnchors(anchors, anchorAngles, maxAnchors, stroke.closed)
        }

        val spans = buildList {
            if (stroke.closed) {
                for (i in anchors.indices) {
                    val s0 = anchors[i]
                    val s1 = if (i == anchors.lastIndex) acc + anchors[0] else anchors[i + 1]
                    add(Span(s0, s1, max(0f, s1 - s0)))
                }
            } else {
                for (i in 0 until anchors.size - 1) {
                    val s0 = anchors[i]
                    val s1 = anchors[i + 1]
                    add(Span(s0, s1, max(0f, s1 - s0)))
                }
            }
        }

        val totalIntervals = if (stroke.closed) count else count - 1
        val intervalsPerSpan = apportionIntervals(spans, totalIntervals)

        val pts = FloatArray(count * 2)
        var outIdx = 0
        for (sIdx in spans.indices) {
            val span = spans[sIdx]
            val k = intervalsPerSpan[sIdx]
            for (step in 0 until k) {
                if (outIdx >= count) break
                val frac = step.toFloat() / k.toFloat()
                val arcS = (span.startS + span.length * frac) % acc
                evaluateAtArcLength(pieces, pieceOffsets, arcS, pts, outIdx * 2)
                outIdx++
            }
        }
        if (!stroke.closed && outIdx < count) {
            val lastPiece = pieces.last()
            pts[(count - 1) * 2] = lastPiece.xs.last()
            pts[(count - 1) * 2 + 1] = lastPiece.ys.last()
        } else {
            while (outIdx < count) {
                val lastIdx = max(0, outIdx - 1) * 2
                pts[outIdx * 2] = pts[lastIdx]
                pts[outIdx * 2 + 1] = pts[lastIdx + 1]
                outIdx++
            }
        }

        return SampledStroke(pts = pts, closed = stroke.closed, fill = stroke.fill)
    }

    /**
     * Resamples every stroke in [glyph] to [samples] points each.
     */
    fun resampleGlyph(
        glyph: RavenMorphGlyph,
        samples: Int = DEFAULT_SAMPLES,
    ): List<SampledStroke> = glyph.strokes.map { resampleStroke(it, samples) }

    /**
     * Pairs source and target sampled strokes:
     * - For `p == q`: minimum-cost optimal bijection via the Hungarian algorithm.
     * - For `p != q`: minimum-cost surjection where every stroke on both sides is covered
     *   (supporting smooth `1 -> 2` Play/Pause and `4 -> 3` Grid/List transitions).
     */
    fun pairSubpaths(
        from: List<SampledStroke>,
        to: List<SampledStroke>,
        samples: Int = DEFAULT_SAMPLES,
    ): List<Pair<SampledStroke, SampledStroke>> {
        if (from.isEmpty() && to.isEmpty()) return emptyList()
        val count = samples.coerceAtLeast(2)
        if (from.isEmpty()) {
            return to.map { target ->
                val (cx, cy) = computeCentroid(target.pts)
                degenerateSampledStroke(cx, cy, count, target.closed, target.fill) to target
            }
        }
        if (to.isEmpty()) {
            return from.map { source ->
                val (cx, cy) = computeCentroid(source.pts)
                source to degenerateSampledStroke(cx, cy, count, source.closed, source.fill)
            }
        }

        val p = from.size
        val q = to.size
        val cost = Array(p) { i ->
            FloatArray(q) { j -> pairMatchCost(from[i], to[j]) }
        }

        if (p == q) {
            val assignment = hungarianSquare(cost)
            return List(p) { i -> from[i] to to[assignment[i]] }
        }

        if (p < q) {
            val assignment = hungarianRectangularRows(cost)
            val usedCols = BooleanArray(q)
            val result = ArrayList<Pair<SampledStroke, SampledStroke>>(q)
            for (i in 0 until p) {
                val j = assignment[i]
                usedCols[j] = true
                result.add(from[i] to to[j])
            }
            for (j in 0 until q) {
                if (!usedCols[j]) {
                    var bestI = 0
                    var bestCost = cost[0][j]
                    for (i in 1 until p) {
                        if (cost[i][j] < bestCost) {
                            bestCost = cost[i][j]
                            bestI = i
                        }
                    }
                    result.add(from[bestI] to to[j])
                }
            }
            return result
        }

        // p > q: transpose cost so every target j in 0 until q is matched once, then attach remaining sources.
        val transposed = Array(q) { j -> FloatArray(p) { i -> cost[i][j] } }
        val colAssignment = hungarianRectangularRows(transposed)
        val usedRows = BooleanArray(p)
        val result = ArrayList<Pair<SampledStroke, SampledStroke>>(p)
        for (j in 0 until q) {
            val i = colAssignment[j]
            usedRows[i] = true
            result.add(from[i] to to[j])
        }
        for (i in 0 until p) {
            if (!usedRows[i]) {
                var bestJ = 0
                var bestCost = cost[i][0]
                for (j in 1 until q) {
                    if (cost[i][j] < bestCost) {
                        bestCost = cost[i][j]
                        bestJ = j
                    }
                }
                result.add(from[i] to to[bestJ])
            }
        }
        return result
    }

    /**
     * Computes optimal 2D Procrustes similarity alignment `(ca, cb, theta, sigma)` between
     * [a] and [b], searching cyclic start indices (and reverse winding) for closed paths
     * and forward/reverse orderings for open paths.
     */
    fun alignPair(
        a: SampledStroke,
        b: SampledStroke,
    ): AlignedMorphPair {
        val n = min(a.sampleCount, b.sampleCount).coerceAtLeast(1)
        val aPts = normalizeSampleCount(a, n)
        val bPts = normalizeSampleCount(b, n)

        val (caX, caY) = computeCentroid(aPts)
        val (cbX, cbY) = computeCentroid(bPts)

        val aC = FloatArray(n * 2)
        val bC = FloatArray(n * 2)
        var normASq = 0.0
        var normBSq = 0.0
        for (i in 0 until n) {
            val ix = i * 2
            val ax = aPts[ix] - caX
            val ay = aPts[ix + 1] - caY
            val bx = bPts[ix] - cbX
            val by = bPts[ix + 1] - cbY
            aC[ix] = ax
            aC[ix + 1] = ay
            bC[ix] = bx
            bC[ix + 1] = by
            normASq += ax * ax + ay * ay
            normBSq += bx * bx + by * by
        }

        if (normASq < 1e-8 || normBSq < 1e-8) {
            return AlignedMorphPair(
                sampleCount = n,
                caX = caX,
                caY = caY,
                cbX = cbX,
                cbY = cbY,
                theta = 0f,
                lnSigma = 0f,
                aC = aC,
                bT = bC,
                fromClosed = a.closed,
                toClosed = b.closed,
                fromFill = a.fill,
                toFill = b.fill,
            )
        }

        val sigma = sqrt(normBSq / normASq).toFloat().coerceIn(MIN_SCALE, MAX_SCALE)
        val lnSigma = ln(sigma)

        var bestScore = -1.0
        var bestTheta = 0f
        var bestShift = 0
        var bestReversed = false

        val closedSearch = a.closed && b.closed
        val shiftLimit = if (closedSearch) n else 1

        for (rev in 0..1) {
            val reversed = rev == 1
            for (shift in 0 until shiftLimit) {
                var dotSum = 0.0
                var crossSum = 0.0
                for (i in 0 until n) {
                    val bIdx = if (reversed) {
                        ((n - 1 - i + shift) % n) * 2
                    } else {
                        ((i + shift) % n) * 2
                    }
                    val ax = aC[i * 2].toDouble()
                    val ay = aC[i * 2 + 1].toDouble()
                    val bx = bC[bIdx].toDouble()
                    val by = bC[bIdx + 1].toDouble()
                    dotSum += ax * bx + ay * by
                    crossSum += ax * by - ay * bx
                }
                val score = hypot(dotSum, crossSum)
                val theta = atan2(crossSum, dotSum).toFloat()
                val tieEps = 1e-5 * max(1.0, bestScore)
                val better = score > bestScore + tieEps ||
                    (abs(score - bestScore) <= tieEps && abs(theta) < abs(bestTheta) - 1e-4f)
                if (better) {
                    bestScore = score
                    bestTheta = theta
                    bestShift = shift
                    bestReversed = reversed
                }
            }
        }

        val invSigma = 1f / sigma
        val cosNeg = cos(-bestTheta)
        val sinNeg = sin(-bestTheta)
        val bT = FloatArray(n * 2)
        for (i in 0 until n) {
            val bIdx = if (bestReversed) {
                ((n - 1 - i + bestShift) % n) * 2
            } else {
                ((i + bestShift) % n) * 2
            }
            val bx = bC[bIdx]
            val by = bC[bIdx + 1]
            bT[i * 2] = invSigma * (cosNeg * bx - sinNeg * by)
            bT[i * 2 + 1] = invSigma * (sinNeg * bx + cosNeg * by)
        }

        return AlignedMorphPair(
            sampleCount = n,
            caX = caX,
            caY = caY,
            cbX = cbX,
            cbY = cbY,
            theta = bestTheta,
            lnSigma = lnSigma,
            aC = aC,
            bT = bT,
            fromClosed = a.closed,
            toClosed = b.closed,
            fromFill = a.fill,
            toFill = b.fill,
        )
    }

    /**
     * Builds a compiled [MorphPlan] between [from] and [to] glyphs.
     */
    fun buildPlan(
        from: RavenMorphGlyph,
        to: RavenMorphGlyph,
        samples: Int = DEFAULT_SAMPLES,
    ): MorphPlan = buildPlan(
        from = resampleGlyph(from, samples),
        to = resampleGlyph(to, samples),
        samples = samples,
    )

    /**
     * Builds a compiled [MorphPlan] from already-sampled strokes (used during mid-flight interruption).
     */
    fun buildPlan(
        from: List<SampledStroke>,
        to: List<SampledStroke>,
        samples: Int = DEFAULT_SAMPLES,
    ): MorphPlan {
        val paired = pairSubpaths(from, to, samples)
        return MorphPlan(pairs = paired.map { (src, dst) -> alignPair(src, dst) })
    }

    /**
     * Zero-allocation polar interpolation:
     * `P(t) = c(t) + exp(lnSigma * t) * R(theta * t) * ((1 - t) * aC + t * bT)`
     * written directly into [outPts].
     */
    fun interpPolar(
        pair: AlignedMorphPair,
        t: Float,
        outPts: FloatArray,
    ) {
        val safeT = if (t.isFinite()) t else 1f
        val cx = pair.caX + (pair.cbX - pair.caX) * safeT
        val cy = pair.caY + (pair.cbY - pair.caY) * safeT
        val scale = exp(pair.lnSigma * safeT)
        val angle = pair.theta * safeT
        val cosA = cos(angle)
        val sinA = sin(angle)
        val oneMinusT = 1f - safeT

        val aC = pair.aC
        val bT = pair.bT
        val limit = min(min(pair.sampleCount * 2, outPts.size), min(aC.size, bT.size))
        var i = 0
        while (i + 1 < limit) {
            val lx = oneMinusT * aC[i] + safeT * bT[i]
            val ly = oneMinusT * aC[i + 1] + safeT * bT[i + 1]
            outPts[i] = cx + scale * (cosA * lx - sinA * ly)
            outPts[i + 1] = cy + scale * (sinA * lx + cosA * ly)
            i += 2
        }
    }

    /**
     * Interpolates every pair in [plan] into preallocated [outBuffers].
     */
    fun interpolatePlan(
        plan: MorphPlan,
        t: Float,
        outBuffers: List<FloatArray>,
    ) {
        val count = min(plan.pairs.size, outBuffers.size)
        for (i in 0 until count) {
            interpPolar(plan.pairs[i], t, outBuffers[i])
        }
    }

    /**
     * Snapshots current in-flight [outBuffers] into a [List] of [SampledStroke]s for seamless
     * mid-flight interruption without visual popping.
     */
    fun snapshotBuffers(
        plan: MorphPlan,
        outBuffers: List<FloatArray>,
        t: Float,
    ): List<SampledStroke> {
        val safeT = if (t.isFinite()) t else 1f
        val count = min(plan.pairs.size, outBuffers.size)
        return List(count) { i ->
            val pair = plan.pairs[i]
            val closed = if (safeT < 0.5f) pair.fromClosed else pair.toClosed
            val fill = if (safeT < 0.5f) pair.fromFill else pair.toFill
            SampledStroke(
                pts = outBuffers[i].copyOf(),
                closed = closed,
                fill = fill,
            )
        }
    }

    private fun flattenPieces(stroke: MorphStroke): List<FlattenedPiece> {
        val pieces = mutableListOf<FlattenedPiece>()
        var curX = 0f
        var curY = 0f
        var startX = 0f
        var startY = 0f
        var hasStart = false

        for (seg in stroke.segments) {
            when (seg) {
                is MorphSegment.MoveTo -> {
                    if (seg.x.isFinite() && seg.y.isFinite()) {
                        curX = seg.x
                        curY = seg.y
                        if (!hasStart) {
                            startX = curX
                            startY = curY
                            hasStart = true
                        }
                    }
                }
                is MorphSegment.LineTo -> {
                    if (seg.x.isFinite() && seg.y.isFinite()) {
                        if (!hasStart) {
                            startX = curX
                            startY = curY
                            hasStart = true
                        }
                        val piece = buildLinePiece(curX, curY, seg.x, seg.y)
                        if (piece.totalLen > EPSILON) {
                            pieces.add(piece)
                        }
                        curX = seg.x
                        curY = seg.y
                    }
                }
                is MorphSegment.QuadTo -> {
                    if (seg.cx.isFinite() && seg.cy.isFinite() && seg.x.isFinite() && seg.y.isFinite()) {
                        if (!hasStart) {
                            startX = curX
                            startY = curY
                            hasStart = true
                        }
                        val piece = buildQuadPiece(curX, curY, seg.cx, seg.cy, seg.x, seg.y)
                        if (piece.totalLen > EPSILON) {
                            pieces.add(piece)
                        }
                        curX = seg.x
                        curY = seg.y
                    }
                }
                is MorphSegment.CubicTo -> {
                    if (
                        seg.c1x.isFinite() && seg.c1y.isFinite() &&
                        seg.c2x.isFinite() && seg.c2y.isFinite() &&
                        seg.x.isFinite() && seg.y.isFinite()
                    ) {
                        if (!hasStart) {
                            startX = curX
                            startY = curY
                            hasStart = true
                        }
                        val piece = buildCubicPiece(
                            curX,
                            curY,
                            seg.c1x,
                            seg.c1y,
                            seg.c2x,
                            seg.c2y,
                            seg.x,
                            seg.y,
                        )
                        if (piece.totalLen > EPSILON) {
                            pieces.add(piece)
                        }
                        curX = seg.x
                        curY = seg.y
                    }
                }
            }
        }

        if (stroke.closed && hasStart) {
            val closeDist = hypot(startX - curX, startY - curY)
            if (closeDist > 1e-4f) {
                pieces.add(buildLinePiece(curX, curY, startX, startY))
            }
        }

        if (pieces.isEmpty() && hasStart) {
            pieces.add(buildLinePiece(startX, startY, startX, startY))
        }
        return pieces
    }

    private fun buildLinePiece(x0: Float, y0: Float, x1: Float, y1: Float): FlattenedPiece {
        val len = hypot(x1 - x0, y1 - y0)
        val dx = if (len > EPSILON) (x1 - x0) / len else 1f
        val dy = if (len > EPSILON) (y1 - y0) / len else 0f
        return FlattenedPiece(
            xs = floatArrayOf(x0, x1),
            ys = floatArrayOf(y0, y1),
            cumLen = floatArrayOf(0f, len),
            totalLen = len,
            startDx = dx,
            startDy = dy,
            endDx = dx,
            endDy = dy,
        )
    }

    private fun buildQuadPiece(
        x0: Float,
        y0: Float,
        cx: Float,
        cy: Float,
        x1: Float,
        y1: Float,
    ): FlattenedPiece {
        val xs = FloatArray(CURVE_STEPS + 1)
        val ys = FloatArray(CURVE_STEPS + 1)
        for (i in 0..CURVE_STEPS) {
            val t = i.toFloat() / CURVE_STEPS
            val u = 1f - t
            xs[i] = u * u * x0 + 2f * u * t * cx + t * t * x1
            ys[i] = u * u * y0 + 2f * u * t * cy + t * t * y1
        }
        return finalizeCurvePiece(xs, ys)
    }

    private fun buildCubicPiece(
        x0: Float,
        y0: Float,
        c1x: Float,
        c1y: Float,
        c2x: Float,
        c2y: Float,
        x1: Float,
        y1: Float,
    ): FlattenedPiece {
        val xs = FloatArray(CURVE_STEPS + 1)
        val ys = FloatArray(CURVE_STEPS + 1)
        for (i in 0..CURVE_STEPS) {
            val t = i.toFloat() / CURVE_STEPS
            val u = 1f - t
            val u2 = u * u
            val t2 = t * t
            xs[i] = u2 * u * x0 + 3f * u2 * t * c1x + 3f * u * t2 * c2x + t2 * t * x1
            ys[i] = u2 * u * y0 + 3f * u2 * t * c1y + 3f * u * t2 * c2y + t2 * t * y1
        }
        return finalizeCurvePiece(xs, ys)
    }

    private fun finalizeCurvePiece(xs: FloatArray, ys: FloatArray): FlattenedPiece {
        val cumLen = FloatArray(xs.size)
        var acc = 0f
        for (i in 1 until xs.size) {
            acc += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            cumLen[i] = acc
        }
        var sDx = 1f
        var sDy = 0f
        for (i in 1 until xs.size) {
            val d = hypot(xs[i] - xs[0], ys[i] - ys[0])
            if (d > EPSILON) {
                sDx = (xs[i] - xs[0]) / d
                sDy = (ys[i] - ys[0]) / d
                break
            }
        }
        var eDx = sDx
        var eDy = sDy
        val last = xs.lastIndex
        for (i in last - 1 downTo 0) {
            val d = hypot(xs[last] - xs[i], ys[last] - ys[i])
            if (d > EPSILON) {
                eDx = (xs[last] - xs[i]) / d
                eDy = (ys[last] - ys[i]) / d
                break
            }
        }
        return FlattenedPiece(
            xs = xs,
            ys = ys,
            cumLen = cumLen,
            totalLen = acc,
            startDx = sDx,
            startDy = sDy,
            endDx = eDx,
            endDy = eDy,
        )
    }

    private fun tangentDiscontinuity(dx1: Float, dy1: Float, dx2: Float, dy2: Float): Float {
        val dot = (dx1 * dx2 + dy1 * dy2).coerceIn(-1f, 1f)
        return acos(dot)
    }

    private fun trimAnchors(
        anchors: MutableList<Float>,
        angles: MutableList<Float>,
        maxCount: Int,
        closed: Boolean,
    ) {
        val minKeepIdx = 1
        val maxKeepIdx = if (closed) anchors.lastIndex else anchors.lastIndex - 1
        while (anchors.size > maxCount && maxKeepIdx >= minKeepIdx) {
            var weakestIdx = -1
            var weakestAngle = Float.POSITIVE_INFINITY
            val upper = if (closed) anchors.lastIndex else anchors.lastIndex - 1
            for (i in 1..upper) {
                if (angles[i] < weakestAngle) {
                    weakestAngle = angles[i]
                    weakestIdx = i
                }
            }
            if (weakestIdx < 0) break
            anchors.removeAt(weakestIdx)
            angles.removeAt(weakestIdx)
        }
    }

    private fun apportionIntervals(spans: List<Span>, totalIntervals: Int): IntArray {
        val n = spans.size
        val counts = IntArray(n) { 1 }
        var remaining = totalIntervals - n
        if (remaining <= 0) return counts

        val totalLen = spans.sumOf { it.length.toDouble() }.toFloat()
        if (totalLen <= EPSILON) {
            var idx = 0
            while (remaining > 0) {
                counts[idx % n]++
                remaining--
                idx++
            }
            return counts
        }

        val remainders = FloatArray(n)
        for (i in 0 until n) {
            val exact = remaining.toFloat() * (spans[i].length / totalLen)
            val floor = exact.toInt()
            counts[i] += floor
            remainders[i] = exact - floor
        }
        var allocated = counts.sum()
        while (allocated < totalIntervals) {
            var bestIdx = 0
            for (i in 1 until n) {
                if (remainders[i] > remainders[bestIdx]) {
                    bestIdx = i
                }
            }
            counts[bestIdx]++
            remainders[bestIdx] = -1f
            allocated++
        }
        return counts
    }

    private fun evaluateAtArcLength(
        pieces: List<FlattenedPiece>,
        pieceOffsets: FloatArray,
        arcS: Float,
        outPts: FloatArray,
        outOffset: Int,
    ) {
        val total = pieceOffsets.last()
        val s = arcS.coerceIn(0f, total)
        var pIdx = 0
        while (pIdx < pieces.size - 1 && s > pieceOffsets[pIdx + 1]) {
            pIdx++
        }
        val piece = pieces[pIdx]
        val localS = (s - pieceOffsets[pIdx]).coerceIn(0f, piece.totalLen)
        val cum = piece.cumLen
        var lo = 0
        var hi = cum.lastIndex
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= localS) lo = mid else hi = mid
        }
        val segLen = cum[hi] - cum[lo]
        val t = if (segLen > EPSILON) ((localS - cum[lo]) / segLen).coerceIn(0f, 1f) else 0f
        outPts[outOffset] = piece.xs[lo] + (piece.xs[hi] - piece.xs[lo]) * t
        outPts[outOffset + 1] = piece.ys[lo] + (piece.ys[hi] - piece.ys[lo]) * t
    }

    private fun degenerateSampledStroke(
        x: Float,
        y: Float,
        samples: Int,
        closed: Boolean,
        fill: Boolean,
    ): SampledStroke {
        val sx = if (x.isFinite()) x else VIEWBOX_CENTER
        val sy = if (y.isFinite()) y else VIEWBOX_CENTER
        val pts = FloatArray(samples * 2)
        for (i in 0 until samples) {
            pts[i * 2] = sx
            pts[i * 2 + 1] = sy
        }
        return SampledStroke(pts = pts, closed = closed, fill = fill)
    }

    private fun normalizeSampleCount(stroke: SampledStroke, targetCount: Int): FloatArray {
        if (stroke.sampleCount == targetCount) return stroke.pts
        if (stroke.sampleCount == 0) return FloatArray(targetCount * 2) { VIEWBOX_CENTER }
        val out = FloatArray(targetCount * 2)
        val srcMax = stroke.sampleCount - 1
        for (i in 0 until targetCount) {
            val frac = if (targetCount > 1) i.toFloat() / (targetCount - 1) else 0f
            val srcPos = frac * srcMax
            val idx0 = srcPos.toInt().coerceIn(0, srcMax)
            val idx1 = min(idx0 + 1, srcMax)
            val local = srcPos - idx0
            out[i * 2] = stroke.pts[idx0 * 2] + (stroke.pts[idx1 * 2] - stroke.pts[idx0 * 2]) * local
            out[i * 2 + 1] = stroke.pts[idx0 * 2 + 1] + (stroke.pts[idx1 * 2 + 1] - stroke.pts[idx0 * 2 + 1]) * local
        }
        return out
    }

    private fun computeCentroid(pts: FloatArray): Pair<Float, Float> {
        val count = pts.size / 2
        if (count == 0) return VIEWBOX_CENTER to VIEWBOX_CENTER
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until count) {
            sx += pts[i * 2]
            sy += pts[i * 2 + 1]
        }
        return (sx / count).toFloat() to (sy / count).toFloat()
    }

    private fun computeRmsRadius(pts: FloatArray, cx: Float, cy: Float): Float {
        val count = pts.size / 2
        if (count == 0) return 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val dx = pts[i * 2] - cx
            val dy = pts[i * 2 + 1] - cy
            sumSq += dx * dx + dy * dy
        }
        return sqrt(sumSq / count).toFloat()
    }

    private fun pairMatchCost(a: SampledStroke, b: SampledStroke): Float {
        val (ax, ay) = computeCentroid(a.pts)
        val (bx, by) = computeCentroid(b.pts)
        val ra = computeRmsRadius(a.pts, ax, ay)
        val rb = computeRmsRadius(b.pts, bx, by)
        val dx = ax - bx
        val dy = ay - by
        val dr = ra - rb
        val closedPenalty = if (a.closed != b.closed) 0.25f else 0f
        return dx * dx + dy * dy + 0.5f * dr * dr + closedPenalty
    }

    /**
     * Exact O(n^3) Hungarian assignment for square [cost] matrix.
     */
    private fun hungarianSquare(cost: Array<FloatArray>): IntArray =
        hungarianRectangularRows(cost)

    /**
     * Exact O(rows^2 * cols) Hungarian assignment where `rows <= cols`, returning column index per row.
     */
    private fun hungarianRectangularRows(cost: Array<FloatArray>): IntArray {
        val n = cost.size
        val m = cost[0].size
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..m) {
                    if (!used[j]) {
                        val cur = cost[i0 - 1][j - 1].toDouble() - u[i0] - v[j]
                        if (cur < minv[j]) {
                            minv[j] = cur
                            way[j] = j0
                        }
                        if (minv[j] < delta) {
                            delta = minv[j]
                            j1 = j
                        }
                    }
                }
                for (j in 0..m) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val result = IntArray(n)
        for (j in 1..m) {
            if (p[j] > 0) {
                result[p[j] - 1] = j - 1
            }
        }
        return result
    }
}
