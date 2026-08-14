package net.inkyquill.pocketeditor.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Query("DELETE FROM remote_revisions WHERE book_id = :bookId")
    suspend fun deleteRemoteRevisions(bookId: String)

    @Query("DELETE FROM remote_revisions WHERE book_id = :bookId AND path = :path")
    suspend fun deleteRemoteRevision(bookId: String, path: String)

    @Query("DELETE FROM merge_bases WHERE book_id = :bookId")
    suspend fun deleteMergeBases(bookId: String)

    @Query("DELETE FROM merge_bases WHERE book_id = :bookId AND path = :path")
    suspend fun deleteMergeBase(bookId: String, path: String)

    @Query("DELETE FROM outbox WHERE book_id = :bookId")
    suspend fun deleteOutbox(bookId: String)

    @Query("DELETE FROM pending_deletions WHERE book_id = :bookId")
    suspend fun deletePendingDeletions(bookId: String)

    @Query("DELETE FROM pending_publications WHERE book_id = :bookId")
    suspend fun deletePendingPublications(bookId: String)

    @Upsert
    suspend fun upsertRemoteRevision(revision: RemoteRevisionEntity)

    @Query("SELECT * FROM remote_revisions WHERE book_id = :bookId ORDER BY path")
    fun observeRemoteRevisions(bookId: String): Flow<List<RemoteRevisionEntity>>

    @Query("SELECT * FROM remote_revisions WHERE book_id = :bookId ORDER BY path")
    suspend fun getRemoteRevisions(bookId: String): List<RemoteRevisionEntity>

    @Upsert
    suspend fun upsertPendingPublication(value: PendingPublicationEntity)

    @Query("SELECT path FROM pending_publications WHERE book_id = :bookId ORDER BY path")
    suspend fun getPendingPublicationPaths(bookId: String): List<String>

    @Query("DELETE FROM pending_publications WHERE book_id = :bookId AND path = :path")
    suspend fun deletePendingPublication(bookId: String, path: String)

    @Transaction
    suspend fun acceptRemoteDeletion(bookId: String, path: String) {
        deleteMergeBase(bookId, path)
        deleteRemoteRevision(bookId, path)
        upsertPendingPublication(PendingPublicationEntity(bookId, path))
    }

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

    @Query("SELECT * FROM outbox WHERE book_id = :bookId ORDER BY path")
    suspend fun getOutbox(bookId: String): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE book_id = :bookId AND path = :path")
    suspend fun deleteOutbox(bookId: String, path: String)

    @Query("SELECT * FROM outbox ORDER BY book_id, path")
    fun observeOutbox(): Flow<List<OutboxEntity>>

    @Upsert
    suspend fun upsertPendingDeletion(value: PendingDeletionEntity)

    @Query("SELECT * FROM pending_deletions WHERE token_id = :tokenId")
    suspend fun getPendingDeletion(tokenId: String): PendingDeletionEntity?

    @Query("SELECT * FROM pending_deletions WHERE book_id = :bookId ORDER BY created_at, token_id")
    suspend fun pendingDeletions(bookId: String): List<PendingDeletionEntity>

    @Query("DELETE FROM pending_deletions WHERE token_id = :tokenId")
    suspend fun deletePendingDeletion(tokenId: String): Int

    @Transaction
    suspend fun completePendingDeletion(tokenId: String, outbox: OutboxEntity?): Int {
        if (outbox != null) upsertOutbox(outbox)
        return deletePendingDeletion(tokenId)
    }
}
