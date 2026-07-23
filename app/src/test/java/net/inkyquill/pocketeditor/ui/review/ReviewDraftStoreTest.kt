package net.inkyquill.pocketeditor.ui.review

import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.database.DraftEntity
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.review.SignalType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewDraftStoreTest {
    @Test
    fun `round trips dirty signal draft through process-safe persistence`() = runBlocking {
        val persistence = FakeDraftPersistence()
        val store = ReviewDraftStore(persistence, currentTimeMillis = { 42L })
        val session = ReviewDraftSession(
            ReviewDraft.Signal(
                recordId = "signal-1",
                selection = ReviewSelection(3, 2, 9, RawRange(21, 32), "passage"),
                type = SignalType.REVIEW,
                comment = "Check the cadence",
                savedType = SignalType.NOTE,
                savedComment = "",
            ),
        )

        store.save("book", "chapter", session)
        val restoredAfterNewProcess = ReviewDraftStore(persistence).load("book", "chapter")

        assertEquals(session, restoredAfterNewProcess)
        assertEquals(42L, persistence.value?.updatedAt)
    }

    @Test
    fun `clear removes only the chapter review composer`() = runBlocking {
        val persistence = FakeDraftPersistence()
        val store = ReviewDraftStore(persistence)
        store.save(
            "book",
            "chapter",
            ReviewDraftStateMachine.chooseSignal(
                ReviewDraftStateMachine.select(ReviewSelection(0, 0, 1, RawRange(0, 1), "A")),
                SignalType.WARNING,
            ),
        )

        store.clear("book", "chapter")

        assertNull(store.load("book", "chapter"))
        assertEquals(listOf("book|chapter|review_composer|active"), persistence.deleted)
    }

    @Test
    fun `malformed persisted draft is quarantined and reported instead of crashing restore`() = runBlocking {
        val persistence = FakeDraftPersistence().apply {
            value = DraftEntity("book", "chapter", "review_composer", null, "{broken", 0, 1, 1, "active")
        }
        val store = ReviewDraftStore(persistence)

        assertNull(store.load("book", "chapter"))
        assertTrue(store.lastLoadError?.contains("черновик", ignoreCase = true) == true)
        assertNull(persistence.value)
    }

    private class FakeDraftPersistence : ReviewDraftPersistence {
        var value: DraftEntity? = null
        val deleted = mutableListOf<String>()

        override suspend fun put(draft: DraftEntity) {
            value = draft
        }

        override suspend fun get(bookId: String, chapterId: String, draftType: String, recordKey: String) = value

        override suspend fun delete(bookId: String, chapterId: String, draftType: String, recordKey: String) {
            deleted += "$bookId|$chapterId|$draftType|$recordKey"
            value = null
        }
    }
}
