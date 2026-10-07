package net.inkyquill.pocketeditor.ui.books

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        compose.onNodeWithText("Провайдер").assertIsDisplayed()
        compose.onNodeWithText("Open Edda").performClick()
        compose.onNodeWithText("Аккаунт").assertIsDisplayed()
        compose.onNodeWithText("Добавить аккаунт").performClick()
        compose.onNodeWithText("Адрес сервера").assertIsDisplayed()
        compose.onNodeWithText("Электронная почта").assertIsDisplayed()
        compose.onNodeWithText("Пароль").assertIsDisplayed()
        compose.onNodeWithText("Войти").assertIsNotEnabled()
    }
    @Test fun existingYandexAccountCanChooseLocation() {
        var chosen: String? = null
        val accounts = EddaAccounts(ApplicationProvider.getApplicationContext())
        compose.setContent {
            PocketEditorTheme {
                SourcePicker(accounts, EddaClient(OkHttpClient(), accounts::credentials), true, {}, { chosen = it }, {})
            }
        }
        compose.onNodeWithText("Яндекс Диск").performClick()
        compose.onNodeWithText("Текущий аккаунт Яндекс Диска").assertIsDisplayed()
        compose.onNodeWithText("Выбрать расположение").performClick()
        compose.runOnIdle { assertEquals("disk:/", chosen) }
    }
    @Test fun yandexAuthorizationFailureIsVisibleInsideSourcePicker() {
        val accounts = EddaAccounts(ApplicationProvider.getApplicationContext())
        compose.setContent {
            PocketEditorTheme {
                SourcePicker(accounts, EddaClient(OkHttpClient(), accounts::credentials), false, {}, {}, {},
                    yandexSignInError = "Не удалось войти")
            }
        }
        compose.onNodeWithText("Яндекс Диск").performClick()
        compose.onNodeWithText("Не удалось войти").assertIsDisplayed()
        compose.onNodeWithText("Войти через Яндекс").assertIsDisplayed()
    }

}
