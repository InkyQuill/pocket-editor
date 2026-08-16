package net.inkyquill.pocketeditor.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface ImportDraftDao {
    @Query("SELECT * FROM import_drafts ORDER BY updated_at DESC, book_id")
    suspend fun getAll(): List<ImportDraftEntity>

    @Query("DELETE FROM import_drafts WHERE book_id = :bookId")
    suspend fun delete(bookId: String)
}
