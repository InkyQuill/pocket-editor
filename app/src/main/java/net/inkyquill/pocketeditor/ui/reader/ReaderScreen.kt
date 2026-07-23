package net.inkyquill.pocketeditor.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
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
import net.inkyquill.pocketeditor.reader.ReviewRecordKind
import net.inkyquill.pocketeditor.markdown.RawRange
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach

import net.inkyquill.pocketeditor.ui.ReaderLayoutMode
import net.inkyquill.pocketeditor.ui.ReaderLayoutPolicy
import net.inkyquill.pocketeditor.ui.russianPluralStringResource
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.sync.ConflictChoice
import net.inkyquill.pocketeditor.anchor.Stale
import net.inkyquill.pocketeditor.anchor.Ambiguous
import net.inkyquill.pocketeditor.ui.review.ChapterNote
import net.inkyquill.pocketeditor.ui.review.ConflictResolver
import net.inkyquill.pocketeditor.ui.review.AnnotationComposerPlacement
import net.inkyquill.pocketeditor.ui.review.InlineAnnotationComposer
import net.inkyquill.pocketeditor.ui.review.labelResource
import net.inkyquill.pocketeditor.ui.review.ReviewDraft
import net.inkyquill.pocketeditor.ui.review.ReviewDraftSession
import net.inkyquill.pocketeditor.ui.review.ReviewSelection
import net.inkyquill.pocketeditor.ui.review.ReviewUiState
import net.inkyquill.pocketeditor.ui.review.SelectionFlyout
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff

data class ReaderSearchTarget(val rawStartByte: Int, val rawEndByte: Int)

