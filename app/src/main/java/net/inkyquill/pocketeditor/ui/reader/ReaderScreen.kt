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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderChapter
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.ui.ReaderLayoutMode
import net.inkyquill.pocketeditor.ui.ReaderLayoutPolicy

data class ReaderCallbacks(
    val onReviewModeChanged: (Boolean) -> Unit = {},
    val onPreviousChapter: (ReaderChapter) -> Unit = {},
    val onNextChapter: (ReaderChapter) -> Unit = {},
    val onChapterSelected: (ReaderChapter) -> Unit = {},
)

@Composable
fun ReaderScreen(
    state: ReaderState,
    callbacks: ReaderCallbacks,
    modifier: Modifier = Modifier,
    windowSize: DpSize? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val resolvedSize = windowSize ?: DpSize(maxWidth, maxHeight)
        val policy = ReaderLayoutPolicy.forWindow(resolvedSize.width.value.toInt(), resolvedSize.height.value.toInt())
        var reviewEnabled by rememberSaveable(state.bookId, state.chapterId) { mutableStateOf(state.reviewEnabled) }
        var contentsExpanded by rememberSaveable(state.bookId, state.chapterId) {
            mutableStateOf(policy.mode == ReaderLayoutMode.TABLET_LANDSCAPE)
        }
        var reviewExpanded by rememberSaveable(state.bookId, state.chapterId) {
            mutableStateOf(reviewEnabled && policy.mode == ReaderLayoutMode.TABLET_LANDSCAPE)
        }

        LaunchedEffect(state.reviewEnabled) { reviewEnabled = state.reviewEnabled }

        AdaptiveReaderScaffold(
            policy = policy,
            contentsExpanded = contentsExpanded,
            reviewExpanded = reviewExpanded,
            reviewEnabled = reviewEnabled,
            isContentsOpen = { contentsExpanded },
            isReviewOpen = { reviewEnabled && reviewExpanded },
            onDismissContents = { contentsExpanded = false },
            onDismissReview = { reviewExpanded = false },
            onExpandContents = {
                if (policy.mode == ReaderLayoutMode.TABLET_PORTRAIT) reviewExpanded = false
                contentsExpanded = true
            },
            onExpandReview = {
                if (policy.mode == ReaderLayoutMode.TABLET_PORTRAIT) contentsExpanded = false
                reviewExpanded = true
            },
            contents = { closeLabel, onClose ->
                ContentsShell(state, closeLabel, onClose, callbacks.onChapterSelected)
            },
            review = { closeLabel, onClose -> ReviewShell(state, closeLabel, onClose) },
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
                )
            },
        )
    }
}

@Composable
private fun ReaderPane(
    state: ReaderState,
    policy: ReaderLayoutPolicy,
    reviewEnabled: Boolean,
    showContentsButton: Boolean,
    onOpenContents: () -> Unit,
    onToggleReview: (Boolean) -> Unit,
    callbacks: ReaderCallbacks,
) {
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
                            ProseBlock(block)
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
private fun ProseBlock(block: ReaderBlock) {
    if (block.kind == BlockKind.HIDDEN_SOURCE) return
    val style = when (block.kind) {
        BlockKind.HEADING -> MaterialTheme.typography.displaySmall
        BlockKind.CODE_BLOCK, BlockKind.TABLE_ROW -> MaterialTheme.typography.bodyMedium
        else -> MaterialTheme.typography.bodyLarge
    }
    val modifier = if (block.kind == BlockKind.HEADING) Modifier.semantics { heading() } else Modifier
    when (block.kind) {
        BlockKind.THEMATIC_BREAK -> HorizontalDivider(Modifier.padding(vertical = 12.dp))
        BlockKind.QUOTE -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        ) {
            Text(
                text = block.canonicalText,
                style = style.copy(fontStyle = FontStyle.Italic),
                modifier = modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
        BlockKind.LIST_ITEM -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("•", style = style, color = MaterialTheme.colorScheme.primary)
            Text(block.canonicalText, style = style, modifier = modifier.weight(1f))
        }
        else -> Text(block.canonicalText, style = style, modifier = modifier.fillMaxWidth())
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
private fun ReviewShell(state: ReaderState, closeLabel: String, onClose: () -> Unit) {
    PanelColumn(
        title = "Review",
        eyebrow = "Complete editorial overlay",
        closeLabel = closeLabel,
        onClose = onClose,
    ) {
        Text("Chapter note", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                state.chapterNote?.takeIf(String::isNotBlank) ?: "No chapter note",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.chapterNote.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val reviewCount = state.document.reviewObjectCount
        Text("$reviewCount review ${if (reviewCount == 1) "item" else "items"}", style = MaterialTheme.typography.titleLarge)
        Text(
            if (reviewCount == 0) "The chapter has no anchored edits or passage notes." else "Highlights, edits, and comments are visible in the reading column.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    Column(Modifier.fillMaxSize()) {
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
            modifier = Modifier.fillMaxWidth().padding(20.dp),
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
