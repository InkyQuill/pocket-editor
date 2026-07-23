package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.review.SignalType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewDraftStateMachineTest {
    private val selection = ReviewSelection(2, 4, 11, RawRange(18, 29), "quiet words")

    @Test
    fun `signal choice opens persistent optional comment draft and color remains changeable`() {
        val selected = ReviewDraftStateMachine.select(selection)
        val composing = ReviewDraftStateMachine.chooseSignal(selected, SignalType.NOTE)
        val changed = ReviewDraftStateMachine.changeSignalType(composing, SignalType.REVIEW)

        val draft = assertInstanceOf(ReviewDraft.Signal::class.java, changed.draft)
        assertEquals(SignalType.REVIEW, draft.type)
        assertEquals("", draft.comment)
        assertTrue(changed.blocksDismissal)
    }

    @Test
    fun `edit starts prefilled and rejects unchanged or overlapping replacement`() {
        val composing = ReviewDraftStateMachine.chooseEdit(
            ReviewDraftStateMachine.select(selection),
            occupiedEditRanges = listOf(RawRange(40, 50)),
        )
        assertEquals("quiet words", assertInstanceOf(ReviewDraft.Edit::class.java, composing.draft).after)
        assertEquals(DraftValidation.Unchanged, ReviewDraftStateMachine.validate(composing))

        val moved = composing.copy(
            draft = assertInstanceOf(ReviewDraft.Edit::class.java, composing.draft).copy(
                after = "louder words",
                rawRange = RawRange(45, 48),
            ),
        )
        assertInstanceOf(DraftValidation.Overlapping::class.java, ReviewDraftStateMachine.validate(moved))
    }

    @Test
    fun `cancel closes both saved and new composers without mutating saved records`() {
        val existing = ReviewDraft.Signal(
            recordId = "signal-1",
            selection = selection,
            type = SignalType.WARNING,
            comment = "Saved",
            savedType = SignalType.WARNING,
            savedComment = "Saved",
        )
        val edited = ReviewDraftStateMachine.changeComment(
            ReviewDraftSession(existing, occupiedEditRanges = emptyList()),
            "Changed",
        )

        assertNull(ReviewDraftStateMachine.cancel(edited).draft)
        assertFalse(ReviewDraftStateMachine.cancel(edited).isDirty)

        val fresh = ReviewDraftStateMachine.chooseSignal(ReviewDraftStateMachine.select(selection), SignalType.NOTE)
        assertNull(ReviewDraftStateMachine.cancel(fresh).draft)
    }

    @Test
    fun `selection failure explains why review actions are unavailable`() {
        val unavailable = ReviewDraftStateMachine.invalidSelection()

        assertEquals(
            "Выберите один цельный фрагмент текста, не разрывая форматирование Markdown.",
            unavailable.selectionProblem,
        )
        assertFalse(unavailable.canChooseAction)
    }
}
