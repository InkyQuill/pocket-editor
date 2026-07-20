package net.inkyquill.pocketeditor.reader

import net.inkyquill.pocketeditor.anchor.AnchorFactory
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.markdown.RenderKind
import net.inkyquill.pocketeditor.markdown.TextRange
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewProjectorTest {
    @Test
    fun `clean and reviewed projections retain heading and inline presentation metadata`() {
        val rendered = MarkdownParser.parse("### Подзаголовок\n\nТихий *вечер* и **свет**.")

        listOf(false, true).forEach { reviewMode ->
            val projected = ReviewProjector.project(rendered, review(), reviewMode)
            assertEquals(3, projected.blocks.first().headingLevel)
            val paragraphRuns = projected.blocks.last().runs
            assertTrue(paragraphRuns.any { "вечер" in it.text && it.renderKind == RenderKind.EMPHASIS })
            assertTrue(paragraphRuns.any { "свет" in it.text && it.renderKind == RenderKind.STRONG })
        }
    }

    @Test
    fun `deep raw search range maps to exact displayed passage`() {
        val rendered = MarkdownParser.parse("# Head\n\nA very long paragraph with the exact needle near its end.")
        val block = ReviewProjector.project(rendered, null, false).blocks.last()
        val rawStart = rendered.sourceBytes.decodeToString().indexOf("exact")

        val displayStart = block.canonicalText.indexOf("exact")
        assertEquals(TextRange(block.sourceIndex, displayStart, displayStart + 5), block.displayRangeForRaw(RawRange(rawStart, rawStart + 5)))
    }
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
    fun `formatted span edits diff rendered text without Markdown syntax`() {
        val formattedSource = "*старый* и **сильный** и [ссылка](https://old.example)"
        val edits = listOf(
            editFor(formattedSource, "emphasis", "*старый*", "*новый*"),
            editFor(formattedSource, "strong", "**сильный**", "**мягкий**"),
            editFor(formattedSource, "link", "[ссылка](https://old.example)", "[переход](https://new.example)"),
        )

        val runs = ReviewProjector.project(
            MarkdownParser.parse(formattedSource),
            review(edits = edits),
            reviewMode = true,
        ).blocks.single().runs
        val projectedDiff = runs.filter { it.kind != ReaderRunKind.CANONICAL }.joinToString("") { it.text }

        assertTrue(runs.any { it.kind == ReaderRunKind.DELETED })
        assertTrue(runs.any { it.kind == ReaderRunKind.ADDED })
        assertTrue(projectedDiff.none { it in "*[]()" })
        assertTrue("http" !in projectedDiff)
    }

    @Test
    fun `signal inside a larger edit applies only to its source-backed diff slice`() {
        val editedSource = "Первый абзац"
        val review = review(
            signals = listOf(signalFor(editedSource, "word", "абзац")),
            edits = listOf(editFor(editedSource, "edit", "Первый абзац", "Совсем иначе")),
        )

        val runs = ReviewProjector.project(MarkdownParser.parse(editedSource), review, reviewMode = true)
            .blocks.single().runs

        assertEquals(listOf("абзац"), runs.filter { "word" in it.signalIds }.map { it.text })
        assertTrue(runs.filter { it.kind == ReaderRunKind.ADDED }.all { it.signalIds.isEmpty() })
        assertTrue(runs.any { it.kind == ReaderRunKind.DELETED && it.text == "Первый " && it.signalIds.isEmpty() })
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

    @Test
    fun `display selection after length-changing insertion maps to exact canonical bytes`() {
        val changed = "alpha beta"
        val block = ReviewProjector.project(
            MarkdownParser.parse(changed),
            review(edits = listOf(editFor(changed, "longer", "alpha", "a very long alpha"))),
            reviewMode = true,
        ).blocks.single()
        val display = block.runs.joinToString("") { it.text }
        val start = display.indexOf("beta")

        val selection = block.sourceSelection(start, start + "beta".length)

        assertEquals(6, selection?.rawRange?.startByte)
        assertEquals(10, selection?.rawRange?.endByte)
        assertEquals("beta", selection?.selectedText)
    }

    @Test
    fun `selection containing only added display text is rejected`() {
        val changed = "alpha beta"
        val block = ReviewProjector.project(
            MarkdownParser.parse(changed),
            review(edits = listOf(editFor(changed, "longer", "alpha", "a very long alpha"))),
            reviewMode = true,
        ).blocks.single()
        val display = block.runs.joinToString("") { it.text }
        val start = display.indexOf("very long")

        assertEquals(null, block.sourceSelection(start, start + "very long".length))
    }

    @Test
    fun `selection mixing added and source-backed display text is rejected`() {
        val changed = "alpha beta"
        val block = ReviewProjector.project(
            MarkdownParser.parse(changed),
            review(edits = listOf(editFor(changed, "longer", "alpha", "a very long alpha"))),
            reviewMode = true,
        ).blocks.single()
        val display = block.runs.joinToString("") { it.text }
        val start = display.indexOf("long")
        val end = display.indexOf("beta") + "beta".length

        assertEquals(null, block.sourceSelection(start, end))
    }

    @Test
    fun `production reader selection rejects protected Markdown interiors without throwing`() {
        listOf(
            "A *quiet* road" to "qui",
            "A [quiet](https://example.com) road" to "qui",
            "A `quiet` road" to "qui",
            "A <mark>quiet</mark> road" to "<mar",
        ).forEach { (source, partial) ->
            val block = ReviewProjector.project(MarkdownParser.parse(source), null, reviewMode = false).blocks.single()
            val start = block.canonicalText.indexOf(partial)

            assertEquals(null, block.sourceSelection(start, start + partial.length), "$source must be rejected safely")
        }
    }

    @Test
    fun `production reader selection accepts the exact complete formatted boundary`() {
        val source = "A *quiet* road"
        val block = ReviewProjector.project(MarkdownParser.parse(source), null, reviewMode = false).blocks.single()
        val start = block.canonicalText.indexOf("quiet")

        val selection = block.sourceSelection(start, start + "quiet".length)

        assertEquals(RawRange(2, 9), selection?.rawRange)
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

    private fun signalFor(source: String, id: String, selected: String): Signal {
        val range = source.byteRangeOf(selected)
        return Signal(
            id,
            SignalType.NOTE,
            selected,
            AnchorFactory.create(source.encodeToByteArray(), range.first, range.last + 1),
        )
    }

    private fun editFor(source: String, id: String, before: String, after: String): Edit {
        val range = source.byteRangeOf(before)
        return Edit(id, before, after, AnchorFactory.create(source.encodeToByteArray(), range.first, range.last + 1))
    }

    private fun byteRange(text: String): IntRange {
        return source.byteRangeOf(text)
    }

    private fun String.byteRangeOf(text: String): IntRange {
        val bytes = encodeToByteArray()
        val needle = text.encodeToByteArray()
        val start = bytes.indices.first { offset ->
            offset + needle.size <= bytes.size && needle.indices.all { bytes[offset + it] == needle[it] }
        }
        return start until start + needle.size
    }
}
