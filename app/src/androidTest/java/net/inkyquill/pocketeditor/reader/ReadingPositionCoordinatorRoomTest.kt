package net.inkyquill.pocketeditor.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.storage.LocalRevision
import net.inkyquill.pocketeditor.sync.RoomPendingDeletionStore
import net.inkyquill.pocketeditor.sync.RoomSyncMetadataStore
import net.inkyquill.pocketeditor.sync.SyncStatus
import net.inkyquill.pocketeditor.sync.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPositionCoordinatorRoomTest {
    @Test
    fun immediatePositionSurvivesEveryReaderExitAndRepositoryRecreationWithoutStaleOverwrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PocketEditorDatabase::class.java).build()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manifests = mutableMapOf<String, BookManifest>()
        val repository = repository(database, manifests)
        val coordinator = ReadingPositionCoordinator(applicationScope, repository::saveReadingPosition, debounceMillis = 25)

        try {
            val exits = listOf("chapter", "search", "switch-book", "manage-books", "appearance", "back")
            exits.forEachIndexed { index, exit ->
                val bookId = UUID.randomUUID().toString()
                val chapterId = UUID.randomUUID().toString()
                manifests[bookId] = BookManifest(
                    bookId = bookId,
                    title = exit,
                    chapters = listOf(ChapterEntry(chapterId, "chapter.md")),
                )
                database.bookDao().upsertRoot(BookRootEntity(bookId, "disk:/$exit", "/cache/$bookId", index.toLong()))

                coordinator.observed(bookId, chapterId, ReaderPosition(index + 3, (index + 3) * 100))
                coordinator.flush(bookId, chapterId)

                val recreated = repository(database, manifests)
                val restored = recreated.observeChapter(bookId, chapterId, reviewEnabled = false).first().requireReady().readingPosition
                assertEquals("$exit block", index + 3, restored?.blockIndex)
                assertEquals("$exit byte", (index + 3) * 100, restored?.byteOffset)
            }

            val bookId = UUID.randomUUID().toString()
            val oldChapter = UUID.randomUUID().toString()
            val newChapter = UUID.randomUUID().toString()
            manifests[bookId] = BookManifest(
                bookId = bookId,
                title = "Generation",
                chapters = listOf(
                    ChapterEntry(oldChapter, "old.md"),
                    ChapterEntry(newChapter, "new.md"),
                ),
            )
            database.bookDao().upsertRoot(BookRootEntity(bookId, "disk:/generation", "/cache/$bookId", 10L))
            coordinator.observed(bookId, oldChapter, ReaderPosition(4, 400))
            coordinator.observed(bookId, newChapter, ReaderPosition(9, 900))
            coordinator.flush(bookId, newChapter)
            delay(75)

            val restored = repository(database, manifests)
                .observeChapter(bookId, newChapter, reviewEnabled = false).first().requireReady().readingPosition
            assertEquals(9, restored?.blockIndex)
            assertEquals(900, restored?.byteOffset)
        } finally {
            applicationScope.cancel()
            database.close()
        }
    }

    private fun repository(
        database: PocketEditorDatabase,
        manifests: Map<String, BookManifest>,
    ) = ReaderRepository(
        bookStore = object : BookStore {
            override suspend fun readSource(bookId: String, path: String) = "# Chapter\n\nText".encodeToByteArray()
            override suspend fun readManifest(bookId: String) = requireNotNull(manifests[bookId])
            override suspend fun writeManifest(bookId: String, value: BookManifest): LocalRevision = error("unused")
            override suspend fun replaceDownloadedManifest(bookId: String, bytes: ByteArray): LocalRevision = error("unused")
            override suspend fun readReview(bookId: String, path: String): ReviewDocument? = null
            override suspend fun writeReview(bookId: String, path: String, value: ReviewDocument): LocalRevision = error("unused")
            override suspend fun deleteReview(bookId: String, path: String) = error("unused")
        },
        books = RoomReaderBookStore(database.bookDao()),
        metadata = RoomSyncMetadataStore(database.syncDao()),
        scheduler = object : ReaderSyncScheduler {
            override fun enqueue(bookId: String, remoteRootPath: String, trigger: SyncTrigger) = Unit
        },
        syncStatus = { flowOf(SyncStatus.Saved) },
        mutations = ReviewMutationCoordinator(),
        deletions = RoomPendingDeletionStore(database.syncDao()),
        contentChanges = ContentChangeNotifier(),
    )
}
