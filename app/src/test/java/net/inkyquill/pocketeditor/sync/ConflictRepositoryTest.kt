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
        val conflict = SyncConflict.Review(PATH, merge.partial, merge.conflicts)
        repository.replace(BOOK_ID, conflict)

        assertThrows(IllegalArgumentException::class.java) {
            repository.previewReviewResolution(BOOK_ID, conflict, emptyMap())
        }
        assertEquals(
            "mine",
            repository.previewReviewResolution(
                BOOK_ID,
                conflict,
                mapOf("chapter-note" to ConflictChoice.KEEP_MINE),
            ).chapterNote,
        )
        org.junit.jupiter.api.Assertions.assertNotNull(repository.conflict(BOOK_ID, PATH))
    }

    @Test
    fun `manifest conflict is an explicit file-level choice`() {
        val repository = InMemoryConflictRepository()
        val mine = BookManifest(bookId = BOOK_ID, title = "Mine")
        val yandex = mine.copy(title = "Yandex")
        val conflict = SyncConflict.Manifest(".pocket-editor.json", mine, yandex)
        repository.replace(BOOK_ID, conflict)

        assertEquals(yandex, repository.previewManifestResolution(BOOK_ID, conflict, ConflictChoice.KEEP_YANDEX))
        org.junit.jupiter.api.Assertions.assertNotNull(repository.conflict(BOOK_ID, ".pocket-editor.json"))
    }

    @Test
    fun `manifest conflict rejects a choice that cannot preserve pending work`() {
        val repository = InMemoryConflictRepository()
        val mine = BookManifest(bookId = BOOK_ID, title = "Mine")
        val conflict = SyncConflict.Manifest(
            ".pocket-editor.json",
            mine,
            mine.copy(title = "Yandex"),
            allowedChoices = setOf(ConflictChoice.KEEP_MINE),
        )
        repository.replace(BOOK_ID, conflict)

        assertThrows(IllegalArgumentException::class.java) {
            repository.previewManifestResolution(BOOK_ID, conflict, ConflictChoice.KEEP_YANDEX)
        }
        assertEquals(mine, repository.previewManifestResolution(BOOK_ID, conflict, ConflictChoice.KEEP_MINE))
    }

    @Test
    fun `captured conflict cannot resolve or remove a replacement at the same path`() {
        val repository = InMemoryConflictRepository()
        val mergeA = ReviewMerge.merge(review("base-a"), review("mine-a"), review("yandex-a")) as MergeResult.Conflicted
        val mergeB = ReviewMerge.merge(review("base-b"), review("mine-b"), review("yandex-b")) as MergeResult.Conflicted
        val captured = SyncConflict.Review(PATH, mergeA.partial, mergeA.conflicts)
        val replacement = SyncConflict.Review(PATH, mergeB.partial, mergeB.conflicts)
        repository.replace(BOOK_ID, captured)
        repository.replace(BOOK_ID, replacement)

        assertThrows(IllegalStateException::class.java) {
            repository.previewReviewResolution(BOOK_ID, captured, mapOf("chapter-note" to ConflictChoice.KEEP_MINE))
        }
        org.junit.jupiter.api.Assertions.assertFalse(repository.removeIfCurrent(BOOK_ID, captured))
        assertEquals(replacement.identity, repository.conflict(BOOK_ID, PATH)?.identity)
    }

    private fun review(note: String) = ReviewDocument(chapterId = CHAPTER_ID, sourcePath = "chapter.md", chapterNote = note)

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
        val CHAPTER_ID = UUID.randomUUID().toString()
        const val PATH = "chapter.md.review.json"
    }
}
