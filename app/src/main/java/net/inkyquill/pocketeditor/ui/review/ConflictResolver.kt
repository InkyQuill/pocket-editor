package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.sync.ConflictChoice

@Composable
fun ConflictResolver(
    conflicts: List<ConflictCard>,
    onChoice: (String, String, ConflictChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (conflicts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier) {
        Text(stringResource(R.string.resolve_conflicts), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.resolve_conflicts_explanation))
        conflicts.forEach { conflict ->
            val selected = stringResource(R.string.selected)
            val notSelected = stringResource(R.string.not_selected)
            Surface(shape = androidx.compose.material3.MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if (conflict.manifest) stringResource(R.string.book_contents) else conflict.recordId)
                    Text(stringResource(R.string.mine_preview, conflict.localPreview))
                    Text(stringResource(R.string.yandex_preview, conflict.yandexPreview))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val mineSelected = conflict.selectedChoice == ConflictChoice.KEEP_MINE
                        val yandexSelected = conflict.selectedChoice == ConflictChoice.KEEP_YANDEX
                        val mineDescription = stringResource(
                            R.string.keep_mine_description,
                            conflict.recordId,
                            if (mineSelected) selected else notSelected,
                        )
                        val yandexDescription = stringResource(
                            R.string.keep_yandex_description,
                            conflict.recordId,
                            if (yandexSelected) selected else notSelected,
                        )
                        FilterChip(
                            selected = mineSelected,
                            onClick = { onChoice(conflict.key, conflict.identity, ConflictChoice.KEEP_MINE) },
                            label = { Text(stringResource(if (mineSelected) R.string.keep_mine_selected else R.string.keep_mine)) },
                            leadingIcon = if (mineSelected) ({ Icon(Icons.Default.Check, null) }) else null,
                            modifier = Modifier.semantics {
                                contentDescription = mineDescription
                            },
                        )
                        FilterChip(
                            selected = yandexSelected,
                            onClick = { onChoice(conflict.key, conflict.identity, ConflictChoice.KEEP_YANDEX) },
                            label = { Text(stringResource(if (yandexSelected) R.string.keep_yandex_selected else R.string.keep_yandex)) },
                            leadingIcon = if (yandexSelected) ({ Icon(Icons.Default.Check, null) }) else null,
                            modifier = Modifier.semantics {
                                contentDescription = yandexDescription
                            },
                        )
                    }
                }
            }
        }
    }
}
