package net.inkyquill.pocketeditor.ui.review

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.inkyquill.pocketeditor.anchor.AnchorFactory
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.markdown.RenderedDocument
import net.inkyquill.pocketeditor.markdown.SelectionMapper
import net.inkyquill.pocketeditor.markdown.TextRange
import net.inkyquill.pocketeditor.reader.PendingDeletion
import net.inkyquill.pocketeditor.reader.ReaderEditItem
import net.inkyquill.pocketeditor.reader.ReaderSignalItem
import net.inkyquill.pocketeditor.reader.ReaderSourceSelection
import net.inkyquill.pocketeditor.reader.ReaderSyncState
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
    suspend fun pendingDeletions(): List<PendingDeletion>
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
    private val deletionRetryMillis: Long = 30_000,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()
    private val mutationMutex = Mutex()
    private var noteJob: Job? = null
    private var pendingChapterNote: String? = null
    private val deletionJobs = mutableMapOf<String, Job>()
    private val failedDeletionTokens = linkedSetOf<String>()
    private val pendingDeletionTokens = linkedMapOf<String, PendingDeletion>()
    private var pendingReanchorId: String? = null
    private var lastRetry: (suspend () -> Unit)? = null

    suspend fun restore(chapterNote: String? = null, syncState: ReaderSyncState? = null) = serialized("Restore review") {
        val restored = drafts.load(bookId, chapterId)
        mutableState.update {
            it.copy(
                draftSession = restored ?: it.draftSession,
                chapterNote = chapterNote ?: it.chapterNote,
                noteSaveStatus = syncState?.noteStatus() ?: it.noteSaveStatus,
                error = drafts.lastLoadError?.let { ReviewUiError(it, retryable = false) },
            )
        }
        for (token in actions.pendingDeletions()) {
            if (token.chapterId == null || token.chapterId == chapterId) restoreDeletionLocked(token)
        }
    }

    suspend fun select(blockIndex: Int, start: Int, end: Int): Unit = serialized("Select passage", retry = { select(blockIndex, start, end) }) {
        val rendered = renderedDocument()
        val raw = SelectionMapper.toRawRange(rendered, TextRange(blockIndex, start, end))
        selectLocked(raw?.let {
            ReaderSourceSelection(it, rendered.sourceBytes.copyOfRange(it.startByte, it.endByte).decodeToString())
        })
    }

    suspend fun select(selection: ReaderSourceSelection?): Unit = serialized("Select passage", retry = { select(selection) }) { selectLocked(selection) }

    suspend fun chooseSignal(type: SignalType): Unit = serialized("Create signal", retry = { chooseSignal(type) }) {
        updateDraftLocked { ReviewDraftStateMachine.chooseSignal(it, type) }
    }

    suspend fun chooseEdit(): Unit = serialized("Create edit", retry = { chooseEdit() }) {
        updateDraftLocked { ReviewDraftStateMachine.chooseEdit(it, occupiedEditRanges()) }
    }

    suspend fun editSignal(item: ReaderSignalItem): Unit = serialized("Edit signal", retry = { editSignal(item) }) {
        val anchor = requireNotNull(item.anchor) { "A saved signal anchor is required" }
        updateDraftLocked {
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

    suspend fun editEdit(item: ReaderEditItem): Unit = serialized("Edit replacement", retry = { editEdit(item) }) {
        val anchor = requireNotNull(item.anchor) { "A saved edit anchor is required" }
        updateDraftLocked {
            ReviewDraftSession(
                draft = ReviewDraft.Edit(item.id, anchor.selection(item.before), item.after, item.after),
                occupiedEditRanges = occupiedEditRanges().filterNot { range ->
                    range.startByte == anchor.startByte.toInt() && range.endByte == anchor.endByte.toInt()
                },
            )
        }
    }

    suspend fun changeSignalType(type: SignalType): Unit = serialized("Change signal color", retry = { changeSignalType(type) }) {
        updateDraftLocked { ReviewDraftStateMachine.changeSignalType(it, type) }
    }

    suspend fun changeDraftText(text: String): Unit = serialized("Update review draft", retry = { changeDraftText(text) }) {
        updateDraftLocked {
            when (it.draft) {
                is ReviewDraft.Signal -> ReviewDraftStateMachine.changeComment(it, text)
                is ReviewDraft.Edit -> ReviewDraftStateMachine.changeEdit(it, text)
                null -> it
            }
        }
    }

    suspend fun saveDraft(): Unit = serialized("Save review item", retry = { saveDraft() }) { saveDraftLocked() }

    suspend fun cancelDraft(): Unit = serialized("Cancel review edit", retry = { cancelDraft() }) {
        drafts.clear(bookId, chapterId)
        mutableState.update { it.copy(draftSession = ReviewDraftStateMachine.cancel(it.draftSession), error = null) }
    }

    suspend fun changeChapterNote(text: String): Unit = serialized("Update chapter note", retry = { changeChapterNote(text) }) {
        pendingChapterNote = text
        mutableState.update { it.copy(chapterNote = text, noteSaveStatus = NoteSaveStatus.SAVING, error = null) }
        noteJob?.cancel()
        noteJob = scope.launch {
            delay(noteDebounceMillis)
            saveChapterNote(text)
        }
    }

    suspend fun chapterNoteFocusLost() {
        noteJob?.cancel()
        saveChapterNote(mutableState.value.chapterNote)
    }

    suspend fun updateChapterContext(text: String, syncState: ReaderSyncState) = serialized("Refresh chapter state") {
        val pending = pendingChapterNote
        val current = mutableState.value
        when {
            pending == null -> mutableState.update { it.copy(chapterNote = text, noteSaveStatus = syncState.noteStatus()) }
            current.noteSaveStatus == NoteSaveStatus.ERROR -> Unit
            text != pending -> Unit
            else -> {
                val status = syncState.noteStatus()
                if (status == NoteSaveStatus.SAVED) pendingChapterNote = null
                mutableState.update { it.copy(chapterNote = pending, noteSaveStatus = status) }
            }
        }
    }

    suspend fun deleteSignal(id: String) = serialized("Delete signal") { beginDeletionLocked(actions.deleteSignal(id)) }
    suspend fun deleteEdit(id: String) = serialized("Delete edit") { beginDeletionLocked(actions.deleteEdit(id)) }

    suspend fun undoDeletion(tokenId: String): Unit = serialized("Undo deletion", retry = { undoDeletion(tokenId) }) {
        val token = pendingDeletionTokens[tokenId] ?: return@serialized
        deletionJobs.remove(tokenId)?.cancel()
        actions.undoDeletion(token)
        removeDeletionLocked(tokenId)
    }

    suspend fun beginReanchor(recordId: String) = serialized("Begin re-anchor") {
        pendingReanchorId = recordId
        mutableState.update { it.copy(reanchorRecordId = recordId) }
    }

    suspend fun showConflicts(conflicts: List<ConflictCard>) = serialized("Show conflicts") {
        mutableState.update { it.copy(conflicts = conflicts) }
    }

    suspend fun chooseConflict(recordId: String, choice: ConflictChoice) = serialized("Resolve conflict") {
        val current = mutableState.value.conflicts
        val selected = current.singleOrNull { it.recordId == recordId }
            ?: throw IllegalArgumentException("Unknown conflict: $recordId")
        val updated = current.map { if (it.recordId == recordId) it.copy(selectedChoice = choice) else it }
        mutableState.update { it.copy(conflicts = updated) }
        if (selected.manifest) {
            actions.resolveManifest(choice)
            mutableState.update { it.copy(conflicts = it.conflicts.filterNot(ConflictCard::manifest)) }
        } else {
            val siblings = updated.filter { !it.manifest && it.path == selected.path }
            if (siblings.all { it.selectedChoice != null }) {
                actions.resolveReview(selected.path, siblings.associate { it.recordId to requireNotNull(it.selectedChoice) })
                mutableState.update { state -> state.copy(conflicts = state.conflicts.filterNot { !it.manifest && it.path == selected.path }) }
            }
        }
    }

    suspend fun retryLastFailure() {
        val retry = mutationMutex.withLock {
            val value = lastRetry
            lastRetry = null
            mutableState.update { it.copy(error = null) }
            value
        }
        retry?.invoke()
    }

    private suspend fun selectLocked(selection: ReaderSourceSelection?) {
        if (selection == null) {
            mutableState.update { it.copy(draftSession = ReviewDraftStateMachine.invalidSelection()) }
            return
        }
        val rendered = renderedDocument()
        val raw = selection.rawRange
        val selectedText = rendered.sourceBytes.copyOfRange(raw.startByte, raw.endByte).decodeToString()
        val reanchorId = pendingReanchorId
        if (reanchorId != null) {
            actions.reanchor(reanchorId, AnchorFactory.create(rendered.sourceBytes, raw.startByte, raw.endByte))
            pendingReanchorId = null
            mutableState.update { it.copy(reanchorRecordId = null) }
            return
        }
        mutableState.update {
            it.copy(draftSession = ReviewDraftStateMachine.select(ReviewSelection(-1, 0, selection.selectedText.length, raw, selectedText)))
        }
    }

    private suspend fun saveDraftLocked() {
        var session = mutableState.value.draftSession
        check(ReviewDraftStateMachine.validate(session) == DraftValidation.Valid) { "Review draft is not valid" }
        val current = requireNotNull(session.draft)
        if (current.recordId == null) {
            val assigned = uuid().toString()
            val identified = when (current) {
                is ReviewDraft.Signal -> current.copy(recordId = assigned)
                is ReviewDraft.Edit -> current.copy(recordId = assigned)
            }
            session = session.copy(draft = identified)
            drafts.save(bookId, chapterId, session)
            mutableState.update { it.copy(draftSession = session) }
        }
        val rendered = renderedDocument()
        when (val draft = requireNotNull(session.draft)) {
            is ReviewDraft.Signal -> actions.saveSignal(
                Signal(
                    id = requireNotNull(draft.recordId),
                    type = draft.type,
                    selectedText = draft.selection.selectedText,
                    anchor = AnchorFactory.create(rendered.sourceBytes, draft.selection.rawRange.startByte, draft.selection.rawRange.endByte),
                    comment = draft.comment,
                ),
            )
            is ReviewDraft.Edit -> actions.saveEdit(
                Edit(
                    id = requireNotNull(draft.recordId),
                    before = draft.selection.selectedText,
                    after = draft.after,
                    anchor = AnchorFactory.create(rendered.sourceBytes, draft.rawRange.startByte, draft.rawRange.endByte),
                ),
            )
        }
        drafts.clear(bookId, chapterId)
        mutableState.update { it.copy(draftSession = ReviewDraftSession(), error = null) }
    }

    private suspend fun saveChapterNote(text: String): Unit = serialized("Save chapter note", retry = { saveChapterNote(text) }) {
        actions.saveChapterNote(text)
        mutableState.update { it.copy(noteSaveStatus = NoteSaveStatus.WAITING, error = null) }
    }

    private suspend fun updateDraftLocked(transform: (ReviewDraftSession) -> ReviewDraftSession) {
        val updated = transform(mutableState.value.draftSession)
        if (updated.draft != null) drafts.save(bookId, chapterId, updated)
        mutableState.update { it.copy(draftSession = updated, error = null) }
    }

    private fun beginDeletionLocked(token: PendingDeletion) {
        pendingDeletionTokens[token.tokenId] = token
        publishPendingDeletions()
        scheduleDeletionLocked(token, (token.createdAt + undoWindowMillis - currentTimeMillis()).coerceAtLeast(0))
    }

    private suspend fun restoreDeletionLocked(token: PendingDeletion) {
        pendingDeletionTokens[token.tokenId] = token
        val remaining = token.createdAt + undoWindowMillis - currentTimeMillis()
        if (remaining <= 0) {
            publishPendingDeletions()
            attemptFinalizeDeletionLocked(token)
        } else {
            publishPendingDeletions()
            scheduleDeletionLocked(token, remaining)
        }
    }

    private fun scheduleDeletionLocked(token: PendingDeletion, delayMillis: Long, replaceExisting: Boolean = true) {
        if (replaceExisting) deletionJobs.remove(token.tokenId)?.cancel()
        deletionJobs[token.tokenId] = scope.launch {
            delay(delayMillis)
            attemptFinalizeDeletion(token.tokenId)
        }
    }

    private suspend fun attemptFinalizeDeletion(tokenId: String) {
        mutationMutex.withLock {
            pendingDeletionTokens[tokenId]?.let { attemptFinalizeDeletionLocked(it) }
        }
    }

    private suspend fun attemptFinalizeDeletionLocked(token: PendingDeletion) {
        try {
            actions.finalizeDeletion(token)
            failedDeletionTokens.remove(token.tokenId)
            removeDeletionLocked(token.tokenId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failedDeletionTokens += token.tokenId
            lastRetry = { retryFailedDeletions() }
            mutableState.update {
                it.copy(error = ReviewUiError("Finalize deletion failed: ${failure.message ?: failure::class.simpleName}"))
            }
            scheduleDeletionLocked(token, deletionRetryMillis, replaceExisting = false)
        }
    }

    private suspend fun retryFailedDeletions() {
        mutationMutex.withLock {
            failedDeletionTokens.toList().forEach { tokenId ->
                val token = pendingDeletionTokens[tokenId] ?: return@forEach
                scheduleDeletionLocked(token, 0, replaceExisting = true)
            }
        }
    }

    private fun removeDeletionLocked(tokenId: String) {
        deletionJobs.remove(tokenId)?.cancel()
        failedDeletionTokens.remove(tokenId)
        pendingDeletionTokens.remove(tokenId)
        publishPendingDeletions()
    }

    private fun publishPendingDeletions() {
        mutableState.update { it.copy(pendingDeletions = pendingDeletionTokens.keys.toList()) }
    }

    private suspend fun serialized(
        operation: String,
        retry: (suspend () -> Unit)? = null,
        block: suspend () -> Unit,
    ) {
        mutationMutex.withLock {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastRetry = retry
                if (operation == "Save chapter note") {
                    mutableState.update { it.copy(noteSaveStatus = NoteSaveStatus.ERROR) }
                }
                mutableState.update {
                    it.copy(error = ReviewUiError(
                        "$operation failed: ${failure.message ?: failure::class.simpleName}",
                        retryable = retry != null,
                    ))
                }
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

    private fun ReaderSyncState.noteStatus() = when (this) {
        ReaderSyncState.SAVED -> NoteSaveStatus.SAVED
        ReaderSyncState.WAITING_TO_SYNC, ReaderSyncState.SYNCING -> NoteSaveStatus.WAITING
        ReaderSyncState.SIGN_IN_REQUIRED, ReaderSyncState.ACTION_REQUIRED -> NoteSaveStatus.ERROR
    }
}
