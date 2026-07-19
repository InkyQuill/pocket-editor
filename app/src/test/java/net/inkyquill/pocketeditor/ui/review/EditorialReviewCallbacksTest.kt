package net.inkyquill.pocketeditor.ui.review

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.database.DraftEntity
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.reader.PendingDeletion
import net.inkyquill.pocketeditor.reader.ReaderSourceSelection
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.sync.ConflictChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EditorialReviewCallbacksTest {
    @Test
    fun `rapid callback keystrokes colors and save are serialized in arrival order`() = runBlocking {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val persistence = BlockingPersistence(firstWriteStarted, releaseFirstWrite)
        val actions = CapturingActions()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rendered = MarkdownParser.parse("Plain road")
        val controller = EditorialReviewController(
            "book",
            "chapter",
            { rendered },
            { emptyList() },
            actions,
            ReviewDraftStore(persistence),
            scope,
            uuid = { UUID.fromString("11111111-1111-1111-1111-111111111111") },
        )
        val callbacks = controller.readerCallbacks(scope)

        callbacks.onTextSelected(ReaderSourceSelection(RawRange(0, 5), "Plain"))
        callbacks.onSignalChosen(SignalType.NOTE)
        firstWriteStarted.await()
        callbacks.onSignalTypeChanged(SignalType.WARNING)
        callbacks.onDraftTextChanged("Latest text")
        callbacks.onSaveDraft()
        releaseFirstWrite.complete(Unit)
        repeat(50) {
            if (actions.signal != null) return@repeat
            delay(10)
        }

        assertEquals(SignalType.WARNING, actions.signal?.type)
        assertEquals("Latest text", actions.signal?.comment)
        assertNull(controller.state.value.error)
    }

    private class BlockingPersistence(
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : ReviewDraftPersistence {
        private var first = true
        private var value: DraftEntity? = null
        override suspend fun put(draft: DraftEntity) {
            if (first) {
                first = false
                started.complete(Unit)
                release.await()
            }
            value = draft
        }
        override suspend fun get(bookId: String, chapterId: String, draftType: String, recordKey: String) = value
        override suspend fun delete(bookId: String, chapterId: String, draftType: String, recordKey: String) { value = null }
    }

    private class CapturingActions : EditorialReviewActions {
        var signal: Signal? = null
        override suspend fun saveSignal(signal: Signal) { this.signal = signal }
        override suspend fun saveEdit(edit: Edit) = Unit
        override suspend fun saveChapterNote(text: String) = Unit
        override suspend fun deleteSignal(id: String) = PendingDeletion("signal", 0)
        override suspend fun deleteEdit(id: String) = PendingDeletion("edit", 0)
        override suspend fun pendingDeletions() = emptyList<PendingDeletion>()
        override suspend fun undoDeletion(token: PendingDeletion) = Unit
        override suspend fun finalizeDeletion(token: PendingDeletion) = Unit
        override suspend fun reanchor(recordId: String, anchor: Anchor) = Unit
        override suspend fun resolveReview(path: String, choices: Map<String, ConflictChoice>) = Unit
        override suspend fun resolveManifest(choice: ConflictChoice) = Unit
    }
}
