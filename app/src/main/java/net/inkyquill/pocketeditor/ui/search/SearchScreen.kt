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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.search.SearchHit
import net.inkyquill.pocketeditor.ui.theme.LocalReaderTypography

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
            label = { Text(stringResource(R.string.search_this_book)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            query.isBlank() -> Text(
                stringResource(R.string.search_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            !searching && results.isEmpty() -> Text(
                stringResource(R.string.no_passages_found),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 8.dp)) {
                items(results, key = { "${it.chapterId}:${it.rawStartByte}:${it.rawEndByte}" }) { hit ->
                    val match = hit.excerpt.substring(hit.excerptMatchStart, hit.excerptMatchEnd)
                    val matchDescription = stringResource(R.string.search_match, match)
                    Surface(onClick = { onResultSelected(hit.toNavigation()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    hit.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = highlightSearchExcerpt(hit, MaterialTheme.colorScheme.tertiaryContainer),
                                    style = LocalReaderTypography.current.searchExcerpt,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { contentDescription = matchDescription },
                                )
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
