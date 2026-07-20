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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.RenderKind
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderComment
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderSourceSelection
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.review.help
import net.inkyquill.pocketeditor.ui.review.label
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

@Composable
internal fun ReaderDocumentBlock(
    block: ReaderBlock,
    reviewEnabled: Boolean,
    onSelection: (ReaderSourceSelection?) -> Unit,
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
    val displayRuns = if (reviewEnabled) {
        block.runs
    } else {
        block.runs
            .asSequence()
            .filterNot { it.kind == ReaderRunKind.ADDED }
            .map { it.copy(kind = ReaderRunKind.CANONICAL, signalIds = emptySet(), signalTypes = emptySet()) }
            .toList()
    }.ifEmpty { listOf(net.inkyquill.pocketeditor.reader.ReaderRun(block.canonicalText, ReaderRunKind.CANONICAL)) }
    val targetDisplayRange = remember(block, searchTarget) { searchTarget?.let(block::displayRangeForRaw) }
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
    val accessibilityDescription = remember(displayRuns, reviewEnabled) {
        if (!reviewEnabled) null else displayRuns.joinToString(". ") { run ->
            when (run.kind) {
                ReaderRunKind.CANONICAL -> run.text
                ReaderRunKind.DELETED -> "Deleted source text: ${run.text}"
                ReaderRunKind.ADDED -> "Added replacement text: ${run.text}"
            }
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
            modifier = headingModifier,
            accessibilityDescription = accessibilityDescription,
            searchResultDescription = targetDisplayRange?.let { range ->
                "Search result: ${annotated.text.substring(range.start, range.end)}"
            },
            searchTargetOffset = targetDisplayRange?.start,
            onSearchTargetOffset = onSearchTargetOffset,
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
    onSelection: (ReaderSourceSelection?) -> Unit,
    modifier: Modifier,
    accessibilityDescription: String?,
    searchResultDescription: String?,
    searchTargetOffset: Int?,
    onSearchTargetOffset: (Int) -> Unit,
) {
    val content: @Composable (Modifier) -> Unit = { textModifier ->
        ReviewableText(
            text = text,
            style = presentation.style,
            block = block,
            onSelection = onSelection,
            modifier = modifier.then(textModifier),
            accessibilityDescription = accessibilityDescription,
            searchResultDescription = searchResultDescription,
            searchTargetOffset = searchTargetOffset,
            onSearchTargetOffset = onSearchTargetOffset,
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
    onSelection: (ReaderSourceSelection?) -> Unit,
    modifier: Modifier,
    accessibilityDescription: String?,
    searchResultDescription: String?,
    searchTargetOffset: Int?,
    onSearchTargetOffset: (Int) -> Unit,
) {
    var value by remember(block.sourceIndex, text.text) { mutableStateOf(TextFieldValue(text.text)) }
    val transformation = remember(text) {
        VisualTransformation { TransformedText(text, OffsetMapping.Identity) }
    }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (next.text != value.text) return@BasicTextField
            value = next
            val selection = next.selection
            if (!selection.collapsed) onSelection(block.sourceSelection(selection.min, selection.max))
        },
        readOnly = true,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onBackground),
        visualTransformation = transformation,
        onTextLayout = { layout: TextLayoutResult ->
            searchTargetOffset?.let { characterOffset ->
                val line = layout.getLineForOffset(characterOffset.coerceAtMost(text.length))
                onSearchTargetOffset(layout.getLineTop(line).toInt())
            }
        },
        modifier = modifier.fillMaxWidth().semantics {
            val descriptions = listOfNotNull(accessibilityDescription, searchResultDescription)
            if (descriptions.isNotEmpty()) contentDescription = descriptions.joinToString(". ")
            if (searchResultDescription != null) liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
private fun SignalLabel(type: SignalType) {
    val color = LocalReviewColors.current.signalColor(type)
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.semantics { contentDescription = "${type.label} signal" },
    ) {
        Text(type.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun CommentBlock(comment: ReaderComment) {
    val color = LocalReviewColors.current.signalColor(comment.type)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, color.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "${comment.type.label} comment. ${comment.type.help}"
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(12.dp)) {
            Text(comment.type.label, style = MaterialTheme.typography.labelMedium, color = color)
            Text(comment.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