private data class EphemeralDraftAnchor(
    val bounds: Rect,
    val selection: ReviewSelection,
    val draftKind: Class<out ReviewDraft>,
) {
    fun matches(draft: ReviewDraft?) = draft != null && draftKind.isInstance(draft) && draft.selection == selection
}

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
    val onConflictChoice: (String, String, ConflictChoice) -> Unit = { _, _, _ -> },
    val onReanchor: (String) -> Unit = {},
    val onEditSignal: (ReaderSignalItem) -> Unit = {},
    val onEditEdit: (ReaderEditItem) -> Unit = {},
    val onDeleteSignal: (String) -> Unit = {},
    val onDeleteEdit: (String) -> Unit = {},
    val onRetryReviewError: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onBreakObservedLock: (net.inkyquill.pocketeditor.reader.ReaderObservedLock) -> Unit = {},
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
        var confirmBreakLock by remember { mutableStateOf<net.inkyquill.pocketeditor.reader.ReaderObservedLock?>(null) }
        val resolvedSize = windowSize ?: DpSize(maxWidth, maxHeight)
        val policy = ReaderLayoutPolicy.forWindow(resolvedSize.width.value.toInt(), resolvedSize.height.value.toInt())
        val tabletDevice = LocalConfiguration.current.smallestScreenWidthDp >= 600
        val tabletFallback = tabletDevice || policy.mode != ReaderLayoutMode.PHONE
        var reviewEnabled by rememberSaveable(state.bookId, state.chapterId, state.reviewEnabled) {
            mutableStateOf(state.reviewEnabled)
        }
        var contentsExpanded by rememberSaveable(state.bookId, state.chapterId) {
            mutableStateOf(policy.mode == ReaderLayoutMode.TABLET_LANDSCAPE)
        }
        var reviewExpanded by rememberSaveable(state.bookId, state.chapterId) {
            mutableStateOf(state.reviewEnabled && policy.mode == ReaderLayoutMode.TABLET_LANDSCAPE)
        }

        val expandContents = {
            if (policy.mode != ReaderLayoutMode.TABLET_LANDSCAPE) reviewExpanded = false
            contentsExpanded = true
        }
        val expandReview = {
            if (policy.mode != ReaderLayoutMode.TABLET_LANDSCAPE) contentsExpanded = false
            reviewExpanded = true
        }

        // The scaffold suppresses the review panel/FAB while a draft session is open on
        // tablet-landscape (the sidebar would fight the inline composer for space); every
        // consumer of "is review actually enabled for chrome purposes" must share this value
        // so the FAB's own visibility and the reader's bottom padding never drift apart.
        val scaffoldReviewEnabled = reviewEnabled && (
            policy.mode != ReaderLayoutMode.TABLET_LANDSCAPE || reviewUiState.draftSession.draft == null
        )
        // Mirrors AdaptiveReaderScaffold's own FAB-rendering conditions exactly (phone:
        // reviewEnabled && !reviewExpanded; tablet-portrait: also requires !contentsExpanded;
        // tablet-landscape: never, it uses a side rail instead) so ReaderPane can reserve
        // exactly enough scroll padding for the FAB when - and only when - it is actually shown.
        val fabVisible = when (policy.mode) {
            ReaderLayoutMode.PHONE -> scaffoldReviewEnabled && !reviewExpanded
            ReaderLayoutMode.TABLET_PORTRAIT -> scaffoldReviewEnabled && !reviewExpanded && !contentsExpanded
            ReaderLayoutMode.TABLET_LANDSCAPE -> false
        }

        AdaptiveReaderScaffold(
            policy = policy,
            contentsExpanded = contentsExpanded,
            reviewExpanded = reviewExpanded,
            reviewEnabled = scaffoldReviewEnabled,
            onDismissContents = { contentsExpanded = false },
            onDismissReview = { if (!reviewUiState.draftSession.blocksDismissal) reviewExpanded = false },
            onExpandContents = expandContents,
            onExpandReview = expandReview,
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
                    tabletDevice = tabletFallback,
                    reviewEnabled = reviewEnabled,
                    fabVisible = fabVisible,
                    reviewDraftSession = reviewUiState.draftSession,
                    showContentsButton = policy.mode != ReaderLayoutMode.TABLET_LANDSCAPE,
                    onOpenContents = expandContents,
                    onToggleReview = { enabled ->
                        reviewEnabled = enabled
                        callbacks.onReviewModeChanged(enabled)
                    },
                    callbacks = callbacks,
                    onRequestBreakLock = { confirmBreakLock = it },
                    searchTarget = searchTarget,
                )
            },
        )
        BackHandler(reviewUiState.draftSession.draft != null) {
            if (!reviewUiState.draftSession.blocksDismissal) callbacks.onCancelDraft()
        }
        reviewUiState.pendingDeletion?.let { token ->
            Snackbar(
                action = { TextButton(onClick = { callbacks.onUndoDeletion(token) }) { Text(stringResource(R.string.undo)) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) { Text(stringResource(R.string.review_item_deleted)) }
        }
        confirmBreakLock?.let { lock ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .76f)).padding(24.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                    ) {
                        Text(stringResource(R.string.break_sync_lock_title), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            stringResource(R.string.break_sync_lock_explanation),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
                            TextButton(onClick = { confirmBreakLock = null }) { Text(stringResource(R.string.cancel)) }
                            Button(onClick = { confirmBreakLock = null; callbacks.onBreakObservedLock(lock) }) {
                                Text(stringResource(R.string.break_stale_lock))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(kotlinx.coroutines.FlowPreview::class)
private fun ReaderPane(
    state: ReaderState,
    policy: ReaderLayoutPolicy,
    tabletDevice: Boolean,
    reviewEnabled: Boolean,
    fabVisible: Boolean,
    reviewDraftSession: ReviewDraftSession,
    showContentsButton: Boolean,
    onOpenContents: () -> Unit,
    onToggleReview: (Boolean) -> Unit,
    callbacks: ReaderCallbacks,
    searchTarget: ReaderSearchTarget?,
    onRequestBreakLock: (net.inkyquill.pocketeditor.reader.ReaderObservedLock) -> Unit,
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
    var activeSelectionBlockIndex by remember(state.chapterId) { mutableStateOf<Int?>(null) }
    var selectionBoundsInRoot by remember(state.chapterId) { mutableStateOf<Rect?>(null) }
    var draftAnchor by remember(state.chapterId) { mutableStateOf<EphemeralDraftAnchor?>(null) }
    var readerColumnBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    var overlayHostBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    val estimatedFlyoutWidthPx = with(LocalDensity.current) { 220.dp.toPx() }
    var flyoutWidthPx by remember(state.chapterId) { mutableStateOf(estimatedFlyoutWidthPx) }
    val estimatedFlyoutHeightPx = with(LocalDensity.current) { 64.dp.toPx() }
    var flyoutHeightPx by remember(state.chapterId) { mutableStateOf(estimatedFlyoutHeightPx) }
    val estimatedComposerHeightPx = with(LocalDensity.current) { 320.dp.toPx() }
    var composerHeightPx by remember(state.chapterId) { mutableStateOf(estimatedComposerHeightPx) }
    val composerWidthPx = with(LocalDensity.current) { 320.dp.toPx() }
    val annotationGapPx = with(LocalDensity.current) { 16.dp.toPx() }
    val flyoutReservedAbovePx = with(LocalDensity.current) { 56.dp.toPx() }
    val composerEdgeMarginPx = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(state.chapterId, listState) {
        snapshotFlow {
            activeSelectionBlockIndex to listState.layoutInfo.visibleItemsInfo.map { it.key }
        }.collect { (activeBlockIndex, visibleKeys) ->
            if (activeBlockIndex != null && activeBlockIndex !in visibleKeys) {
                activeSelectionBlockIndex = null
                selectionBoundsInRoot = null
            }
        }
    }
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
    LaunchedEffect(reviewDraftSession.draft) {
        if (reviewDraftSession.draft != null && draftAnchor?.matches(reviewDraftSession.draft) != true) {
            draftAnchor = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        ReaderTopBar(
            title = state.title,
            syncState = state.syncState,
            syncReason = state.syncReason,
            observedLock = state.observedSyncLock,
            reviewEnabled = reviewEnabled,
            showContentsButton = showContentsButton,
            compactTitle = policy.mode == ReaderLayoutMode.PHONE,
            onOpenContents = onOpenContents,
            onToggleReview = onToggleReview,
            onSyncNow = callbacks.onSyncNow,
            onRequestBreakLock = onRequestBreakLock,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { overlayHostBoundsInRoot = it.boundsInRoot() }
                .testTag("reader-overlay-host"),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = policy.readerMaxWidthDp.dp)
                    .onGloballyPositioned { readerColumnBoundsInRoot = it.boundsInRoot() }
                    .testTag("reader-column"),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = policy.readerHorizontalPaddingDp.dp,
                        end = policy.readerHorizontalPaddingDp.dp,
                        top = 32.dp,
                        // 56dp FAB + 16dp margin + 24dp buffer so the last paragraph can
                        // scroll fully clear of it, not just adjacent to it.
                        bottom = if (fabVisible) 96.dp else 48.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
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
                                onSelection = { sourceIndex, selection ->
                                    if (selection != null) {
                                        activeSelectionBlockIndex = sourceIndex
                                        callbacks.onTextSelected(selection)
                                    } else if (activeSelectionBlockIndex == sourceIndex) {
                                        activeSelectionBlockIndex = null
                                        selectionBoundsInRoot = null
                                        callbacks.onTextSelected(null)
                                    }
                                },
                                onSelectionBounds = { sourceIndex, bounds ->
                                    if (activeSelectionBlockIndex == sourceIndex) selectionBoundsInRoot = bounds
                                },
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
            val selectionBounds = selectionBoundsInRoot
            val readerColumnBounds = readerColumnBoundsInRoot
            val overlayHostBounds = overlayHostBoundsInRoot
            if (selectionBounds != null && readerColumnBounds != null && overlayHostBounds != null) {
                SelectionFlyout(
                    session = reviewDraftSession,
                    onSignal = { type ->
                        draftAnchor = reviewDraftSession.pendingSelection?.let {
                            EphemeralDraftAnchor(selectionBounds, it, ReviewDraft.Signal::class.java)
                        }
                        callbacks.onSignalChosen(type)
                    },
                    onEdit = {
                        draftAnchor = reviewDraftSession.pendingSelection?.let {
                            EphemeralDraftAnchor(selectionBounds, it, ReviewDraft.Edit::class.java)
                        }
                        callbacks.onEditChosen()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .onGloballyPositioned {
                            flyoutWidthPx = it.size.width.toFloat()
                            flyoutHeightPx = it.size.height.toFloat()
                        }
                        .offset {
                            val below = flyoutPlacementIsBelow(
                                selection = selectionBounds,
                                viewport = readerColumnBounds,
                                flyoutHeightPx = flyoutHeightPx,
                                gapPx = annotationGapPx,
                                reservedAbovePx = flyoutReservedAbovePx,
                            )
                            val desiredTop = if (below) {
                                selectionBounds.bottom - readerColumnBounds.top + annotationGapPx
                            } else {
                                selectionBounds.top - readerColumnBounds.top - flyoutHeightPx - annotationGapPx
                            }
                            IntOffset(
                                (anchoredHorizontalOffsetInRoot(selectionBounds, readerColumnBounds, flyoutWidthPx) - overlayHostBounds.left).toInt(),
                                desiredTop.coerceIn(0f, (readerColumnBounds.height - flyoutHeightPx).coerceAtLeast(0f)).toInt(),
                            )
                        }
                        .testTag("selection-flyout"),
                )
            }
            val activeDraft = reviewDraftSession.draft
            val draftAnchorBounds = draftAnchor?.takeIf { it.matches(activeDraft) }?.bounds
            if (readerColumnBounds != null && overlayHostBounds != null && activeDraft != null) {
                // Decided once per editing session (keyed on the anchor, not recomputed every
                // frame) so opening the keyboard - which shrinks readerColumnBounds - can never
                // flip Below/Above into a modal mid-edit. Switching branches recreates the
                // composer's composition subtree, which drops text-field focus.
                val placement = remember(draftAnchor) {
                    draftAnchorBounds?.let { anchor ->
                        annotationPlacement(
                            selection = anchor,
                            viewport = readerColumnBounds,
                            composerHeightPx = composerHeightPx,
                            composerWidthPx = composerWidthPx,
                            gapPx = annotationGapPx,
                            tablet = tabletDevice,
                        )
                    } ?: if (tabletDevice) AnnotationComposerPlacement.TabletModal else AnnotationComposerPlacement.PhoneSheet
                }
                val composerModifier = when (placement) {
                    AnnotationComposerPlacement.Below -> Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            val anchor = requireNotNull(draftAnchorBounds)
                            val maxTop = readerColumnBounds.bottom - readerColumnBounds.top - composerHeightPx
                            val desiredTop = anchor.bottom - readerColumnBounds.top + annotationGapPx
                            IntOffset(
                                (anchoredHorizontalOffsetInRoot(anchor, readerColumnBounds, composerWidthPx, composerEdgeMarginPx) - overlayHostBounds.left).toInt(),
                                desiredTop.coerceAtMost(maxTop).toInt(),
                            )
                        }
                    AnnotationComposerPlacement.Above -> Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            val anchor = requireNotNull(draftAnchorBounds)
                            val desiredTop = anchor.top - readerColumnBounds.top - composerHeightPx - annotationGapPx
                            IntOffset(
                                (anchoredHorizontalOffsetInRoot(anchor, readerColumnBounds, composerWidthPx, composerEdgeMarginPx) - overlayHostBounds.left).toInt(),
                                desiredTop.coerceAtLeast(0f).toInt(),
                            )
                        }
                    AnnotationComposerPlacement.PhoneSheet,
                    AnnotationComposerPlacement.TabletModal,
                    -> Modifier
                }
                InlineAnnotationComposer(
                    session = reviewDraftSession,
                    callbacks = callbacks.copy(
                        onSaveDraft = {
                            draftAnchor = null
                            callbacks.onSaveDraft()
                        },
                        onCancelDraft = {
                            draftAnchor = null
                            callbacks.onCancelDraft()
                        },
                    ),
                    placement = placement,
                    modifier = composerModifier
                        .widthIn(max = 320.dp)
                        .onSizeChanged { composerHeightPx = it.height.toFloat() },
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    syncState: ReaderSyncState,
    syncReason: String?,
    observedLock: net.inkyquill.pocketeditor.reader.ReaderObservedLock?,
    reviewEnabled: Boolean,
    showContentsButton: Boolean,
    compactTitle: Boolean,
    onOpenContents: () -> Unit,
    onToggleReview: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onRequestBreakLock: (net.inkyquill.pocketeditor.reader.ReaderObservedLock) -> Unit,
) {
    val openContentsDescription = stringResource(R.string.open_contents)
    val syncDescription = stringResource(
        if (syncState == ReaderSyncState.WAITING_TO_SYNC) R.string.sync_now else R.string.retry_sync,
    )
    val breakLockDescription = stringResource(R.string.break_observed_stale_sync_lock)
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
                    Icon(Icons.Default.Menu, contentDescription = openContentsDescription)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (compactTitle) title.substringBefore(" · ") else title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("reader-topbar-title"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = syncState.label(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("reader-topbar-sync"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                syncReason?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
            }
            if (syncState == ReaderSyncState.WAITING_TO_SYNC || syncState == ReaderSyncState.SIGN_IN_REQUIRED || syncState == ReaderSyncState.ACTION_REQUIRED) {
                IconButton(onClick = onSyncNow, modifier = Modifier.semantics { contentDescription = syncDescription }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            }
            observedLock?.let { lock ->
                IconButton(onClick = { onRequestBreakLock(lock) }, modifier = Modifier.semantics { contentDescription = breakLockDescription }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }
            ReviewToggle(reviewEnabled, onToggleReview)
        }
    }
}

@Composable
private fun ReviewToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val description = stringResource(if (enabled) R.string.review_mode_on else R.string.review_mode_off)
    FilledIconButton(
        onClick = { onToggle(!enabled) },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
                toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off
            },
    ) {
        Icon(
            imageVector = if (enabled) Lucide.Eye else Lucide.EyeOff,
            contentDescription = null,
        )
    }
}

@Composable
private fun EmptyChapter() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp),
    ) {
        Text(stringResource(R.string.empty_chapter), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.empty_chapter_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        title = stringResource(R.string.contents),
        eyebrow = stringResource(R.string.current_book_eyebrow, state.bookId),
        closeLabel = closeLabel,
        onClose = onClose,
    ) {
        Text(stringResource(R.string.chapters), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        state.previousChapter?.let { ChapterRow(it, false, onChapterSelected) }
        ChapterRow(ReaderChapter(state.chapterId, state.title), true, onChapterSelected)
        state.nextChapter?.let { ChapterRow(it, false, onChapterSelected) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            stringResource(R.string.reader_setup_placeholder),
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
        title = stringResource(R.string.review),
        eyebrow = stringResource(R.string.complete_editorial_overlay),
        closeLabel = closeLabel,
        onClose = onClose,
    ) {
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
                        OutlinedButton(onClick = callbacks.onRetryReviewError) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
        state.reviewItems?.signals?.forEach { signal ->
            ReviewRecordCard(
                title = stringResource(signal.type.labelResource),
                preview = signal.comment.ifBlank { signal.selectedText },
                editLabel = stringResource(R.string.edit_signal, signal.id),
                deleteLabel = stringResource(R.string.delete_signal, signal.id),
                onEdit = { callbacks.onEditSignal(signal) },
                onDelete = { callbacks.onDeleteSignal(signal.id) },
            )
        }
        state.reviewItems?.edits?.forEach { edit ->
            ReviewRecordCard(
                title = stringResource(R.string.edit),
                preview = "${edit.before} → ${edit.after}",
                editLabel = stringResource(R.string.edit_change, edit.id),
                deleteLabel = stringResource(R.string.delete_edit, edit.id),
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
        Text(
            russianPluralStringResource(R.plurals.review_items_count, reviewCount, reviewCount),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(if (reviewCount == 0) R.string.no_anchored_review_items else R.string.review_items_visible),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.document.unresolved.forEach { unresolved ->
            val recordName = stringResource(
                if (unresolved.kind == ReviewRecordKind.SIGNAL) R.string.signal_record else R.string.edit_record,
            )
            OutlinedButton(onClick = { callbacks.onReanchor(unresolved.recordId) }) {
                Text(
                    when (unresolved.resolution) {
                        Stale -> stringResource(R.string.find_new_passage_for_stale, recordName)
                        is Ambiguous -> stringResource(R.string.choose_passage_for_ambiguous, recordName)
                        else -> stringResource(R.string.reanchor_record, recordName)
                    },
                )
            }
        }
    }
}

internal fun annotationPlacement(
    selection: Rect,
    viewport: Rect,
    composerHeightPx: Float,
    composerWidthPx: Float,
    gapPx: Float,
    tablet: Boolean,
): AnnotationComposerPlacement = when {
    viewport.width < composerWidthPx -> if (tablet) AnnotationComposerPlacement.TabletModal else AnnotationComposerPlacement.PhoneSheet
    viewport.bottom - selection.bottom >= composerHeightPx + gapPx -> AnnotationComposerPlacement.Below
    selection.top - viewport.top >= composerHeightPx + gapPx -> AnnotationComposerPlacement.Above
    tablet -> AnnotationComposerPlacement.TabletModal
    else -> AnnotationComposerPlacement.PhoneSheet
}

internal fun flyoutPlacementIsBelow(
    selection: Rect,
    viewport: Rect,
    flyoutHeightPx: Float,
    gapPx: Float,
    reservedAbovePx: Float,
): Boolean = when {
    viewport.bottom - selection.bottom >= flyoutHeightPx + gapPx -> true
    selection.top - viewport.top >= flyoutHeightPx + gapPx + reservedAbovePx -> false
    else -> true
}

private fun anchoredHorizontalOffset(anchor: Rect, viewport: Rect, contentWidthPx: Float, marginPx: Float): Int =
    (anchor.left - viewport.left)
        .coerceIn(marginPx, (viewport.width - contentWidthPx - marginPx).coerceAtLeast(marginPx))
        .toInt()

internal fun anchoredHorizontalOffsetInRoot(anchor: Rect, viewport: Rect, contentWidthPx: Float, marginPx: Float = 0f): Int =
    viewport.left.toInt() + anchoredHorizontalOffset(anchor, viewport, contentWidthPx, marginPx)

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
                TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = editLabel }) { Text(stringResource(R.string.edit_action)) }
                TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = deleteLabel }) { Text(stringResource(R.string.delete)) }
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

@Composable
private fun ReaderSyncState.label(): String =
    stringResource(
        when (this) {
            ReaderSyncState.SAVED -> R.string.saved
            ReaderSyncState.WAITING_TO_SYNC -> R.string.waiting_to_sync
            ReaderSyncState.SYNCING -> R.string.syncing
            ReaderSyncState.SIGN_IN_REQUIRED -> R.string.sign_in_required
            ReaderSyncState.ACTION_REQUIRED -> R.string.action_required
        },
    )
