package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.theme.LocalReviewColors

@Composable
fun SelectionFlyout(
    session: ReviewDraftSession,
    onSignal: (SignalType) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (session.pendingSelection == null && session.selectionProblem == null) return
    Surface(shape = androidx.compose.material3.MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(10.dp),
        ) {
            session.selectionProblem?.let { Text(it, modifier = Modifier.semantics { contentDescription = "Review action unavailable: $it" }) }
            if (session.canChooseAction) {
                SignalType.entries.forEach { type ->
                    val signalColor = LocalReviewColors.current.signalColor(type)
                    AssistChip(
                        onClick = { onSignal(type) },
                        label = { Text(type.label) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = signalColor.copy(alpha = 0.14f),
                            labelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, signalColor.copy(alpha = 0.72f)),
                        modifier = Modifier.semantics { contentDescription = "Create ${type.label.lowercase()} signal. ${type.help}" },
                    )
                }
                AssistChip(onClick = onEdit, label = { Text("Edit") })
            }
        }
    }
}
