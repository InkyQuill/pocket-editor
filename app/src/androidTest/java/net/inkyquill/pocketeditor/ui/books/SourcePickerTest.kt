package net.inkyquill.pocketeditor.ui.books

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import net.inkyquill.pocketeditor.ui.SourceErrorKey
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import net.inkyquill.pocketeditor.edda.EddaAccount
import net.inkyquill.pocketeditor.storage.sha256
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test fun reauthenticationPreservesAccountAndReturnsToBookWithoutProjectSelection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val accounts = EddaAccounts(context)
        val server = "https://reauth.example.invalid"
        val key = "$server\nauthor".encodeToByteArray().sha256()
        val account = EddaAccount(key, server, "author", "reader@example.invalid")
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"token":"access-fixture","refreshToken":"refresh-fixture","refreshExpiresAt":1900000000,"author":{"id":"author","email":"reader@example.invalid"}}""".toResponseBody()).build()
        }.build()
        val authenticated = androidx.compose.runtime.mutableStateOf(false)
        try {
            compose.setContent {
                PocketEditorTheme {
                    if (!authenticated.value) SourcePicker(accounts, EddaClient(http, accounts::credentials), false, {},
                        { throw AssertionError("Must not choose a new book") }, {},
                        reauthenticate = account, onAuthenticated = { authenticated.value = true })
                }
            }
            compose.onNodeWithTag("source-server").assertTextContains(server).assertIsNotEnabled()
            compose.onNodeWithTag("source-email").assertTextContains(account.email).assertIsNotEnabled()
            compose.onNodeWithTag("source-password").performTextInput("fixture-password")
            compose.onNodeWithTag("source-sign-in").performClick()
            compose.waitUntil(5_000) { authenticated.value }
            assertEquals(listOf("/api/auth/login"), requests.toList())
            // Recreate the store: both credentials must survive beyond the dialog instance.
            val restored = EddaAccounts(context).credentials(key)
            assertEquals("access-fixture", restored.token)
            assertEquals("refresh-fixture", restored.refreshToken)
            assertEquals(account, accounts.list().single { it.key == key })
        } finally {
            accounts.signOut(key)
        }
    }

}
