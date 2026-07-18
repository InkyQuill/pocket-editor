package net.inkyquill.pocketeditor.reader

import net.inkyquill.pocketeditor.anchor.AnchorFactory
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewProjectorTest {
    private val source = "Первый абзац.\n\nВторой фрагмент."
    private val rendered = MarkdownParser.parse(source)

    @Test
    fun `clean mode contains no review-derived objects even when review exists`() {
        val review = review(
            signals = listOf(signal("signal", "Первый", comment = "Комментарий")),
            edits = listOf(edit("edit", "фрагмент", "текст")),
        )

        val reader = ReviewProjector.project(rendered, review, reviewMode = false)

        assertTrue(reader.blocks.flatMap { it.runs }.all { it.kind == ReaderRunKind.CANONICAL && it.signalIds.isEmpty() })
        assertTrue(reader.blocks.all { it.comments.isEmpty() })
        assertTrue(reader.unresolved.isEmpty())
        assertEquals(0, reader.reviewObjectCount)
    }

    @Test
    fun `review mode projects red deleted and green added diff runs`() {
        val reader = ReviewProjector.project(
            rendered,
            review(edits = listOf(edit("edit", "фрагмент", "текст"))),
            reviewMode = true,
        )
        val runs = reader.blocks.single { "Второй" in it.canonicalText }.runs

        assertEquals("фрагмен", runs.filter { it.kind == ReaderRunKind.DELETED }.joinToString("") { it.text })
        assertEquals("текс", runs.filter { it.kind == ReaderRunKind.ADDED }.joinToString("") { it.text })
    }

    @Test
    fun `intersecting signals remain independently attached to the overlap`() {
        val reader = ReviewProjector.project(
            rendered,
            review(
                signals = listOf(
                    signal("wide", "Первый абзац"),
                    signal("inner", "абзац", type = SignalType.WARNING),
                ),
            ),
            reviewMode = true,
        )

        val overlap = reader.blocks.first().runs.single { it.text == "абзац" }
        assertEquals(setOf("wide", "inner"), overlap.signalIds)
        assertEquals(setOf(SignalType.NOTE, SignalType.WARNING), overlap.signalTypes)
    }

    @Test
    fun `non-empty comments follow containing blocks in source-range order`() {
        val reader = ReviewProjector.project(
            rendered,
            review(
                signals = listOf(
                    signal("later", "абзац", comment = "Второй комментарий"),
                    signal("empty", "Первый", comment = ""),
                    signal("earlier", "Первый", comment = "Первый комментарий"),
                    signal("next-block", "Второй", comment = "Под вторым блоком"),
                ),
            ),
            reviewMode = true,
        )
        assertEquals(listOf("earlier", "later"), reader.blocks[0].comments.map { it.signalId })
        assertEquals(listOf("next-block"), reader.blocks[1].comments.map { it.signalId })
    }

    @Test
    fun `unresolved records remain available for explicit re-anchoring`() {
        val stale = signal("stale", "Первый").copy(selectedText = "Отсутствует")

        val reader = ReviewProjector.project(rendered, review(signals = listOf(stale)), reviewMode = true)

        assertEquals(listOf("stale"), reader.unresolved.map { it.recordId })
    }

    private fun review(signals: List<Signal> = emptyList(), edits: List<Edit> = emptyList()) = ReviewDocument(
        chapterId = "00000000-0000-4000-8000-000000000000",
        sourcePath = "chapter.md",
        signals = signals,
        edits = edits,
    )

    private fun signal(
        id: String,
        selected: String,
        comment: String = "",
        type: SignalType = SignalType.NOTE,
    ): Signal {
        val range = byteRange(selected)
        return Signal(id, type, selected, AnchorFactory.create(source.encodeToByteArray(), range.first, range.last + 1), comment)
    }

    private fun edit(id: String, before: String, after: String): Edit {
        val range = byteRange(before)
        return Edit(id, before, after, AnchorFactory.create(source.encodeToByteArray(), range.first, range.last + 1))
    }

    private fun byteRange(text: String): IntRange {
        val bytes = source.encodeToByteArray()
        val needle = text.encodeToByteArray()
        val start = bytes.indices.first { offset ->
            offset + needle.size <= bytes.size && needle.indices.all { bytes[offset + it] == needle[it] }
        }
        return start until start + needle.size
    }
}
