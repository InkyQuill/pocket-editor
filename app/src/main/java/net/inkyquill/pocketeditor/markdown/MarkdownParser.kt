package net.inkyquill.pocketeditor.markdown

import org.commonmark.Extension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

object MarkdownParser {
    private val extensions: List<Extension> = listOf(TablesExtension.create())
    private val parser = Parser.builder()
        .extensions(extensions)
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    fun parse(source: String): RenderedDocument {
        val index = Utf8Index(source)
        val frontMatter = frontMatterRange(source, index)
        val drafts = buildList {
            if (frontMatter != null) add(BlockDraft.hidden(frontMatter))
            collectBlocks(parser.parse(source), source, index, frontMatter, this)
        }
        val blocks = drafts.mapIndexed { blockIndex, draft -> draft.finish(blockIndex) }
        return RenderedDocument(source, source.encodeToByteArray(), blocks)
    }

    private fun collectBlocks(
        node: Node,
        source: String,
        index: Utf8Index,
        frontMatter: RawRange?,
        output: MutableList<BlockDraft>,
    ) {
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            val raw = child.rawRange(index)
            if (raw != null && frontMatter?.intersects(raw) == true) {
                child = next
                continue
            }
            when (child) {
                is Heading -> output += renderInlineBlock(
                    node = child,
                    kind = BlockKind.HEADING,
                    source = source,
                    index = index,
                    headingLevel = child.level,
                )
                is Paragraph -> output += renderInlineBlock(child, child.paragraphKind(), source, index)
                is FencedCodeBlock -> output += renderProtectedBlock(child, child.literal, BlockKind.CODE_BLOCK, index)
                is IndentedCodeBlock -> output += renderProtectedBlock(child, child.literal, BlockKind.CODE_BLOCK, index)
                is HtmlBlock -> output += renderProtectedBlock(child, child.literal, BlockKind.HTML_BLOCK, index, RenderKind.INERT_HTML)
                is TableRow -> output += renderTableRow(child, source, index)
                is ThematicBreak -> output += renderProtectedBlock(child, "", BlockKind.THEMATIC_BREAK, index)
                is TableBlock, is Document, is BlockQuote, is ListItem -> collectBlocks(child, source, index, frontMatter, output)
                else -> collectBlocks(child, source, index, frontMatter, output)
            }
            child = next
        }
    }

    private fun Paragraph.paragraphKind(): BlockKind = when {
        ancestors().any { it is BlockQuote } -> BlockKind.QUOTE
        ancestors().any { it is ListItem } -> BlockKind.LIST_ITEM
        else -> BlockKind.PARAGRAPH
    }

    private fun Node.ancestors(): Sequence<Node> = generateSequence(parent) { it.parent }

    private fun renderInlineBlock(
        node: Node,
        kind: BlockKind,
        source: String,
        index: Utf8Index,
        headingLevel: Int? = null,
    ): BlockDraft {
        val builder = InlineBuilder(source, index)
        var child = node.firstChild
        while (child != null) {
            builder.render(child)
            child = child.next
        }
        return builder.build(kind, requireNotNull(node.rawRange(index)), headingLevel = headingLevel)
    }

    private fun renderTableRow(node: TableRow, source: String, index: Utf8Index): BlockDraft {
        val builder = InlineBuilder(source, index)
        var cell = node.firstChild
        var first = true
        while (cell != null) {
            if (cell is TableCell) {
                if (!first) builder.appendSynthetic(" | ")
                var inline = cell.firstChild
                while (inline != null) {
                    builder.render(inline)
                    inline = inline.next
                }
                first = false
            }
            cell = cell.next
        }
        val raw = requireNotNull(node.rawRange(index))
        return builder.build(BlockKind.TABLE_ROW, raw, protectWhole = true)
    }

    private fun renderProtectedBlock(
        node: Node,
        text: String,
        kind: BlockKind,
        index: Utf8Index,
        renderKind: RenderKind = RenderKind.CODE,
    ): BlockDraft {
        val raw = requireNotNull(node.rawRange(index))
        return BlockDraft.protected(text, kind, raw, renderKind)
    }

    private fun frontMatterRange(source: String, index: Utf8Index): RawRange? {
        if (!(source.startsWith("---\n") || source.startsWith("---\r\n"))) return null
        var cursor = source.indexOf('\n') + 1
        while (cursor < source.length) {
            val lineEnd = source.indexOf('\n', cursor).let { if (it == -1) source.length else it + 1 }
            val line = source.substring(cursor, lineEnd).trimEnd('\r', '\n')
            if (line == "---") return RawRange(0, index.byteAt(lineEnd))
            cursor = lineEnd
        }
        return null
    }

    private fun Node.rawRange(index: Utf8Index): RawRange? {
        if (sourceSpans.isEmpty()) return null
        val start = sourceSpans.minOf { it.inputIndex }
        val end = sourceSpans.maxOf { it.inputIndex + it.length }
        return RawRange(index.byteAt(start), index.byteAt(end))
    }

    private class InlineBuilder(source: String, private val index: Utf8Index) {
        private val sourceBytes = source.encodeToByteArray()
        private val text = StringBuilder()
        private val runs = mutableListOf<RenderRun>()
        private val syntax = mutableListOf<SyntaxSpan>()
        private val boundaries = mutableListOf(0)

        fun render(node: Node, inheritedKind: RenderKind = RenderKind.TEXT) {
            val start = text.length
            val raw = node.rawRange(index)
            when (node) {
                is Text -> appendLiteral(node.literal, requireNotNull(raw), inheritedKind)
                is SoftLineBreak -> appendLiteral("\n", requireNotNull(raw), inheritedKind)
                is HardLineBreak -> appendProtected("\n", requireNotNull(raw), inheritedKind)
                is Code -> appendProtected(node.literal, requireNotNull(raw), RenderKind.CODE)
                is HtmlInline -> appendProtected(node.literal, requireNotNull(raw), RenderKind.INERT_HTML)
                is Emphasis -> renderContainer(node, RenderKind.EMPHASIS)
                is StrongEmphasis -> renderContainer(node, RenderKind.STRONG)
                is Link -> renderContainer(node, RenderKind.LINK)
                is Image -> renderContainer(node, RenderKind.LINK)
                else -> renderChildren(node, inheritedKind)
            }
            if (node is Emphasis || node is StrongEmphasis || node is Link || node is Image) {
                val end = text.length
                if (end > start && raw != null) {
                    syntax += SyntaxSpan(start, end, raw)
                    boundaries[start] = raw.startByte
                    boundaries[end] = raw.endByte
                }
            }
        }

        private fun renderContainer(node: Node, kind: RenderKind) = renderChildren(node, kind)

        private fun renderChildren(node: Node, kind: RenderKind) {
            var child = node.firstChild
            while (child != null) {
                render(child, kind)
                child = child.next
            }
        }

        fun appendSynthetic(value: String) {
            text.append(value)
            repeat(value.length) { boundaries += -1 }
        }

        private fun appendLiteral(value: String, raw: RawRange, kind: RenderKind) {
            val rawText = sourceBytes.copyOfRange(raw.startByte, raw.endByte).decodeToString()
            if (rawText != value) {
                appendProtected(value, raw, kind)
                return
            }
            val start = text.length
            boundaries[start] = raw.startByte
            text.append(value)
            val local = Utf8Index(value)
            for (offset in 1..value.length) {
                val localByte = local.byteAtOrInvalid(offset)
                boundaries += if (localByte >= 0) raw.startByte + localByte else -1
            }
            runs += RenderRun(value, start, text.length, kind, raw)
        }

        private fun appendProtected(value: String, raw: RawRange, kind: RenderKind) {
            val start = text.length
            text.append(value)
            repeat(value.length) { boundaries += -1 }
            boundaries[start] = raw.startByte
            boundaries[text.length] = raw.endByte
            if (value.isNotEmpty()) {
                runs += RenderRun(value, start, text.length, kind, raw)
                syntax += SyntaxSpan(start, text.length, raw)
            }
        }

        fun build(
            kind: BlockKind,
            raw: RawRange,
            protectWhole: Boolean = false,
            headingLevel: Int? = null,
        ): BlockDraft {
            if (protectWhole && text.isNotEmpty()) {
                syntax += SyntaxSpan(0, text.length, raw)
                boundaries[0] = raw.startByte
                boundaries[text.length] = raw.endByte
            }
            return BlockDraft(
                kind,
                text.toString(),
                raw,
                runs.toList(),
                false,
                boundaries.toIntArray(),
                syntax.toList(),
                headingLevel,
            )
        }
    }

    private class Utf8Index(private val source: String) {
        private val bytes = IntArray(source.length + 1) { -1 }

        init {
            var charIndex = 0
            var byteIndex = 0
            while (charIndex < source.length) {
                val codePoint = source.codePointAt(charIndex)
                val chars = Character.charCount(codePoint)
                bytes[charIndex] = byteIndex
                charIndex += chars
                byteIndex += String(Character.toChars(codePoint)).encodeToByteArray().size
                bytes[charIndex] = byteIndex
            }
        }

        fun byteAt(charIndex: Int): Int = bytes[charIndex]

        fun byteAtOrInvalid(charIndex: Int): Int = bytes[charIndex]
    }

    private data class BlockDraft(
        val kind: BlockKind,
        val text: String,
        val rawRange: RawRange,
        val runs: List<RenderRun>,
        val hidden: Boolean,
        val boundaries: IntArray,
        val syntax: List<SyntaxSpan>,
        val headingLevel: Int? = null,
    ) {
        fun finish(index: Int) = RenderedBlock(
            index = index,
            kind = kind,
            text = text,
            rawRange = rawRange,
            runs = runs,
            hidden = hidden,
            byteBoundaries = boundaries,
            syntaxSpans = syntax,
            headingLevel = headingLevel,
        )

        companion object {
            fun hidden(raw: RawRange) = BlockDraft(BlockKind.HIDDEN_SOURCE, "", raw, emptyList(), true, intArrayOf(raw.startByte), emptyList())

            fun protected(text: String, kind: BlockKind, raw: RawRange, renderKind: RenderKind): BlockDraft {
                val boundaries = IntArray(text.length + 1) { -1 }.also {
                    it[0] = raw.startByte
                    it[text.length] = raw.endByte
                }
                val runs = if (text.isEmpty()) emptyList() else listOf(RenderRun(text, 0, text.length, renderKind, raw))
                val syntax = if (text.isEmpty()) emptyList() else listOf(SyntaxSpan(0, text.length, raw))
                return BlockDraft(kind, text, raw, runs, false, boundaries, syntax)
            }
        }
    }
}
