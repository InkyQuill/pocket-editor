package net.inkyquill.pocketeditor.sync

import java.util.UUID
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.merge.MergeResult
import net.inkyquill.pocketeditor.merge.ReviewMerge
import net.inkyquill.pocketeditor.review.ReviewDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConflictRepositoryTest {
    @Test
    fun `review requires every record decision and applies mine or Yandex values`() {
        val repository = InMemoryConflictRepository()
        val base = review("base")
        val local = review("mine")
        val remote = review("yandex")
        val merge = ReviewMerge.merge(base, local, remote) as MergeResult.Conflicted
        repository.replace(BOOK_ID, SyncConflict.Review(PATH, merge.partial, merge.conflicts))

        assertThrows(IllegalArgumentException::class.java) {
            repository.resolveReview(BOOK_ID, PATH, emptyMap())
        }
        assertEquals("mine", repository.resolveReview(BOOK_ID, PATH, mapOf("chapter-note" to ConflictChoice.KEEP_MINE)).chapterNote)
    }

    @Test
    fun `manifest conflict is an explicit file-level choice`() {
        val repository = InMemoryConflictRepository()
        val mine = BookManifest(bookId = BOOK_ID, title = "Mine")
        val yandex = mine.copy(title = "Yandex")
        repository.replace(BOOK_ID, SyncConflict.Manifest(".pocket-editor.json", mine, yandex))

        assertEquals(yandex, repository.resolveManifest(BOOK_ID, ConflictChoice.KEEP_YANDEX))
    }

    private fun review(note: String) = ReviewDocument(chapterId = CHAPTER_ID, sourcePath = "chapter.md", chapterNote = note)

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
        val CHAPTER_ID = UUID.randomUUID().toString()
        const val PATH = "chapter.md.review.json"
    }
}
