package com.raven.app.core.designsystem

import com.raven.app.core.designsystem.motion.BloubMood
import com.raven.app.core.designsystem.motion.BloubSilhouette
import com.raven.app.core.designsystem.motion.BloubSilhouettes
import com.raven.app.core.designsystem.motion.MorphIconEngine
import com.raven.app.core.designsystem.motion.NOTIF_POP_SCALE
import com.raven.app.core.designsystem.motion.PROFILE_SAMPLES
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.motion.blendSilhouettes
import com.raven.app.core.designsystem.motion.computeOrbitArcs
import com.raven.app.core.designsystem.motion.computeSpiralParticles
import com.raven.app.core.designsystem.motion.loopNoise
import com.raven.app.core.designsystem.motion.projectSilhouettePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class RavenBloubEngineTest {

    @Test
    fun `profile constants and mood silhouettes stay bounded and consistent`() {
        assertEquals(64, PROFILE_SAMPLES)
        assertEquals(1.14f, NOTIF_POP_SCALE, 0.0001f)

        for (mood in BloubMood.entries) {
            val silhouette = BloubSilhouettes.forMood(mood)
            assertEquals("Expected $PROFILE_SAMPLES samples for $mood", PROFILE_SAMPLES, silhouette.radii.size)
            for (r in silhouette.radii) {
                assertTrue("Expected finite positive radius for $mood, got $r", r.isFinite() && r > 0f)
            }
            assertTrue(silhouette.rot.isFinite())
            assertTrue(silhouette.cx.isFinite())
            assertTrue(silhouette.cy.isFinite())
            assertTrue(silhouette.sx.isFinite() && silhouette.sx > 0f)
            assertTrue(silhouette.sy.isFinite() && silhouette.sy > 0f)
        }
    }

    @Test
    fun `blendSilhouettes matches endpoints in-place and wraps rotation via shortest path across plus-minus PI`() {
        val a = BloubSilhouette().copyFrom(BloubSilhouettes.Squircle).apply {
            rot = 3.0f
            cx = -0.2f
            cy = 0.1f
            sx = 0.9f
            sy = 1.1f
        }
        val b = BloubSilhouette().copyFrom(BloubSilhouettes.DropletPetal).apply {
            rot = -3.0f
            cx = 0.4f
            cy = -0.3f
            sx = 1.1f
            sy = 0.9f
        }
        val out = BloubSilhouette()

        val returnedStart = blendSilhouettes(a, b, 0f, out)
        assertSame(out, returnedStart)
        assertEquals(a.rot, out.rot, 0.0001f)
        assertEquals(a.cx, out.cx, 0.0001f)
        assertEquals(a.cy, out.cy, 0.0001f)
        assertEquals(a.sx, out.sx, 0.0001f)
        assertEquals(a.sy, out.sy, 0.0001f)
        for (i in 0 until PROFILE_SAMPLES) {
            assertEquals(a.radii[i], out.radii[i], 0.0001f)
        }

        blendSilhouettes(a, b, 1f, out)
        assertEquals(b.rot, out.rot, 0.0001f)
        assertEquals(b.cx, out.cx, 0.0001f)
        assertEquals(b.cy, out.cy, 0.0001f)
        assertEquals(b.sx, out.sx, 0.0001f)
        assertEquals(b.sy, out.sy, 0.0001f)
        for (i in 0 until PROFILE_SAMPLES) {
            assertEquals(b.radii[i], out.radii[i], 0.0001f)
        }

        // Midpoint between +3.0 and -3.0 rad must cross near ±PI (~3.14159), NOT 0.0
        blendSilhouettes(a, b, 0.5f, out)
        assertTrue(
            "Expected shortest-angle wrap across ±PI, got rot=${out.rot}",
            abs(out.rot) > 3.0f && abs(out.rot) <= PI.toFloat() + 0.001f,
        )

        // Reverse direction -3.0 -> +3.0 must also cross near ±PI
        blendSilhouettes(b, a, 0.5f, out)
        assertTrue(
            "Expected reverse shortest-angle wrap across ±PI, got rot=${out.rot}",
            abs(out.rot) > 3.0f && abs(out.rot) <= PI.toFloat() + 0.001f,
        )
    }

    @Test
    fun `loopNoise is strictly periodic bounded and smooth`() {
        val period = 3.5f
        val seed = 2.4f
        var minSeen = Float.POSITIVE_INFINITY
        var maxSeen = Float.NEGATIVE_INFINITY

        for (step in 0..20) {
            val t = step * (period / 20f)
            val v0 = loopNoise(t, period = period, seed = seed)
            val v1 = loopNoise(t + period, period = period, seed = seed)
            val v2 = loopNoise(t + 2f * period, period = period, seed = seed)
            val vNeg = loopNoise(t - period, period = period, seed = seed)

            assertTrue("Expected finite noise in [-1, 1], got $v0", v0.isFinite() && v0 in -1f..1f)
            assertEquals("Periodic mismatch at t=$t", v0, v1, 0.0002f)
            assertEquals("Multi-period mismatch at t=$t", v0, v2, 0.0003f)
            assertEquals("Negative-period mismatch at t=$t", v0, vNeg, 0.0002f)

            if (v0 < minSeen) minSeen = v0
            if (v0 > maxSeen) maxSeen = v0
        }
        assertTrue("Expected non-degenerate variation across cycle", maxSeen - minSeen > 0.3f)
    }

    @Test
    fun `computeOrbitArcs splits rings into depth-sorted back and front finite segments`() {
        val arcs = computeOrbitArcs(t = 0.75f, scale = 24f)
        assertTrue("Expected at least 4 split arcs for 2 tilted rings", arcs.size >= 4)

        val backArcs = arcs.filter { !it.isFront }
        val frontArcs = arcs.filter { it.isFront }
        assertTrue("Expected non-empty back arcs", backArcs.isNotEmpty())
        assertTrue("Expected non-empty front arcs", frontArcs.isNotEmpty())

        for (i in 0 until arcs.lastIndex) {
            assertTrue(
                "Expected ascending avgDepth sort order",
                arcs[i].avgDepth <= arcs[i + 1].avgDepth,
            )
        }
        for (arc in arcs) {
            assertTrue(arc.pointCount >= 2)
            assertTrue(arc.avgDepth.isFinite())
            assertTrue(arc.alpha.isFinite() && arc.alpha in 0f..1f)
            assertTrue(arc.strokeScale.isFinite() && arc.strokeScale > 0f)
            for (coord in arc.pointsXY) {
                assertTrue("Expected finite orbit coordinate, got $coord", coord.isFinite())
            }
            for (p in 0 until arc.pointCount - 1) {
                val dx = arc.xAt(p + 1) - arc.xAt(p)
                val dy = arc.yAt(p + 1) - arc.yAt(p)
                val stepDist = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                assertTrue(
                    "Orbit arc should be continuous without diameter jump, stepDist=$stepDist",
                    stepDist < 24.0 * 0.35,
                )
            }
            if (arc.isFront) {
                assertTrue("Front arc avgDepth must be >= 0, got ${arc.avgDepth}", arc.avgDepth >= -1e-4f)
            } else {
                assertTrue("Back arc avgDepth must be <= 0, got ${arc.avgDepth}", arc.avgDepth <= 1e-4f)
            }
        }
    }

    @Test
    fun `computeSpiralParticles respects lifetime and opacity bounds`() {
        val particles = computeSpiralParticles(t = 1.25f, scale = 32f, count = 12)
        assertEquals(12, particles.size)
        for (p in particles) {
            assertTrue(p.x.isFinite() && p.y.isFinite())
            assertTrue(p.radius.isFinite() && p.radius >= 0f)
            assertTrue("Expected life in [0, 1), got ${p.life}", p.life.isFinite() && p.life in 0f..<1f)
            assertTrue("Expected alpha in [0, 1], got ${p.alpha}", p.alpha.isFinite() && p.alpha in 0f..1f)
        }
    }

    @Test
    fun `non-finite and degenerate inputs fail safely without NaN or Infinity`() {
        assertEquals(0f, loopNoise(Float.NaN, 1f, 0f), 0.0001f)
        assertEquals(0f, loopNoise(1f, 0f, 0f), 0.0001f)
        assertEquals(0f, loopNoise(1f, -2f, 0f), 0.0001f)
        assertEquals(0f, loopNoise(Float.POSITIVE_INFINITY, 1f, Float.NEGATIVE_INFINITY), 0.0001f)

        val corruptA = BloubSilhouette(
            radii = FloatArray(PROFILE_SAMPLES) { Float.NaN },
            rot = Float.POSITIVE_INFINITY,
            cx = Float.NaN,
            cy = Float.NEGATIVE_INFINITY,
            sx = Float.NaN,
            sy = Float.POSITIVE_INFINITY,
        )
        val out = BloubSilhouette()
        blendSilhouettes(corruptA, BloubSilhouettes.Squircle, Float.NaN, out)
        assertTrue(out.rot.isFinite() && out.cx.isFinite() && out.cy.isFinite() && out.sx.isFinite() && out.sy.isFinite())
        assertTrue(out.radii.all { it.isFinite() })

        val pts = FloatArray(PROFILE_SAMPLES * 2)
        assertEquals(0, projectSilhouettePoints(BloubSilhouettes.Squircle, 0f, 0f, Float.NaN, pts))
        assertEquals(0, projectSilhouettePoints(BloubSilhouettes.Squircle, 0f, 0f, -5f, pts))
        assertEquals(PROFILE_SAMPLES, projectSilhouettePoints(corruptA, 10f, 10f, 20f, pts))
        assertTrue(pts.all { it.isFinite() })

        assertTrue(computeOrbitArcs(t = 0f, scale = Float.NaN).isEmpty())
        assertTrue(computeOrbitArcs(t = 0f, scale = -1f).isEmpty())
        val nanTimeArcs = computeOrbitArcs(t = Float.NaN, scale = 10f)
        assertTrue(nanTimeArcs.isNotEmpty())
        assertTrue(nanTimeArcs.all { arc -> arc.pointsXY.all { it.isFinite() } && arc.avgDepth.isFinite() })

        assertTrue(computeSpiralParticles(t = 0f, scale = Float.POSITIVE_INFINITY).isEmpty())
        assertTrue(computeSpiralParticles(t = 0f, scale = 10f, count = 0).isEmpty())
        val nanTimeParticles = computeSpiralParticles(t = Float.NaN, scale = 10f, count = 6)
        assertEquals(6, nanTimeParticles.size)
        assertTrue(nanTimeParticles.all { it.x.isFinite() && it.y.isFinite() && it.alpha.isFinite() })
    }

    @Test
    fun `RavenMorphIcons catalog contains all newly added glyphs with finite coordinates and valid morph plans`() {
        val allGlyphs = RavenMorphIcons.all
        assertEquals(52, allGlyphs.size)

        val newGlyphPairs = listOf(
            RavenMorphIcons.CheckBoxEmpty to RavenMorphIcons.CheckBoxChecked,
            RavenMorphIcons.MailRead to RavenMorphIcons.MailUnread,
            RavenMorphIcons.FilterOutline to RavenMorphIcons.FilterActive,
            RavenMorphIcons.Verified to RavenMorphIcons.Build,
            RavenMorphIcons.Link to RavenMorphIcons.ChevronRight,
        )

        for ((first, second) in newGlyphPairs) {
            assertTrue("Missing ${first.id} in RavenMorphIcons.all", first in allGlyphs)
            assertTrue("Missing ${second.id} in RavenMorphIcons.all", second in allGlyphs)

            val plan = MorphIconEngine.buildPlan(first, second)
            assertTrue("Expected non-empty morph plan for ${first.id} -> ${second.id}", plan.pairs.isNotEmpty())
            val buffers = plan.allocateBuffers()
            for (t in listOf(0f, 0.5f, 1f)) {
                MorphIconEngine.interpolatePlan(plan, t, buffers)
                for (buf in buffers) {
                    for (coord in buf) {
                        assertTrue("Expected finite coord in ${first.id} -> ${second.id}", coord.isFinite())
                        assertTrue("Coord out of viewBox bounds: $coord", coord in -2f..26f)
                    }
                }
            }
        }
    }
}
