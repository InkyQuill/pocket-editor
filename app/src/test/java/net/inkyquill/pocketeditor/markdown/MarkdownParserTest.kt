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

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")).readText()
}
