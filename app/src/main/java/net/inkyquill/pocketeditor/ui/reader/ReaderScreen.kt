package net.inkyquill.pocketeditor.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Snackbar
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderChapter
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderSignalItem
import net.inkyquill.pocketeditor.reader.ReaderEditItem
import net.inkyquill.pocketeditor.reader.ReaderSourceSelection
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.reader.ReaderPosition
import net.inkyquill.pocketeditor.markdown.RawRange
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach

import net.inkyquill.pocketeditor.ui.ReaderLayoutMode
import net.inkyquill.pocketeditor.ui.ReaderLayoutPolicy
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.sync.ConflictChoice
import net.inkyquill.pocketeditor.ui.review.ChapterNote
import net.inkyquill.pocketeditor.ui.review.ConflictResolver
import net.inkyquill.pocketeditor.ui.review.EditComposer
import net.inkyquill.pocketeditor.ui.review.ReviewDraft
import net.inkyquill.pocketeditor.ui.review.ReviewDraftStateMachine
import net.inkyquill.pocketeditor.ui.review.ReviewUiState
import net.inkyquill.pocketeditor.ui.review.SelectionFlyout
import net.inkyquill.pocketeditor.ui.review.SignalComposer

data class ReaderSearchTarget(val rawStartByte: Int, val rawEndByte: Int)

data class ReaderCallbacks(
    val onReviewModeChanged: (Boolean) -> Unit = {},
    val onPreviousChapter: (ReaderChapter) -> Unit = {},
    val onNextChapter: (ReaderChapter) -> Unit = {},
    val onChapterSelected: (ReaderChapter) -> Unit = {},
    val onTextSelected: (ReaderSourceSelection?) -> Unit = {},
    val onSignalChosen: (SignalType) -> Unit = {},
    val onEditChosen: () -> Unit = {},
    val onSignalTypeChanged: (SignalType) -> Unit = {},
    val onDraftTextChanged: (String) -> Unit = {},
    val onSaveDraft: () -> Unit = {},
    val onCancelDraft: () -> Unit = {},
    val onChapterNoteChanged: (String) -> Unit = {},
    val onChapterNoteFocusLost: () -> Unit = {},
    val onUndoDeletion: (String) -> Unit = {},
    val onConflictChoice: (String, ConflictChoice) -> Unit = { _, _ -> },
    val onReanchor: (String) -> Unit = {},
    val onEditSignal: (ReaderSignalItem) -> Unit = {},
    val onEditEdit: (ReaderEditItem) -> Unit = {},
    val onDeleteSignal: (String) -> Unit = {},
    val onDeleteEdit: (String) -> Unit = {},
    val onRetryReviewError: () -> Unit = {},
    val onReadingPositionChanged: (ReaderPosition) -> Unit = {},
    val onReadingPositionObserved: (ReaderPosition) -> Unit = {},
    val onSearchTargetPositioned: (Int) -> Unit = {},
)

