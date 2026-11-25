package com.raven.app.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RAVEN_RAIL_BREAKPOINT_DP
import com.raven.app.core.designsystem.component.RavenTopDestination
import com.raven.app.core.designsystem.component.displayLabel
import com.raven.app.core.designsystem.component.isRavenRailWidth
import com.raven.app.core.designsystem.theme.RAVEN_TABULAR_FONT_FEATURE
import com.raven.app.core.designsystem.theme.RavenColors
import com.raven.app.core.designsystem.theme.RavenDarkColorScheme
import com.raven.app.core.designsystem.theme.RavenExtendedColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.RavenTypography
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.RavenRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RavenDesignTokensTest {

    @Test
    fun `obsidian palette matches locked hex specification with zero legacy pink`() {
        assertEquals(Color(0xFF09090B), RavenColors.Canvas)
        assertEquals(Color(0xFF121216), RavenColors.Surface1)
        assertEquals(Color(0xFF1B1B22), RavenColors.Surface2)
        assertEquals(Color(0xFF24242D), RavenColors.Surface3)
        assertEquals(Color(0x14FFFFFF), RavenColors.Hairline)
        assertEquals(Color(0xFF2A2A33), RavenColors.OutlineSubtle)
        assertEquals(Color(0xFFF5F5F7), RavenColors.InkPrimary)
        assertEquals(Color(0xFF9E9EA8), RavenColors.InkSecondary)
        assertEquals(Color(0xFF6E6E78), RavenColors.InkTertiary)
        assertEquals(Color(0xFFFF4D58), RavenColors.Crimson)
        assertEquals(Color(0xFFF5F5F7), RavenColors.PrimaryCtaContainer)
        assertEquals(Color(0xFF09090B), RavenColors.PrimaryCtaInk)
        assertEquals(0.78f, RavenColors.SecondaryCtaContainer.alpha, 0.01f)
        assertEquals(0.92f, RavenColors.FrostedFallbackScrim.alpha, 0.01f)
        assertEquals(Color(0xFF30D158), RavenColors.Success)
        assertEquals(Color(0xFFFFD60A), RavenColors.Warning)
        assertEquals(Color(0xFFFF453A), RavenColors.Error)

        // Regression guard against legacy R34V pink theme (#FF8DB4)
        val legacyPink = Color(0xFFFF8DB4)
        assertNotEquals(legacyPink, RavenColors.Crimson)
        assertNotEquals(legacyPink, RavenDarkColorScheme.primary)
        assertNotEquals(legacyPink, RavenDarkColorScheme.secondary)
    }

    @Test
    fun `material dark color scheme preserves OLED canvas and disables tonal surface tint`() {
        assertEquals(RavenColors.Canvas, RavenDarkColorScheme.background)
        assertEquals(RavenColors.Surface1, RavenDarkColorScheme.surface)
        assertEquals(RavenColors.Crimson, RavenDarkColorScheme.primary)
        assertEquals(RavenColors.PrimaryCtaContainer, RavenDarkColorScheme.secondary)
        assertEquals(RavenColors.PrimaryCtaInk, RavenDarkColorScheme.onSecondary)
        assertEquals(Color.Transparent, RavenDarkColorScheme.surfaceTint)
        assertEquals(RavenColors.Hairline, RavenDarkColorScheme.outlineVariant)
        assertEquals(RavenColors.OutlineSubtle, RavenDarkColorScheme.outline)

        val extended = RavenExtendedColors()
        assertEquals(RavenColors.Canvas, extended.canvas)
        assertEquals(RavenColors.Crimson, extended.crimson)
    }

    @Test
    fun `typography enforces tight-tracked headers and tabular numerals`() {
        val headerStyles = listOf(
            RavenTypography.displayLarge,
            RavenTypography.displayMedium,
            RavenTypography.headlineLarge,
            RavenTypography.headlineMedium,
            RavenTypography.titleLarge,
            RavenTypography.titleMedium,
        )
        headerStyles.forEach { style ->
            assertTrue("Expected sp letterSpacing", style.letterSpacing.isSp)
            assertTrue(
                "Expected tight negative tracking in [-0.4sp, -0.2sp], got ${style.letterSpacing}",
                style.letterSpacing.value in -0.4f..-0.2f,
            )
        }

        assertEquals(RAVEN_TABULAR_FONT_FEATURE, RavenTabularTextStyle.fontFeatureSettings)
        assertEquals("tnum", RavenTypography.bodyMedium.tabular().fontFeatureSettings)
    }

    @Test
    fun `shapes match HarmonyOS Design Z-depth corner geometry`() {
        assertEquals(4.dp, RavenShapes.MicroRadius)
        assertEquals(6.dp, RavenShapes.BadgeRadius)
        assertEquals(8.dp, RavenShapes.SmallRadius)
        assertEquals(12.dp, RavenShapes.ControlRadius)
        assertEquals(16.dp, RavenShapes.CardRadius)
        assertEquals(20.dp, RavenShapes.ButtonRadius)
        assertEquals(28.dp, RavenShapes.DialogRadius)
        assertEquals(32.dp, RavenShapes.SheetRadius)
        assertEquals(CircleShape, RavenShapes.PillShape)
    }

    @Test
    fun `harmony optical light and material tokens stay bounded and consistent`() {
        assertEquals(Color(0x2EFFFFFF), RavenColors.LightRimTop)
        assertEquals(Color(0x0AFFFFFF), RavenColors.LightRimBottom)
        assertEquals(Color(0x26FFFFFF), RavenColors.InteractiveGlow)
        assertEquals(Color(0x38FF4D58), RavenColors.AccentGlow)
        assertEquals(Color(0xE0121216), RavenColors.FloatingBarScrim)

        val extended = RavenExtendedColors()
        assertEquals(RavenColors.LightRimTop, extended.lightRimTop)
        assertEquals(RavenColors.LightRimBottom, extended.lightRimBottom)
        assertEquals(RavenColors.InteractiveGlow, extended.interactiveGlow)
        assertEquals(RavenColors.AccentGlow, extended.accentGlow)
        assertEquals(RavenColors.FloatingBarScrim, extended.floatingBarScrim)
    }

    @Test
    fun `motion enforces spring press scale, ambient glow cap, and API 31 blur gating`() {
        assertEquals(0.968f, RavenMotion.SPRING_PRESSED_SCALE, 0.0001f)
        assertEquals(0.18f, RavenMotion.AMBIENT_GLOW_MAX_ALPHA, 0.0001f)
        assertEquals(220, RavenMotion.IMAGE_CROSSFADE_MILLIS)
        assertEquals(220, RavenMotion.TAB_TRANSITION_DURATION_MS)
        assertEquals(0.985f, RavenMotion.TAB_ENTER_INITIAL_SCALE, 0.0001f)
        assertEquals(0.72f, RavenMotion.PUSH_EXIT_ALPHA, 0.0001f)

        assertEquals(0f, RavenMotion.clampAmbientGlowAlpha(-0.5f), 0.0001f)
        assertEquals(0f, RavenMotion.clampAmbientGlowAlpha(Float.NaN), 0.0001f)
        assertEquals(0f, RavenMotion.clampAmbientGlowAlpha(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(0.12f, RavenMotion.clampAmbientGlowAlpha(0.12f), 0.0001f)
        assertEquals(0.18f, RavenMotion.clampAmbientGlowAlpha(0.18f), 0.0001f)
        assertEquals(0.18f, RavenMotion.clampAmbientGlowAlpha(0.65f), 0.0001f)

        // API 24-30 always degrades to clean scrim; API 31+ uses native blur unless reduceTransparency is true
        assertFalse(RavenMotion.supportsNativeBlur(sdkInt = 24, reduceTransparency = false))
        assertFalse(RavenMotion.supportsNativeBlur(sdkInt = 30, reduceTransparency = false))
        assertTrue(RavenMotion.supportsNativeBlur(sdkInt = 31, reduceTransparency = false))
        assertTrue(RavenMotion.supportsNativeBlur(sdkInt = 36, reduceTransparency = false))
        assertFalse(RavenMotion.supportsNativeBlur(sdkInt = 31, reduceTransparency = true))
        assertFalse(RavenMotion.supportsNativeBlur(sdkInt = 36, reduceTransparency = true))
    }

    @Test
    fun `hero parallax and navigation transition math stay bounded and gap-free`() {
        val rest = RavenMotion.computeHeroPageTransform(0f)
        assertEquals(0f, rest.parallaxFraction, 0.0001f)
        assertEquals(1f, rest.artworkScale, 0.0001f)
        assertEquals(1f, rest.contentAlpha, 0.0001f)

        // Non-finite inputs safely fall back to rest transform
        assertEquals(rest, RavenMotion.computeHeroPageTransform(Float.NaN))
        assertEquals(rest, RavenMotion.computeHeroPageTransform(Float.POSITIVE_INFINITY))
        assertEquals(rest, RavenMotion.computeHeroPageTransform(Float.NEGATIVE_INFINITY))

        // Swiping left (pageOffset = -0.5) lags artwork slightly right (+parallax) without exposing edges
        val leftHalf = RavenMotion.computeHeroPageTransform(-0.5f)
        assertEquals(0.11f, leftHalf.parallaxFraction, 0.0001f)
        assertTrue(kotlin.math.abs(leftHalf.parallaxFraction) < 0.5f)
        assertEquals(1.0225f, leftHalf.artworkScale, 0.0001f)
        assertEquals(0.675f, leftHalf.contentAlpha, 0.0001f)

        // Incoming right page (pageOffset = +1.0, or overscrolled +2.5 clamped to +1.0)
        val rightFull = RavenMotion.computeHeroPageTransform(2.5f)
        assertEquals(-RavenMotion.HERO_PARALLAX_FACTOR, rightFull.parallaxFraction, 0.0001f)
        assertEquals(1f + RavenMotion.HERO_MAX_EXTRA_SCALE, rightFull.artworkScale, 0.0001f)
        assertEquals(0.35f, rightFull.contentAlpha, 0.0001f)

        // iOS parallax navigation offsets (-18% push parent exit, -20% pop parent enter)
        assertEquals(-180, RavenMotion.computeNavPushParentExitOffset(1000))
        assertEquals(0, RavenMotion.computeNavPushParentExitOffset(0))
        assertEquals(0, RavenMotion.computeNavPushParentExitOffset(-400))

        assertEquals(-200, RavenMotion.computeNavPopParentEnterOffset(1000))
        assertEquals(0, RavenMotion.computeNavPopParentEnterOffset(0))
        assertEquals(0, RavenMotion.computeNavPopParentEnterOffset(-400))

        // Apple deceleration curve starts at 0, accelerates early, and lands at 1
        assertEquals(0f, RavenMotion.AppleDecelerationEasing.transform(0f), 0.0001f)
        assertTrue(RavenMotion.AppleDecelerationEasing.transform(0.5f) > 0.7f)
        assertEquals(1f, RavenMotion.AppleDecelerationEasing.transform(1f), 0.0001f)
    }

    @Test
    fun `top destinations, adaptive rail breakpoint, and language labels stay consistent`() {
        val destinations = RavenTopDestination.entries
        assertEquals(5, destinations.size)
        assertEquals(listOf("首页", "发现", "搜索", "片库", "我的"), destinations.map { it.label })
        assertEquals(5, destinations.map { it.testTag }.toSet().size)
        assertTrue(destinations.all { it.testTag.isNotBlank() })

        assertTrue(RavenTopDestination.HOME.matchesRoute(RavenRoute.Home))
        assertTrue(RavenTopDestination.EXPLORE.matchesRoute(RavenRoute.Explore))
        assertTrue(RavenTopDestination.SEARCH.matchesRoute(RavenRoute.Search(initialQuery = "tag", crossSite = true)))
        assertTrue(RavenTopDestination.LIBRARY.matchesRoute(RavenRoute.Library))
        assertTrue(RavenTopDestination.PROFILE.matchesRoute(RavenRoute.Profile))
        assertFalse(RavenTopDestination.HOME.matchesRoute(RavenRoute.Settings))

        assertEquals(600, RAVEN_RAIL_BREAKPOINT_DP)
        assertFalse(isRavenRailWidth(360))
        assertFalse(isRavenRailWidth(599))
        assertTrue(isRavenRailWidth(600))
        assertTrue(isRavenRailWidth(840))
        assertTrue(isRavenRailWidth(1199))
        assertFalse(isRavenRailWidth(1200))
        assertFalse(com.raven.app.core.designsystem.component.isRavenDrawerWidth(1199))
        assertTrue(com.raven.app.core.designsystem.component.isRavenDrawerWidth(1200))

        assertEquals("全部", ComicLanguage.ALL.displayLabel())
        assertEquals("中文", ComicLanguage.CHINESE.displayLabel())
        assertEquals("日文", ComicLanguage.JAPANESE.displayLabel())
        assertEquals("中＋日", ComicLanguage.CHINESE_AND_JAPANESE.displayLabel())
    }
}
