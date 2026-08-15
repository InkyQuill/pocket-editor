package net.inkyquill.pocketeditor.ui.contents

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import net.inkyquill.pocketeditor.ui.books.BookChapter
import net.inkyquill.pocketeditor.ui.books.BookSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ContentsReorderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editModeMovesCompleteSpineAndCancelDiscardsDraft() {
        var saved: List<String>? = null
        val chapters = listOf(
            BookChapter("one", "one.md", "One", true),
            BookChapter("two", "two.md", "Two", false),
            BookChapter("three", "three.md", "Three", false),
        )
        compose.setContent {
            ContentsPanel(
                books = listOf(BookSummary(BOOK_ID, "Book", "disk:/Book", chapters)),
                currentBookId = BOOK_ID,
                currentChapterId = "one",
                query = "",
                searchResults = emptyList(),
                searching = false,
                closeLabel = "Закрыть",
                onClose = {},
                onSwitchBook = {},
                onChapterSelected = {},
                onQueryChanged = {},
                onSearchResult = {},
                onOpenBooks = {},
                onAppearance = {},
                onSaveOrder = { saved = it },
            )
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        compose.onNodeWithContentDescription("Переместить Three вверх")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Отмена").performClick()
        assertNull(saved)

        compose.onNodeWithText("Изменить порядок").performClick()
        compose.onNodeWithContentDescription("Переместить Three вверх")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(listOf("one", "three", "two"), saved)
        assertEquals(
            mapOf("one" to "one.md", "two" to "two.md", "three" to "three.md"),
            chapters.associate { it.id to it.path },
        )
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
    }
}
