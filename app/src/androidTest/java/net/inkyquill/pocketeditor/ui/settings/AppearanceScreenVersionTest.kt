package net.inkyquill.pocketeditor.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.inkyquill.pocketeditor.BuildConfig
import net.inkyquill.pocketeditor.ui.books.AppearancePreference
import net.inkyquill.pocketeditor.ui.books.BookDestination
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppearanceScreenVersionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun versionIsLocalizedVisibleAndExposedToAccessibilityServices() {
        val version = "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        compose.setContent {
            PocketEditorTheme(darkTheme = false, textScale = 1f) {
                AppearanceScreen(
                    appearance = AppearancePreference(),
                    onBack = {},
                    onDarkChanged = {},
                    onDecrease = {},
                    onReset = {},
                    onIncrease = {},
                )
            }
        }

        compose.onNodeWithText(version).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(version).assertIsDisplayed()
    }

    @Test
    fun systemBackUsesTheAppearanceReturnRoute() {
        val reader = BookDestination.Reader("book-1", "chapter-7", blockIndex = 4, byteOffset = 27, rawEndByte = 39)
        var destination: BookDestination = BookDestination.Appearance
        setAppearanceContent(onBack = { destination = reader })

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.runOnIdle { assertEquals(reader, destination) }
    }

    @Test
    fun topBackUsesTheAppearanceReturnRoute() {
        val reader = BookDestination.Reader("book-1", "chapter-7", blockIndex = 4, byteOffset = 27, rawEndByte = 39)
        var destination: BookDestination = BookDestination.Appearance
        setAppearanceContent(onBack = { destination = reader })

        compose.onNodeWithContentDescription("Назад").performClick()

        compose.runOnIdle { assertEquals(reader, destination) }
    }

    private fun setAppearanceContent(onBack: () -> Unit) {
        compose.setContent {
            PocketEditorTheme(darkTheme = false, textScale = 1f) {
                AppearanceScreen(
                    appearance = AppearancePreference(),
                    onBack = onBack,
                    onDarkChanged = {},
                    onDecrease = {},
                    onReset = {},
                    onIncrease = {},
                )
            }
        }
    }
}
