package com.raven.app.core.designsystem.motion

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 标准 24f×24f 视口下的路径段、路径构建器与描边模型。 */

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
