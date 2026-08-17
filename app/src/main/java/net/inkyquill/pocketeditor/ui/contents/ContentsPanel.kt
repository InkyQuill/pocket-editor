package net.inkyquill.pocketeditor.ui.contents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        if (chapterIds.isEmpty()) {
            editing = false
        } else {
            reorderState?.reconcileCanonical(chapterIds)
        }
    }
    val activeReorderState = reorderState.takeIf { editing }
    Surface(
        modifier.then(if (activeReorderState != null) Modifier.clearAndSetSemantics { } else Modifier),
        color = MaterialTheme.colorScheme.background,
    ) {
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
            if (chapterIds.isNotEmpty()) {
                TextButton(onClick = { editing = true }) {
                    Text(stringResource(R.string.change_chapter_order))
                }
            }
        }
        val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0)
        val listState = key(currentBookId) {
            rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
        }
        LaunchedEffect(currentBookId, currentChapterId, chapterIds) {
            val index = chapters.indexOfFirst { it.id == currentChapterId }
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!chapter.cached) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).testTag("chapter-download-${chapter.id}"),
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
        Surface(
            onClick = onOpenBooks,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("manage-books"),
        ) {
            Text(stringResource(R.string.manage_books), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(14.dp))
        }
    }
    }
    activeReorderState?.let { state ->
        ChapterReorderDialog(
            bookTitle = book?.title.orEmpty(),
            chapters = chapters,
            currentChapterId = currentChapterId,
            reorderState = state,
            onCancel = {
                state.cancel()
                editing = false
                onCancelOrder()
            },
            onSave = {
                state.orderForSave(chapterIds)?.let { ordered ->
                    onSaveOrder(state.expectedOriginalChapterIds, ordered)
                    editing = false
                }
            },
        )
    }
}

@Composable
private fun ChapterReorderDialog(
    bookTitle: String,
    chapters: List<BookChapter>,
    currentChapterId: String,
    reorderState: ContentsReorderState,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val byId = chapters.associateBy(BookChapter::id)
    val displayedChapters = reorderState.orderedChapterIds.mapNotNull(byId::get)
    val currentIndex = displayedChapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
    val canonicalIds = chapters.map(BookChapter::id)
    LaunchedEffect(currentChapterId, canonicalIds) {
        val index = displayedChapters.indexOfFirst { it.id == currentChapterId }
        if (index >= 0) listState.scrollToItem(index)
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().testTag("chapter-reorder-dialog"),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.chapter_order), style = MaterialTheme.typography.titleLarge)
                        Text(
                            bookTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = onSave, enabled = reorderState.changed) {
                        Text(stringResource(R.string.save))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("contents-chapter-list"),
                ) {
                    itemsIndexed(displayedChapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                        val moveUpDescription = stringResource(R.string.move_chapter_up, chapter.title)
                        val moveDownDescription = stringResource(R.string.move_chapter_down, chapter.title)
                        Surface(
                            color = if (chapter.id == currentChapterId) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics {
                                    customActions = buildList {
                                        if (index > 0) add(CustomAccessibilityAction(moveUpDescription) {
                                            reorderState.move(index, index - 1)
                                            true
                                        })
                                        if (index < displayedChapters.lastIndex) add(CustomAccessibilityAction(moveDownDescription) {
                                            reorderState.move(index, index + 1)
                                            true
                                        })
                                    }
                                }
                                .pointerInput(chapter.id) {
                                    var dragDistance = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragDistance = 0f },
                                        onDragCancel = { dragDistance = 0f },
                                        onDragEnd = { dragDistance = 0f },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragDistance += dragAmount.y
                                            val from = reorderState.orderedChapterIds.indexOf(chapter.id)
                                                .takeIf { it >= 0 }
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
                            Text(
                                chapter.title,
                                fontWeight = if (chapter.id == currentChapterId) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                            )
                        }
                        if (index != displayedChapters.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
