package com.raven.app.core.designsystem

import com.raven.app.core.designsystem.motion.MorphIconEngine
import com.raven.app.core.designsystem.motion.MorphSpring
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.MorphStroke
import com.raven.app.core.designsystem.motion.RavenMorphGlyph
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.motion.SampledStroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphIconEngineTest {

    @Test
    fun `resampleStroke produces exact point count and retains sharp corners`() {
        val square = MorphStroke.build(closed = true) {
            moveTo(4f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 20f)
            lineTo(4f, 20f)
            close()
        }
        val sampled = MorphIconEngine.resampleStroke(square, MorphIconEngine.DEFAULT_SAMPLES)
        assertEquals(MorphIconEngine.DEFAULT_SAMPLES, sampled.sampleCount)
        assertEquals(MorphIconEngine.DEFAULT_SAMPLES * 2, sampled.pts.size)

        val corners = listOf(4f to 4f, 20f to 4f, 20f to 20f, 4f to 20f)
        for ((cx, cy) in corners) {
            assertTrue(
                "Expected corner ($cx, $cy) to be anchored in sampled points",
                containsPoint(sampled.pts, cx, cy, tol = 1e-3f),
            )
        }

        val lShape = MorphStroke.polyline(4f to 4f, 4f to 20f, 18f to 20f, closed = false)
        val sampledOpen = MorphIconEngine.resampleStroke(lShape, 33)
        assertEquals(33, sampledOpen.sampleCount)
        assertEquals(4f, sampledOpen.pts[0], 1e-4f)
        assertEquals(4f, sampledOpen.pts[1], 1e-4f)
        assertEquals(18f, sampledOpen.pts[64], 1e-4f)
        assertEquals(20f, sampledOpen.pts[65], 1e-4f)
        assertTrue(
            "Expected corner (4, 20) to be anchored in open L-shape",
            containsPoint(sampledOpen.pts, 4f, 20f, tol = 1e-3f),
        )
    }

    @Test
    fun `procrustes alignPair recovers 90 degree rotation between rotated asymmetric strokes`() {
        val base = MorphStroke.polyline(6f to 6f, 6f to 16f, 14f to 16f, closed = false)
        // Rotate (x, y) around (12, 12) by +90 degrees: (x', y') = (12 - (y - 12), 12 + (x - 12))
        val rotated = MorphStroke.polyline(18f to 6f, 8f to 6f, 8f to 14f, closed = false)

        val sampledA = MorphIconEngine.resampleStroke(base)
        val sampledB = MorphIconEngine.resampleStroke(rotated)
        val aligned = MorphIconEngine.alignPair(sampledA, sampledB)

        assertEquals((PI / 2.0).toFloat(), aligned.theta, 0.02f)
        assertEquals(0f, aligned.lnSigma, 0.02f)
    }

    @Test
    fun `pairSubpaths handles unequal counts 1 to 2 and 4 to 3 with full coverage`() {
        val playSampled = MorphIconEngine.resampleGlyph(RavenMorphIcons.Play)
        val pauseSampled = MorphIconEngine.resampleGlyph(RavenMorphIcons.Pause)
        assertEquals(1, playSampled.size)
        assertEquals(2, pauseSampled.size)

        val playToPause = MorphIconEngine.pairSubpaths(playSampled, pauseSampled)
        assertEquals(2, playToPause.size)
        assertTrue(playToPause.all { it.first === playSampled[0] })
        assertTrue(pauseSampled.all { target -> playToPause.any { it.second === target } })

        val gridSampled = MorphIconEngine.resampleGlyph(RavenMorphIcons.GridView)
        val listSampled = MorphIconEngine.resampleGlyph(RavenMorphIcons.ListView)
        assertEquals(4, gridSampled.size)
        assertEquals(3, listSampled.size)

        val gridToList = MorphIconEngine.pairSubpaths(gridSampled, listSampled)
        assertEquals(4, gridToList.size)
        assertTrue(gridSampled.all { src -> gridToList.any { it.first === src } })
        assertTrue(listSampled.all { dst -> gridToList.any { it.second === dst } })
    }

    @Test
    fun `interpPolar matches exact source at t=0 and exact target at t=1`() {
        val pairsToVerify = listOf(
            RavenMorphIcons.Play to RavenMorphIcons.Pause,
            RavenMorphIcons.GridView to RavenMorphIcons.ListView,
            RavenMorphIcons.HomeOutline to RavenMorphIcons.HomeActive,
            RavenMorphIcons.FullscreenEnter to RavenMorphIcons.FullscreenExit,
            RavenMorphIcons.CheckBoxEmpty to RavenMorphIcons.CheckBoxChecked,
            RavenMorphIcons.FilterOutline to RavenMorphIcons.FilterActive,
        )

        for ((fromGlyph, toGlyph) in pairsToVerify) {
            val fromSampled = MorphIconEngine.resampleGlyph(fromGlyph)
            val toSampled = MorphIconEngine.resampleGlyph(toGlyph)
            val paired = MorphIconEngine.pairSubpaths(fromSampled, toSampled)
            val plan = MorphIconEngine.buildPlan(fromSampled, toSampled)
            val buffers = plan.allocateBuffers()

            MorphIconEngine.interpolatePlan(plan, 0f, buffers)
            for (i in plan.pairs.indices) {
                assertFloatArraysClose(
                    "Start t=0 mismatch for ${fromGlyph.id} -> ${toGlyph.id} pair $i",
                    paired[i].first.pts,
                    buffers[i],
                    tol = 1e-3f,
                )
            }

            MorphIconEngine.interpolatePlan(plan, 1f, buffers)
            for (i in plan.pairs.indices) {
                // Target may be cyclically shifted/reversed for closed paths; every target sample
                // must land on the target sampled point set within tolerance.
                val expectedPts = paired[i].second.pts
                val actualPts = buffers[i]
                for (k in 0 until actualPts.size / 2) {
                    val ax = actualPts[k * 2]
                    val ay = actualPts[k * 2 + 1]
                    assertTrue(
                        "End t=1 point ($ax, $ay) missing from target ${toGlyph.id} pair $i",
                        containsPoint(expectedPts, ax, ay, tol = 2e-3f),
                    )
                }
            }
        }
    }

    @Test
    fun `MorphSpring converges for all presets and BOUNCY exhibits overshoot`() {
        for (preset in MorphSpringPreset.entries) {
            val spring = MorphSpring(preset)
            spring.start(fromValue = 0f, toValue = 1f, preserveVelocity = false)
            var maxObserved = 0f
            var steps = 0
            while (spring.isRunning && steps < 600) {
                spring.step(1f / 60f)
                if (spring.value > maxObserved) maxObserved = spring.value
                steps++
            }
            assertFalse("Spring $preset failed to settle within 10s", spring.isRunning)
            assertEquals(1f, spring.value, 1e-3f)
            assertEquals(0f, spring.velocity, 1e-3f)
            if (preset == MorphSpringPreset.BOUNCY) {
                assertTrue("Expected BOUNCY preset to overshoot > 1.05f, was $maxObserved", maxObserved > 1.05f)
            }
            if (preset == MorphSpringPreset.SMOOTH) {
                assertTrue("Expected SMOOTH preset not to overshoot significantly, was $maxObserved", maxObserved <= 1.01f)
            }
        }
    }

    @Test
    fun `MorphSpring start mid-flight preserves and clamps velocity to plus-minus 14`() {
        val spring = MorphSpring(MorphSpringPreset.SNAPPY)
        spring.start(fromValue = 0f, toValue = 1f, preserveVelocity = false)
        // Step briefly while velocity is high positive.
        repeat(3) { spring.step(1f / 60f) }
        val midVelocity = spring.velocity
        assertTrue("Expected positive mid-flight velocity, got $midVelocity", midVelocity > 0.5f)

        spring.start(fromValue = 0f, toValue = 1f, preserveVelocity = true)
        assertEquals(midVelocity.coerceIn(-14f, 14f), spring.velocity, 1e-5f)
        assertTrue(spring.isRunning)

        // Drive extreme impulse and confirm clamping at +14f / -14f.
        val extreme = MorphSpring(MorphSpringPreset.SNAPPY)
        extreme.start(fromValue = -100f, toValue = 100f, preserveVelocity = false)
        extreme.step(1f / 60f)
        assertTrue("Expected extreme velocity > 14f before restart, was ${extreme.velocity}", extreme.velocity > 14f)
        extreme.start(fromValue = 0f, toValue = 1f, preserveVelocity = true)
        assertEquals(14f, extreme.velocity, 1e-5f)
    }

    @Test
    fun `empty degenerate and non-finite inputs remain finite and safe`() {
        val emptyStroke = MorphStroke(segments = emptyList(), closed = false)
        val pointStroke = MorphStroke.build(closed = true) {
            moveTo(7f, 9f)
            lineTo(7f, 9f)
        }
        val nanStroke = MorphStroke.build(closed = false) {
            moveTo(Float.NaN, 5f)
            lineTo(Float.POSITIVE_INFINITY, 10f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
        }

        val emptyGlyph = RavenMorphGlyph("empty", emptyList())
        val degGlyph = RavenMorphGlyph("deg", listOf(emptyStroke, pointStroke, nanStroke))

        val planA = MorphIconEngine.buildPlan(emptyGlyph, degGlyph)
        val planB = MorphIconEngine.buildPlan(degGlyph, emptyGlyph)
        for (plan in listOf(planA, planB)) {
            val bufs = plan.allocateBuffers()
            for (t in listOf(0f, 0.25f, 0.5f, 1f, Float.NaN)) {
                MorphIconEngine.interpolatePlan(plan, t, bufs)
                for (buf in bufs) {
                    for (v in buf) {
                        assertTrue("Expected finite coordinate, got $v", v.isFinite())
                    }
                }
            }
        }
    }

    @Test
    fun `all RavenMorphIcons validate finite coordinates and valid buildPlan against peers`() {
        val glyphs = RavenMorphIcons.all
        assertEquals("Expected full RavenMorphIcons catalog (52 glyphs)", 52, glyphs.size)
        assertEquals("Expected unique glyph ids", glyphs.size, glyphs.map { it.id }.toSet().size)

        val requiredNewGlyphs = listOf(
            RavenMorphIcons.CheckBoxEmpty,
            RavenMorphIcons.CheckBoxChecked,
            RavenMorphIcons.MailRead,
            RavenMorphIcons.MailUnread,
            RavenMorphIcons.FilterOutline,
            RavenMorphIcons.FilterActive,
            RavenMorphIcons.Verified,
            RavenMorphIcons.Build,
            RavenMorphIcons.Link,
            RavenMorphIcons.ChevronRight,
        )
        for (expected in requiredNewGlyphs) {
            assertTrue("Expected ${expected.id} to be included in RavenMorphIcons.all", expected in glyphs)
        }

        for (glyph in glyphs) {
            val sampled = MorphIconEngine.resampleGlyph(glyph)
            assertTrue("Glyph ${glyph.id} must have at least 1 stroke", sampled.isNotEmpty())
            for (stroke in sampled) {
                assertEquals(MorphIconEngine.DEFAULT_SAMPLES, stroke.sampleCount)
                for (coord in stroke.pts) {
                    assertTrue("Non-finite coord in ${glyph.id}: $coord", coord.isFinite())
                    assertTrue(
                        "Coord out of reasonable viewBox bounds in ${glyph.id}: $coord",
                        coord in -2f..26f,
                    )
                }
            }
        }

        val pairs = listOf(
            RavenMorphIcons.HomeOutline to RavenMorphIcons.HomeActive,
            RavenMorphIcons.ExploreOutline to RavenMorphIcons.ExploreActive,
            RavenMorphIcons.SearchOutline to RavenMorphIcons.SearchActive,
            RavenMorphIcons.LibraryOutline to RavenMorphIcons.LibraryActive,
            RavenMorphIcons.ProfileOutline to RavenMorphIcons.ProfileActive,
            RavenMorphIcons.Play to RavenMorphIcons.Pause,
            RavenMorphIcons.Pause to RavenMorphIcons.Replay,
            RavenMorphIcons.FullscreenEnter to RavenMorphIcons.FullscreenExit,
            RavenMorphIcons.LockOpen to RavenMorphIcons.LockClosed,
            RavenMorphIcons.VolumeOn to RavenMorphIcons.VolumeMuted,
            RavenMorphIcons.Plus to RavenMorphIcons.Check,
            RavenMorphIcons.HeartOutline to RavenMorphIcons.HeartFilled,
            RavenMorphIcons.BookmarkOutline to RavenMorphIcons.BookmarkFilled,
            RavenMorphIcons.PinOutline to RavenMorphIcons.PinFilled,
            RavenMorphIcons.Download to RavenMorphIcons.DownloadDone,
            RavenMorphIcons.BellOutline to RavenMorphIcons.BellActive,
            RavenMorphIcons.GridView to RavenMorphIcons.ListView,
            RavenMorphIcons.VisibilityOn to RavenMorphIcons.VisibilityOff,
            RavenMorphIcons.ChevronDown to RavenMorphIcons.ChevronUp,
            RavenMorphIcons.Close to RavenMorphIcons.Plus,
            RavenMorphIcons.Refresh to RavenMorphIcons.Replay,
            RavenMorphIcons.Movie to RavenMorphIcons.Book,
            RavenMorphIcons.Book to RavenMorphIcons.Photo,
            RavenMorphIcons.CheckBoxEmpty to RavenMorphIcons.CheckBoxChecked,
            RavenMorphIcons.MailRead to RavenMorphIcons.MailUnread,
            RavenMorphIcons.FilterOutline to RavenMorphIcons.FilterActive,
            RavenMorphIcons.Verified to RavenMorphIcons.Build,
            RavenMorphIcons.Link to RavenMorphIcons.ChevronRight,
            RavenMorphIcons.ChevronRight to RavenMorphIcons.ChevronDown,
        )

        for ((a, b) in pairs) {
            val plan = MorphIconEngine.buildPlan(a, b)
            assertTrue("Empty plan for ${a.id} -> ${b.id}", plan.pairs.isNotEmpty())
            val bufs = plan.allocateBuffers()
            for (t in listOf(0f, 0.5f, 1f)) {
                MorphIconEngine.interpolatePlan(plan, t, bufs)
                for (buf in bufs) {
                    for (v in buf) {
                        assertTrue("Non-finite interpolated value in ${a.id} -> ${b.id}", v.isFinite())
                    }
                }
            }
        }
    }

    private fun containsPoint(pts: FloatArray, tx: Float, ty: Float, tol: Float): Boolean {
        var i = 0
        while (i + 1 < pts.size) {
            if (hypot(pts[i] - tx, pts[i + 1] - ty) <= tol) return true
            i += 2
        }
        return false
    }

    private fun assertFloatArraysClose(
        message: String,
        expected: FloatArray,
        actual: FloatArray,
        tol: Float,
    ) {
        assertEquals("$message (length)", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("$message (index $i)", expected[i], actual[i], tol)
        }
    }
}
