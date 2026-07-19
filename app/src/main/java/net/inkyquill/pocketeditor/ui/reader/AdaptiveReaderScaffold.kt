package net.inkyquill.pocketeditor.ui.reader

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.ui.ReaderLayoutMode
import net.inkyquill.pocketeditor.ui.ReaderLayoutPolicy

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
                            EdgeControl(
                                label = "Open review panel",
                                side = EdgeSide.RIGHT,
                                onClick = onExpandReview,
                            )
                        }
                    }
                    if (contentsExpanded) {
                        ModalBottomSheet(
                            onDismissRequest = onDismissContents,
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.testTag("contents-sheet"),
                        ) { contents("Close contents", onDismissContents) }
                    }
                    if (reviewEnabled && reviewExpanded) {
                        ModalBottomSheet(
                            onDismissRequest = onDismissReview,
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.testTag("review-sheet"),
                        ) { review("Close review panel", onDismissReview) }
                    }
                }

                ReaderLayoutMode.TABLET_PORTRAIT -> Box(Modifier.fillMaxSize()) {
                    reader()
                    if (contentsExpanded) {
                        OverlayScrim(onDismissContents)
                        Surface(
                            tonalElevation = 4.dp,
                            shadowElevation = 12.dp,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(344.dp)
                                .testTag("contents-drawer"),
                        ) { contents("Close contents", onDismissContents) }
                    }
                    if (reviewEnabled && reviewExpanded) {
                        OverlayScrim(onDismissReview)
                        Surface(
                            tonalElevation = 4.dp,
                            shadowElevation = 12.dp,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(360.dp)
                                .testTag("review-overlay"),
                        ) { review("Close review panel", onDismissReview) }
                    } else if (reviewEnabled) {
                        EdgeControl("Expand review panel", EdgeSide.RIGHT, onExpandReview)
                    }
                }

                ReaderLayoutMode.TABLET_LANDSCAPE -> Row(Modifier.fillMaxSize()) {
                    if (contentsExpanded) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxHeight().width(248.dp).testTag("contents-sidebar"),
                        ) { contents("Collapse contents", onDismissContents) }
                    } else {
                        SideRailControl("Expand contents", EdgeSide.LEFT, onExpandContents)
                    }

                    Box(Modifier.weight(1f).fillMaxHeight()) { reader() }

                    if (reviewEnabled && reviewExpanded) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxHeight().width(296.dp).testTag("review-sidebar"),
                        ) { review("Collapse review panel", onDismissReview) }
                    } else if (reviewEnabled) {
                        SideRailControl("Expand review panel", EdgeSide.RIGHT, onExpandReview)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.OverlayScrim(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = onClick),
    )
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
