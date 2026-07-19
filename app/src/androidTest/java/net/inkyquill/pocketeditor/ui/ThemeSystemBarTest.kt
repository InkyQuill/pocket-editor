package net.inkyquill.pocketeditor.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ThemeSystemBarTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun explicitThemeControlsSystemBarIconAppearance() {
        val dark = mutableStateOf(false)
        compose.setContent { PocketEditorTheme(darkTheme = dark.value) { Box {} } }

        compose.runOnIdle {
            val controller = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
            dark.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val controller = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }
    }
}
