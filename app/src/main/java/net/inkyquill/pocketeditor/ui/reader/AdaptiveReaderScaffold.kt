package net.inkyquill.pocketeditor.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquareText
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.ui.ReaderLayoutMode
import net.inkyquill.pocketeditor.ui.ReaderLayoutPolicy
import net.inkyquill.pocketeditor.ui.theme.LocalOverlayScrim

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AdaptiveReaderScaffold(
    policy: ReaderLayoutPolicy,
    contentsExpanded: Boolean,
    reviewExpanded: Boolean,
    reviewEnabled: Boolean,
    onDismissContents: () -> Unit,
    onDismissReview: () -> Unit,
    onExpandContents: () -> Unit,
    onExpandReview: () -> Unit,
    contents: @Composable (closeLabel: String, onClose: () -> Unit) -> Unit,
    review: @Composable (closeLabel: String, onClose: () -> Unit) -> Unit,
    reader: @Composable () -> Unit,
) {
    val closeContents = stringResource(R.string.close_contents)
    val closeReview = stringResource(R.string.close_review_panel)
    val dismissContents = stringResource(R.string.dismiss_contents)
    val dismissReview = stringResource(R.string.dismiss_review)
    val contentsTitle = stringResource(R.string.contents)
    val reviewTitle = stringResource(R.string.review)
    val collapseContents = stringResource(R.string.collapse_contents)
    val expandContents = stringResource(R.string.expand_contents)
    val collapseReview = stringResource(R.string.collapse_review_panel)
    val expandReview = stringResource(R.string.expand_review_panel)
    val fullHeightContentsSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fullHeightReviewSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val phoneReviewOpen = policy.mode == ReaderLayoutMode.PHONE && reviewEnabled && reviewExpanded
    val phoneContentsOpen = policy.mode == ReaderLayoutMode.PHONE && contentsExpanded && !phoneReviewOpen
    val portraitReviewOpen = policy.mode == ReaderLayoutMode.TABLET_PORTRAIT && reviewEnabled && reviewExpanded
    val portraitContentsOpen = policy.mode == ReaderLayoutMode.TABLET_PORTRAIT && contentsExpanded && !portraitReviewOpen
    BackHandler(enabled = portraitContentsOpen || portraitReviewOpen) {
        if (portraitReviewOpen) onDismissReview() else onDismissContents()
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().testTag("reader-root"),
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (policy.mode) {
                ReaderLayoutMode.PHONE -> {
                    Box(Modifier.fillMaxSize()) {
                        reader()
                        if (reviewEnabled && !reviewExpanded) {
                            ReviewFab(onClick = onExpandReview)
                        }
                    }
                    if (phoneContentsOpen) {
                        ModalBottomSheet(
                            onDismissRequest = onDismissContents,
                            sheetState = fullHeightContentsSheet,
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.testTag("contents-sheet"),
                        ) { contents(closeContents, onDismissContents) }
                    }
                    if (phoneReviewOpen) {
                        ModalBottomSheet(
                            onDismissRequest = onDismissReview,
                            sheetState = fullHeightReviewSheet,
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.testTag("review-sheet"),
                        ) { review(closeReview, onDismissReview) }
                    }
                }

                ReaderLayoutMode.TABLET_PORTRAIT -> Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (portraitContentsOpen || portraitReviewOpen) {
                                    Modifier.clearAndSetSemantics { }
                                } else {
                                    Modifier
                                },
                            ),
                    ) { reader() }
                    if (portraitContentsOpen) {
                        OverlayScrim(
                            tag = "contents-scrim",
                            label = dismissContents,
                            panelSide = EdgeSide.LEFT,
                            panelWidth = 344.dp,
                            onClick = onDismissContents,
                        )
                        Surface(
                            tonalElevation = 4.dp,
                            shadowElevation = 12.dp,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(344.dp)
                                .testTag("contents-drawer")
                                .semantics {
                                    paneTitle = contentsTitle
                                    isTraversalGroup = true
                                    dismiss {
                                        onDismissContents()
                                        true
                                    }
                                },
                        ) { contents(closeContents, onDismissContents) }
                    }
                    if (reviewEnabled && reviewExpanded) {
                        OverlayScrim(
                            tag = "review-scrim",
                            label = dismissReview,
                            panelSide = EdgeSide.RIGHT,
                            panelWidth = 360.dp,
                            onClick = onDismissReview,
                        )
                        Surface(
                            tonalElevation = 4.dp,
                            shadowElevation = 12.dp,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(360.dp)
                                .testTag("review-overlay")
                                .semantics {
                                    paneTitle = reviewTitle
                                    isTraversalGroup = true
                                    dismiss {
                                        onDismissReview()
                                        true
                                    }
                                },
                        ) { review(closeReview, onDismissReview) }
                    } else if (reviewEnabled && !contentsExpanded) {
                        ReviewFab(onClick = onExpandReview)
                    }
                }

                ReaderLayoutMode.TABLET_LANDSCAPE -> Row(Modifier.fillMaxSize()) {
                    if (contentsExpanded) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxHeight().width(248.dp).testTag("contents-sidebar"),
                        ) { contents(collapseContents, onDismissContents) }
                    } else {
                        SideRailControl(expandContents, EdgeSide.LEFT, onExpandContents)
                    }

                    Box(Modifier.weight(1f).fillMaxHeight()) { reader() }

                    if (reviewEnabled && reviewExpanded) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxHeight().width(296.dp).testTag("review-sidebar"),
                        ) { review(collapseReview, onDismissReview) }
                    } else if (reviewEnabled) {
                        SideRailControl(expandReview, EdgeSide.RIGHT, onExpandReview)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ReviewFab(onClick: () -> Unit) {
    val description = stringResource(R.string.open_review_panel)
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp)
            .size(56.dp)
            .semantics { contentDescription = description },
    ) {
        Icon(imageVector = Lucide.MessageSquareText, contentDescription = null)
    }
}

@Composable
private fun BoxScope.OverlayScrim(
    tag: String,
    label: String,
    panelSide: EdgeSide,
    panelWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(LocalOverlayScrim.current),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    start = if (panelSide == EdgeSide.LEFT) panelWidth else 0.dp,
                    end = if (panelSide == EdgeSide.RIGHT) panelWidth else 0.dp,
                )
                .testTag(tag)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
        )
    }
}

private enum class EdgeSide { LEFT, RIGHT }

@Composable
private fun BoxScope.EdgeControl(label: String, side: EdgeSide, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = if (side == EdgeSide.LEFT) {
            MaterialTheme.shapes.large.copy(
                topStart = androidx.compose.foundation.shape.CornerSize(0.dp),
                bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            )
        } else {
            MaterialTheme.shapes.large.copy(
                topEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
                bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
            )
        },
        shadowElevation = 6.dp,
        modifier = Modifier
            .align(if (side == EdgeSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button }
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 16.dp),
    ) {
        Icon(
            imageVector = if (side == EdgeSide.LEFT) {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowLeft
            },
            contentDescription = null,
        )
    }
}

@Composable
private fun SideRailControl(label: String, side: EdgeSide, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxHeight().width(52.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            EdgeControl(label, side, onClick)
        }
    }
}
