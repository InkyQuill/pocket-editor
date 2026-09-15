package net.inkyquill.pocketeditor.ui.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UnavailableReviewCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readsAndDeletesWithoutRebinding() {
        var deleted = false
        composeRule.setContent {
            UnavailableReviewCard("Правка", "старый текст → новый текст", "Привязка недоступна") {
                deleted = true
            }
        }
        composeRule.onNodeWithText("старый текст → новый текст").assertIsDisplayed()
        composeRule.onNodeWithText("Перепривязать").assertDoesNotExist()
        composeRule.onNodeWithText("Удалить").performClick()
        assertTrue(deleted)
    }
}