@Composable
fun ReaderScreen(
    state: ReaderState,
    callbacks: ReaderCallbacks,
    reviewUiState: ReviewUiState = ReviewUiState(chapterNote = state.chapterNote.orEmpty()),
    modifier: Modifier = Modifier,
    windowSize: DpSize? = null,
    contentsContent: (@Composable (closeLabel: String, onClose: () -> Unit) -> Unit)? = null,
    searchTarget: ReaderSearchTarget? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        BackHandler(reviewUiState.draftSession.blocksDismissal) { /* Explicit Save or Cancel owns a dirty draft. */ }
        val resolvedSize = windowSize ?: DpSize(maxWidth, maxHeight)
        val policy = ReaderLayoutPolicy.forWindow(resolvedSize.width.value.toInt(), resolvedSize.height.value.toInt())
        var reviewEnabled by rememberSaveable(state.bookId, state.chapterId, state.reviewEnabled) {
            mutableStateOf(state.reviewEnabled)
        }
        var contentsExpanded by rememberSaveable(state.bookId, state.chapterId) {
            mutableStateOf(policy.mode == ReaderLayoutMode.TABLET_LANDSCAPE)
        }
        var reviewExpanded by rememberSaveable(state.bookId, state.chapterId) {
            mutableStateOf(reviewEnabled && policy.mode == ReaderLayoutMode.TABLET_LANDSCAPE)
        }

        val effectivePanels = normalizeExpandedPanels(
            mode = policy.mode,
            reviewEnabled = reviewEnabled,
            contentsExpanded = contentsExpanded,
            reviewExpanded = reviewExpanded,
        )
        LaunchedEffect(policy.mode, reviewEnabled, effectivePanels) {
            contentsExpanded = effectivePanels.contents
            reviewExpanded = effectivePanels.review
        }

        AdaptiveReaderScaffold(
            policy = policy,
            contentsExpanded = effectivePanels.contents,
            reviewExpanded = effectivePanels.review,
            reviewEnabled = reviewEnabled,
            onDismissContents = { contentsExpanded = false },
            onDismissReview = { if (!reviewUiState.draftSession.blocksDismissal) reviewExpanded = false },
            onExpandContents = {
                if (policy.mode == ReaderLayoutMode.TABLET_PORTRAIT) reviewExpanded = false
                contentsExpanded = true
            },
            onExpandReview = {
                if (policy.mode == ReaderLayoutMode.TABLET_PORTRAIT) contentsExpanded = false
                reviewExpanded = true
            },
            contents = { closeLabel, onClose ->
                if (contentsContent == null) {
                    ContentsShell(state, closeLabel, onClose, callbacks.onChapterSelected)
                } else {
                    contentsContent(closeLabel, onClose)
                }
            },
            review = { closeLabel, onClose -> ReviewShell(state, reviewUiState, closeLabel, onClose, callbacks) },
            reader = {
                ReaderPane(
                    state = state,
                    policy = policy,
                    reviewEnabled = reviewEnabled,
                    showContentsButton = policy.mode != ReaderLayoutMode.TABLET_LANDSCAPE,
                    onOpenContents = {
                        if (policy.mode == ReaderLayoutMode.TABLET_PORTRAIT) reviewExpanded = false
                        contentsExpanded = true
                    },
                    onToggleReview = { enabled ->
                        reviewEnabled = enabled
                        reviewExpanded = enabled
                        if (enabled && policy.mode == ReaderLayoutMode.TABLET_PORTRAIT) contentsExpanded = false
                        callbacks.onReviewModeChanged(enabled)
                    },
                    callbacks = callbacks,
                    searchTarget = searchTarget,
                )
            },
        )
        reviewUiState.pendingDeletion?.let { token ->
            Snackbar(
                action = { TextButton(onClick = { callbacks.onUndoDeletion(token) }) { Text("Undo") } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) { Text("Review item deleted") }
        }
    }
}

private data class ExpandedPanels(
    val contents: Boolean,
    val review: Boolean,
)

private fun normalizeExpandedPanels(
    mode: ReaderLayoutMode,
    reviewEnabled: Boolean,
    contentsExpanded: Boolean,
    reviewExpanded: Boolean,
): ExpandedPanels {
    val eligibleReview = reviewEnabled && reviewExpanded
    if (mode == ReaderLayoutMode.TABLET_LANDSCAPE) {
        return ExpandedPanels(contents = contentsExpanded, review = eligibleReview)
    }
    return if (contentsExpanded && eligibleReview) {
        ExpandedPanels(contents = false, review = true)
    } else {
        ExpandedPanels(contents = contentsExpanded, review = eligibleReview)
    }
}

