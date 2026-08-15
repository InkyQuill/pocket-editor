package net.inkyquill.pocketeditor.ui.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.merge.RecordConflict
import net.inkyquill.pocketeditor.merge.RecordValue
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.sync.SyncConflict
import net.inkyquill.pocketeditor.sync.ConflictChoice

class ConflictCardMapperTest {
    @Test fun `maps every record plus manifest using canonical path`() {
        val review = SyncConflict.Review(
            "chapter.md.review.json", ReviewDocument(chapterId = CHAPTER, sourcePath = "chapter.md"),
            listOf(RecordConflict("chapter-note", null, RecordValue.ChapterNoteValue("mine"), RecordValue.ChapterNoteValue("remote"))),
            identity = "review-v1",
        )
        val manifest = SyncConflict.Manifest(
            BookPaths.MANIFEST_NAME,
            BookManifest(
                bookId = BOOK,
                title = "Local title",
                chapters = listOf(
                    ChapterEntry(CHAPTER, "chapter.md"),
                    ChapterEntry(REORDERED, "second.md"),
                    ChapterEntry(REMOVED, "removed.md"),
                ),
            ),
            BookManifest(
                bookId = BOOK,
                title = "Remote title",
                chapters = listOf(
                    ChapterEntry(REORDERED, "second.md"),
                    ChapterEntry(CHAPTER, "chapter-v2.md"),
                    ChapterEntry(ADDED, "added.md"),
                ),
            ),
            identity = "manifest-v1",
        )
        val cards = ConflictCardMapper.map(listOf(review, manifest))
        assertEquals(listOf("chapter-note", BookPaths.MANIFEST_NAME), cards.map { it.recordId })
        assertEquals(listOf(false, true), cards.map { it.manifest })
        assertEquals(listOf("review-v1", "manifest-v1"), cards.map { it.identity })
        assertEquals(
            listOf("review:chapter.md.review.json:chapter-note", "manifest:${BookPaths.MANIFEST_NAME}"),
            cards.map { it.key },
        )
        assertEquals("mine", cards.first().localPreview)
        val manifestCard = cards.last()
        listOf(CHAPTER, "chapter.md", REORDERED, "second.md", REMOVED, "removed.md").forEach {
            assertTrue(manifestCard.localPreview.contains(it))
        }
        listOf(CHAPTER, "chapter-v2.md", REORDERED, "second.md", ADDED, "added.md").forEach {
            assertTrue(manifestCard.yandexPreview.contains(it))
        }
        assertTrue(manifestCard.localPreview.contains("Порядок"))
        assertTrue(manifestCard.yandexPreview.contains("Порядок"))
        assertTrue(manifestCard.localPreview.contains("Local title"))
        assertTrue(manifestCard.yandexPreview.contains("Remote title"))
    }

    @Test fun `preservation manifest exposes only safe choices`() {
        val manifest = BookManifest(bookId = BOOK, title = "Book")
        val conflict = SyncConflict.Manifest(
            BookPaths.MANIFEST_NAME,
            manifest,
            manifest.copy(title = "Remote"),
            allowedChoices = setOf(ConflictChoice.KEEP_MINE),
        )

        val card = ConflictCardMapper.map(listOf(conflict)).single()

        assertEquals(setOf(ConflictChoice.KEEP_MINE), card.allowedChoices)
    }

    private companion object {
        const val BOOK = "11111111-1111-4111-8111-111111111111"
        const val CHAPTER = "22222222-2222-4222-8222-222222222222"
        const val REORDERED = "33333333-3333-4333-8333-333333333333"
        const val REMOVED = "44444444-4444-4444-8444-444444444444"
        const val ADDED = "55555555-5555-4555-8555-555555555555"
    }
}
