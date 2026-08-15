package net.inkyquill.pocketeditor.ui.reader

import androidx.compose.ui.text.AnnotatedString
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.markdown.RenderedBlock
import net.inkyquill.pocketeditor.markdown.TextRange
import net.inkyquill.pocketeditor.reader.ReviewProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderSelectionAdapterTest {
    @Test
    fun `adapter uses annotated block identity when texts repeat`() {
        val all = listOf(tagged(3, "same"), tagged(4, "same"), tagged(5, "last"))
        val selected = listOf(all[1].subSequence(2, 4), all[2].subSequence(0, 2))

        assertEquals(
            ReaderSelectionResult(TextRange(4, 2, 5, 2), ReaderSelectionEndpoint.End),
            ReaderSelectionAdapter.selection(selected, all),
        )
    }

    @Test
    fun `adapter preserves reverse active handle from selected text order`() {
        val all = listOf(tagged(8, "alpha"), tagged(3, "beta"), tagged(12, "gamma"))
        val selected = listOf(
            all[2].subSequence(0, 3),
            all[1],
            all[0].subSequence(2, 5),
        )

        assertEquals(
            ReaderSelectionResult(TextRange(8, 2, 12, 3), ReaderSelectionEndpoint.Start),
            ReaderSelectionAdapter.selection(selected, all),
        )
    }

    @Test
    fun `adapter rejects stale generation even at identical index and offsets`() {
        val old = tagged(1, "same", generation = "old")
        val refreshed = tagged(1, "same", generation = "new")

        assertNull(ReaderSelectionAdapter.selection(listOf(old.subSequence(1, 3)), listOf(refreshed)))
    }

    @Test
    fun `adapter rejects same provenance attached to a different current character`() {
        val selected = tagged(1, "abc", generation = "same").subSequence(1, 2)
        val current = tagged(1, "axc", generation = "same")

        assertNull(ReaderSelectionAdapter.selection(listOf(selected), listOf(current)))
    }

    @Test
    fun `adapter rejects incomplete cross block fragments`() {
        val all = listOf(tagged(0, "first"), tagged(1, "middle"), tagged(2, "last"))

        assertNull(ReaderSelectionAdapter.selection(listOf(all[0].subSequence(2, 4), all[1]), all))
        assertNull(ReaderSelectionAdapter.selection(listOf(all[0].subSequence(2, 5), all[1].subSequence(1, 6)), all))
        assertNull(
            ReaderSelectionAdapter.selection(
                listOf(all[0].subSequence(2, 5), all[1].subSequence(0, 3), all[2].subSequence(0, 2)),
                all,
            ),
        )
    }

    @Test
    fun `adapter rejects untagged noncontiguous and synthetic endpoints`() {
        val all = listOf(tagged(1, "abcdef"))
        assertNull(ReaderSelectionAdapter.selection(listOf(AnnotatedString("bc")), all))

        val noncontiguous = AnnotatedString.Builder().apply {
            append(all.single().subSequence(0, 1))
            append(all.single().subSequence(3, 4))
        }.toAnnotatedString()
        assertNull(ReaderSelectionAdapter.selection(listOf(noncontiguous), all))

        val withSynthetic = ReaderSelectionAdapter.annotate(
            text = AnnotatedString("+ab"),
            blockIndex = 1,
            generation = Generation,
            sourceOffsets = listOf(null, 0, 1),
        )
        assertNull(ReaderSelectionAdapter.selection(listOf(withSynthetic), listOf(withSynthetic)))
        assertEquals(
            TextRange(1, 0, 2),
            ReaderSelectionAdapter.selection(listOf(withSynthetic.subSequence(1, 3)), listOf(withSynthetic))?.range,
        )

        val internalSynthetic = ReaderSelectionAdapter.annotate(
            text = AnnotatedString("a+b"),
            blockIndex = 1,
            generation = Generation,
            sourceOffsets = listOf(0, null, 1),
        )
        assertEquals(
            ReaderSelectionGlyph(1, 2),
            ReaderSelectionAdapter.selection(listOf(internalSynthetic), listOf(internalSynthetic))?.endGlyph,
        )
    }

    @Test
    fun `authoritative mapper slices original bytes including separators and soft breaks`() {
        val across = MarkdownParser.parse("first\n\nsecond")
        assertEquals(
            "rst\n\nsec",
            ReaderSelectionAdapter.sourceSelection(
                ReaderSelectionResult(TextRange(0, 2, 1, 3), ReaderSelectionEndpoint.End),
                across,
            )?.selectedText,
        )

        val softBreak = MarkdownParser.parse("first\ncontinued")
        assertEquals(
            "\n",
            ReaderSelectionAdapter.sourceSelection(
                ReaderSelectionResult(TextRange(0, 5, 6), ReaderSelectionEndpoint.End),
                softBreak,
            )?.selectedText,
        )
    }

    @Test
    fun `authoritative mapper rejects hidden intermediate block and partial syntax`() {
        val parsed = MarkdownParser.parse("First.\n\nLast.")
        val first = parsed.blocks[0].copy(index = 0)
        val hidden = RenderedBlock(
            index = 1,
            kind = BlockKind.HIDDEN_SOURCE,
            text = "",
            rawRange = RawRange(first.rawRange.endByte, parsed.blocks[1].rawRange.startByte),
            runs = emptyList(),
            hidden = true,
            byteBoundaries = intArrayOf(first.rawRange.endByte),
        )
        val last = parsed.blocks[1].copy(index = 2)
        val withHidden = parsed.copy(blocks = listOf(first, hidden, last))
        assertNull(
            ReaderSelectionAdapter.sourceSelection(
                ReaderSelectionResult(
                    TextRange(first.index, 1, last.index, 2),
                    ReaderSelectionEndpoint.End,
                ),
                withHidden,
            ),
        )

        val emphasis = MarkdownParser.parse("Before *emphasis* after")
        assertNull(
            ReaderSelectionAdapter.sourceSelection(
                ReaderSelectionResult(TextRange(0, 8, 12), ReaderSelectionEndpoint.End),
                emphasis,
            ),
        )
    }

    @Test
    fun `stable generation changes when same chapter projection refreshes`() {
        val rendered = MarkdownParser.parse("same")
        val original = ReviewProjector.project(rendered, review = null, reviewMode = false)
        val refreshed = original.copy(
            blocks = original.blocks.map { block -> block.copy(canonicalText = "changed") },
        )

        assertEquals(
            ReaderSelectionAdapter.generation(original, rendered, reviewEnabled = false),
            ReaderSelectionAdapter.generation(original.copy(), rendered, reviewEnabled = false),
        )
        assertNotEquals(
            ReaderSelectionAdapter.generation(original, rendered, reviewEnabled = false),
            ReaderSelectionAdapter.generation(refreshed, rendered, reviewEnabled = false),
        )
    }

    private fun tagged(
        blockIndex: Int,
        text: String,
        generation: String = Generation,
    ): AnnotatedString = ReaderSelectionAdapter.annotate(
        text = AnnotatedString(text),
        blockIndex = blockIndex,
        generation = generation,
        sourceOffsets = text.indices.toList(),
    )

    private companion object {
        const val Generation = "generation"
    }
}
