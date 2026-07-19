package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.review.SignalType

data class ReviewSelection(
    val blockIndex: Int,
    val renderedStart: Int,
    val renderedEnd: Int,
    val rawRange: RawRange,
    val selectedText: String,
)

sealed interface ReviewDraft {
    val recordId: String?
    val selection: ReviewSelection

    data class Signal(
        override val recordId: String?,
        override val selection: ReviewSelection,
        val type: SignalType,
        val comment: String,
        val savedType: SignalType? = null,
        val savedComment: String? = null,
    ) : ReviewDraft

    data class Edit(
        override val recordId: String?,
        override val selection: ReviewSelection,
        val after: String,
        val savedAfter: String? = null,
        val rawRange: RawRange = selection.rawRange,
    ) : ReviewDraft
}

data class ReviewDraftSession(
    val draft: ReviewDraft? = null,
    val pendingSelection: ReviewSelection? = null,
    val occupiedEditRanges: List<RawRange> = emptyList(),
    val selectionProblem: String? = null,
) {
    val isDirty: Boolean
        get() = when (val value = draft) {
            null -> false
            is ReviewDraft.Signal -> value.savedType == null || value.type != value.savedType || value.comment != value.savedComment
            is ReviewDraft.Edit -> value.savedAfter == null || value.after != value.savedAfter
        }
    val blocksDismissal: Boolean get() = isDirty
    val canChooseAction: Boolean get() = pendingSelection != null && selectionProblem == null
}

sealed interface DraftValidation {
    data object Valid : DraftValidation
    data object Unchanged : DraftValidation
    data class Overlapping(val ranges: List<RawRange>) : DraftValidation
}

object ReviewDraftStateMachine {
    fun select(selection: ReviewSelection) = ReviewDraftSession(pendingSelection = selection)

    fun invalidSelection() = ReviewDraftSession(
        selectionProblem = "Select one complete prose span without splitting Markdown formatting.",
    )

    fun chooseSignal(session: ReviewDraftSession, type: SignalType): ReviewDraftSession {
        val selection = requireNotNull(session.pendingSelection)
        return session.copy(
            draft = ReviewDraft.Signal(null, selection, type, ""),
            pendingSelection = null,
        )
    }

    fun chooseEdit(session: ReviewDraftSession, occupiedEditRanges: List<RawRange>): ReviewDraftSession {
        val selection = requireNotNull(session.pendingSelection)
        return session.copy(
            draft = ReviewDraft.Edit(null, selection, selection.selectedText),
            pendingSelection = null,
            occupiedEditRanges = occupiedEditRanges,
        )
    }

    fun changeSignalType(session: ReviewDraftSession, type: SignalType): ReviewDraftSession =
        session.copy(draft = requireSignal(session).copy(type = type))

    fun changeComment(session: ReviewDraftSession, comment: String): ReviewDraftSession =
        session.copy(draft = requireSignal(session).copy(comment = comment))

    fun changeEdit(session: ReviewDraftSession, after: String): ReviewDraftSession =
        session.copy(draft = requireEdit(session).copy(after = after))

    fun validate(session: ReviewDraftSession): DraftValidation = when (val draft = session.draft) {
        null, is ReviewDraft.Signal -> DraftValidation.Valid
        is ReviewDraft.Edit -> when {
            draft.after == draft.selection.selectedText -> DraftValidation.Unchanged
            else -> session.occupiedEditRanges.filter(draft.rawRange::intersects).let { overlaps ->
                if (overlaps.isEmpty()) DraftValidation.Valid else DraftValidation.Overlapping(overlaps)
            }
        }
    }

    fun cancel(session: ReviewDraftSession): ReviewDraftSession = when (val draft = session.draft) {
        null -> ReviewDraftSession()
        is ReviewDraft.Signal -> if (draft.savedType == null) {
            ReviewDraftSession()
        } else {
            session.copy(draft = draft.copy(type = draft.savedType, comment = draft.savedComment.orEmpty()))
        }
        is ReviewDraft.Edit -> if (draft.savedAfter == null) {
            ReviewDraftSession()
        } else {
            session.copy(draft = draft.copy(after = draft.savedAfter))
        }
    }

    private fun requireSignal(session: ReviewDraftSession) =
        requireNotNull(session.draft as? ReviewDraft.Signal) { "A signal draft is required" }

    private fun requireEdit(session: ReviewDraftSession) =
        requireNotNull(session.draft as? ReviewDraft.Edit) { "An edit draft is required" }
}
