package net.inkyquill.pocketeditor.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SelectionMapperTest {
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
    fun `selections that split syntax nodes are rejected`() {
        val source = "До *курсив* и [ссылка](https://example.com) после."
        val document = MarkdownParser.parse(source)
        val block = document.blocks.single()

        assertNull(SelectionMapper.toRawRange(document, block.rangeOf("кур")))
        assertNull(SelectionMapper.toRawRange(document, TextRange(block.index, 0, block.text.indexOf("сив") + 2)))
        assertNull(SelectionMapper.toRawRange(document, block.rangeOf("ссыл")))
    }

    @Test
    fun `selection cannot span rendered blocks`() {
        val document = MarkdownParser.parse("Первый\n\nВторой")

        assertNull(SelectionMapper.toRawRange(document, TextRange(0, 0, 1, 6)))
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

    private fun String.rawRangeOf(needle: String): RawRange {
        val start = encodeToByteArray().indexOfSlice(needle.encodeToByteArray())
        return RawRange(start, start + needle.encodeToByteArray().size)
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int =
        indices.first { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } }
}
