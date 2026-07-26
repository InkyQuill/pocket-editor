package net.inkyquill.pocketeditor.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportDraftDao {
    @Query("SELECT * FROM import_drafts ORDER BY updated_at DESC, book_id")
    fun observeAll(): Flow<List<ImportDraftEntity>>

    @Query("SELECT * FROM import_drafts ORDER BY updated_at DESC, book_id")
    suspend fun getAll(): List<ImportDraftEntity>

    @Query("SELECT * FROM import_drafts WHERE book_id = :bookId")
    suspend fun getByBookId(bookId: String): ImportDraftEntity?

    @Query("SELECT * FROM import_drafts WHERE remote_root_path = :remoteRootPath")
    suspend fun getByRemoteRoot(remoteRootPath: String): ImportDraftEntity?

    @Upsert
    suspend fun upsert(draft: ImportDraftEntity)

    @Query("DELETE FROM import_drafts WHERE book_id = :bookId")
    suspend fun delete(bookId: String)
}
