package net.inkyquill.pocketeditor.ui.books

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import net.inkyquill.pocketeditor.ui.SourceErrorKey
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import net.inkyquill.pocketeditor.edda.EddaAccounts
import net.inkyquill.pocketeditor.edda.EddaClient
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SourcePickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun providerThenAccountKeepsEddaAvailableWithoutYandexLogin() {
        val accounts = EddaAccounts(ApplicationProvider.getApplicationContext())
        compose.setContent {
            PocketEditorTheme {
                SourcePicker(accounts, EddaClient(OkHttpClient(), accounts::credentials), false, {}, {}, {})
            }
        }
        compose.onNodeWithTag("source-provider:edda").performClick()
        compose.onNodeWithTag("source-add-account").performClick()
        compose.onNodeWithTag("source-server").assertIsDisplayed()
        compose.onNodeWithTag("source-email").assertIsDisplayed()
        compose.onNodeWithTag("source-password").assertIsDisplayed()
        compose.onNodeWithTag("source-sign-in").assertIsNotEnabled()
    }
    @Test fun existingYandexAccountCanChooseLocation() {
        var chosen: String? = null
        val accounts = EddaAccounts(ApplicationProvider.getApplicationContext())
        compose.setContent {
            PocketEditorTheme {
                SourcePicker(accounts, EddaClient(OkHttpClient(), accounts::credentials), true, {}, { chosen = it }, {})
            }
        }
        compose.onNodeWithTag("source-provider:disk").performClick()
        compose.onNodeWithTag("source-location").performClick()
        compose.runOnIdle { assertEquals("disk:/", chosen) }
    }
    @Test fun yandexAuthorizationFailureIsVisibleInsideSourcePicker() {
        val accounts = EddaAccounts(ApplicationProvider.getApplicationContext())
        compose.setContent {
            PocketEditorTheme {
                SourcePicker(accounts, EddaClient(OkHttpClient(), accounts::credentials), false, {}, {}, {},
                    yandexSignInError = "Sign-in failed")
            }
        }
        compose.onNodeWithTag("source-provider:disk").performClick()
        compose.onNode(SemanticsMatcher.expectValue(SourceErrorKey, SourceErrorCode.SIGN_IN_FAILED)).assertIsDisplayed()
        compose.onNodeWithTag("source-sign-in").assertIsDisplayed()
    }

}
