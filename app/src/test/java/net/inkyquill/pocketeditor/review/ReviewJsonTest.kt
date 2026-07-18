package net.inkyquill.pocketeditor.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewJsonTest {
    private val chapterId = "0b4f1cad-c846-4551-a497-a745087f5de2"
    private val sourcePath = "chapter-01.md"
    private val hash = "a".repeat(64)

    @Test
    fun deterministicRoundTrip() {
        val input = fixture("review-v1.json")
        val document = ReviewJson.decode(input, chapterId, sourcePath)
        assertEquals(input, ReviewJson.encode(document))
    }

    @Test
    fun encodeSortsSignalsAndEditsByIdAndUsesOneTrailingLf() {
        val later = signal("ffffffff-ffff-4fff-8fff-ffffffffffff")
        val earlier = signal("00000000-0000-4000-8000-000000000000")
        val laterEdit = edit("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", 10, 14)
        val earlierEdit = edit("11111111-1111-4111-8111-111111111111", 0, 4)
        val document = ReviewDocument(
            chapterId = chapterId,
            sourcePath = sourcePath,
            signals = listOf(later, earlier),
            edits = listOf(laterEdit, earlierEdit),
        )

        val encoded = ReviewJson.encode(document)

        assertTrue(encoded.indexOf(earlier.id) < encoded.indexOf(later.id))
        assertTrue(encoded.lastIndexOf(earlierEdit.id) < encoded.lastIndexOf(laterEdit.id))
        assertTrue(encoded.endsWith("\n"))
        assertFalse(encoded.endsWith("\n\n"))
        assertFalse(encoded.contains("\r"))
    }

    @Test
    fun preservesTextBytes() {
        val text = "  строка 1\nстрока 2\t🙂  "
        val document = ReviewDocument(
            chapterId = chapterId,
            sourcePath = sourcePath,
            chapterNote = text,
            signals = listOf(signal("00000000-0000-4000-8000-000000000000").copy(selectedText = text, comment = text)),
        )
        val decoded = ReviewJson.decode(ReviewJson.encode(document), chapterId, sourcePath)
        assertEquals(text, decoded.chapterNote)
        assertEquals(text, decoded.signals.single().selectedText)
        assertEquals(text, decoded.signals.single().comment)
    }

    @Test
    fun rejectsDuplicateRecordIdsAcrossKinds() {
        val id = "00000000-0000-4000-8000-000000000000"
        assertInvalid(ReviewDocument(chapterId = chapterId, sourcePath = sourcePath, signals = listOf(signal(id)), edits = listOf(edit(id))))
    }

    @Test
    fun rejectsTraversalAndChapterPathMismatch() {
        assertInvalid(ReviewDocument(chapterId = chapterId, sourcePath = "../chapter.md"))
        val input = fixture("review-v1.json")
        assertThrows(IllegalArgumentException::class.java) { ReviewJson.decode(input, chapterId, "other.md") }
        assertThrows(IllegalArgumentException::class.java) { ReviewJson.decode(input, "157a5b73-cd42-462f-a481-abe8c96ae58e", sourcePath) }
    }

    @Test
    fun rejectsEmptyBeforeAndEqualEditText() {
        assertInvalid(ReviewDocument(chapterId = chapterId, sourcePath = sourcePath, edits = listOf(edit().copy(before = ""))))
        assertInvalid(ReviewDocument(chapterId = chapterId, sourcePath = sourcePath, edits = listOf(edit().copy(before = "same", after = "same"))))
    }

    @Test
    fun allowsEmptyAfterAsDeletion() {
        ReviewJson.encode(
            ReviewDocument(chapterId = chapterId, sourcePath = sourcePath, edits = listOf(edit().copy(after = ""))),
        )
    }

    @Test
    fun rejectsOverlappingEditsButAllowsAdjacentEdits() {
        val first = edit("00000000-0000-4000-8000-000000000001", 0, 5)
        val overlapping = edit("00000000-0000-4000-8000-000000000002", 4, 8)
        val adjacent = edit("00000000-0000-4000-8000-000000000003", 5, 8)
        assertInvalid(ReviewDocument(chapterId = chapterId, sourcePath = sourcePath, edits = listOf(first, overlapping)))
        ReviewJson.encode(ReviewDocument(chapterId = chapterId, sourcePath = sourcePath, edits = listOf(first, adjacent)))
    }

    @Test
    fun rejectsMalformedHashesRangesAndContextOver128CodePoints() {
        val base = signal().anchor
        listOf(
            base.copy(sourceSha256 = "A".repeat(64)),
            base.copy(selectionSha256 = "abc"),
            base.copy(startByte = -1),
            base.copy(startByte = 2, endByte = 1),
            base.copy(startLine = 0),
            base.copy(startLine = 2, endLine = 1),
            base.copy(prefix = "🙂".repeat(129)),
            base.copy(suffix = "x".repeat(129)),
        ).forEach { anchor -> assertInvalid(ReviewDocument(chapterId = chapterId, sourcePath = sourcePath, signals = listOf(signal().copy(anchor = anchor)))) }
    }

    @Test
    fun rejectsMalformedIdsUnknownFieldsUnknownSignalTypesAndVersion() {
        val fixture = fixture("review-v1.json")
        listOf(
            fixture.replace("77b2f145-faa5-4de8-8fb2-050dc805978e", "not-a-uuid"),
            fixture.replace("\"schema_version\": 1", "\"schema_version\": 2"),
            fixture.replace("\"type\": \"warning\"", "\"type\": \"other\""),
            fixture.replace("\"chapter_note\":", "\"unexpected\": true,\n  \"chapter_note\":"),
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { ReviewJson.decode(invalid, chapterId, sourcePath) } }
    }

    private fun signal(id: String = "00000000-0000-4000-8000-000000000000") = Signal(
        id = id,
        type = SignalType.NOTE,
        selectedText = "text",
        anchor = anchor(0, 4),
        comment = "",
    )

    private fun edit(
        id: String = "00000000-0000-4000-8000-000000000000",
        startByte: Long = 0,
        endByte: Long = 4,
    ) = Edit(id = id, before = "text", after = "changed", anchor = anchor(startByte, endByte))

    private fun anchor(startByte: Long, endByte: Long) = Anchor(
        sourceSha256 = hash,
        selectionSha256 = hash,
        startByte = startByte,
        endByte = endByte,
        startLine = 1,
        endLine = 1,
        prefix = "",
        suffix = "",
    )

    private fun assertInvalid(document: ReviewDocument) {
        assertThrows(IllegalArgumentException::class.java) { ReviewJson.encode(document) }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")).readText()
}
