package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.review.SignalType

@Composable
fun SignalComposer(
    draft: ReviewDraft.Signal,
    onTypeChange: (SignalType) -> Unit,
    onCommentChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(if (draft.recordId == null) "New passage signal" else "Edit passage signal", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SignalType.entries.forEach { type ->
                FilterChip(
                    selected = draft.type == type,
                    onClick = { onTypeChange(type) },
                    label = { Text(type.label) },
                    modifier = Modifier.testTag("signal-${type.name.lowercase()}").semantics {
                        contentDescription = "${type.label} signal color. ${type.help}"
                    },
                )
            }
        }
        OutlinedTextField(
            value = draft.comment,
            onValueChange = onCommentChange,
            label = { Text("Optional comment") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Signal comment, optional" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, modifier = Modifier.testTag("save-draft")) { Text("Save") }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel-draft")) { Text("Cancel") }
        }
    }
}
