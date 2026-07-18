package net.inkyquill.pocketeditor.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Upsert
    suspend fun upsertRemoteRevision(revision: RemoteRevisionEntity)

    @Query("SELECT * FROM remote_revisions WHERE book_id = :bookId ORDER BY path")
    fun observeRemoteRevisions(bookId: String): Flow<List<RemoteRevisionEntity>>

    @Upsert
    suspend fun upsertMergeBase(base: MergeBaseEntity)

    @Query("SELECT * FROM merge_bases WHERE book_id = :bookId AND path = :path")
    suspend fun getMergeBase(bookId: String, path: String): MergeBaseEntity?

    @Query("SELECT * FROM merge_bases WHERE book_id = :bookId ORDER BY path")
    fun observeMergeBases(bookId: String): Flow<List<MergeBaseEntity>>

    @Upsert
    suspend fun upsertOutbox(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE book_id = :bookId AND path = :path")
    suspend fun getOutbox(bookId: String, path: String): OutboxEntity?

    @Query("DELETE FROM outbox WHERE book_id = :bookId AND path = :path")
    suspend fun deleteOutbox(bookId: String, path: String)

    @Query("SELECT * FROM outbox ORDER BY book_id, path")
    fun observeOutbox(): Flow<List<OutboxEntity>>
}
