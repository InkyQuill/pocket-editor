package net.inkyquill.pocketeditor.book

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookManifestTest {
    private val bookId = "2054f247-0f2e-4d7b-8c67-583526d51540"
    private val chapterOne = ChapterEntry(
        id = "0b4f1cad-c846-4551-a497-a745087f5de2",
        path = "chapter-01.md",
        title = "Первая",
    )
    private val chapterTwo = ChapterEntry(
        id = "157a5b73-cd42-462f-a481-abe8c96ae58e",
        path = "chapter-02.md",
        title = "Вторая",
    )

    @Test
    fun deterministicRoundTrip() {
        val input = fixture("manifest-v1.json")
        assertEquals(input, BookManifest.encode(BookManifest.decode(input)))
    }

    @Test
    fun encodePreservesChapterOrderAndSortsIgnoredFiles() {
        val manifest = BookManifest(
            bookId = bookId,
            title = "Книга",
            chapters = listOf(chapterTwo, chapterOne),
            ignoredFiles = listOf("z.md", "a.md"),
        )

        val encoded = BookManifest.encode(manifest)

        assertTrue(encoded.indexOf("chapter-02.md") < encoded.indexOf("chapter-01.md"))
        assertTrue(encoded.indexOf("a.md") < encoded.indexOf("z.md"))
        assertTrue(encoded.endsWith("\n"))
        assertFalse(encoded.endsWith("\n\n"))
        assertFalse(encoded.contains("\r"))
    }

    @Test
    fun rejectsDuplicateChapterIds() {
        assertInvalid(BookManifest(bookId = bookId, title = "", chapters = listOf(chapterOne, chapterOne.copy(path = "other.md"))))
    }

    @Test
    fun rejectsDuplicateChapterPaths() {
        assertInvalid(BookManifest(bookId = bookId, title = "", chapters = listOf(chapterOne, chapterTwo.copy(path = chapterOne.path))))
    }

    @Test
    fun rejectsTraversalAndNonDirectChildPaths() {
        listOf("../chapter.md", "dir/chapter.md", "/chapter.md", ".", "").forEach { path ->
            assertInvalid(BookManifest(bookId = bookId, title = "", chapters = listOf(chapterOne.copy(path = path))))
        }
    }

    @Test
    fun rejectsNulInChapterPathOnDecode() {
        val invalid = fixture("manifest-v1.json").replace("chapter-02.md", "chapter-\\u0000.md")
        assertThrows(IllegalArgumentException::class.java) { BookManifest.decode(invalid) }
    }

    @Test
    fun rejectsNulInChapterPathOnEncode() {
        assertInvalid(
            BookManifest(
                bookId = bookId,
                title = "",
                chapters = listOf(chapterOne.copy(path = "chapter-\u0000.md")),
            ),
        )
    }

    @Test
    fun rejectsChapterPathAlsoIgnored() {
        assertInvalid(BookManifest(bookId = bookId, title = "", chapters = listOf(chapterOne), ignoredFiles = listOf(chapterOne.path)))
    }

    @Test
    fun rejectsDuplicateIgnoredFiles() {
        assertInvalid(BookManifest(bookId = bookId, title = "", ignoredFiles = listOf("notes.md", "notes.md")))
    }

    @Test
    fun rejectsMalformedIdsUnknownFieldsAndVersion() {
        val fixture = fixture("manifest-v1.json")
        listOf(
            fixture.replace(bookId, "not-a-uuid"),
            fixture.replace(chapterOne.id, "not-a-uuid"),
            fixture.replace("\"schema_version\": 1", "\"schema_version\": 2"),
            fixture.replace("\"title\": \"Алхимик\"", "\"title\": \"Алхимик\",\n  \"unexpected\": true"),
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { BookManifest.decode(invalid) } }
    }

    private fun assertInvalid(manifest: BookManifest) {
        assertThrows(IllegalArgumentException::class.java) { BookManifest.encode(manifest) }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")).readText()
}
