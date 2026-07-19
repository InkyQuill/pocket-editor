package net.inkyquill.pocketeditor.ui.review

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.inkyquill.pocketeditor.anchor.AnchorFactory
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.markdown.RenderedDocument
import net.inkyquill.pocketeditor.markdown.SelectionMapper
import net.inkyquill.pocketeditor.markdown.TextRange
import net.inkyquill.pocketeditor.reader.PendingDeletion
import net.inkyquill.pocketeditor.reader.ReaderEditItem
import net.inkyquill.pocketeditor.reader.ReaderSignalItem
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.sync.ConflictChoice

interface EditorialReviewActions {
    suspend fun saveSignal(signal: Signal)
    suspend fun saveEdit(edit: Edit)
    suspend fun saveChapterNote(text: String)
    suspend fun deleteSignal(id: String): PendingDeletion
    suspend fun deleteEdit(id: String): PendingDeletion
    suspend fun undoDeletion(token: PendingDeletion)
    suspend fun finalizeDeletion(token: PendingDeletion)
    suspend fun reanchor(recordId: String, anchor: Anchor)
    suspend fun resolveReview(path: String, choices: Map<String, ConflictChoice>)
    suspend fun resolveManifest(choice: ConflictChoice)
}

class EditorialReviewController(
    private val bookId: String,
    private val chapterId: String,
    private val renderedDocument: () -> RenderedDocument,
    private val occupiedEditRanges: () -> List<RawRange>,
    private val actions: EditorialReviewActions,
    private val drafts: ReviewDraftStore,
    private val scope: CoroutineScope,
    private val uuid: () -> UUID = UUID::randomUUID,
    private val noteDebounceMillis: Long = 450,
    private val undoWindowMillis: Long = 5_000,
) {
    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()
    private var noteJob: Job? = null
    private var deletionJob: Job? = null
    private var pendingDeletion: PendingDeletion? = null
    private var pendingReanchorId: String? = null

    suspend fun restore() {
        val restored = drafts.load(bookId, chapterId)
        if (restored != null) mutableState.update { it.copy(draftSession = restored) }
    }

    suspend fun select(blockIndex: Int, start: Int, end: Int) {
        val rendered = renderedDocument()
        val raw = SelectionMapper.toRawRange(rendered, TextRange(blockIndex, start, end))
        if (raw == null) {
            mutableState.update { it.copy(draftSession = ReviewDraftStateMachine.invalidSelection()) }
            return
        }
        val selectedText = rendered.sourceBytes.copyOfRange(raw.startByte, raw.endByte).decodeToString()
        val reanchorId = pendingReanchorId
        if (reanchorId != null) {
            actions.reanchor(reanchorId, AnchorFactory.create(rendered.sourceBytes, raw.startByte, raw.endByte))
            pendingReanchorId = null
            mutableState.update { it.copy(reanchorRecordId = null) }
            return
        }
        mutableState.update {
            it.copy(draftSession = ReviewDraftStateMachine.select(ReviewSelection(blockIndex, start, end, raw, selectedText)))
        }
    }

    suspend fun chooseSignal(type: SignalType) = updateDraft {
        ReviewDraftStateMachine.chooseSignal(it, type)
    }

    suspend fun chooseEdit() = updateDraft {
        ReviewDraftStateMachine.chooseEdit(it, occupiedEditRanges())
    }

    suspend fun editSignal(item: ReaderSignalItem) {
        val anchor = requireNotNull(item.anchor) { "A saved signal anchor is required" }
        updateDraft {
            ReviewDraftSession(
                draft = ReviewDraft.Signal(
                    recordId = item.id,
                    selection = anchor.selection(item.selectedText),
                    type = item.type,
                    comment = item.comment,
                    savedType = item.type,
                    savedComment = item.comment,
                ),
            )
        }
    }

    suspend fun editEdit(item: ReaderEditItem) {
        val anchor = requireNotNull(item.anchor) { "A saved edit anchor is required" }
        updateDraft {
            ReviewDraftSession(
                draft = ReviewDraft.Edit(
                    recordId = item.id,
                    selection = anchor.selection(item.before),
                    after = item.after,
                    savedAfter = item.after,
                ),
                occupiedEditRanges = occupiedEditRanges().filterNot { range ->
                    range.startByte == anchor.startByte.toInt() && range.endByte == anchor.endByte.toInt()
                },
            )
        }
    }

    suspend fun changeSignalType(type: SignalType) = updateDraft {
        ReviewDraftStateMachine.changeSignalType(it, type)
    }

    suspend fun changeDraftText(text: String) = updateDraft {
        when (it.draft) {
            is ReviewDraft.Signal -> ReviewDraftStateMachine.changeComment(it, text)
            is ReviewDraft.Edit -> ReviewDraftStateMachine.changeEdit(it, text)
            null -> it
        }
    }

    suspend fun saveDraft() {
        val session = mutableState.value.draftSession
        check(ReviewDraftStateMachine.validate(session) == DraftValidation.Valid) { "Review draft is not valid" }
        val rendered = renderedDocument()
        when (val draft = requireNotNull(session.draft)) {
            is ReviewDraft.Signal -> actions.saveSignal(
                Signal(
                    id = draft.recordId ?: uuid().toString(),
                    type = draft.type,
                    selectedText = draft.selection.selectedText,
                    anchor = AnchorFactory.create(
                        rendered.sourceBytes,
                        draft.selection.rawRange.startByte,
                        draft.selection.rawRange.endByte,
                    ),
                    comment = draft.comment,
                ),
            )
            is ReviewDraft.Edit -> actions.saveEdit(
                Edit(
                    id = draft.recordId ?: uuid().toString(),
                    before = draft.selection.selectedText,
                    after = draft.after,
                    anchor = AnchorFactory.create(rendered.sourceBytes, draft.rawRange.startByte, draft.rawRange.endByte),
                ),
            )
        }
        drafts.clear(bookId, chapterId)
        mutableState.update { it.copy(draftSession = ReviewDraftSession()) }
    }

    suspend fun cancelDraft() {
        drafts.clear(bookId, chapterId)
        mutableState.update { it.copy(draftSession = ReviewDraftStateMachine.cancel(it.draftSession)) }
    }

    fun changeChapterNote(text: String) {
        mutableState.update { it.copy(chapterNote = text, noteSaveStatus = NoteSaveStatus.WAITING) }
        noteJob?.cancel()
        noteJob = scope.launch {
            delay(noteDebounceMillis)
            actions.saveChapterNote(text)
        }
    }

    suspend fun chapterNoteFocusLost() {
        noteJob?.cancel()
        actions.saveChapterNote(mutableState.value.chapterNote)
    }

    suspend fun deleteSignal(id: String) = beginDeletion(actions.deleteSignal(id))
    suspend fun deleteEdit(id: String) = beginDeletion(actions.deleteEdit(id))

    suspend fun undoDeletion(tokenId: String) {
        val token = pendingDeletion?.takeIf { it.tokenId == tokenId } ?: return
        deletionJob?.cancel()
        actions.undoDeletion(token)
        pendingDeletion = null
        mutableState.update { it.copy(pendingDeletion = null) }
    }

    fun beginReanchor(recordId: String) {
        pendingReanchorId = recordId
        mutableState.update { it.copy(reanchorRecordId = recordId) }
    }

    fun showConflicts(conflicts: List<ConflictCard>) {
        mutableState.update { it.copy(conflicts = conflicts) }
    }

    suspend fun chooseConflict(recordId: String, choice: ConflictChoice) {
        val current = mutableState.value.conflicts
        val selected = current.singleOrNull { it.recordId == recordId }
            ?: throw IllegalArgumentException("Unknown conflict: $recordId")
        val updated = current.map { if (it.recordId == recordId) it.copy(selectedChoice = choice) else it }
        mutableState.update { it.copy(conflicts = updated) }
        if (selected.manifest) {
            actions.resolveManifest(choice)
            mutableState.update { it.copy(conflicts = it.conflicts.filterNot(ConflictCard::manifest)) }
            return
        }
        val siblings = updated.filter { !it.manifest && it.path == selected.path }
        if (siblings.all { it.selectedChoice != null }) {
            actions.resolveReview(selected.path, siblings.associate { it.recordId to requireNotNull(it.selectedChoice) })
            mutableState.update { state -> state.copy(conflicts = state.conflicts.filterNot { !it.manifest && it.path == selected.path }) }
        }
    }

    private suspend fun updateDraft(transform: (ReviewDraftSession) -> ReviewDraftSession) {
        val updated = transform(mutableState.value.draftSession)
        if (updated.draft != null) drafts.save(bookId, chapterId, updated)
        mutableState.update { it.copy(draftSession = updated) }
    }

    private fun beginDeletion(token: PendingDeletion) {
        deletionJob?.cancel()
        pendingDeletion = token
        mutableState.update { it.copy(pendingDeletion = token.tokenId) }
        deletionJob = scope.launch {
            delay(undoWindowMillis)
            actions.finalizeDeletion(token)
            if (pendingDeletion == token) {
                pendingDeletion = null
                mutableState.update { it.copy(pendingDeletion = null) }
            }
        }
    }

    private fun Anchor.selection(text: String) = ReviewSelection(
        blockIndex = -1,
        renderedStart = 0,
        renderedEnd = text.length,
        rawRange = RawRange(Math.toIntExact(startByte), Math.toIntExact(endByte)),
        selectedText = text,
    )
}
