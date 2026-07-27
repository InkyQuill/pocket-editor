package net.inkyquill.pocketeditor.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks

enum class AnnotationComposerPlacement { Below, Above, PhoneSheet, TabletModal }

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
    modifier: Modifier = Modifier,
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
    LaunchedEffect(identity) { focusRequester.requestFocus() }
    BackHandler(
        enabled = placement == AnnotationComposerPlacement.Below || placement == AnnotationComposerPlacement.Above,
    ) {
        if (!session.blocksDismissal) callbacks.onCancelDraft()
    }
    val content: @Composable (Modifier) -> Unit = { surfaceModifier ->
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = surfaceModifier.testTag("inline-annotation-composer"),
        ) {
            when (draft) {
                is ReviewDraft.Signal -> SignalComposer(
                    draft = requireNotNull(localSession.draft as? ReviewDraft.Signal),
                    value = inputValue,
                    onTypeChange = onSignalTypeChange,
                    onCommentChange = onInputChange,
                    onSave = callbacks.onSaveDraft,
                    onCancel = callbacks.onCancelDraft,
                    stackedActions = placement == AnnotationComposerPlacement.PhoneSheet,
                    inputModifier = Modifier.focusRequester(focusRequester).testTag("inline-annotation-input"),
                )
                is ReviewDraft.Edit -> EditComposer(
                    draft = draft,
                    value = inputValue,
                    validation = localValidation,
                    onAfterChange = onInputChange,
                    onSave = callbacks.onSaveDraft,
                    onCancel = callbacks.onCancelDraft,
                    stackedActions = placement == AnnotationComposerPlacement.PhoneSheet,
                    inputModifier = Modifier.focusRequester(focusRequester).testTag("inline-annotation-input"),
                )
            }
        }
    }
    when (placement) {
        AnnotationComposerPlacement.Below,
        AnnotationComposerPlacement.Above,
        -> content(modifier)
        AnnotationComposerPlacement.PhoneSheet -> ModalBottomSheet(
            onDismissRequest = { if (!session.blocksDismissal) callbacks.onCancelDraft() },
            modifier = Modifier.testTag("inline-annotation-phone-sheet"),
        ) {
            content(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
        AnnotationComposerPlacement.TabletModal -> Dialog(
            onDismissRequest = { if (!session.blocksDismissal) callbacks.onCancelDraft() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier.fillMaxWidth().imePadding().padding(24.dp).testTag("inline-annotation-modal"),
                contentAlignment = Alignment.Center,
            ) {
                content(Modifier.widthIn(max = 420.dp))
            }
        }
    }
}
