package net.inkyquill.pocketeditor.review

import net.inkyquill.pocketeditor.anchor.AnchorFactory
import net.inkyquill.pocketeditor.anchor.Ambiguous
import net.inkyquill.pocketeditor.anchor.Resolved
import net.inkyquill.pocketeditor.anchor.Stale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewAvailabilityTest {
    private val chapterId = "0b4f1cad-c846-4551-a497-a745087f5de2"
    private val sourcePath = "chapter-01.md"

    @Test
    fun `classifies active conflicting and unavailable records`() {
        val current = "alpha beta target more target".encodeToByteArray()
        val activeEdit = edit("11111111-1111-4111-8111-111111111111", before = "alpha", anchor = AnchorFactory.create(current, 0, 5))
        val conflictingA = edit("22222222-2222-4222-8222-222222222222", before = "beta", anchor = AnchorFactory.create(current, 6, 10))
        val conflictingB = edit("33333333-3333-4333-8333-333333333333", before = "ta tar", anchor = AnchorFactory.create(current, 8, 14))
        val staleEdit = edit("44444444-4444-4444-8444-444444444444", before = "omega", anchor = AnchorFactory.create("omega in old source".encodeToByteArray(), 0, 5))
        val activeSignal = signal("55555555-5555-4555-8555-555555555555", selectedText = "more", anchor = AnchorFactory.create(current, 18, 22))
        val ambiguousSignal = signal("66666666-6666-4666-8666-666666666666", selectedText = "target", anchor = AnchorFactory.create("unique target place".encodeToByteArray(), 7, 13))

        val availability = classifyReview(
            current,
            ReviewDocument(
                chapterId = chapterId,
                sourcePath = sourcePath,
                edits = listOf(activeEdit, conflictingA, conflictingB, staleEdit),
                signals = listOf(activeSignal, ambiguousSignal),
            ),
        )

        assertEquals(mapOf(activeEdit.id to Resolved(0, 5)), availability.activeEdits)
        assertEquals(mapOf(activeSignal.id to Resolved(18, 22)), availability.activeSignals)
        assertEquals(setOf(conflictingA.id, conflictingB.id), availability.conflictingEdits)
        assertEquals(
            mapOf(
                staleEdit.id to Stale,
                ambiguousSignal.id to Ambiguous(listOf(Resolved(11, 17), Resolved(23, 29))),
            ),
            availability.unavailable,
        )
    }

    @Test
    fun `chained overlaps conflict all participants without direct intersection`() {
        val source = "abcdefgh".encodeToByteArray()
        val a = edit("11111111-1111-4111-8111-111111111111", before = "abcd", anchor = AnchorFactory.create(source, 0, 4))
        val b = edit("22222222-2222-4222-8222-222222222222", before = "cdef", anchor = AnchorFactory.create(source, 2, 6))
        val c = edit("33333333-3333-4333-8333-333333333333", before = "efgh", anchor = AnchorFactory.create(source, 4, 8))

        val availability = classifyReview(source, document(edits = listOf(a, b, c)))

        assertEquals(setOf(a.id, b.id, c.id), availability.conflictingEdits)
        assertEquals(emptyMap<String, Resolved>(), availability.activeEdits)
    }

    @Test
    fun `adjacent resolved edits do not conflict`() {
        val source = "abcdefgh".encodeToByteArray()
        val first = edit("11111111-1111-4111-8111-111111111111", before = "abcd", anchor = AnchorFactory.create(source, 0, 4))
        val second = edit("22222222-2222-4222-8222-222222222222", before = "efgh", anchor = AnchorFactory.create(source, 4, 8))

        val availability = classifyReview(source, document(edits = listOf(first, second)))

        assertEquals(mapOf(first.id to Resolved(0, 4), second.id to Resolved(4, 8)), availability.activeEdits)
        assertEquals(emptySet<String>(), availability.conflictingEdits)
    }

    @Test
    fun `overlapping signals stay active`() {
        val source = "alpha beta".encodeToByteArray()
        val wide = signal("11111111-1111-4111-8111-111111111111", selectedText = "alpha beta", anchor = AnchorFactory.create(source, 0, 10))
        val inner = signal("22222222-2222-4222-8222-222222222222", selectedText = "beta", anchor = AnchorFactory.create(source, 6, 10))

        val availability = classifyReview(source, document(signals = listOf(wide, inner)))

        assertEquals(mapOf(wide.id to Resolved(0, 10), inner.id to Resolved(6, 10)), availability.activeSignals)
        assertTrue(availability.conflictingEdits.isEmpty())
        assertTrue(availability.unavailable.isEmpty())
    }

    @Test
    fun `identical stored offsets from different sources resolve disjointly`() {
        val current = "alpha omega".encodeToByteArray()
        val fromAlphaSource = edit("11111111-1111-4111-8111-111111111111", before = "alpha", anchor = AnchorFactory.create("alpha".encodeToByteArray(), 0, 5))
        val fromOmegaSource = edit("22222222-2222-4222-8222-222222222222", before = "omega", anchor = AnchorFactory.create("omega".encodeToByteArray(), 0, 5))

        val availability = classifyReview(current, document(edits = listOf(fromAlphaSource, fromOmegaSource)))

        assertEquals(
            mapOf(fromAlphaSource.id to Resolved(0, 5), fromOmegaSource.id to Resolved(6, 11)),
            availability.activeEdits,
        )
        assertEquals(emptySet<String>(), availability.conflictingEdits)
    }

    @Test
    fun `unavailable records keep their payload when chapter note changes`() {
        val oldSource = "only old text".encodeToByteArray()
        val staleEdit = edit("11111111-1111-4111-8111-111111111111", before = "old text", anchor = AnchorFactory.create(oldSource, 5, 13))
        val staleSignal = signal("22222222-2222-4222-8222-222222222222", selectedText = "only", anchor = AnchorFactory.create(oldSource, 0, 4))
            .copy(comment = "please check")

        val updated = ReviewJson.decode(
            ReviewJson.encode(
                ReviewDocument(
                    chapterId = chapterId,
                    sourcePath = sourcePath,
                    chapterNote = "before note",
                    signals = listOf(staleSignal),
                    edits = listOf(staleEdit),
                ).copy(chapterNote = "after note"),
            ),
            chapterId,
            sourcePath,
        )

        assertEquals("after note", updated.chapterNote)
        assertEquals(listOf(staleSignal), updated.signals)
        assertEquals(listOf(staleEdit), updated.edits)
        val availability = classifyReview("совсем другой текст".encodeToByteArray(), updated)
        assertEquals(setOf(staleEdit.id, staleSignal.id), availability.unavailable.keys)
    }

    @Test
    fun `classification does not depend on record order`() {
        val source = "alpha beta gamma".encodeToByteArray()
        val conflictingA = edit("11111111-1111-4111-8111-111111111111", before = "alpha", anchor = AnchorFactory.create(source, 0, 5))
        val conflictingB = edit("22222222-2222-4222-8222-222222222222", before = "a bet", anchor = AnchorFactory.create(source, 4, 9))
        val activeEdit = edit("33333333-3333-4333-8333-333333333333", before = "gamma", anchor = AnchorFactory.create(source, 11, 16))
        val staleEdit = edit("44444444-4444-4444-8444-444444444444", before = "omega", anchor = AnchorFactory.create("omega alone".encodeToByteArray(), 0, 5))
        val firstSignal = signal("55555555-5555-4555-8555-555555555555", selectedText = "gamma", anchor = AnchorFactory.create(source, 11, 16))
        val secondSignal = signal("66666666-6666-4666-8666-666666666666", selectedText = "alpha", anchor = AnchorFactory.create(source, 0, 5))

        val forward = classifyReview(source, document(signals = listOf(firstSignal, secondSignal), edits = listOf(conflictingA, activeEdit, conflictingB, staleEdit)))
        val backward = classifyReview(source, document(signals = listOf(secondSignal, firstSignal), edits = listOf(staleEdit, conflictingB, activeEdit, conflictingA)))

        assertEquals(forward, backward)
        assertEquals(setOf(conflictingA.id, conflictingB.id), forward.conflictingEdits)
    }

    private fun document(signals: List<Signal> = emptyList(), edits: List<Edit> = emptyList()) =
        ReviewDocument(chapterId = chapterId, sourcePath = sourcePath, signals = signals, edits = edits)

    private fun edit(id: String, before: String, anchor: Anchor) = Edit(id = id, before = before, after = "$before!", anchor = anchor)

    private fun signal(id: String, selectedText: String, anchor: Anchor) =
        Signal(id = id, type = SignalType.NOTE, selectedText = selectedText, anchor = anchor, comment = "")
}
