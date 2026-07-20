package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.theme.LocalReviewColors

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SelectionFlyout(
    session: ReviewDraftSession,
    onSignal: (SignalType) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (session.pendingSelection == null && session.selectionProblem == null) return
    Surface(shape = androidx.compose.material3.MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(10.dp),
        ) {
            session.selectionProblem?.let { Text(it, modifier = Modifier.semantics { contentDescription = "Review action unavailable: $it" }) }
            if (session.canChooseAction) {
                SignalType.entries.forEach { type ->
                    SelectionAction(
                        onClick = { onSignal(type) },
                        label = type.selectionLabel,
                        icon = type.icon,
                        tint = LocalReviewColors.current.signalColor(type),
                    )
                }
                SelectionAction(
                    onClick = onEdit,
                    label = "Edit",
                    icon = Icons.Filled.Edit,
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SelectionAction(
    onClick: () -> Unit,
    label: String,
    icon: ImageVector,
    tint: Color,
) {
    TooltipBox(
        state = rememberTooltipState(),
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                .semantics { contentDescription = label },
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
        }
    }
}

private val SignalType.icon: ImageVector
    get() = when (this) {
        SignalType.NOTE -> Icons.AutoMirrored.Filled.Note
        SignalType.WARNING -> Icons.Filled.Warning
        SignalType.CHANGE_REQUIRED -> Icons.Filled.Error
        SignalType.REVIEW -> Icons.AutoMirrored.Filled.Help
    }

private val SignalType.selectionLabel: String
    get() = when (this) {
        SignalType.NOTE -> "Add note"
        SignalType.CHANGE_REQUIRED -> "Change needed"
        else -> label
    }
