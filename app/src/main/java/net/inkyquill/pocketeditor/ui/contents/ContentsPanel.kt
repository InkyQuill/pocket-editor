package net.inkyquill.pocketeditor.ui.contents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.search.SearchHit
import net.inkyquill.pocketeditor.ui.russianPluralStringResource
import net.inkyquill.pocketeditor.ui.books.BookChapter
import net.inkyquill.pocketeditor.ui.books.BookSummary
import net.inkyquill.pocketeditor.ui.books.DiscoveryNotice
import net.inkyquill.pocketeditor.ui.search.SearchNavigation
import net.inkyquill.pocketeditor.ui.search.SearchScreen

@Composable
fun ContentsPanel(
    books: List<BookSummary>,
    currentBookId: String,
    currentChapterId: String,
    query: String,
    searchResults: List<SearchHit>,
    searching: Boolean,
    closeLabel: String,
    onClose: () -> Unit,
    onSwitchBook: (String) -> Unit,
    onChapterSelected: (BookChapter) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearchResult: (SearchNavigation) -> Unit,
    onOpenBooks: () -> Unit,
    onAppearance: () -> Unit,
    discoveryNotices: List<DiscoveryNotice> = emptyList(),
    onAddDiscovered: (path: String, position: Int) -> Unit = { _, _ -> },
    onReplaceDiscovered: (chapterId: String, path: String) -> Unit = { _, _ -> },
    onIgnoreDiscovered: (path: String) -> Unit = {},
    onUpdateRenamed: (chapterId: String, path: String) -> Unit = { _, _ -> },
    onLocateMissing: (chapterId: String, path: String) -> Unit = { _, _ -> },
    onRemoveMissing: (chapterId: String) -> Unit = {},
    initialDiscoveryExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val book = books.singleOrNull { it.bookId == currentBookId }
    var discoveryExpanded by rememberSaveable(currentBookId) { mutableStateOf(initialDiscoveryExpanded) }
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.contents), style = MaterialTheme.typography.titleLarge)
                Text(book?.title.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onAppearance) { Icon(Icons.Default.Settings, stringResource(R.string.appearance)) }
            FilledTonalIconButton(onClick = onClose) { Icon(Icons.Default.Close, closeLabel) }
        }
        if (books.size > 1) {
            Text(stringResource(R.string.books_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                books.forEach { candidate ->
                    Surface(
                        selected = candidate.bookId == currentBookId,
                        onClick = { onSwitchBook(candidate.bookId) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (candidate.bookId == currentBookId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ) { Text(candidate.title, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) }
                }
            }
        }
        val currentNotices = discoveryNotices.filter { it.bookId == currentBookId }
        if (currentNotices.isNotEmpty()) {
            OutlinedButton(
                onClick = { discoveryExpanded = !discoveryExpanded },
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 48.dp),
            ) {
                Text(
                    if (discoveryExpanded) stringResource(R.string.hide_book_updates)
                    else russianPluralStringResource(
                        R.plurals.review_book_updates,
                        currentNotices.size,
                        currentNotices.size,
                    ),
                )
            }
            if (discoveryExpanded) {
                DiscoveryPanel(
                    notices = currentNotices,
                    currentChapterId = currentChapterId,
                    chapters = book?.chapters.orEmpty(),
                    onAdd = onAddDiscovered,
                    onReplace = onReplaceDiscovered,
                    onIgnore = onIgnoreDiscovered,
                    onUpdateRenamed = onUpdateRenamed,
                    onLocateMissing = onLocateMissing,
                    onRemoveMissing = onRemoveMissing,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
        SearchScreen(
            query,
            searchResults,
            searching,
            onQueryChanged,
            onSearchResult,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        Text(stringResource(R.string.chapters), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        val chapters = book?.chapters.orEmpty()
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 6.dp)) {
            itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                val current = chapter.id == currentChapterId
                Surface(
                    selected = current,
                    onClick = { onChapterSelected(chapter) },
                    color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            chapter.title,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (chapter.cached) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Доступно без сети",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                if (index != chapters.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.testTag("chapter-divider"),
                    )
                }
            }
        }
        Surface(onClick = onOpenBooks, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.manage_books), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(14.dp))
        }
    }
    }
}
