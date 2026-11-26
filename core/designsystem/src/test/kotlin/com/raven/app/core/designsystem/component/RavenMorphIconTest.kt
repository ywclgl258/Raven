package com.raven.app.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Test

class RavenMorphIconTest {

    @Test
    fun `boundedMorphTint uses Compose color interpolation at the requested progress`() {
        val start = Color(red = 16, green = 32, blue = 48, alpha = 255)
        val target = Color(red = 224, green = 64, blue = 80, alpha = 128)

        val midpoint = boundedMorphTint(start, target, 0.5f)

        assertEquals(lerp(start, target, 0.5f), midpoint)
        assertEquals(192f / 255f, midpoint.alpha, 1e-4f)
    }

    @Test
    fun `boundedMorphTint clamps overshoot and resolves non-finite progress to target`() {
        val start = Color(red = 16, green = 32, blue = 48)
        val target = Color(red = 224, green = 64, blue = 80)

        assertEquals(start, boundedMorphTint(start, target, -0.5f))
        assertEquals(target, boundedMorphTint(start, target, 1.5f))
        assertEquals(target, boundedMorphTint(start, target, Float.NaN))
    }
}
