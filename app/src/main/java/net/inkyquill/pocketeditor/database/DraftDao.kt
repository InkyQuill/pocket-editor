package net.inkyquill.pocketeditor.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Upsert
    suspend fun upsert(draft: DraftEntity)

    @Query("SELECT * FROM drafts WHERE book_id = :bookId ORDER BY updated_at, chapter_id")
    fun observeBookDrafts(bookId: String): Flow<List<DraftEntity>>

    @Query("DELETE FROM drafts WHERE book_id = :bookId AND chapter_id = :chapterId AND draft_type = :draftType AND record_key = :recordKey")
    suspend fun delete(bookId: String, chapterId: String, draftType: String, recordKey: String = "")
}
