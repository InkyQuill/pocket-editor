package net.inkyquill.pocketeditor.merge

import net.inkyquill.pocketeditor.anchor.AnchorFactory
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReviewMergeTest {
    @Test
    fun `different record IDs merge automatically and deterministically`() {
        val localSignal = signal(ID_B, "beta", 5, 9)
        val remoteSignal = signal(ID_A, "alpha", 0, 5)

        val result = ReviewMerge.merge(
            base = document(),
            local = document(signals = listOf(localSignal)),
            remote = document(signals = listOf(remoteSignal)),
        )

        val merged = assertInstanceOf(MergeResult.Merged::class.java, result).document
        assertEquals(listOf(ID_A, ID_B), merged.signals.map(Signal::id))
    }

    @Test
    fun `same record changed on one side uses changed version`() {
        val baseSignal = signal(ID_A, "alpha", 0, 5)
        val changed = baseSignal.copy(comment = "local change")

        val result = ReviewMerge.merge(
            base = document(signals = listOf(baseSignal)),
            local = document(signals = listOf(changed)),
            remote = document(signals = listOf(baseSignal)),
        )

        val merged = assertInstanceOf(MergeResult.Merged::class.java, result).document
        assertEquals(changed, merged.signals.single())
    }

    @Test
    fun `record deleted on one side and unchanged on other remains deleted`() {
        val baseSignal = signal(ID_A, "alpha", 0, 5)

        val result = ReviewMerge.merge(
            base = document(signals = listOf(baseSignal)),
            local = document(),
            remote = document(signals = listOf(baseSignal)),
        )

        val merged = assertInstanceOf(MergeResult.Merged::class.java, result).document
        assertEquals(emptyList<Signal>(), merged.signals)
    }

    @Test
    fun `record deleted on one side and changed on other conflicts`() {
        val baseSignal = signal(ID_A, "alpha", 0, 5)
        val remoteChange = baseSignal.copy(comment = "remote change")

        val result = ReviewMerge.merge(
            base = document(signals = listOf(baseSignal)),
            local = document(),
            remote = document(signals = listOf(remoteChange)),
        )

        val conflicted = assertInstanceOf(MergeResult.Conflicted::class.java, result)
        assertEquals(listOf(baseSignal), conflicted.partial.signals)
        assertEquals(
            RecordConflict(
                id = ID_A,
                base = RecordValue.SignalValue(baseSignal),
                local = null,
                remote = RecordValue.SignalValue(remoteChange),
            ),
            conflicted.conflicts.single(),
        )
    }

    @Test
    fun `same record changed differently on both sides conflicts`() {
        val baseSignal = signal(ID_A, "alpha", 0, 5)
        val localChange = baseSignal.copy(comment = "local")
        val remoteChange = baseSignal.copy(comment = "remote")

        val result = ReviewMerge.merge(
            base = document(signals = listOf(baseSignal)),
            local = document(signals = listOf(localChange)),
            remote = document(signals = listOf(remoteChange)),
        )

        val conflicted = assertInstanceOf(MergeResult.Conflicted::class.java, result)
        assertEquals(ID_A, conflicted.conflicts.single().id)
        assertEquals(listOf(baseSignal), conflicted.partial.signals)
    }

    @Test
    fun `identical changes on both sides coalesce`() {
        val baseSignal = signal(ID_A, "alpha", 0, 5)
        val identical = baseSignal.copy(comment = "same")

        val result = ReviewMerge.merge(
            base = document(signals = listOf(baseSignal)),
            local = document(signals = listOf(identical)),
            remote = document(signals = listOf(identical)),
        )

        val merged = assertInstanceOf(MergeResult.Merged::class.java, result).document
        assertEquals(identical, merged.signals.single())
    }

    @Test
    fun `overlapping signals remain independent records`() {
        val localSignal = signal(ID_A, "alpha beta", 0, 10)
        val remoteSignal = signal(ID_B, "beta", 6, 10)

        val result = ReviewMerge.merge(
            base = document(),
            local = document(signals = listOf(localSignal)),
            remote = document(signals = listOf(remoteSignal)),
        )

        val merged = assertInstanceOf(MergeResult.Merged::class.java, result).document
        assertEquals(listOf(localSignal, remoteSignal), merged.signals)
    }

    @Test
    fun `edit records participate in the same stable ID merge`() {
        val remoteEdit = Edit(
            id = ID_A,
            before = "alpha",
            after = "ALPHA",
            anchor = AnchorFactory.create(SOURCE, 0, 5),
        )

        val result = ReviewMerge.merge(
            base = document(),
            local = document(),
            remote = document(edits = listOf(remoteEdit)),
        )

        val merged = assertInstanceOf(MergeResult.Merged::class.java, result).document
        assertEquals(listOf(remoteEdit), merged.edits)
    }

    @Test
    fun `chapter note merges as reserved singleton without persisting an ID`() {
        val result = ReviewMerge.merge(
            base = document(chapterNote = "base"),
            local = document(chapterNote = "local"),
            remote = document(chapterNote = "base"),
        )

        val merged = assertInstanceOf(MergeResult.Merged::class.java, result).document
        assertEquals("local", merged.chapterNote)
        assertFalse(merged.toString().contains(CHAPTER_NOTE_RECORD_ID))
    }

    @Test
    fun `different concurrent chapter note changes conflict as one record`() {
        val result = ReviewMerge.merge(
            base = document(chapterNote = "base"),
            local = document(chapterNote = "local"),
            remote = document(chapterNote = "remote"),
        )

        val conflicted = assertInstanceOf(MergeResult.Conflicted::class.java, result)
        assertEquals("base", conflicted.partial.chapterNote)
        assertEquals(
            RecordConflict(
                id = CHAPTER_NOTE_RECORD_ID,
                base = RecordValue.ChapterNoteValue("base"),
                local = RecordValue.ChapterNoteValue("local"),
                remote = RecordValue.ChapterNoteValue("remote"),
            ),
            conflicted.conflicts.single(),
        )
    }

    @Test
    fun `documents with different identities cannot be merged`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReviewMerge.merge(document(), document(sourcePath = "other.md"), document())
        }
    }

    private fun document(
        chapterNote: String = "",
        signals: List<Signal> = emptyList(),
        edits: List<Edit> = emptyList(),
        sourcePath: String = "chapter.md",
    ) = ReviewDocument(
        chapterId = CHAPTER_ID,
        sourcePath = sourcePath,
        chapterNote = chapterNote,
        signals = signals,
        edits = edits,
    )

    private fun signal(id: String, selected: String, start: Int, end: Int) = Signal(
        id = id,
        type = SignalType.NOTE,
        selectedText = selected,
        anchor = AnchorFactory.create(SOURCE, start, end),
    )

    private companion object {
        const val CHAPTER_ID = "00000000-0000-0000-0000-000000000000"
        const val ID_A = "00000000-0000-0000-0000-000000000001"
        const val ID_B = "00000000-0000-0000-0000-000000000002"
        val SOURCE = "alpha beta".encodeToByteArray()
    }
}