@Composable
@OptIn(kotlinx.coroutines.FlowPreview::class)
private fun ReaderPane(
    state: ReaderState,
    policy: ReaderLayoutPolicy,
    reviewEnabled: Boolean,
    showContentsButton: Boolean,
    onOpenContents: () -> Unit,
    onToggleReview: (Boolean) -> Unit,
    callbacks: ReaderCallbacks,
    searchTarget: ReaderSearchTarget?,
) {
    val initialIndex = state.readingPosition?.let { position ->
        state.document.blocks.indexOfFirst { it.sourceIndex >= position.blockIndex }.coerceAtLeast(0)
    } ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallbacks by rememberUpdatedState(callbacks)
    var latestPosition by remember(state.bookId, state.chapterId) { mutableStateOf<ReaderPosition?>(null) }
    var lastDispatchedPosition by remember(state.bookId, state.chapterId) { mutableStateOf<ReaderPosition?>(null) }
    fun dispatchLatestPosition() {
        latestPosition?.takeIf { it != lastDispatchedPosition }?.let { position ->
            lastDispatchedPosition = position
            currentCallbacks.onReadingPositionChanged(position)
        }
    }
    DisposableEffect(lifecycleOwner, state.bookId, state.chapterId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) dispatchLatestPosition()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dispatchLatestPosition()
        }
    }
    val targetBlockIndex = remember(state.chapterId, searchTarget) {
        searchTarget?.let { target ->
            state.document.blocks.indexOfFirst { block ->
                block.rawRange.startByte <= target.rawStartByte && target.rawStartByte < block.rawRange.endByte
            }.takeIf { it >= 0 }
        }
    }
    var targetPixelOffset by remember(state.chapterId, searchTarget) { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.chapterId, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .onEach { (index, _) ->
                state.document.blocks.getOrNull(index)?.let { block ->
                    val position = ReaderPosition(block.sourceIndex, block.rawRange.startByte)
                    latestPosition = position
                    currentCallbacks.onReadingPositionObserved(position)
                }
            }
            .debounce(450)
            .collect { dispatchLatestPosition() }
    }
    LaunchedEffect(targetBlockIndex, targetPixelOffset) {
        val index = targetBlockIndex ?: return@LaunchedEffect
        listState.scrollToItem(index, targetPixelOffset ?: 0)
    }
    Column(Modifier.fillMaxSize()) {
        ReaderTopBar(
            title = state.title,
            syncState = state.syncState,
            reviewEnabled = reviewEnabled,
            showContentsButton = showContentsButton,
            compactTitle = policy.mode == ReaderLayoutMode.PHONE,
            onOpenContents = onOpenContents,
            onToggleReview = onToggleReview,
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = policy.readerMaxWidthDp.dp)
                    .testTag("reader-column"),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = policy.readerHorizontalPaddingDp.dp,
                        end = policy.readerHorizontalPaddingDp.dp,
                        top = 32.dp,
                        bottom = 48.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize().testTag("reader-scroll"),
                ) {
                    if (state.document.blocks.isEmpty()) {
                        item {
                            EmptyChapter()
                        }
                    } else {
                        items(state.document.blocks, key = ReaderBlock::sourceIndex) { block ->
                            ReaderDocumentBlock(
                                block = block,
                                reviewEnabled = reviewEnabled,
                                onSelection = callbacks.onTextSelected,
                                searchTarget = searchTarget?.let { RawRange(it.rawStartByte, it.rawEndByte) },
                                onSearchTargetOffset = { offset ->
                                    if (block.sourceIndex == state.document.blocks.getOrNull(targetBlockIndex ?: -1)?.sourceIndex) {
                                        targetPixelOffset = offset
                                        callbacks.onSearchTargetPositioned(offset)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        ChapterNavigation(state, callbacks)
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    syncState: ReaderSyncState,
    reviewEnabled: Boolean,
    showContentsButton: Boolean,
    compactTitle: Boolean,
    onOpenContents: () -> Unit,
    onToggleReview: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (showContentsButton) {
                IconButton(
                    onClick = onOpenContents,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Open contents")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (compactTitle) title.substringBefore(" · ") else title,
                    style = if (compactTitle) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = syncState.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            ReviewToggle(reviewEnabled, onToggleReview)
        }
    }
}

@Composable
private fun ReviewToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    FilledTonalButton(
        onClick = { onToggle(!enabled) },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = if (enabled) "Review mode on" else "Review mode off"
                role = Role.Button
                toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off
            },
    ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Review", maxLines = 1)
    }
}

@Composable
private fun EmptyChapter() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp),
    ) {
        Text("This chapter is empty", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Its Markdown file is available, but it contains no readable prose yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChapterNavigation(state: ReaderState, callbacks: ReaderCallbacks) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            ChapterButton("Previous", state.previousChapter, true, callbacks.onPreviousChapter)
            ChapterButton("Next", state.nextChapter, false, callbacks.onNextChapter)
        }
    }
}

