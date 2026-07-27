package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.theme.LocalReviewColors

@Composable
fun SignalComposer(
    draft: ReviewDraft.Signal,
    value: TextFieldValue,
    onTypeChange: (SignalType) -> Unit,
    onCommentChange: (TextFieldValue) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    stackedActions: Boolean,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
) {
    val commentDescription = stringResource(R.string.signal_comment_optional)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(16.dp).testTag("signal-composer"),
    ) {
        Text(
            stringResource(if (draft.recordId == null) R.string.new_passage_signal else R.string.edit_passage_signal),
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SignalType.entries.forEach { type ->
                val signalColor = LocalReviewColors.current.signalColor(type)
                val label = stringResource(type.labelResource)
                val help = stringResource(type.helpResource)
                val description = stringResource(R.string.signal_color_description, label, help)
                FilterChip(
                    selected = draft.type == type,
                    onClick = { onTypeChange(type) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = signalColor.copy(alpha = 0.10f),
                        labelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = signalColor.copy(alpha = 0.28f),
                        selectedLabelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = draft.type == type,
                        borderColor = signalColor.copy(alpha = 0.55f),
                        selectedBorderColor = signalColor,
                        borderWidth = 1.dp,
                        selectedBorderWidth = 2.dp,
                    ),
                    modifier = Modifier.testTag("signal-${type.name.lowercase()}").semantics {
                        contentDescription = description
                    },
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onCommentChange,
            label = { Text(stringResource(R.string.optional_comment)) },
            minLines = 3,
            modifier = inputModifier.fillMaxWidth().semantics { contentDescription = commentDescription },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, modifier = Modifier.testTag("save-draft")) { Text(stringResource(R.string.save)) }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel-draft")) { Text(stringResource(R.string.cancel)) }
        }
    }
}
