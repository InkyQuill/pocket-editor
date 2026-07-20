package net.inkyquill.pocketeditor.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceSearchTest {
    @Test
    fun `indexes rendered Russian prose with exact raw UTF-8 byte ranges`() = runBlocking {
        val dao = FakeSearchDao()
        val search = SourceSearch(dao)
        val source = """---
            |secret: metadata
            |---
            |
            |# Глава
            |
            |Он увидел **золотой ключ** у двери.
        """.trimMargin().encodeToByteArray()

        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Прибытие", source)
        val hit = search.query(BOOK_ID, "золотой ключ").first().single()

        val selected = source.copyOfRange(hit.rawStartByte, hit.rawEndByte).decodeToString()
        assertEquals("**золотой ключ**", selected)
        assertEquals(CHAPTER_ID, hit.chapterId)
        assertEquals("Прибытие", hit.title)
        assertEquals("золотой ключ", hit.excerpt.substring(hit.excerptMatchStart, hit.excerptMatchEnd))
        assertFalse(hit.excerpt.contains("**"))
        assertFalse(dao.rows.any { it.content.contains("secret") || it.content.contains("metadata") })
    }

    @Test
    fun `query escapes FTS syntax and an empty query returns no rows`() = runBlocking {
        val dao = FakeSearchDao()
        val search = SourceSearch(dao)
        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", "Текст с кавычкой и словом.".encodeToByteArray())

        assertEquals(emptyList<SearchHit>(), search.query(BOOK_ID, "   ").first())
        search.query(BOOK_ID, "слово\"").first()
        assertEquals("\"слово\"\"\"", dao.lastMatchQuery)
    }

    @Test
    fun `excerpt match offsets survive leading ellipsis and normalized Cyrillic case`() = runBlocking {
        val search = SourceSearch(FakeSearchDao())
        val prefix = "Очень длинное начало. ".repeat(8)
        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", "${prefix}ЁЖИК идёт дальше.".encodeToByteArray())

        val hit = search.query(BOOK_ID, "ежик").first().single()

        assertTrue(hit.excerpt.startsWith("…"))
        assertEquals("ЁЖИК", hit.excerpt.substring(hit.excerptMatchStart, hit.excerptMatchEnd))
    }

    @Test
    fun `malformed UTF-8 is rejected without replacing a valid chapter index`() = runBlocking {
        val dao = FakeSearchDao()
        val search = SourceSearch(dao)
        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", "Сохранённый текст".encodeToByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", byteArrayOf(0xC3.toByte(), 0x28)) }
        }

        assertEquals(1, search.query(BOOK_ID, "Сохранённый").first().size)
    }

    @Test
    fun `atomic book rebuild removes stale chapters and can clear the disposable index`() = runBlocking {
        val dao = FakeSearchDao()
        val search = SourceSearch(dao)
        search.rebuildBook(
            BOOK_ID,
            listOf(
                SearchChapterSource(CHAPTER_ID, "Первая", "Старый текст".encodeToByteArray()),
                SearchChapterSource(OTHER_CHAPTER_ID, "Вторая", "Лишний текст".encodeToByteArray()),
            ),
        )

        search.rebuildBook(
            BOOK_ID,
            listOf(SearchChapterSource(CHAPTER_ID, "Первая", "Новый текст".encodeToByteArray())),
        )
        assertEquals(emptyList<SearchHit>(), search.query(BOOK_ID, "Лишний").first())
        assertEquals(1, search.query(BOOK_ID, "Новый").first().size)

        search.clearBook(BOOK_ID)
        assertEquals(emptyList<SearchHit>(), search.query(BOOK_ID, "Новый").first())
    }

    private class FakeSearchDao : SearchDao {
        val rows = mutableListOf<SearchEntity>()
        var lastMatchQuery: String? = null

        override suspend fun replaceChapter(bookId: String, chapterId: String, rows: List<SearchEntity>) {
            this.rows.removeAll { it.bookId == bookId && it.chapterId == chapterId }
            this.rows += rows
        }

        override suspend fun deleteChapter(bookId: String, chapterId: String) {
            rows.removeAll { it.bookId == bookId && it.chapterId == chapterId }
        }

        override suspend fun insert(rows: List<SearchEntity>) {
            this.rows += rows
        }

        override suspend fun deleteBook(bookId: String) {
            rows.removeAll { it.bookId == bookId }
        }

        override suspend fun replaceBook(bookId: String, rows: List<SearchEntity>) {
            deleteBook(bookId)
            insert(rows)
        }

        override fun query(bookId: String, matchQuery: String): Flow<List<SearchEntity>> {
            lastMatchQuery = matchQuery
            return flowOf(rows.filter { it.bookId == bookId })
        }
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ID = "22222222-2222-2222-2222-222222222222"
        const val OTHER_CHAPTER_ID = "33333333-3333-3333-3333-333333333333"
    }
}
