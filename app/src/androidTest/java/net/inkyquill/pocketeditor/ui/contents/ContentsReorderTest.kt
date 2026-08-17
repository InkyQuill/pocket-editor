package net.inkyquill.pocketeditor.ui.contents

import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
    fun contentsHidesRedundantHelpAndCachedMarkersButShowsPendingDownload() {
        val chapters = listOf(
            BookChapter("one", "one.md", "A very long downloaded chapter title that must stay on one line", true),
            BookChapter("two", "two.md", "Pending", false),
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
                onClose = {}, onChapterSelected = {}, onQueryChanged = {},
                onSearchResult = {}, onOpenBooks = {}, onAppearance = {},
            )
        }

        compose.onNodeWithText("Поиск работает без сети по исходному тексту глав. Заметки и правки не учитываются.")
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Доступно без сети").assertDoesNotExist()
        compose.onNodeWithTag("chapter-download-two").assertExists()
    }

    @Test
    fun editModeMovesCompleteSpineAndCancelDiscardsDraft() {
        var saved: List<String>? = null
        var expectedOriginal: List<String>? = null
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
                onChapterSelected = {},
                onQueryChanged = {},
                onSearchResult = {},
                onOpenBooks = {},
                onAppearance = {},
                onSaveOrder = { expected, order -> expectedOriginal = expected; saved = order },
            )
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        compose.onNodeWithTag("chapter-reorder-dialog").assertExists()
        dragUp("Three")
        compose.onNodeWithText("Отмена").performClick()
        assertNull(saved)

        compose.onNodeWithText("Изменить порядок").performClick()
        dragUp("Three")
        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(listOf("one", "three", "two"), saved)
        assertEquals(listOf("one", "two", "three"), expectedOriginal)
        assertEquals(
            mapOf("one" to "one.md", "two" to "two.md", "three" to "three.md"),
            chapters.associate { it.id to it.path },
        )
    }

    @Test
    fun longPressDragMovesAChapterWithoutChangingItsIdentity() {
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
                onClose = {}, onChapterSelected = {}, onQueryChanged = {},
                onSearchResult = {}, onOpenBooks = {}, onAppearance = {},
                onSaveOrder = { _, order -> saved = order },
            )
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        val threeBounds = compose.onNodeWithText("Three").fetchSemanticsNode().boundsInRoot
        val twoBounds = compose.onNodeWithText("Two").fetchSemanticsNode().boundsInRoot
        val upwardDistance = threeBounds.center.y - twoBounds.center.y
        compose.onNodeWithText("Three").performTouchInput {
            down(center)
            advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
            moveTo(center.copy(y = center.y - upwardDistance))
            up()
        }
        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(listOf("one", "three", "two"), saved)
        assertEquals("three.md", chapters.single { it.id == "three" }.path)
    }

    @Test
    fun canonicalAdditionDuringEditKeepsDraftAndNewChapterCanMoveAndSave() {
        var saved: List<String>? = null
        var expectedOriginal: List<String>? = null
        val chapters = mutableStateOf(
            listOf(
                BookChapter("one", "one.md", "One", true),
                BookChapter("two", "two.md", "Two", true),
                BookChapter("three", "three.md", "Three", true),
            ),
        )
        compose.setContent {
            ContentsPanel(
                books = listOf(BookSummary(BOOK_ID, "Book", "disk:/Book", chapters.value)),
                currentBookId = BOOK_ID,
                currentChapterId = "one",
                query = "",
                searchResults = emptyList(),
                searching = false,
                closeLabel = "Закрыть",
                onClose = {}, onChapterSelected = {}, onQueryChanged = {},
                onSearchResult = {}, onOpenBooks = {}, onAppearance = {},
                onSaveOrder = { expected, order -> expectedOriginal = expected; saved = order },
            )
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        dragUp("Three")
        compose.runOnIdle {
            chapters.value = chapters.value + BookChapter("four", "four.md", "Four", false)
        }
        compose.onNodeWithText("Four").assertExists()
        dragUp("Four")
        compose.onNodeWithText("Сохранить").performClick()

        compose.runOnIdle {
            assertEquals(listOf("one", "two", "three", "four"), expectedOriginal)
            assertEquals(listOf("one", "three", "four", "two"), saved)
        }
    }

    private fun dragUp(title: String) {
        val node = compose.onNodeWithText(title)
        val height = node.fetchSemanticsNode().boundsInRoot.height
        node.performTouchInput {
            down(center)
            advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
            moveTo(center.copy(y = center.y - height * 2f))
            up()
        }
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
    }
}
