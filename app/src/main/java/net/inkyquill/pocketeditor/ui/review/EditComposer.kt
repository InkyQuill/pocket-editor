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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("edit-composer"),
    ) {
        Text("Edit passage", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text("Before", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        Text(draft.selection.selectedText, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = draft.after,
            onValueChange = onAfterChange,
            label = { Text("After") },
            minLines = 3,
            modifier = inputModifier.fillMaxWidth().semantics { contentDescription = "Edited passage" },
        )
        when (validation) {
            DraftValidation.Valid -> Unit
            DraftValidation.Unchanged -> Text("Change the text before saving.", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            is DraftValidation.Overlapping -> Text("This edit overlaps another edit. Choose a different passage.", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = validation == DraftValidation.Valid, modifier = Modifier.testTag("save-draft")) { Text("Save") }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel-draft")) { Text("Cancel") }
        }
    }
}
