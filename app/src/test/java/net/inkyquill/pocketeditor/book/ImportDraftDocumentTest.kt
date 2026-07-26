package net.inkyquill.pocketeditor.book

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImportDraftDocumentTest {
    @Test
    fun `draft document round trips stable identity and remote revisions`() {
        val original = ImportDraftDocument(
            bookId = BOOK_ID,
            remoteRootPath = "disk:/growth-cheat/result/book01",
            title = "book01",
            phase = ImportDraftPhase.READY,
            chapters = listOf(
                ImportDraftChapter(
                    id = CHAPTER_ID,
                    path = "01-пролог.md",
                    title = "Пролог",
                    included = true,
                    remoteRevision = "rev-1",
                    sha256 = "abc",
                    byteSize = 13,
                ),
            ),
        )

        assertEquals(original, ImportDraftDocument.decode(ImportDraftDocument.encode(original)))
    }

    @Test
    fun `draft rejects duplicate chapter paths`() {
        val chapter = ImportDraftChapter(
            id = CHAPTER_ID,
            path = "01-пролог.md",
            title = "Пролог",
            included = true,
            remoteRevision = "rev-1",
            sha256 = "abc",
            byteSize = 13,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ImportDraftDocument(
                bookId = BOOK_ID,
                remoteRootPath = "disk:/growth-cheat/result/book01",
                title = "book01",
                phase = ImportDraftPhase.READY,
                chapters = listOf(chapter, chapter.copy(id = OTHER_CHAPTER_ID)),
            )
        }
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ID = "22222222-2222-2222-2222-222222222222"
        const val OTHER_CHAPTER_ID = "33333333-3333-3333-3333-333333333333"
    }
}
