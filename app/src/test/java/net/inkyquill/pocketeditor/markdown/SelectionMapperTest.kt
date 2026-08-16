package net.inkyquill.pocketeditor.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SelectionMapperTest {
    @Test
    fun `soft break displays as space and maps over raw newline`() {
        val source = "Первый фрагмент\nвторой фрагмент"
        val document = MarkdownParser.parse(source)
        val block = document.blocks.single()
        val display = block.text.indexOf(" второй")

        assertEquals("Первый фрагмент второй фрагмент", block.text)
        assertEquals(
            source.rawRangeOf("\n"),
            SelectionMapper.toRawRange(document, TextRange(block.index, display, display + 1)),
        )
    }

    @Test
    fun `soft break mapping excludes stripped indentation after LF`() {
        assertBreakMapsOnlyLineEnding(
            source = "foo\n  bar",
            displayed = "foo bar",
            lineEnding = "\n",
        )
    }

    @Test
    fun `soft break mapping covers the complete CRLF sequence`() {
        assertBreakMapsOnlyLineEnding(
            source = "foo\r\nbar",
            displayed = "foo bar",
            lineEnding = "\r\n",
        )
    }

    @Test
    fun `soft break mapping covers a standalone CR`() {
        assertBreakMapsOnlyLineEnding(
            source = "foo\rbar",
            displayed = "foo bar",
            lineEnding = "\r",
        )
    }

    @Test
    fun `hard break mapping excludes its markdown marker`() {
        assertBreakMapsOnlyLineEnding(
            source = "a  \nb",
            displayed = "a\nb",
            lineEnding = "\n",
        )
        assertBreakMapsOnlyLineEnding(
            source = "a\\\nb",
            displayed = "a\nb",
            lineEnding = "\n",
        )
    }

    @Test
    fun `Russian and emoji selections map to exact UTF-8 byte ranges`() {
        val source = "# До 😀 после\n"
        val document = MarkdownParser.parse(source)
        val block = document.blocks.single { !it.hidden }
        val start = block.text.indexOf("😀")

        val raw = SelectionMapper.toRawRange(document, TextRange(block.index, start, start + "😀".length))

        assertEquals(source.encodeToByteArray().indexOfSlice("😀".encodeToByteArray()), raw?.startByte)
        assertEquals(raw?.startByte?.plus(4), raw?.endByte)
    }

    @Test
    fun `whole formatted spans map including their Markdown syntax`() {
        val source = "До *курсив* и [ссылка](https://example.com) после."
        val document = MarkdownParser.parse(source)
        val block = document.blocks.single()

        assertEquals(
            source.rawRangeOf("*курсив*"),
            SelectionMapper.toRawRange(document, block.rangeOf("курсив")),
        )
        assertEquals(
            source.rawRangeOf("[ссылка](https://example.com)"),
            SelectionMapper.toRawRange(document, block.rangeOf("ссылка")),
        )
    }

    @Test
    fun `equal display ranges map to outermost nested emphasis syntax`() {
        val source = "***a***"
        val document = MarkdownParser.parse(source)
        val block = document.blocks.single()

        assertEquals(
            source.rawRangeOf(source),
            SelectionMapper.toRawRange(document, block.rangeOf("a")),
        )
    }

    @Test
    fun `equal display ranges map to outermost link syntax`() {
        val source = "[**a**](https://example.com)"
        val document = MarkdownParser.parse(source)
        val block = document.blocks.single()

        assertEquals(
            source.rawRangeOf(source),
            SelectionMapper.toRawRange(document, block.rangeOf("a")),
        )
    }

    @Test
    fun `selections that split syntax nodes are rejected`() {
        val source = "До *курсив* и [ссылка](https://example.com) после."
        val document = MarkdownParser.parse(source)
        val block = document.blocks.single()

        assertNull(SelectionMapper.toRawRange(document, block.rangeOf("кур")))
        assertNull(SelectionMapper.toRawRange(document, TextRange(block.index, 0, block.text.indexOf("сив") + 2)))
        assertNull(SelectionMapper.toRawRange(document, block.rangeOf("ссыл")))
    }

    @Test
    fun `selection across three utf8 blocks includes raw separators and syntax`() {
        val source = "Первый 😀 абзац\n\nВторой *абзац*\n\nТретий абзац"
        val document = MarkdownParser.parse(source)

        val raw = requireNotNull(SelectionMapper.toRawRange(document, TextRange(0, 7, 2, 6)))

        assertEquals("😀 абзац\n\nВторой *абзац*\n\nТретий", source.bytes(raw))
    }

    @Test
    fun `reverse handles across blocks normalize to the same raw range`() {
        val source = "Первый абзац\n\nВторой абзац"
        val document = MarkdownParser.parse(source)
        val forward = SelectionMapper.toRawRange(document, TextRange(0, 7, 1, 6))

        assertEquals(forward, SelectionMapper.toRawRange(document, TextRange(1, 6, 0, 7)))
        assertEquals("абзац\n\nВторой", source.bytes(requireNotNull(forward)))
    }

    @Test
    fun `reverse handles in one block preserve same-block mapping`() {
        val source = "Первый абзац"
        val document = MarkdownParser.parse(source)

        assertEquals(
            source.rawRangeOf("Первый"),
            SelectionMapper.toRawRange(document, TextRange(0, 6, 0, 0)),
        )
    }

    @Test
    fun `cross-block selection rejects hidden endpoints and intermediate blocks`() {
        val frontMatter = MarkdownParser.parse("---\ntitle: test\n---\nПервый")
        assertNull(SelectionMapper.toRawRange(frontMatter, TextRange(0, 0, 1, 3)))

        val source = "Первый\n\nВторой"
        val parsed = MarkdownParser.parse(source)
        val first = parsed.blocks[0].copy(index = 0)
        val separator = RenderedBlock(
            index = 1,
            kind = BlockKind.HIDDEN_SOURCE,
            text = "",
            rawRange = RawRange(first.rawRange.endByte, parsed.blocks[1].rawRange.startByte),
            runs = emptyList(),
            hidden = true,
            byteBoundaries = intArrayOf(first.rawRange.endByte),
        )
        val last = parsed.blocks[1].copy(index = 2)
        val withHiddenMiddle = parsed.copy(blocks = listOf(first, separator, last))

        assertNull(SelectionMapper.toRawRange(withHiddenMiddle, TextRange(0, 0, 2, 6)))
    }

    @Test
    fun `cross-block selection rejects synthetic table endpoints`() {
        val document = MarkdownParser.parse("| a | b |\n| - | - |")
        val block = document.blocks.first()
        val synthetic = block.text.indexOf(" | ") + 1

        assertNull(SelectionMapper.toRawRange(document, TextRange(block.index, synthetic, block.index, synthetic + 1)))
    }

    @Test
    fun `cross-block selection rejects partial Markdown syntax at either endpoint`() {
        val document = MarkdownParser.parse("*Первый* абзац\n\nВторой *абзац*")

        assertNull(SelectionMapper.toRawRange(document, TextRange(0, 1, 1, 6)))
        assertNull(SelectionMapper.toRawRange(document, TextRange(0, 0, 1, 10)))
    }

    @Test
    fun `selection cannot split a UTF-16 surrogate pair`() {
        val document = MarkdownParser.parse("a😀b")

        assertNull(SelectionMapper.toRawRange(document, TextRange(0, 1, 2)))
        assertNull(SelectionMapper.toRawRange(document, TextRange(0, 2, 3)))
    }

    @Test
    fun `inline HTML tags are selectable only as whole inert syntax nodes`() {
        val source = "До <b>текст</b> после"
        val document = MarkdownParser.parse(source)
        val block = document.blocks.single()

        assertEquals(source.rawRangeOf("<b>"), SelectionMapper.toRawRange(document, block.rangeOf("<b>")))
        assertEquals(source.rawRangeOf("</b>"), SelectionMapper.toRawRange(document, block.rangeOf("</b>")))
        assertNull(SelectionMapper.toRawRange(document, block.rangeOf("<b")))
        assertNull(SelectionMapper.toRawRange(document, block.rangeOf("/b>")))
    }

    private fun RenderedBlock.rangeOf(needle: String): TextRange {
        val start = text.indexOf(needle)
        return TextRange(index, start, start + needle.length)
    }

    private fun assertBreakMapsOnlyLineEnding(source: String, displayed: String, lineEnding: String) {
        val document = MarkdownParser.parse(source)
        val block = document.blocks.single()
        val separator = displayed.indexOfFirst { it == ' ' || it == '\n' }

        assertEquals(displayed, block.text)
        assertEquals(
            source.rawRangeOf(lineEnding),
            SelectionMapper.toRawRange(document, TextRange(block.index, separator, separator + 1)),
        )
    }

    private fun String.rawRangeOf(needle: String): RawRange {
        val start = encodeToByteArray().indexOfSlice(needle.encodeToByteArray())
        return RawRange(start, start + needle.encodeToByteArray().size)
    }

    private fun String.bytes(range: RawRange): String =
        encodeToByteArray().copyOfRange(range.startByte, range.endByte).decodeToString()

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int =
        indices.first { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } }
}
