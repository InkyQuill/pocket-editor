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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
    onSaveOrder: (
        expectedOriginalChapterIds: List<String>,
        orderedChapterIds: List<String>,
    ) -> Unit = { _, _ -> },
    onCancelOrder: () -> Unit = {},
    error: String? = null,
    onDismissError: () -> Unit = {},
    onRetryOrder: (() -> Unit)? = null,
    retryOrderLoading: Boolean = false,
    initialDiscoveryExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val book = books.singleOrNull { it.bookId == currentBookId }
    var discoveryExpanded by rememberSaveable(currentBookId) { mutableStateOf(initialDiscoveryExpanded) }
    var editing by rememberSaveable(currentBookId) { mutableStateOf(false) }
    val chapters = book?.chapters.orEmpty()
    val chapterIds = chapters.map(BookChapter::id)
    val reorderState = chapterIds.takeIf(List<String>::isNotEmpty)?.let { ids ->
        rememberSaveable(currentBookId, saver = ContentsReorderState.saver(ids)) {
            ContentsReorderState.create(ids)
        }
    }
    LaunchedEffect(reorderState, chapterIds) {
        reorderState?.reconcileCanonical(chapterIds)
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
        error?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismissError) {
                            Icon(Icons.Default.Close, stringResource(R.string.dismiss_error))
                        }
                    }
                    onRetryOrder?.let { retry ->
                        TextButton(
                            onClick = retry,
                            enabled = !retryOrderLoading,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(if (retryOrderLoading) R.string.refreshing_book_base else R.string.refresh_book_base))
                        }
                    }
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
                        reorderState?.orderForSave(chapterIds)?.let { ordered ->
                            onSaveOrder(reorderState.expectedOriginalChapterIds, ordered)
                            editing = false
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            } else if (chapterIds.isNotEmpty()) {
                TextButton(onClick = { editing = true }) {
                    Text(stringResource(R.string.change_chapter_order))
                }
            }
        }
        val displayedChapters = if (editing) {
            val byId = chapters.associateBy(BookChapter::id)
            reorderState?.orderedChapterIds.orEmpty().mapNotNull(byId::get)
        } else chapters
        val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0)
        val listState = key(currentBookId) {
            rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
        }
        LaunchedEffect(currentBookId, currentChapterId, chapterIds) {
            val index = displayedChapters.indexOfFirst { it.id == currentChapterId }
            if (index >= 0) listState.scrollToItem(index)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .testTag("contents-chapter-list")
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(top = 6.dp),
        ) {
            itemsIndexed(displayedChapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                val current = chapter.id == currentChapterId
                val moveUpDescription = stringResource(R.string.move_chapter_up, chapter.title)
                val moveDownDescription = stringResource(R.string.move_chapter_down, chapter.title)
                Surface(
                    selected = current,
                    onClick = { if (!editing) onChapterSelected(chapter) },
                    enabled = !editing,
                    color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .pointerInput(editing, chapter.id) {
                            if (!editing) return@pointerInput
                            var dragDistance = 0f
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragDistance = 0f },
                                onDragCancel = { dragDistance = 0f },
                                onDragEnd = { dragDistance = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragDistance += dragAmount.y
                                    val from = reorderState?.orderedChapterIds
                                        ?.indexOf(chapter.id)
                                        ?.takeIf { it >= 0 }
                                        ?: return@detectDragGesturesAfterLongPress
                                    val threshold = size.height / 2f
                                    when {
                                        dragDistance <= -threshold && from > 0 -> {
                                            reorderState.move(from, from - 1)
                                            dragDistance += size.height
                                        }
                                        dragDistance >= threshold && from < displayedChapters.lastIndex -> {
                                            reorderState.move(from, from + 1)
                                            dragDistance -= size.height
                                        }
                                    }
                                },
                            )
                        },
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
                                    contentDescription = moveUpDescription
                                },
                            ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) }
                            IconButton(
                                onClick = { reorderState?.move(index, index + 1) },
                                enabled = index < displayedChapters.lastIndex,
                                modifier = Modifier.semantics {
                                    contentDescription = moveDownDescription
                                },
                            ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) }
                        }
                        if (chapter.cached) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.available_offline),
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
        Surface(
            onClick = onOpenBooks,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("manage-books"),
        ) {
            Text(stringResource(R.string.manage_books), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(14.dp))
        }
    }
    }
}
