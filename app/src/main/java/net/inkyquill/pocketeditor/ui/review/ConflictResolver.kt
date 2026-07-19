package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.sync.ConflictChoice

@Composable
fun ConflictResolver(
    conflicts: List<ConflictCard>,
    onChoice: (String, ConflictChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (conflicts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier) {
        Text("Resolve conflicts", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text("Choose a version for every item before sync can continue.")
        conflicts.forEach { conflict ->
            Surface(shape = androidx.compose.material3.MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if (conflict.manifest) "Book contents" else conflict.recordId)
                    Text("Mine: ${conflict.localPreview}")
                    Text("Yandex Disk: ${conflict.yandexPreview}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val mineSelected = conflict.selectedChoice == ConflictChoice.KEEP_MINE
                        val yandexSelected = conflict.selectedChoice == ConflictChoice.KEEP_YANDEX
                        FilterChip(
                            selected = mineSelected,
                            onClick = { onChoice(conflict.recordId, ConflictChoice.KEEP_MINE) },
                            label = { Text(if (mineSelected) "Keep mine · Selected" else "Keep mine") },
                            leadingIcon = if (mineSelected) ({ Icon(Icons.Default.Check, null) }) else null,
                            modifier = Modifier.semantics {
                                contentDescription = "Keep mine for ${conflict.recordId}, ${if (mineSelected) "selected" else "not selected"}"
                            },
                        )
                        FilterChip(
                            selected = yandexSelected,
                            onClick = { onChoice(conflict.recordId, ConflictChoice.KEEP_YANDEX) },
                            label = { Text(if (yandexSelected) "Keep Yandex Disk · Selected" else "Keep Yandex Disk") },
                            leadingIcon = if (yandexSelected) ({ Icon(Icons.Default.Check, null) }) else null,
                            modifier = Modifier.semantics {
                                contentDescription = "Keep Yandex Disk for ${conflict.recordId}, ${if (yandexSelected) "selected" else "not selected"}"
                            },
                        )
                    }
                }
            }
        }
    }
}
