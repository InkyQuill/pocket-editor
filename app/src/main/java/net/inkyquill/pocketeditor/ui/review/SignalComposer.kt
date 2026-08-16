package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    contentPadding: Dp = 16.dp,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
) {
    val commentDescription = stringResource(R.string.signal_comment_optional)
    val signalColor = LocalReviewColors.current.signalColor(draft.type)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(contentPadding).testTag("signal-composer"),
    ) {
        Text(
            stringResource(if (draft.recordId == null) R.string.new_passage_signal else R.string.edit_passage_signal),
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(signalColor)
                    .testTag("signal-selection-marker"),
            )
            Text(
                text = draft.selection.selectedText,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("signal-selection-quote"),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SignalType.entries.forEach { type ->
                val typeColor = LocalReviewColors.current.signalColor(type)
                val label = stringResource(type.labelResource)
                val help = stringResource(type.helpResource)
                val description = stringResource(R.string.signal_color_description, label, help)
                FilterChip(
                    selected = draft.type == type,
                    onClick = { onTypeChange(type) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = typeColor.copy(alpha = 0.10f),
                        labelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = typeColor.copy(alpha = 0.28f),
                        selectedLabelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = draft.type == type,
                        borderColor = typeColor.copy(alpha = 0.55f),
                        selectedBorderColor = typeColor,
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
        ComposerActions(
            stacked = stackedActions,
            saveEnabled = true,
            onSave = onSave,
            onCancel = onCancel,
        )
    }
}

@Composable
internal fun ComposerActions(
    stacked: Boolean,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.fillMaxWidth().testTag("save-draft"),
            ) {
                Text(stringResource(R.string.save))
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().testTag("cancel-draft"),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel-draft")) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.testTag("save-draft"),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
