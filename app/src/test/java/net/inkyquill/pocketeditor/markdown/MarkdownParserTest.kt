package net.inkyquill.pocketeditor.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownParserTest {
    private val source = fixture("markdown-source-map.md")

    @Test
    fun `parses representative Markdown into renderable source-mapped blocks`() {
        val document = MarkdownParser.parse(source)

        assertEquals(source.encodeToByteArray().size, document.sourceBytes.size)
        assertTrue(document.blocks.first().hidden)
        assertEquals("---\ntitle: Скрытое название\n---\n", document.blocks.first().rawText(document))
        assertTrue(document.blocks.any { it.kind == BlockKind.HEADING && it.text == "Заголовок 😀" })
        assertTrue(document.blocks.any { it.kind == BlockKind.PARAGRAPH && "курсив" in it.text && "жирный" in it.text })
        assertTrue(document.blocks.any { it.kind == BlockKind.QUOTE && it.text == "Цитата" })
        assertEquals(listOf("Первый", "Второй"), document.blocks.filter { it.kind == BlockKind.LIST_ITEM }.map { it.text })
        assertTrue(document.blocks.any { it.kind == BlockKind.CODE_BLOCK && "val emoji" in it.text })
        assertTrue(document.blocks.any { it.kind == BlockKind.TABLE_ROW && it.text == "Имя | Значение" })
    }

    @Test
    fun `raw HTML is retained as inert rendered text`() {
        val block = MarkdownParser.parse(source).blocks.single { "сырой HTML" in it.text }

        assertTrue("<b>" in block.text)
        assertTrue(block.runs.any { it.kind == RenderKind.INERT_HTML && it.text == "<b>" })
        assertTrue(block.runs.any { it.kind == RenderKind.INERT_HTML && it.text == "</b>" })
        assertFalse(block.hidden)
    }

    @Test
    fun `preserves all heading levels and prose inline kinds`() {
        val source = (1..6).joinToString("\n\n") { level -> "${"#".repeat(level)} H$level" } +
            "\n\nОбычный *курсив*, **жирный** и [ссылка](https://example.com)."

        val document = MarkdownParser.parse(source)

        assertEquals((1..6).toList(), document.blocks.filter { it.kind == BlockKind.HEADING }.map { it.headingLevel })
        val paragraph = document.blocks.single { it.kind == BlockKind.PARAGRAPH }
        assertTrue(paragraph.runs.any { it.text == "курсив" && it.kind == RenderKind.EMPHASIS })
        assertTrue(paragraph.runs.any { it.text == "жирный" && it.kind == RenderKind.STRONG })
        assertTrue(paragraph.runs.any { it.text == "ссылка" && it.kind == RenderKind.LINK })
    }

    @Test
    fun `soft break displays as space`() {
        val source = "Первая строка\nвторая строка"

        val block = MarkdownParser.parse(source).blocks.single()

        assertEquals("Первая строка вторая строка", block.text)
        assertEquals(source, block.rawText(MarkdownParser.parse(source)))
    }

    @Test
    fun `both hard break forms display newlines`() {
        assertEquals("a\nb", MarkdownParser.parse("a  \nb").blocks.single().text)
        assertEquals("a\nb", MarkdownParser.parse("a\\\nb").blocks.single().text)
    }

    @Test
    fun `fenced code embedded break remains unchanged`() {
        val source = """
            ```text
            first line
            second line
            ```
        """.trimIndent()

        assertEquals("first line\nsecond line\n", MarkdownParser.parse(source).blocks.single().text)
    }

    @Test
    fun `footnote plain text distinguishes soft and hard breaks`() {
        val source = "Text[^soft] and text[^hard].\n\n" +
            "[^soft]: first\n    second\n\n" +
            "[^hard]: first  \n    second"

        val footnotes = MarkdownParser.parse(source).footnotes

        assertEquals("first second", footnotes["soft"])
        assertEquals("first\nsecond", footnotes["hard"])
    }

    @Test
    fun `parses footnote references and definitions alongside quotes`() {
        val source = """
            Текст со сноской[^note].

            > Цитата не теряется.

            [^note]: Примечание с *курсивом*.
        """.trimIndent()

        val document = MarkdownParser.parse(source)

        assertEquals("Примечание с курсивом.", document.footnotes["note"])
        assertTrue(document.blocks.any { it.kind == BlockKind.QUOTE && it.text == "Цитата не теряется." })
        assertTrue(
            document.blocks
                .flatMap(RenderedBlock::runs)
                .any { it.kind == RenderKind.FOOTNOTE_REFERENCE && it.text == "1" && it.footnoteLabel == "note" },
        )
        assertFalse(document.blocks.any { "Примечание с курсивом." in it.text })
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")).readText()
}
