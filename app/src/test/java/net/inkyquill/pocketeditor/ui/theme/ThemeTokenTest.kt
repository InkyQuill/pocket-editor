package net.inkyquill.pocketeditor.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeTokenTest {
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
