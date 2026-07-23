package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R

@Composable
fun EditComposer(
    draft: ReviewDraft.Edit,
    validation: DraftValidation,
    onAfterChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
) {
    val editedPassageDescription = stringResource(R.string.edited_passage)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(16.dp).testTag("edit-composer"),
    ) {
        Text(stringResource(R.string.edit_passage), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.before), style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        Text(draft.selection.selectedText, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = draft.after,
            onValueChange = onAfterChange,
            label = { Text(stringResource(R.string.after)) },
            minLines = 3,
            modifier = inputModifier.fillMaxWidth().semantics { contentDescription = editedPassageDescription },
        )
        when (validation) {
            DraftValidation.Valid -> Unit
            DraftValidation.Unchanged -> Text(stringResource(R.string.change_text_before_saving), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            is DraftValidation.Overlapping -> Text(stringResource(R.string.overlapping_edit), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = validation == DraftValidation.Valid, modifier = Modifier.testTag("save-draft")) { Text(stringResource(R.string.save)) }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel-draft")) { Text(stringResource(R.string.cancel)) }
        }
    }
}
