package net.inkyquill.pocketeditor.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeTokenTest {
    @Test
    fun `reader typography matches approved prose scale`() {
        assertEquals(28.sp, DefaultReaderTypography.h1.fontSize)
        assertEquals(35.sp, DefaultReaderTypography.h1.lineHeight)
        assertEquals(23.sp, DefaultReaderTypography.h2.fontSize)
        assertEquals(19.sp, DefaultReaderTypography.h3.fontSize)
        assertEquals(17.sp, DefaultReaderTypography.h4.fontSize)
        assertEquals(16.sp, DefaultReaderTypography.h5.fontSize)
        assertEquals(14.sp, DefaultReaderTypography.h6.fontSize)
        assertEquals(16.sp, DefaultReaderTypography.prose.fontSize)
        assertEquals(25.sp, DefaultReaderTypography.prose.lineHeight)
        assertEquals(14.sp, DefaultReaderTypography.searchExcerpt.fontSize)
        assertEquals(21.sp, DefaultReaderTypography.searchExcerpt.lineHeight)
    }

    @Test
    fun `reader scale changes prose but not Manrope chrome`() {
        val scaled = DefaultReaderTypography.scaled(1.3f)

        assertEquals(20.8.sp, scaled.prose.fontSize)
        assertEquals(32.5.sp, scaled.prose.lineHeight)
        assertEquals(18.sp, PocketTypography.titleLarge.fontSize)
        assertEquals(13.sp, PocketTypography.labelMedium.fontSize)
        assertEquals(ManropeFamily, PocketTypography.titleLarge.fontFamily)
        assertEquals(LiterataFamily, scaled.prose.fontFamily)
    }

    @Test
    fun `light semantic and tonal roles are warm complete and contrast safe`() {
        assertWarmScheme(LightPocketColors)
        assertContrast(LightPocketColors.onPrimary, LightPocketColors.primary)
        assertContrast(LightPocketColors.onSecondary, LightPocketColors.secondary)
        assertContrast(LightPocketColors.onTertiary, LightPocketColors.tertiary)
        assertContrast(LightPocketColors.onSurface, LightPocketColors.surface)
        assertTrue(LightOverlayScrim.alpha in 0.35f..0.55f)
    }

    @Test
    fun `dark semantic and tonal roles are warm complete and contrast safe`() {
        assertWarmScheme(DarkPocketColors)
        assertContrast(DarkPocketColors.onPrimary, DarkPocketColors.primary)
        assertContrast(DarkPocketColors.onSecondary, DarkPocketColors.secondary)
        assertContrast(DarkPocketColors.onTertiary, DarkPocketColors.tertiary)
        assertContrast(DarkPocketColors.onSurface, DarkPocketColors.surface)
        assertTrue(DarkOverlayScrim.alpha in 0.5f..0.7f)
    }

    @Test
    fun `four semantic signal colors remain distinct and readable in flyout and selected chips`() {
        assertSignalPalette(LightReviewColors, LightPocketColors.surface, LightPocketColors.onSurface)
        assertSignalPalette(DarkReviewColors, DarkPocketColors.surface, DarkPocketColors.onSurface)
    }

    private fun assertSignalPalette(colors: ReviewColors, surface: Color, content: Color) {
        val signals = listOf(colors.note, colors.changeNeeded, colors.warning, colors.review)
        assertEquals(4, signals.distinct().size)
        signals.forEach { signal ->
            assertContrast(content, signal.copy(alpha = 0.14f).compositeOver(surface))
            assertContrast(content, signal.copy(alpha = 0.28f).compositeOver(surface))
        }
    }

    private fun assertWarmScheme(scheme: androidx.compose.material3.ColorScheme) {
        val stockPurple = Color(0xFF625B71)
        assertNotEquals(stockPurple, scheme.secondary)
        assertNotEquals(stockPurple, scheme.tertiary)
        assertNotEquals(scheme.primaryContainer, scheme.secondaryContainer)
        assertNotEquals(scheme.secondaryContainer, scheme.tertiaryContainer)
        assertNotEquals(scheme.surfaceContainerLow, scheme.surfaceContainerHigh)
        assertNotEquals(scheme.surfaceContainer, scheme.surfaceContainerHighest)
        assertNotEquals(Color.Unspecified, scheme.onSecondaryContainer)
        assertNotEquals(Color.Unspecified, scheme.onTertiaryContainer)
        val stockFixedRoles = setOf(
            Color(0xFFEADDFF), Color(0xFFD0BCFF), Color(0xFF21005D), Color(0xFF4F378B),
            Color(0xFFE8DEF8), Color(0xFFCCC2DC), Color(0xFF1D192B), Color(0xFF49454F),
            Color(0xFFFFD8E4), Color(0xFFEFB8C8), Color(0xFF31111D), Color(0xFF633B48),
        )
        listOf(
            scheme.primaryFixed,
            scheme.primaryFixedDim,
            scheme.onPrimaryFixed,
            scheme.onPrimaryFixedVariant,
            scheme.secondaryFixed,
            scheme.secondaryFixedDim,
            scheme.onSecondaryFixed,
            scheme.onSecondaryFixedVariant,
            scheme.tertiaryFixed,
            scheme.tertiaryFixedDim,
            scheme.onTertiaryFixed,
            scheme.onTertiaryFixedVariant,
        ).forEach { role ->
            assertTrue(role !in stockFixedRoles, "Fixed role leaked a stock Material purple token: $role")
        }
    }

    private fun assertContrast(foreground: Color, background: Color) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        assertTrue((lighter + 0.05f) / (darker + 0.05f) >= 4.5f)
    }
}
