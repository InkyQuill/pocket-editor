package net.inkyquill.pocketeditor.ui.review

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.reader.PendingDeletion
import net.inkyquill.pocketeditor.reader.ReaderSignalItem
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.sync.ConflictChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorialReviewControllerTest {
    @Test
    fun `safe selection saves anchored signal and clears persisted draft only after success`() = runBlocking {
        val source = "A *quiet* road."
        val rendered = MarkdownParser.parse(source)
        val persistence = MemoryDraftPersistence()
        val actions = FakeActions()
        val controller = controller(rendered, actions, persistence)

        controller.select(blockIndex = 0, start = 2, end = 7)
        controller.chooseSignal(SignalType.NOTE)
        controller.changeDraftText("Keep this")
        controller.saveDraft()

        assertEquals("*quiet*", actions.signal?.selectedText)
        assertEquals("Keep this", actions.signal?.comment)
        assertNull(persistence.entity)
        assertNull(controller.state.value.draftSession.draft)
    }

    @Test
    fun `syntax splitting selection explains disabled action and does not create draft`() = runBlocking {
        val rendered = MarkdownParser.parse("A *quiet* road.")
        val controller = controller(rendered, FakeActions(), MemoryDraftPersistence())

        controller.select(blockIndex = 0, start = 1, end = 4)

        assertFalse(controller.state.value.draftSession.canChooseAction)
        assertTrue(controller.state.value.draftSession.selectionProblem!!.contains("Markdown"))
    }

    @Test
    fun `restore recreates a dirty composer after a new controller process`() = runBlocking {
        val rendered = MarkdownParser.parse("Plain road")
        val persistence = MemoryDraftPersistence()
        val first = controller(rendered, FakeActions(), persistence)
        first.select(0, 0, 5)
        first.chooseEdit()
        first.changeDraftText("Rough")

        val recreated = controller(rendered, FakeActions(), persistence)
        recreated.restore()

        assertEquals("Rough", (recreated.state.value.draftSession.draft as ReviewDraft.Edit).after)
        assertTrue(recreated.state.value.draftSession.blocksDismissal)
    }

    @Test
    fun `failed atomic save keeps draft persisted for retry`() = runBlocking {
        val rendered = MarkdownParser.parse("Plain road")
        val persistence = MemoryDraftPersistence()
        val actions = FakeActions().apply { failSignal = true }
        val controller = controller(rendered, actions, persistence)
        controller.select(0, 0, 5)
        controller.chooseSignal(SignalType.NOTE)

        runCatching { controller.saveDraft() }

        assertTrue(controller.state.value.draftSession.blocksDismissal)
        assertTrue(persistence.entity != null)
    }

    @Test
    fun `editing a saved signal keeps its id and cancel can restore saved values`() = runBlocking {
        val rendered = MarkdownParser.parse("Plain road")
        val persistence = MemoryDraftPersistence()
        val actions = FakeActions()
        val controller = controller(rendered, actions, persistence)

        controller.editSignal(
            ReaderSignalItem(
                "saved-id",
                SignalType.WARNING,
                "Plain",
                "Saved",
                net.inkyquill.pocketeditor.anchor.AnchorFactory.create(rendered.sourceBytes, 0, 5),
            ),
        )
        controller.changeSignalType(SignalType.REVIEW)
        controller.changeDraftText("Changed")
        controller.saveDraft()

        assertEquals("saved-id", actions.signal?.id)
        assertEquals(SignalType.REVIEW, actions.signal?.type)
    }

    @Test
    fun `explicit reanchor waits for one exact selection then delegates anchor`() = runBlocking {
        val rendered = MarkdownParser.parse("Plain road")
        val actions = FakeActions()
        val controller = controller(rendered, actions, MemoryDraftPersistence())

        controller.beginReanchor("stale-1")
        controller.select(0, 0, 5)

        assertEquals("stale-1", actions.reanchored.first().first)
        assertNull(controller.state.value.reanchorRecordId)
    }

    @Test
    fun `chapter note debounces and focus loss flushes immediately`() = runBlocking {
        val actions = FakeActions()
        val controller = controller(MarkdownParser.parse("Plain"), actions, MemoryDraftPersistence(), debounceMillis = 20)

        controller.changeChapterNote("One")
        controller.changeChapterNote("Two")
        delay(35)
        assertEquals(listOf("Two"), actions.notes)

        controller.changeChapterNote("Three")
        controller.chapterNoteFocusLost()
        assertEquals(listOf("Two", "Three"), actions.notes)
    }

    @Test
    fun `delete is pending until window and undo consumes durable token`() = runBlocking {
        val actions = FakeActions()
        val controller = controller(
            MarkdownParser.parse("Plain"), actions, MemoryDraftPersistence(), undoMillis = 30,
        )

        controller.deleteSignal("signal-1")
        assertEquals("token", controller.state.value.pendingDeletion)
        controller.undoDeletion("token")
        delay(40)

        assertEquals(listOf("token"), actions.undone)
        assertTrue(actions.finalized.isEmpty())
    }

    @Test
    fun `review conflict waits for all choices while manifest choice resolves whole file`() = runBlocking {
        val actions = FakeActions()
        val controller = controller(MarkdownParser.parse("Plain"), actions, MemoryDraftPersistence())
        controller.showConflicts(
            listOf(
                ConflictCard("review.json", "one", "mine", "remote"),
                ConflictCard("review.json", "two", "mine", "remote"),
                ConflictCard(".pocket-editor-book.json", "manifest", "mine", "remote", manifest = true),
            ),
        )

        controller.chooseConflict("one", ConflictChoice.KEEP_MINE)
        assertTrue(actions.reviewResolutions.isEmpty())
        controller.chooseConflict("two", ConflictChoice.KEEP_YANDEX)
        assertEquals(mapOf("one" to ConflictChoice.KEEP_MINE, "two" to ConflictChoice.KEEP_YANDEX), actions.reviewResolutions.single())
        controller.chooseConflict("manifest", ConflictChoice.KEEP_MINE)
        assertEquals(listOf(ConflictChoice.KEEP_MINE), actions.manifestResolutions)
    }

    private fun controller(
        rendered: net.inkyquill.pocketeditor.markdown.RenderedDocument,
        actions: FakeActions,
        persistence: MemoryDraftPersistence,
        debounceMillis: Long = 1,
        undoMillis: Long = 1,
    ) = EditorialReviewController(
        bookId = "book",
        chapterId = "chapter",
        renderedDocument = { rendered },
        occupiedEditRanges = { emptyList() },
        actions = actions,
        drafts = ReviewDraftStore(persistence),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        uuid = { UUID.fromString("11111111-1111-1111-1111-111111111111") },
        noteDebounceMillis = debounceMillis,
        undoWindowMillis = undoMillis,
    )

    private class FakeActions : EditorialReviewActions {
        var signal: Signal? = null
        var edit: Edit? = null
        val notes = mutableListOf<String>()
        val undone = mutableListOf<String>()
        val finalized = mutableListOf<String>()
        val reviewResolutions = mutableListOf<Map<String, ConflictChoice>>()
        val manifestResolutions = mutableListOf<ConflictChoice>()
        val reanchored = mutableListOf<Pair<String, Anchor>>()
        var failSignal = false

        override suspend fun saveSignal(signal: Signal) { if (failSignal) error("disk full"); this.signal = signal }
        override suspend fun saveEdit(edit: Edit) { this.edit = edit }
        override suspend fun saveChapterNote(text: String) { notes += text }
        override suspend fun deleteSignal(id: String) = PendingDeletion("token")
        override suspend fun deleteEdit(id: String) = PendingDeletion("token")
        override suspend fun undoDeletion(token: PendingDeletion) { undone += token.tokenId }
        override suspend fun finalizeDeletion(token: PendingDeletion) { finalized += token.tokenId }
        override suspend fun reanchor(recordId: String, anchor: Anchor) { reanchored += recordId to anchor }
        override suspend fun resolveReview(path: String, choices: Map<String, ConflictChoice>) { reviewResolutions += choices }
        override suspend fun resolveManifest(choice: ConflictChoice) { manifestResolutions += choice }
    }

    private class MemoryDraftPersistence : ReviewDraftPersistence {
        var entity: net.inkyquill.pocketeditor.database.DraftEntity? = null
        override suspend fun put(draft: net.inkyquill.pocketeditor.database.DraftEntity) { entity = draft }
        override suspend fun get(bookId: String, chapterId: String, draftType: String, recordKey: String) = entity
        override suspend fun delete(bookId: String, chapterId: String, draftType: String, recordKey: String) { entity = null }
    }
}
