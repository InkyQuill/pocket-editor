package net.inkyquill.pocketeditor.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceSearchRoomTest {
    private lateinit var database: PocketEditorDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PocketEditorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun unicodeFtsSearchFindsRussianCanonicalProseOffline() = runBlocking {
        val search = SourceSearch(database.searchDao())
        val source = """---
            |private: metadata
            |---
            |
            |Тихий **золотой ключ** лежал рядом.
        """.trimMargin().encodeToByteArray()

        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", source)
        val hit = search.query(BOOK_ID, "золотой ключ").first().single()

        assertEquals("**золотой ключ**", source.copyOfRange(hit.rawStartByte, hit.rawEndByte).decodeToString())
        assertEquals("Глава", hit.title)
    }

    @Test
    fun replacingAChapterRemovesStaleSearchRows() = runBlocking {
        val search = SourceSearch(database.searchDao())
        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", "Старый текст".encodeToByteArray())
        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", "Новый текст".encodeToByteArray())

        assertEquals(emptyList<SearchHit>(), search.query(BOOK_ID, "Старый").first())
        assertEquals(1, search.query(BOOK_ID, "Новый").first().size)
    }

    @Test
    fun quotedUserTextIsHandledAsDataRatherThanFtsSyntax() = runBlocking {
        val search = SourceSearch(database.searchDao())
        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", "Он сказал слово рядом.".encodeToByteArray())

        assertEquals(emptyList<SearchHit>(), search.query(BOOK_ID, "слово\"").first())
    }

    @Test
    fun unicode61EquivalentQueryReturnsEveryOccurrenceWithoutSplittingEmojiExcerpt() = runBlocking {
        val search = SourceSearch(database.searchDao())
        val source = (("😀".repeat(40)) + " Ёжик прошёл, потом ёжик вернулся.").encodeToByteArray()
        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", source)

        val hits = search.query(BOOK_ID, "ЁЖИК").first()

        assertEquals(2, hits.size)
        assertEquals(listOf("Ёжик", "ёжик"), hits.map { source.copyOfRange(it.rawStartByte, it.rawEndByte).decodeToString() })
        hits.forEach { hit ->
            val excerpt = hit.excerpt
            org.junit.Assert.assertFalse(excerpt.firstOrNull()?.isLowSurrogate() == true)
            org.junit.Assert.assertFalse(excerpt.lastOrNull()?.isHighSurrogate() == true)
        }
    }

    @Test
    fun rebuildingBookAtomicallyRemovesChaptersMissingFromNewSnapshot() = runBlocking {
        val search = SourceSearch(database.searchDao())
        search.rebuildBook(
            BOOK_ID,
            listOf(
                SearchChapterSource(CHAPTER_ID, "Первая", "Старое слово".encodeToByteArray()),
                SearchChapterSource(SECOND_CHAPTER_ID, "Вторая", "Оставшееся слово".encodeToByteArray()),
            ),
        )

        search.rebuildBook(
            BOOK_ID,
            listOf(SearchChapterSource(SECOND_CHAPTER_ID, "Вторая", "Новый текст".encodeToByteArray())),
        )

        assertEquals(emptyList<SearchHit>(), search.query(BOOK_ID, "Старое").first())
        assertEquals(1, search.query(BOOK_ID, "Новый").first().size)
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ID = "22222222-2222-2222-2222-222222222222"
        const val SECOND_CHAPTER_ID = "33333333-3333-3333-3333-333333333333"
    }
}
