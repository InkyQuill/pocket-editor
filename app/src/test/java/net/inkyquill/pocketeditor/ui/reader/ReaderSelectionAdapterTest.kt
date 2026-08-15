package net.inkyquill.pocketeditor.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.markdown.RenderedBlock
import net.inkyquill.pocketeditor.markdown.TextRange
import net.inkyquill.pocketeditor.reader.ReviewProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `adapter accepts explicit reverse active handle while selected texts stay in visual order`() {
        val all = listOf(tagged(8, "alpha"), tagged(3, "beta"), tagged(12, "gamma"))
        val selected = listOf(
            all[0].subSequence(2, 5),
            all[1],
            all[2].subSequence(0, 3),
        )

        assertEquals(
            ReaderSelectionResult(TextRange(8, 2, 12, 3), ReaderSelectionEndpoint.Start),
            ReaderSelectionAdapter.selection(selected, all, activeEndpoint = ReaderSelectionEndpoint.Start),
        )
    }

    @Test
    fun `same block reverse handle and forward default use explicit production state`() {
        val all = listOf(tagged(7, "paragraph"))
        val selected = listOf(all.single().subSequence(2, 9))

        assertEquals(
            ReaderSelectionEndpoint.End,
            ReaderSelectionAdapter.selection(selected, all)?.activeEndpoint,
        )
        assertEquals(
            ReaderSelectionEndpoint.Start,
            ReaderSelectionAdapter.selection(
                selected,
                all,
                activeEndpoint = ReaderSelectionEndpoint.Start,
            )?.activeEndpoint,
        )
    }

    @Test
    fun `one character selection exposes distinct leading and trailing cursor anchors`() {
        val all = listOf(tagged(7, "abc"))

        val selection = ReaderSelectionAdapter.selection(
            selected = listOf(all.single().subSequence(1, 2)),
            all = all,
        )

        assertEquals(ReaderSelectionAnchor(7, 1), selection?.startAnchor)
        assertEquals(ReaderSelectionAnchor(7, 2), selection?.endAnchor)
        assertNotEquals(selection?.startAnchor, selection?.endAnchor)
    }

    @Test
    fun `cursor anchors remain logical for rtl and ligature shaped text`() {
        val rtl = tagged(3, "אבג")
        val ligature = tagged(4, "office")

        val rtlSelection = ReaderSelectionAdapter.selection(listOf(rtl.subSequence(1, 2)), listOf(rtl))
        val ligatureSelection = ReaderSelectionAdapter.selection(
            listOf(ligature.subSequence(1, 4)),
            listOf(ligature),
        )

        assertEquals(ReaderSelectionAnchor(3, 1), rtlSelection?.startAnchor)
        assertEquals(ReaderSelectionAnchor(3, 2), rtlSelection?.endAnchor)
        assertEquals(ReaderSelectionAnchor(4, 1), ligatureSelection?.startAnchor)
        assertEquals(ReaderSelectionAnchor(4, 4), ligatureSelection?.endAnchor)
    }

    @Test
    fun `no change drag is cleared before a later programmatic selection`() {
        val tracker = ReaderSelectionGestureTracker()
        val original = fingerprint(tagged(1, "abc").subSequence(0, 1))
        val programmatic = fingerprint(tagged(1, "abc").subSequence(1, 2))

        tracker.begin(ReaderSelectionEndpoint.Start, original)
        tracker.drag(Offset(4f, 5f))
        tracker.release(original)

        assertEquals(ReaderSelectionEndpoint.End, tracker.consume(programmatic).endpoint)
    }

    @Test
    fun `delayed emission after release uses dragged handle exactly once`() {
        val tracker = ReaderSelectionGestureTracker()
        val original = fingerprint(tagged(1, "abc").subSequence(0, 1))
        val dragged = fingerprint(tagged(1, "abc").subSequence(0, 2))
        val programmatic = fingerprint(tagged(1, "abc").subSequence(1, 3))

        val token = tracker.begin(ReaderSelectionEndpoint.Start, original)
        tracker.drag(Offset(7f, 8f))
        tracker.release(dragged)

        val draggedResolution = tracker.consume(dragged)
        assertEquals(ReaderSelectionEndpoint.Start, draggedResolution.endpoint)
        assertEquals(token, draggedResolution.token)
        assertEquals(Offset(7f, 8f), draggedResolution.pointerInRoot)
        val programmaticResolution = tracker.consume(programmatic)
        assertEquals(ReaderSelectionEndpoint.End, programmaticResolution.endpoint)
        assertNull(programmaticResolution.token)
    }

    @Test
    fun `conflated programmatic fingerprint cannot consume a released drag token`() {
        val tracker = ReaderSelectionGestureTracker()
        val original = fingerprint(tagged(1, "abc").subSequence(0, 1))
        val dragged = fingerprint(tagged(1, "abc").subSequence(0, 2))
        val programmatic = fingerprint(tagged(1, "abc").subSequence(1, 3))

        tracker.begin(ReaderSelectionEndpoint.Start, original)
        tracker.drag(Offset(7f, 8f))
        tracker.release(dragged)

        val conflatedResolution = tracker.consume(programmatic)
        assertEquals(ReaderSelectionEndpoint.End, conflatedResolution.endpoint)
        assertNull(conflatedResolution.pointerInRoot)
        assertNull(conflatedResolution.token)
        assertEquals(ReaderSelectionEndpoint.End, tracker.consume(dragged).endpoint)
    }

    @Test
    fun `cancel and multitouch discard pending handle identity`() {
        val original = fingerprint(tagged(1, "abc").subSequence(0, 1))
        val changed = fingerprint(tagged(1, "abc").subSequence(0, 2))

        listOf(ReaderSelectionGestureTracker.CancelReason.Cancel, ReaderSelectionGestureTracker.CancelReason.Multitouch)
            .forEach { reason ->
                val tracker = ReaderSelectionGestureTracker()
                tracker.begin(ReaderSelectionEndpoint.Start, original)
                tracker.drag(Offset(7f, 8f))
                tracker.cancel(reason)
                assertEquals(ReaderSelectionEndpoint.End, tracker.consume(changed).endpoint)
            }
    }

    @Test
    fun `pointer seam resolves real active handle and orders offscreen fallback`() {
        val startBounds = Rect(0f, 0f, 10f, 10f)
        val endBounds = Rect(90f, 0f, 100f, 10f)
        assertEquals(
            ReaderSelectionEndpoint.Start,
            ReaderSelectionAdapter.resolveActiveEndpoint(Offset(8f, 5f), startBounds, endBounds),
        )
        assertEquals(
            ReaderSelectionEndpoint.End,
            ReaderSelectionAdapter.resolveActiveEndpoint(Offset(92f, 5f), startBounds, endBounds),
        )
        assertEquals(
            ReaderSelectionEndpoint.End,
            ReaderSelectionAdapter.resolveActiveEndpoint(null, startBounds, endBounds),
        )
        assertEquals(
            ReaderSelectionEndpoint.Start,
            ReaderSelectionAdapter.hitTestEndpoint(Offset(8f, 14f), startBounds, endBounds, maxDistancePx = 8f),
        )
        assertNull(
            ReaderSelectionAdapter.hitTestEndpoint(Offset(50f, 50f), startBounds, endBounds, maxDistancePx = 8f),
        )

        val selection = ReaderSelectionResult(TextRange(1, 2, 3, 4), ReaderSelectionEndpoint.Start)
        assertEquals(
            listOf(selection.startAnchor, selection.endAnchor),
            ReaderSelectionAdapter.preferredAnchors(selection),
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
            ReaderSelectionAnchor(1, 3),
            ReaderSelectionAdapter.selection(listOf(internalSynthetic), listOf(internalSynthetic))?.endAnchor,
        )
    }

    @Test
    fun `authoritative mapper slices original bytes including separators and soft breaks`() {
        val across = MarkdownParser.parse("first\n\nsecond")
        val crossBlock = ReaderSelectionAdapter.sourceSelection(
            ReaderSelectionResult(TextRange(0, 2, 1, 3), ReaderSelectionEndpoint.End),
            across,
        )
        assertEquals("rst\n\nsec", crossBlock?.selectedText)
        assertTrue(requireNotNull(crossBlock).spansMultipleBlocks)

        val softBreak = MarkdownParser.parse("first\ncontinued")
        val sameBlock = ReaderSelectionAdapter.sourceSelection(
            ReaderSelectionResult(TextRange(0, 5, 6), ReaderSelectionEndpoint.End),
            softBreak,
        )
        assertEquals("\n", sameBlock?.selectedText)
        assertFalse(requireNotNull(sameBlock).spansMultipleBlocks)
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

    private fun fingerprint(text: AnnotatedString): ReaderSelectionFingerprint =
        ReaderSelectionFingerprint(listOf(text))

    private companion object {
        const val Generation = "generation"
    }
}
