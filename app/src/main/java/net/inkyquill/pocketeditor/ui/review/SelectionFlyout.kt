package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
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
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.NotebookPen
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.TriangleAlert
import net.inkyquill.pocketeditor.R
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
    val unavailableDescription = session.selectionProblem?.let {
        stringResource(R.string.review_action_unavailable, it)
    }
    Surface(shape = androidx.compose.material3.MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(10.dp),
        ) {
            session.selectionProblem?.let {
                Text(it, modifier = Modifier.semantics { contentDescription = requireNotNull(unavailableDescription) })
            }
            if (session.canChooseSignal) {
                SignalType.entries.forEach { type ->
                    SelectionAction(
                        onClick = { onSignal(type) },
                        label = stringResource(type.selectionLabelResource),
                        icon = type.icon,
                        tint = LocalReviewColors.current.signalColor(type),
                    )
                }
                if (session.canSuggestEdit) {
                    SelectionAction(
                        onClick = onEdit,
                        label = stringResource(R.string.edit_action),
                        icon = Lucide.Pencil,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                }
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
        SignalType.NOTE -> Lucide.NotebookPen
        SignalType.WARNING -> Lucide.TriangleAlert
        SignalType.CHANGE_REQUIRED -> Lucide.CircleAlert
        SignalType.REVIEW -> Lucide.CircleHelp
    }

@get:StringRes
private val SignalType.selectionLabelResource: Int
    get() = when (this) {
        SignalType.NOTE -> R.string.add_note
        SignalType.CHANGE_REQUIRED -> R.string.change_needed
        SignalType.WARNING -> R.string.warning
        SignalType.REVIEW -> R.string.review
    }
