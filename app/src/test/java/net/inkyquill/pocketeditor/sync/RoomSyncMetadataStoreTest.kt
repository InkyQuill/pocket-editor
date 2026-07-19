package net.inkyquill.pocketeditor.sync

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.PendingDeletionEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.SyncDao
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoomSyncMetadataStoreTest {
    @Test
    fun `adapter scopes outbox and delegates durable metadata mutations`() = runBlocking {
        val dao = FakeSyncDao()
        val store = RoomSyncMetadataStore(dao)
        val pending = OutboxEntity(BOOK_ID, "chapter.md.review.json", HASH, HASH, OutboxState.PENDING)
        val other = pending.copy(bookId = UUID.randomUUID().toString())
        dao.outbox.value = listOf(pending, other)

        assertEquals(listOf(pending), store.outbox(BOOK_ID))
        store.recordOutbox(pending.copy(state = OutboxState.RETRY))
        assertEquals(OutboxState.RETRY, dao.outbox.value.first { it.bookId == BOOK_ID }.state)
        store.removeOutbox(BOOK_ID, pending.path)
        assertEquals(listOf(other), dao.outbox.value)
    }

    private class FakeSyncDao : SyncDao {
        val revisions = MutableStateFlow<List<RemoteRevisionEntity>>(emptyList())
        val bases = MutableStateFlow<List<MergeBaseEntity>>(emptyList())
        val outbox = MutableStateFlow<List<OutboxEntity>>(emptyList())
        override suspend fun deleteRemoteRevisions(bookId: String) { revisions.value = revisions.value.filterNot { it.bookId == bookId } }
        override suspend fun deleteMergeBases(bookId: String) { bases.value = bases.value.filterNot { it.bookId == bookId } }
        override suspend fun deleteOutbox(bookId: String) { outbox.value = outbox.value.filterNot { it.bookId == bookId } }
        override suspend fun deletePendingDeletions(bookId: String) = Unit
        override suspend fun upsertRemoteRevision(revision: RemoteRevisionEntity) {
            revisions.value = revisions.value.filterNot { it.bookId == revision.bookId && it.path == revision.path } + revision
        }
        override fun observeRemoteRevisions(bookId: String): Flow<List<RemoteRevisionEntity>> = revisions
        override suspend fun upsertMergeBase(base: MergeBaseEntity) {
            bases.value = bases.value.filterNot { it.bookId == base.bookId && it.path == base.path } + base
        }
        override suspend fun getMergeBase(bookId: String, path: String) = bases.value.singleOrNull { it.bookId == bookId && it.path == path }
        override fun observeMergeBases(bookId: String): Flow<List<MergeBaseEntity>> = bases
        override suspend fun upsertOutbox(item: OutboxEntity) {
            outbox.value = outbox.value.filterNot { it.bookId == item.bookId && it.path == item.path } + item
        }
        override suspend fun getOutbox(bookId: String, path: String) = outbox.value.singleOrNull { it.bookId == bookId && it.path == path }
        override suspend fun deleteOutbox(bookId: String, path: String) {
            outbox.value = outbox.value.filterNot { it.bookId == bookId && it.path == path }
        }
        override fun observeOutbox(): Flow<List<OutboxEntity>> = outbox
        override suspend fun upsertPendingDeletion(value: PendingDeletionEntity) = Unit
        override suspend fun getPendingDeletion(tokenId: String): PendingDeletionEntity? = null
        override suspend fun pendingDeletions(bookId: String): List<PendingDeletionEntity> = emptyList()
        override suspend fun deletePendingDeletion(tokenId: String): Int = 0
    }

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
