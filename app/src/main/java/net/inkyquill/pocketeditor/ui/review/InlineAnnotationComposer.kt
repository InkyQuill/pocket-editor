package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks

enum class AnnotationComposerPlacement { Below, Above, PhoneSheet, TabletModal }

@Composable
fun InlineAnnotationComposer(
    session: ReviewDraftSession,
    callbacks: ReaderCallbacks,
    placement: AnnotationComposerPlacement,
    modifier: Modifier = Modifier,
    modalFallback: @Composable (@Composable () -> Unit) -> Unit = { content -> content() },
) {
    val draft = session.draft ?: return
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(draft.recordId, draft::class) { focusRequester.requestFocus() }
    val content: @Composable () -> Unit = {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = modifier.testTag("inline-annotation-composer"),
        ) {
            when (draft) {
                is ReviewDraft.Signal -> SignalComposer(
                    draft = draft,
                    onTypeChange = callbacks.onSignalTypeChanged,
                    onCommentChange = callbacks.onDraftTextChanged,
                    onSave = callbacks.onSaveDraft,
                    onCancel = callbacks.onCancelDraft,
                    inputModifier = Modifier.focusRequester(focusRequester).testTag("inline-annotation-input"),
                )
                is ReviewDraft.Edit -> EditComposer(
                    draft = draft,
                    validation = ReviewDraftStateMachine.validate(session),
                    onAfterChange = callbacks.onDraftTextChanged,
                    onSave = callbacks.onSaveDraft,
                    onCancel = callbacks.onCancelDraft,
                    inputModifier = Modifier.focusRequester(focusRequester).testTag("inline-annotation-input"),
                )
            }
        }
    }
    when (placement) {
        AnnotationComposerPlacement.Below,
        AnnotationComposerPlacement.Above,
        -> content()
        AnnotationComposerPlacement.PhoneSheet,
        AnnotationComposerPlacement.TabletModal,
        -> modalFallback(content)
    }
}
