package net.inkyquill.pocketeditor.ui.contents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    onSaveOrder: (List<String>) -> Unit = {},
    onCancelOrder: () -> Unit = {},
    initialDiscoveryExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val book = books.singleOrNull { it.bookId == currentBookId }
    var discoveryExpanded by rememberSaveable(currentBookId) { mutableStateOf(initialDiscoveryExpanded) }
    var editing by rememberSaveable(currentBookId) { mutableStateOf(false) }
    val chapterIds = book?.chapters.orEmpty().map(BookChapter::id)
    val reorderState = chapterIds.takeIf(List<String>::isNotEmpty)?.let { ids ->
        rememberSaveable(currentBookId, ids, saver = ContentsReorderState.saver(ids)) {
            ContentsReorderState.create(ids)
        }
    }
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.chapters),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                TextButton(onClick = {
                    reorderState?.cancel()
                    editing = false
                    onCancelOrder()
                }) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    enabled = reorderState?.changed == true,
                    onClick = {
                        val ordered = requireNotNull(reorderState).orderedChapterIds
                        require(ordered.size == chapterIds.size && ordered.toSet() == chapterIds.toSet())
                        onSaveOrder(ordered)
                        editing = false
                    },
                ) { Text(stringResource(R.string.save)) }
            } else if (chapterIds.isNotEmpty()) {
                TextButton(onClick = { editing = true }) {
                    Text(stringResource(R.string.change_chapter_order))
                }
            }
        }
        val chapters = book?.chapters.orEmpty()
        val displayedChapters = if (editing) {
            val byId = chapters.associateBy(BookChapter::id)
            reorderState?.orderedChapterIds.orEmpty().map(byId::getValue)
        } else chapters
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(top = 6.dp)
                .pointerInput(editing, reorderState?.orderedChapterIds) {
                    if (!editing) return@pointerInput
                    var draggedIndex: Int? = null
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            draggedIndex = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { offset.y.toInt() in it.offset until (it.offset + it.size) }
                                ?.index
                        },
                        onDragCancel = { draggedIndex = null },
                        onDragEnd = { draggedIndex = null },
                        onDrag = { change, _ ->
                            change.consume()
                            val from = draggedIndex ?: return@detectDragGesturesAfterLongPress
                            val y = change.position.y.toInt()
                            val target = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { y in it.offset until (it.offset + it.size) }
                                ?.index ?: return@detectDragGesturesAfterLongPress
                            if (target != from) {
                                reorderState?.move(from, target)
                                draggedIndex = target
                            }
                        },
                    )
                },
        ) {
            itemsIndexed(displayedChapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                val current = chapter.id == currentChapterId
                Surface(
                    selected = current,
                    onClick = { if (!editing) onChapterSelected(chapter) },
                    enabled = !editing,
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
                        if (editing) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = stringResource(R.string.drag_chapter, chapter.title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(
                                onClick = { reorderState?.move(index, index - 1) },
                                enabled = index > 0,
                                modifier = Modifier.semantics {
                                    contentDescription = "Переместить ${chapter.title} вверх"
                                },
                            ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) }
                            IconButton(
                                onClick = { reorderState?.move(index, index + 1) },
                                enabled = index < displayedChapters.lastIndex,
                                modifier = Modifier.semantics {
                                    contentDescription = "Переместить ${chapter.title} вниз"
                                },
                            ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) }
                        }
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
                if (index != displayedChapters.lastIndex) {
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
