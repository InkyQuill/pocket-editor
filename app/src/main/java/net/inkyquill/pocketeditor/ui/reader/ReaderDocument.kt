package net.inkyquill.pocketeditor.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.RenderKind
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderComment
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderSourceSelection
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.review.helpResource
import net.inkyquill.pocketeditor.ui.review.labelResource
import net.inkyquill.pocketeditor.ui.review.signalColor
import net.inkyquill.pocketeditor.ui.theme.LocalReviewColors
import net.inkyquill.pocketeditor.ui.theme.LocalReaderTypography

private data class ReaderBlockPresentation(
    val style: TextStyle,
    val before: Dp,
    val after: Dp,
    val quote: Boolean = false,
    val listItem: Boolean = false,
)

private data class FootnoteTarget(val label: String, val start: Int, val end: Int)

@Composable
internal fun ReaderDocumentBlock(
    block: ReaderBlock,
    footnotes: Map<String, String> = emptyMap(),
    reviewEnabled: Boolean,
    onSelection: (Int, ReaderSourceSelection?) -> Unit,
    onSelectionBounds: (Int, Rect?) -> Unit,
    searchTarget: net.inkyquill.pocketeditor.markdown.RawRange? = null,
    onSearchTargetOffset: (Int) -> Unit = {},
) {
    if (block.kind == BlockKind.HIDDEN_SOURCE) return
    if (block.kind == BlockKind.THEMATIC_BREAK) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        return
    }
    val presentation = readerBlockPresentation(block)
    val headingModifier = if (block.kind == BlockKind.HEADING) Modifier.semantics { heading() } else Modifier
    val colors = LocalReviewColors.current
    val linkColor = MaterialTheme.colorScheme.primary
    var openFootnoteLabel by remember(block.sourceIndex) { mutableStateOf<String?>(null) }
    val canonicalRuns = block.runs
            .asSequence()
            .filterNot { it.kind == ReaderRunKind.ADDED }
            .map { it.copy(kind = ReaderRunKind.CANONICAL, signalIds = emptySet(), signalTypes = emptySet()) }
            .toList()
    val displayRuns = when {
        reviewEnabled -> block.runs
        canonicalRuns.joinToString("") { it.text } == block.canonicalText -> canonicalRuns
        else -> listOf(net.inkyquill.pocketeditor.reader.ReaderRun(block.canonicalText, ReaderRunKind.CANONICAL))
    }
    val targetDisplayRange = remember(block, searchTarget) { searchTarget?.let(block::displayRangeForRaw) }
    val footnoteTargets = remember(displayRuns) {
        buildList {
            var offset = 0
            displayRuns.forEach { run ->
                val end = offset + run.text.length
                run.footnoteLabel?.let { add(FootnoteTarget(it, offset, end)) }
                offset = end
            }
        }
    }
    val annotated = remember(displayRuns, reviewEnabled, colors, linkColor, targetDisplayRange) {
        AnnotatedString.Builder().apply {
            displayRuns.forEach { run ->
                val start = length
                append(run.text)
                addStyle(
                    when (run.renderKind) {
                        RenderKind.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
                        RenderKind.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
                        RenderKind.LINK -> SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        )
                        RenderKind.FOOTNOTE_REFERENCE -> SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            baselineShift = BaselineShift.Superscript,
                        )
                        RenderKind.TEXT, RenderKind.CODE, RenderKind.INERT_HTML -> SpanStyle()
                    },
                    start,
                    length,
                )
                val signalColor = run.signalTypes.firstOrNull()?.let { type -> colors.signalColor(type) }
                addStyle(
                    when (run.kind) {
                        ReaderRunKind.CANONICAL -> SpanStyle(background = signalColor?.copy(alpha = 0.22f) ?: Color.Transparent)
                        ReaderRunKind.DELETED -> SpanStyle(
                            color = colors.deletion,
                            background = colors.deletionContainer,
                            textDecoration = TextDecoration.LineThrough,
                        )
                        ReaderRunKind.ADDED -> SpanStyle(color = colors.addition, background = colors.additionContainer)
                    },
                    start,
                    length,
                )
            }
            targetDisplayRange?.let { range ->
                addStyle(
                    SpanStyle(background = colors.warning.copy(alpha = 0.45f)),
                    range.start,
                    range.end,
                )
            }
        }.toAnnotatedString()
    }
    val resources = LocalContext.current.resources
    val accessibilityDescription = remember(displayRuns, reviewEnabled, resources) {
        if (!reviewEnabled) null else displayRuns.joinToString(". ") { run ->
            when (run.kind) {
                ReaderRunKind.CANONICAL -> run.text
                ReaderRunKind.DELETED -> resources.getString(R.string.deleted_source_text, run.text)
                ReaderRunKind.ADDED -> resources.getString(R.string.added_replacement_text, run.text)
            }
        }
    }
    val searchResultDescription = remember(targetDisplayRange, annotated, resources) {
        targetDisplayRange?.let { range ->
            resources.getString(R.string.search_result, annotated.text.substring(range.start, range.end))
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = presentation.before, bottom = presentation.after)
            .testTag("reader-block-${block.sourceIndex}"),
    ) {
        ReaderBlockText(
            text = annotated,
            presentation = presentation,
            block = block,
            onSelection = onSelection,
            onSelectionBounds = onSelectionBounds,
            modifier = headingModifier,
            accessibilityDescription = accessibilityDescription,
            searchResultDescription = searchResultDescription,
            searchTargetOffset = targetDisplayRange?.start,
            onSearchTargetOffset = onSearchTargetOffset,
            footnoteTargets = footnoteTargets,
            onFootnoteClick = { openFootnoteLabel = it },
        )
        if (reviewEnabled) {
            val types = block.runs.flatMap { it.signalTypes }.distinct()
            if (types.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { type -> SignalLabel(type) }
                }
            }
            block.comments.forEach { comment -> CommentBlock(comment) }
        }
    }
    openFootnoteLabel?.let { label ->
        val note = footnotes[label] ?: return@let
        Popup(alignment = Alignment.Center, onDismissRequest = { openFootnoteLabel = null }) {
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.widthIn(max = 360.dp).padding(24.dp).testTag("footnote-popover"),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(20.dp),
                ) {
                    Text(stringResource(R.string.footnote_label, label), style = MaterialTheme.typography.labelLarge)
                    Text(note, style = MaterialTheme.typography.bodyLarge)
                    TextButton(
                        onClick = { openFootnoteLabel = null },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
private fun readerBlockPresentation(block: ReaderBlock): ReaderBlockPresentation {
    val type = LocalReaderTypography.current
    return when (block.kind) {
        BlockKind.HEADING -> when (block.headingLevel ?: 1) {
            1 -> ReaderBlockPresentation(type.h1, 24.dp, 10.dp)
            2 -> ReaderBlockPresentation(type.h2, 22.dp, 8.dp)
            3 -> ReaderBlockPresentation(type.h3, 18.dp, 6.dp)
            4 -> ReaderBlockPresentation(type.h4, 16.dp, 4.dp)
            5 -> ReaderBlockPresentation(type.h5, 14.dp, 4.dp)
            else -> ReaderBlockPresentation(type.h6, 12.dp, 4.dp)
        }
        BlockKind.QUOTE -> ReaderBlockPresentation(type.prose, 0.dp, 12.dp, quote = true)
        BlockKind.LIST_ITEM -> ReaderBlockPresentation(type.prose, 0.dp, 12.dp, listItem = true)
        BlockKind.CODE_BLOCK,
        BlockKind.TABLE_ROW,
        BlockKind.PARAGRAPH,
        BlockKind.HTML_BLOCK,
        -> ReaderBlockPresentation(type.prose, 0.dp, 12.dp)
        BlockKind.HIDDEN_SOURCE,
        BlockKind.THEMATIC_BREAK,
        -> ReaderBlockPresentation(type.prose, 0.dp, 0.dp)
    }
}

@Composable
private fun ReaderBlockText(
    text: AnnotatedString,
    presentation: ReaderBlockPresentation,
    block: ReaderBlock,
    onSelection: (Int, ReaderSourceSelection?) -> Unit,
    onSelectionBounds: (Int, Rect?) -> Unit,
    modifier: Modifier,
    accessibilityDescription: String?,
    searchResultDescription: String?,
    searchTargetOffset: Int?,
    onSearchTargetOffset: (Int) -> Unit,
    footnoteTargets: List<FootnoteTarget>,
    onFootnoteClick: (String) -> Unit,
) {
    val content: @Composable (Modifier) -> Unit = { textModifier ->
        ReviewableText(
            text = text,
            style = presentation.style,
            block = block,
            onSelection = onSelection,
            onSelectionBounds = onSelectionBounds,
            modifier = modifier.then(textModifier),
            accessibilityDescription = accessibilityDescription,
            searchResultDescription = searchResultDescription,
            searchTargetOffset = searchTargetOffset,
            onSearchTargetOffset = onSearchTargetOffset,
            footnoteTargets = footnoteTargets,
            onFootnoteClick = onFootnoteClick,
        )
    }
    when {
        presentation.quote -> Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VerticalDivider(
                modifier = Modifier.testTag("reader-quote-marker-${block.sourceIndex}"),
                thickness = 2.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            content(Modifier.weight(1f))
        }
        presentation.listItem -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "•",
                style = presentation.style,
                modifier = Modifier.width(12.dp).testTag("reader-list-marker-${block.sourceIndex}"),
            )
            content(Modifier.weight(1f))
        }
        else -> content(Modifier)
    }
}

@Composable
private fun ReviewableText(
    text: AnnotatedString,
    style: TextStyle,
    block: ReaderBlock,
    onSelection: (Int, ReaderSourceSelection?) -> Unit,
    onSelectionBounds: (Int, Rect?) -> Unit,
    modifier: Modifier,
    accessibilityDescription: String?,
    searchResultDescription: String?,
    searchTargetOffset: Int?,
    onSearchTargetOffset: (Int) -> Unit,
    footnoteTargets: List<FootnoteTarget>,
    onFootnoteClick: (String) -> Unit,
) {
    var value by remember(block.sourceIndex, text.text) { mutableStateOf(TextFieldValue(text.text)) }
    var isFocused by remember(block.sourceIndex, text.text) { mutableStateOf(false) }
    var textLayout by remember(block.sourceIndex, text.text) { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember(block.sourceIndex, text.text) { mutableStateOf<LayoutCoordinates?>(null) }
    val transformation = remember(text) {
        VisualTransformation { TransformedText(text, OffsetMapping.Identity) }
    }
    fun selectedBounds(selection: androidx.compose.ui.text.TextRange): Rect? {
        val layout = textLayout ?: return null
        val layoutCoordinates = coordinates ?: return null
        if (selection.collapsed) return null
        val start = layout.getBoundingBox(selection.min)
        val end = layout.getBoundingBox((selection.max - 1).coerceAtLeast(selection.min))
        return Rect(
            topLeft = layoutCoordinates.localToRoot(start.topLeft),
            bottomRight = layoutCoordinates.localToRoot(end.bottomRight),
        )
    }
    fun updateSelectionBounds(selection: androidx.compose.ui.text.TextRange = value.selection) {
        if (!selection.collapsed) onSelectionBounds(block.sourceIndex, selectedBounds(selection))
    }
    fun footnoteAt(offset: Int): FootnoteTarget? = footnoteTargets.firstOrNull { target ->
        offset in target.start until target.end || (offset - 1) in target.start until target.end
    }
    val latestSelection by rememberUpdatedState(value.selection)
    DisposableEffect(block.sourceIndex) {
        onDispose {
            if (!latestSelection.collapsed) {
                onSelectionBounds(block.sourceIndex, null)
            }
        }
    }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (next.text != value.text) return@BasicTextField
            value = next
            val selection = next.selection
            if (!selection.collapsed) {
                onSelection(block.sourceIndex, block.sourceSelection(selection.min, selection.max))
                updateSelectionBounds(selection)
            } else if (isFocused) {
                val offset = selection.start
                footnoteTargets.firstOrNull { offset in it.start..it.end }?.let {
                    onFootnoteClick(it.label)
                }
                onSelection(block.sourceIndex, null)
                onSelectionBounds(block.sourceIndex, null)
            }
        },
        readOnly = true,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onBackground),
        visualTransformation = transformation,
        onTextLayout = { layout: TextLayoutResult ->
            textLayout = layout
            updateSelectionBounds()
            searchTargetOffset?.let { characterOffset ->
                val line = layout.getLineForOffset(characterOffset.coerceAtMost(text.length))
                onSearchTargetOffset(layout.getLineTop(line).toInt())
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(footnoteTargets) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Final)
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                    if (up != null) {
                        textLayout
                            ?.getOffsetForPosition(down.position)
                            ?.let(::footnoteAt)
                            ?.let { onFootnoteClick(it.label) }
                    }
                }
            }
            .onFocusChanged { isFocused = it.isFocused }
            .testTag("reader-text-${block.sourceIndex}")
            .onGloballyPositioned {
                coordinates = it
                updateSelectionBounds()
            }
            .semantics {
            val descriptions = listOfNotNull(accessibilityDescription, searchResultDescription)
            if (descriptions.isNotEmpty()) contentDescription = descriptions.joinToString(". ")
            if (searchResultDescription != null) liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
private fun SignalLabel(type: SignalType) {
    val color = LocalReviewColors.current.signalColor(type)
    val description = stringResource(R.string.signal_description, stringResource(type.labelResource))
    // The signal's color is already shown as a highlight on the passage itself; this dot is a
    // compact, color-only echo of it for the block summary, not a second text label.
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .size(10.dp)
            .semantics { contentDescription = description },
    ) {}
}

@Composable
private fun CommentBlock(comment: ReaderComment) {
    val color = LocalReviewColors.current.signalColor(comment.type)
    val description = stringResource(
        R.string.comment_description,
        stringResource(comment.type.labelResource),
        stringResource(comment.type.helpResource),
    )
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, color.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    ) {
        Text(
            comment.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}
