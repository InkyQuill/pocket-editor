package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks

enum class AnnotationComposerPlacement { Below, Above, PhoneSheet, TabletModal }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun InlineAnnotationComposer(
    session: ReviewDraftSession,
    callbacks: ReaderCallbacks,
    placement: AnnotationComposerPlacement,
    modifier: Modifier = Modifier,
) {
    val draft = session.draft ?: return
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(draft.recordId, draft::class) { focusRequester.requestFocus() }
    val content: @Composable (Modifier) -> Unit = { surfaceModifier ->
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = surfaceModifier.testTag("inline-annotation-composer"),
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
            Box(Modifier.fillMaxWidth().padding(24.dp).testTag("inline-annotation-modal")) {
                content(Modifier.widthIn(max = 420.dp))
            }
        }
    }
}
