package net.inkyquill.pocketeditor.ui.review

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks

fun EditorialReviewController.readerCallbacks(
    scope: CoroutineScope,
    base: ReaderCallbacks = ReaderCallbacks(),
): ReaderCallbacks {
    val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    scope.launch {
        for (event in events) event()
    }
    fun enqueue(event: suspend () -> Unit) {
        events.trySend(event)
    }
    return base.copy(
        onTextSelected = { enqueue { select(it) } },
        onSignalChosen = { type -> enqueue { chooseSignal(type) } },
        onEditChosen = { enqueue { chooseEdit() } },
        onSignalTypeChanged = { type -> enqueue { changeSignalType(type) } },
        onDraftTextChanged = { text -> enqueue { changeDraftText(text) } },
        onSaveDraft = { enqueue { saveDraft() } },
        onCancelDraft = { enqueue { cancelDraft() } },
        onChapterNoteChanged = { text -> enqueue { changeChapterNote(text) } },
        onChapterNoteFocusLost = { enqueue { chapterNoteFocusLost() } },
        onUndoDeletion = { token -> enqueue { undoDeletion(token) } },
        onConflictChoice = { key, identity, choice -> enqueue { chooseConflict(key, identity, choice) } },
        onReanchor = { id -> enqueue { beginReanchor(id) } },
        onEditSignal = { signal -> enqueue { editSignal(signal) } },
        onEditEdit = { edit -> enqueue { editEdit(edit) } },
        onDeleteSignal = { id -> enqueue { deleteSignal(id) } },
        onDeleteEdit = { id -> enqueue { deleteEdit(id) } },
        onRetryReviewError = { enqueue { retryLastFailure() } },
    )
}
