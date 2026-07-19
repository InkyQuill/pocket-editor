package net.inkyquill.pocketeditor.ui.review

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks

fun EditorialReviewController.readerCallbacks(
    scope: CoroutineScope,
    base: ReaderCallbacks = ReaderCallbacks(),
): ReaderCallbacks = base.copy(
    onTextSelected = { block, start, end -> scope.launch { select(block, start, end) } },
    onSignalChosen = { scope.launch { chooseSignal(it) } },
    onEditChosen = { scope.launch { chooseEdit() } },
    onSignalTypeChanged = { scope.launch { changeSignalType(it) } },
    onDraftTextChanged = { scope.launch { changeDraftText(it) } },
    onSaveDraft = { scope.launch { saveDraft() } },
    onCancelDraft = { scope.launch { cancelDraft() } },
    onChapterNoteChanged = ::changeChapterNote,
    onChapterNoteFocusLost = { scope.launch { chapterNoteFocusLost() } },
    onUndoDeletion = { scope.launch { undoDeletion(it) } },
    onConflictChoice = { id, choice -> scope.launch { chooseConflict(id, choice) } },
    onReanchor = ::beginReanchor,
    onEditSignal = { scope.launch { editSignal(it) } },
    onEditEdit = { scope.launch { editEdit(it) } },
    onDeleteSignal = { scope.launch { deleteSignal(it) } },
    onDeleteEdit = { scope.launch { deleteEdit(it) } },
)
