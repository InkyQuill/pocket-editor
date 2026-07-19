package net.inkyquill.pocketeditor.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {
    @Query("DELETE FROM source_search WHERE book_id = :bookId AND chapter_id = :chapterId")
    suspend fun deleteChapter(bookId: String, chapterId: String)

    @Insert
    suspend fun insert(rows: List<SearchEntity>)

    @Query("DELETE FROM source_search WHERE book_id = :bookId")
    suspend fun deleteBook(bookId: String)

    @Transaction
    suspend fun replaceBook(bookId: String, rows: List<SearchEntity>) {
        deleteBook(bookId)
        if (rows.isNotEmpty()) insert(rows)
    }

    @Transaction
    suspend fun replaceChapter(bookId: String, chapterId: String, rows: List<SearchEntity>) {
        deleteChapter(bookId, chapterId)
        if (rows.isNotEmpty()) insert(rows)
    }

    @Query(
        "SELECT rowid, * FROM source_search " +
            "WHERE source_search MATCH :matchQuery AND book_id = :bookId ORDER BY chapter_id, rowid",
    )
    fun query(bookId: String, matchQuery: String): Flow<List<SearchEntity>>
}
