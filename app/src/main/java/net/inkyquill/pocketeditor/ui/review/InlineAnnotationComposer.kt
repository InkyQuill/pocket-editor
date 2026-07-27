package net.inkyquill.pocketeditor.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.rememberModalBottomSheetState
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks

enum class AnnotationComposerPlacement { PhoneSheet, TabletModal }

private data class DraftInputIdentity(
    val kind: String,
    val recordId: String?,
    val rawStartByte: Int,
    val rawEndByte: Int,
)

internal fun applyDraftTextFieldChange(
    current: TextFieldValue,
    next: TextFieldValue,
    onTextChanged: (String) -> Unit,
): TextFieldValue {
    if (next.text != current.text) onTextChanged(next.text)
    return next
}

private val ReviewDraft.inputIdentity: DraftInputIdentity
    get() = DraftInputIdentity(
        kind = when (this) {
            is ReviewDraft.Signal -> "signal"
            is ReviewDraft.Edit -> "edit"
        },
        recordId = recordId,
        rawStartByte = selection.rawRange.startByte,
        rawEndByte = selection.rawRange.endByte,
    )

private val ReviewDraft.inputText: String
    get() = when (this) {
        is ReviewDraft.Signal -> comment
        is ReviewDraft.Edit -> after
    }

private fun ReviewDraft.isDirtyWithInput(text: String): Boolean = when (this) {
    is ReviewDraft.Signal -> if (recordId == null) {
        text.isNotEmpty()
    } else {
        savedType == null || type != savedType || text != savedComment
    }
    is ReviewDraft.Edit -> if (recordId == null) {
        text != selection.selectedText
    } else {
        savedAfter == null || text != savedAfter
    }
}

private fun ReviewDraftSession.withInput(
    text: String,
    signalType: SignalType?,
): ReviewDraftSession = copy(
    draft = when (val value = requireNotNull(draft)) {
        is ReviewDraft.Signal -> value.copy(
            type = requireNotNull(signalType),
            comment = text,
        )
        is ReviewDraft.Edit -> value.copy(after = text)
    },
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun InlineAnnotationComposer(
    session: ReviewDraftSession,
    callbacks: ReaderCallbacks,
    placement: AnnotationComposerPlacement,
) {
    val draft = session.draft ?: return
    val identity = draft.inputIdentity
    val focusRequester = remember(identity) { FocusRequester() }
    var inputValue by rememberSaveable(
        identity,
        stateSaver = TextFieldValue.Saver,
    ) {
        mutableStateOf(
            TextFieldValue(
                text = draft.inputText,
                selection = TextRange(draft.inputText.length),
            ),
        )
    }
    var localSignalType by rememberSaveable(identity) {
        mutableStateOf((draft as? ReviewDraft.Signal)?.type)
    }
    val localSession = session.withInput(
        text = inputValue.text,
        signalType = localSignalType,
    )
    val localValidation = ReviewDraftStateMachine.validate(localSession)
    val onInputChange: (TextFieldValue) -> Unit = { next ->
        inputValue = applyDraftTextFieldChange(
            current = inputValue,
            next = next,
            onTextChanged = callbacks.onDraftTextChanged,
        )
    }
    val onSignalTypeChange: (SignalType) -> Unit = { type ->
        localSignalType = type
        callbacks.onSignalTypeChanged(type)
    }
    var confirmDiscard by rememberSaveable(identity) { mutableStateOf(false) }
    val isDirty = requireNotNull(localSession.draft).isDirtyWithInput(inputValue.text)
    val requestDismiss = {
        if (isDirty) confirmDiscard = true else callbacks.onCancelDraft()
    }
    val currentRequestDismiss by rememberUpdatedState(requestDismiss)
    val currentIsDirty by rememberUpdatedState(isDirty)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { targetValue ->
            if (targetValue == SheetValue.Hidden && currentIsDirty) {
                currentRequestDismiss()
                false
            } else {
                true
            }
        },
    )
    val scrollState = rememberScrollState()

    BackHandler(enabled = true, onBack = requestDismiss)
    LaunchedEffect(identity, placement) {
        if (placement == AnnotationComposerPlacement.PhoneSheet) {
            snapshotFlow { sheetState.currentValue }
                .first { it == SheetValue.Expanded }
        }
        focusRequester.requestFocus()
    }
    val form: @Composable (stackedActions: Boolean, contentPadding: Dp) -> Unit =
        { stackedActions, contentPadding ->
            when (draft) {
                is ReviewDraft.Signal -> SignalComposer(
                    draft = requireNotNull(localSession.draft as? ReviewDraft.Signal),
                    value = inputValue,
                    onTypeChange = onSignalTypeChange,
                    onCommentChange = onInputChange,
                    onSave = callbacks.onSaveDraft,
                    onCancel = callbacks.onCancelDraft,
                    stackedActions = stackedActions,
                    contentPadding = contentPadding,
                    inputModifier = Modifier
                        .focusRequester(focusRequester)
                        .testTag("inline-annotation-input"),
                )
                is ReviewDraft.Edit -> EditComposer(
                    draft = draft,
                    value = inputValue,
                    validation = localValidation,
                    onAfterChange = onInputChange,
                    onSave = callbacks.onSaveDraft,
                    onCancel = callbacks.onCancelDraft,
                    stackedActions = stackedActions,
                    contentPadding = contentPadding,
                    inputModifier = Modifier
                        .focusRequester(focusRequester)
                        .testTag("inline-annotation-input"),
                )
            }
        }
    when (placement) {
        AnnotationComposerPlacement.PhoneSheet -> {
            ModalBottomSheet(
                onDismissRequest = requestDismiss,
                sheetState = sheetState,
                modifier = Modifier.testTag("inline-annotation-phone-sheet"),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(scrollState)
                        .testTag("inline-annotation-composer"),
                ) {
                    form(true, 16.dp)
                }
            }
        }
        AnnotationComposerPlacement.TabletModal -> {
            Dialog(
                onDismissRequest = requestDismiss,
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .imePadding()
                        .padding(24.dp)
                        .testTag("inline-annotation-modal"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(isDirty) {
                                detectTapGestures { requestDismiss() }
                            },
                    )
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .verticalScroll(scrollState)
                            .pointerInput(Unit) {
                                detectTapGestures { }
                            }
                            .testTag("inline-annotation-composer"),
                    ) {
                        form(false, 24.dp)
                    }
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_review_changes_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        callbacks.onCancelDraft()
                    },
                ) {
                    Text(stringResource(R.string.discard_review_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.continue_review_editing))
                }
            },
        )
    }
}
