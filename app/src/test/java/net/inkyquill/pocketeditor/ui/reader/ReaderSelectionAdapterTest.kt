package net.inkyquill.pocketeditor.ui.reader

import androidx.compose.ui.text.AnnotatedString
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.markdown.TextRange
import net.inkyquill.pocketeditor.reader.ReviewProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderSelectionAdapterTest {
    @Test
    fun `adapter uses annotated block identity when texts repeat`() {
        val all = listOf(tagged(3, "same"), tagged(4, "same"), tagged(5, "last"))
        val selected = listOf(all[1].subSequence(2, 4), all[2].subSequence(0, 2))

        assertEquals(TextRange(4, 2, 5, 2), ReaderSelectionAdapter.range(selected, all))
    }

    @Test
    fun `adapter normalizes reverse selected text order by current document order`() {
        val all = listOf(tagged(8, "alpha"), tagged(3, "beta"), tagged(12, "gamma"))
        val selected = listOf(
            all[2].subSequence(0, 3),
            all[1],
            all[0].subSequence(2, 5),
        )

        assertEquals(TextRange(8, 2, 12, 3), ReaderSelectionAdapter.range(selected, all))
    }

    @Test
    fun `adapter preserves offsets for a reverse selection within one block`() {
        val all = listOf(tagged(7, "paragraph"))
        val selected = listOf(all.single().subSequence(2, 9))

        assertEquals(TextRange(7, 2, 7, 9), ReaderSelectionAdapter.range(selected, all))
    }

    @Test
    fun `adapter rejects provenance from another reader document`() {
        val all = listOf(tagged(1, "same"), tagged(2, "same"))
        val selected = listOf(tagged(9, "same").subSequence(0, 2))

        assertNull(ReaderSelectionAdapter.range(selected, all))
    }

    @Test
    fun `adapter rejects untagged and noncontiguous selected characters`() {
        val all = listOf(tagged(1, "abcdef"))
        assertNull(ReaderSelectionAdapter.range(listOf(AnnotatedString("bc")), all))

        val noncontiguous = AnnotatedString.Builder().apply {
            append(all.single().subSequence(0, 1))
            append(all.single().subSequence(3, 4))
        }.toAnnotatedString()
        assertNull(ReaderSelectionAdapter.range(listOf(noncontiguous), all))
    }

    @Test
    fun `reader selection slices original bytes including block separators`() {
        val source = "first\n\nsecond"
        val document = ReviewProjector.project(MarkdownParser.parse(source), review = null, reviewMode = false)

        val selection = document.sourceSelection(TextRange(0, 2, 1, 3))

        assertEquals("rst\n\nsec", selection?.selectedText)
        assertEquals(source.encodeToByteArray().copyOfRange(2, 10).toList(), selection?.rawRange?.let {
            document.sourceBytes?.copyOfRange(it.startByte, it.endByte)?.toList()
        })
    }

    @Test
    fun `reader selection slices original soft break rather than displayed space`() {
        val source = "first\ncontinued"
        val document = ReviewProjector.project(MarkdownParser.parse(source), review = null, reviewMode = false)

        val selection = document.sourceSelection(TextRange(0, 5, 6))

        assertEquals("\n", selection?.selectedText)
    }

    private fun tagged(blockIndex: Int, text: String): AnnotatedString =
        ReaderSelectionAdapter.annotate(AnnotatedString(text), blockIndex)
}
