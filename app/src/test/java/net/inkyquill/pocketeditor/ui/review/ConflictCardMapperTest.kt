package net.inkyquill.pocketeditor.ui.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.merge.RecordConflict
import net.inkyquill.pocketeditor.merge.RecordValue
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.sync.SyncConflict

class ConflictCardMapperTest {
    @Test fun `maps every record plus manifest using canonical path`() {
        val review = SyncConflict.Review(
            "chapter.md.review.json", ReviewDocument(chapterId = CHAPTER, sourcePath = "chapter.md"),
            listOf(RecordConflict("chapter-note", null, RecordValue.ChapterNoteValue("mine"), RecordValue.ChapterNoteValue("remote"))),
        )
        val manifest = SyncConflict.Manifest(
            BookPaths.MANIFEST_NAME,
            BookManifest(bookId = BOOK, title = "Mine"),
            BookManifest(bookId = BOOK, title = "Remote"),
        )
        val cards = ConflictCardMapper.map(listOf(review, manifest))
        assertEquals(listOf("chapter-note", BookPaths.MANIFEST_NAME), cards.map { it.recordId })
        assertEquals(listOf(false, true), cards.map { it.manifest })
        assertEquals("mine", cards.first().localPreview)
    }

    private companion object {
        const val BOOK = "11111111-1111-4111-8111-111111111111"
        const val CHAPTER = "22222222-2222-4222-8222-222222222222"
    }
}