@Composable
private fun ChapterButton(
    label: String,
    chapter: ReaderChapter?,
    leading: Boolean,
    onClick: (ReaderChapter) -> Unit,
) {
    OutlinedButton(
        enabled = chapter != null,
        onClick = { chapter?.let(onClick) },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        if (leading) Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
        Text(label, maxLines = 1)
        if (!leading) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@Composable
private fun ContentsShell(
    state: ReaderState,
    closeLabel: String,
    onClose: () -> Unit,
    onChapterSelected: (ReaderChapter) -> Unit,
) {
    PanelColumn(
        title = "Contents",
        eyebrow = "${state.bookId} · current book",
        closeLabel = closeLabel,
        onClose = onClose,
    ) {
        Text("Chapters", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        state.previousChapter?.let { ChapterRow(it, false, onChapterSelected) }
        ChapterRow(ReaderChapter(state.chapterId, state.title), true, onChapterSelected)
        state.nextChapter?.let { ChapterRow(it, false, onChapterSelected) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "Search and the complete chapter list arrive with book setup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChapterRow(chapter: ReaderChapter, current: Boolean, onClick: (ReaderChapter) -> Unit) {
    Surface(
        color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        onClick = { onClick(chapter) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(
            chapter.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.Normal),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ReviewShell(
    state: ReaderState,
    reviewUiState: ReviewUiState,
    closeLabel: String,
    onClose: () -> Unit,
    callbacks: ReaderCallbacks,
) {
    PanelColumn(
        title = "Review",
        eyebrow = "Complete editorial overlay",
        closeLabel = closeLabel,
        onClose = onClose,
    ) {
        SelectionFlyout(reviewUiState.draftSession, callbacks.onSignalChosen, callbacks.onEditChosen)
        when (val draft = reviewUiState.draftSession.draft) {
            is ReviewDraft.Signal -> SignalComposer(
                draft,
                callbacks.onSignalTypeChanged,
                callbacks.onDraftTextChanged,
                callbacks.onSaveDraft,
                callbacks.onCancelDraft,
            )
            is ReviewDraft.Edit -> EditComposer(
                draft,
                ReviewDraftStateMachine.validate(reviewUiState.draftSession),
                callbacks.onDraftTextChanged,
                callbacks.onSaveDraft,
                callbacks.onCancelDraft,
            )
            null -> Unit
        }
        ConflictResolver(reviewUiState.conflicts, callbacks.onConflictChoice)
        reviewUiState.error?.let { error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error.message)
                    if (error.retryable) {
                        OutlinedButton(onClick = callbacks.onRetryReviewError) { Text("Retry") }
                    }
                }
            }
        }
        state.reviewItems?.signals?.forEach { signal ->
            ReviewRecordCard(
                title = signal.type.name.replace('_', ' ').lowercase().replaceFirstChar(Char::titlecase),
                preview = signal.comment.ifBlank { signal.selectedText },
                editLabel = "Edit signal ${signal.id}",
                deleteLabel = "Delete signal ${signal.id}",
                onEdit = { callbacks.onEditSignal(signal) },
                onDelete = { callbacks.onDeleteSignal(signal.id) },
            )
        }
        state.reviewItems?.edits?.forEach { edit ->
            ReviewRecordCard(
                title = "Edit",
                preview = "${edit.before} → ${edit.after}",
                editLabel = "Edit change ${edit.id}",
                deleteLabel = "Delete edit ${edit.id}",
                onEdit = { callbacks.onEditEdit(edit) },
                onDelete = { callbacks.onDeleteEdit(edit.id) },
            )
        }
        ChapterNote(
            text = reviewUiState.chapterNote.ifEmpty { state.chapterNote.orEmpty() },
            status = reviewUiState.noteSaveStatus,
            onTextChange = callbacks.onChapterNoteChanged,
            onFocusLost = callbacks.onChapterNoteFocusLost,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val reviewCount = state.document.reviewObjectCount
        Text("$reviewCount review ${if (reviewCount == 1) "item" else "items"}", style = MaterialTheme.typography.titleLarge)
        Text(
            if (reviewCount == 0) "The chapter has no anchored edits or passage notes." else "Highlights, edits, and comments are visible in the reading column.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.document.unresolved.forEach { unresolved ->
            OutlinedButton(onClick = { callbacks.onReanchor(unresolved.recordId) }) {
                Text("Re-anchor ${unresolved.recordId}")
            }
        }
    }
}

@Composable
private fun ReviewRecordCard(
    title: String,
    preview: String,
    editLabel: String,
    deleteLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(preview, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = editLabel }) { Text("Edit") }
                TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = deleteLabel }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun PanelColumn(
    title: String,
    eyebrow: String,
    closeLabel: String,
    onClose: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    eyebrow,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalIconButton(onClick = onClose, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Default.Close, contentDescription = closeLabel)
            }
        }
        HorizontalDivider()
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            content = body,
        )
    }
}

private val ReaderSyncState.label: String
    get() = when (this) {
        ReaderSyncState.SAVED -> "Saved"
        ReaderSyncState.WAITING_TO_SYNC -> "Waiting to sync"
        ReaderSyncState.SYNCING -> "Syncing"
        ReaderSyncState.SIGN_IN_REQUIRED -> "Sign in required"
        ReaderSyncState.ACTION_REQUIRED -> "Action required"
    }
