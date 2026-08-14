package net.inkyquill.pocketeditor.storage

import java.io.File
import java.io.IOException
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.review.ReviewDocument
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AtomicBookStoreTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `book paths reject traversal and absolute paths`() {
        val paths = BookPaths(root)

        listOf("../chapter.md", "folder/chapter.md", "/chapter.md", "folder\\chapter.md", "..", "chapter.md\u0000")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    paths.source(BOOK_ID, path)
                }
            }
    }

    @Test
    fun `invalid manifest is rejected before replacing the saved manifest`() {
        val store = AtomicBookStore(BookPaths(root))
        val original = manifest(title = "Original")
        store.writeManifestBlocking(BOOK_ID, original)

        assertThrows(IllegalArgumentException::class.java) {
            store.writeManifestBlocking(BOOK_ID, original.copy(schemaVersion = 3, title = "Invalid"))
        }

        assertEquals(original, store.readManifestBlocking(BOOK_ID))
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `interruption before replacement preserves old review data`() {
        val normalStore = AtomicBookStore(BookPaths(root))
        normalStore.writeManifestBlocking(BOOK_ID, manifest("Book"))
        val original = review("Original")
        normalStore.writeReviewBlocking(BOOK_ID, REVIEW_PATH, original)
        val interruptedStore = AtomicBookStore(BookPaths(root)) { _, _ ->
            throw IOException("simulated interruption")
        }

        assertThrows(IOException::class.java) {
            interruptedStore.writeReviewBlocking(BOOK_ID, REVIEW_PATH, review("Replacement"))
        }

        assertEquals(original, normalStore.readReviewBlocking(BOOK_ID, REVIEW_PATH))
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `review chapter mismatch is rejected before replacing saved review`() {
        val store = AtomicBookStore(BookPaths(root))
        store.writeManifestBlocking(BOOK_ID, manifest("Book"))
        val original = review("Original")
        store.writeReviewBlocking(BOOK_ID, REVIEW_PATH, original)

        assertThrows(IllegalArgumentException::class.java) {
            store.writeReviewBlocking(
                BOOK_ID,
                REVIEW_PATH,
                original.copy(chapterId = "33333333-3333-3333-3333-333333333333", chapterNote = "Wrong chapter"),
            )
        }

        assertEquals(original, store.readReviewBlocking(BOOK_ID, REVIEW_PATH))
    }

    @Test
    fun `downloaded source replacement is internal and returns its deterministic revision`() {
        val store = AtomicBookStore(BookPaths(root))
        val sourceCache: SourceCache = store
        val bytes = "# Chapter\n".encodeToByteArray()

        val revision = sourceCache.replaceDownloadedSourceBlocking(BOOK_ID, SOURCE_PATH, bytes)

        assertArrayEquals(bytes, store.readSourceBlocking(BOOK_ID, SOURCE_PATH))
        assertEquals("d81cdf94dfa10bd03ec006c5c1a5cdc74ac07f21b388d8c20b3c3dbcf3680431", revision.sha256)
        assertEquals(bytes.size.toLong(), revision.byteSize)
    }

    @Test
    fun `public store exposes writes only for manifest and review documents`() {
        val writeMethods = BookStore::class.java.methods
            .map { it.name }
            .filter { it.startsWith("write") }
            .toSet()

        assertEquals(setOf("writeManifest", "writeReview"), writeMethods)
        assertTrue(SourceCache::class.java.methods.any { it.name == "replaceDownloadedSource" })
    }

    @Test
    fun `successful replacement syncs parent directory only after rename`() {
        val events = mutableListOf<String>()
        val paths = BookPaths(root)
        val store = AtomicBookStore(
            paths = paths,
            beforeReplace = { temporary, target ->
                assertTrue(temporary.exists())
                assertFalse(target.exists())
                events += "before-replace"
            },
            directoryFsync = { directory ->
                assertTrue(paths.manifest(BOOK_ID).exists())
                assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
                events += "directory-fsync"
                DirectorySyncStatus.SYNCED
            },
        )

        val revision = store.writeManifestBlocking(BOOK_ID, manifest("Book"))

        assertEquals(listOf("before-replace", "directory-fsync"), events)
        assertEquals(DirectorySyncStatus.SYNCED, revision.directorySyncStatus)
    }

    @Test
    fun `unsupported directory sync is reported without claiming durability`() {
        val store = AtomicBookStore(
            paths = BookPaths(root),
            beforeReplace = { _, _ -> },
            directoryFsync = { DirectorySyncStatus.UNSUPPORTED },
        )

        val revision = store.writeManifestBlocking(BOOK_ID, manifest("Book"))

        assertEquals(DirectorySyncStatus.UNSUPPORTED, revision.directorySyncStatus)
    }

    @Test
    fun `review deletion reports unsupported directory durability even when file is already absent`() {
        val store = AtomicBookStore(
            paths = BookPaths(root),
            beforeReplace = { _, _ -> },
            directoryFsync = { DirectorySyncStatus.UNSUPPORTED },
        )

        val status = store.deleteReviewBlocking(BOOK_ID, REVIEW_PATH)

        assertEquals(DirectorySyncStatus.UNSUPPORTED, status)
    }

    private fun manifest(title: String) = BookManifest(
        bookId = BOOK_ID,
        title = title,
        chapters = listOf(ChapterEntry(CHAPTER_ID, SOURCE_PATH)),
    )

    private fun review(note: String) = ReviewDocument(
        chapterId = CHAPTER_ID,
        sourcePath = SOURCE_PATH,
        chapterNote = note,
    )

    private fun AtomicBookStore.writeManifestBlocking(bookId: String, value: BookManifest) =
        kotlinx.coroutines.runBlocking { writeManifest(bookId, value) }

    private fun AtomicBookStore.readManifestBlocking(bookId: String) =
        kotlinx.coroutines.runBlocking { readManifest(bookId) }

    private fun AtomicBookStore.writeReviewBlocking(bookId: String, path: String, value: ReviewDocument) =
        kotlinx.coroutines.runBlocking { writeReview(bookId, path, value) }

    private fun AtomicBookStore.readReviewBlocking(bookId: String, path: String) =
        kotlinx.coroutines.runBlocking { readReview(bookId, path) }

    private fun AtomicBookStore.deleteReviewBlocking(bookId: String, path: String) =
        kotlinx.coroutines.runBlocking { deleteReview(bookId, path) }

    private fun AtomicBookStore.readSourceBlocking(bookId: String, path: String) =
        kotlinx.coroutines.runBlocking { readSource(bookId, path) }

    private fun SourceCache.replaceDownloadedSourceBlocking(bookId: String, path: String, bytes: ByteArray) =
        kotlinx.coroutines.runBlocking { replaceDownloadedSource(bookId, path, bytes) }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ID = "22222222-2222-2222-2222-222222222222"
        const val SOURCE_PATH = "chapter.md"
        const val REVIEW_PATH = "chapter.md.review.json"
    }
}
