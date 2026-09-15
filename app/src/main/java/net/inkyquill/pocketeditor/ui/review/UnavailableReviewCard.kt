package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R

/**
 * A saved review record whose anchor no longer resolves against the current chapter source
 * (or that overlaps another saved edit). There is no manual rebinding in v1: the card shows
 * what was saved and why it is unavailable, and offers deletion so the reader can record a
 * fresh annotation if needed (ADR 0001).
 */
@Composable
fun UnavailableReviewCard(
    label: String,
    savedText: String,
    reason: String,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = savedText,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.delete))
            }
        }
    }
}
