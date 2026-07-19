package net.inkyquill.pocketeditor.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.search.SearchHit

@Composable
fun SearchScreen(
    query: String,
    results: List<SearchHit>,
    searching: Boolean,
    onQueryChanged: (String) -> Unit,
    onResultSelected: (SearchNavigation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            label = { Text("Search this book") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            query.isBlank() -> Text(
                "Searches canonical chapter text offline. Notes and edits are not included.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            !searching && results.isEmpty() -> Text(
                "No passages found",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 8.dp)) {
                items(results, key = { "${it.chapterId}:${it.rawStartByte}:${it.rawEndByte}" }) { hit ->
                    Surface(onClick = { onResultSelected(hit.toNavigation()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        ListItem(
                            headlineContent = { Text(hit.title, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text(hit.excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
