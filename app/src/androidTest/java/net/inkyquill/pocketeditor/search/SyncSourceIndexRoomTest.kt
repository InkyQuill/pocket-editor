package net.inkyquill.pocketeditor.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.PendingDeletionEntity
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.sync.AtomicSyncBaseStore
import net.inkyquill.pocketeditor.sync.InMemoryConflictRepository
import net.inkyquill.pocketeditor.sync.PendingDeletionStore
import net.inkyquill.pocketeditor.sync.RoomSyncMetadataStore
import net.inkyquill.pocketeditor.sync.SyncEngine
import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SyncSourceIndexRoomTest {
    private lateinit var database: PocketEditorDatabase
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PocketEditorDatabase::class.java).build()
        root = File(context.cacheDir, "sync-source-index-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun remoteCanonicalReplacementUpdatesRealRoomFtsWithExactUtf8Range() = runBlocking {
        val paths = BookPaths(File(root, "books"))
        val store = AtomicBookStore(paths)
        val manifest = BookManifest(
            bookId = BOOK_ID,
            title = "Книга",
            chapters = listOf(ChapterEntry(CHAPTER_ID, "chapter.md")),
        )
        val oldSource = "Старый якорь исчезнет.".encodeToByteArray()
        val newSource = "Новый текст: ёжик нашёл золотой ключ 🔑.".encodeToByteArray()
        store.writeManifest(BOOK_ID, manifest)
        store.replaceDownloadedSource(BOOK_ID, "chapter.md", oldSource)
        val search = SourceSearch(database.searchDao())
        search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", oldSource)
        val gateway = CanonicalGateway(manifest, newSource)
        val holder = "test-device"
        val engine = SyncEngine(
            gateway = gateway,
            bookStore = store,
            sourceCache = store,
            metadata = RoomSyncMetadataStore(database.syncDao()),
            baseStore = AtomicSyncBaseStore(File(root, "bases")),
            conflicts = InMemoryConflictRepository(),
            reviewMutations = ReviewMutationCoordinator(),
            pendingDeletions = EmptyPendingDeletions,
            contentChanges = ContentChangeNotifier(),
            holderId = holder,
            lockFactory = { SyncLock(1, UUID.randomUUID().toString(), holder, Instant.now()) },
            sourceIndexUpdater = { bookId, chapters ->
                search.rebuildBook(bookId, chapters.map { SearchChapterSource(it.chapterId, it.title, it.bytes) })
            },
        )

        engine.syncBook(BOOK_ID, ROOT)

        assertEquals(emptyList<SearchHit>(), search.query(BOOK_ID, "Старый").first())
        val hit = search.query(BOOK_ID, "золотой ключ").first().single()
        assertEquals("золотой ключ", newSource.copyOfRange(hit.rawStartByte, hit.rawEndByte).decodeToString())
    }

    private class CanonicalGateway(manifest: BookManifest, source: ByteArray) : YandexDiskGateway {
        private val files = mapOf(
            "$ROOT/${BookPaths.MANIFEST_NAME}" to BookManifest.encode(manifest).encodeToByteArray(),
            "$ROOT/chapter.md" to source,
        )

        override suspend fun listFolder(path: String) = files.map { (filePath, bytes) ->
            RemoteEntry(filePath.substringAfterLast('/'), filePath, "file", bytes.size.toLong(), "rev-${bytes.contentHashCode()}")
        }
        override suspend fun download(path: String) = RemoteFile(path, files.getValue(path), "rev-${files.getValue(path).contentHashCode()}")
        override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock) = lock
        override suspend fun readLock(rootPath: String): SyncLock = error("No competing lock")
        override suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock): String =
            error("No local canonical upload expected")
        override suspend fun uploadManifestConditionally(
            rootPath: String,
            bytes: ByteArray,
            expected: RemoteFile?,
            ownedLock: SyncLock,
            beforeTransaction: suspend () -> Boolean,
        ): String = error("No local manifest upload expected")
        override suspend fun recoverManifestPublication(rootPath: String, ownedLock: SyncLock) = Unit
        override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) = Unit
        override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) = Unit
    }

    private object EmptyPendingDeletions : PendingDeletionStore {
        override suspend fun put(value: PendingDeletionEntity) = Unit
        override suspend fun get(tokenId: String): PendingDeletionEntity? = null
        override suspend fun pendingForBook(bookId: String): List<PendingDeletionEntity> = emptyList()
        override suspend fun remove(tokenId: String) = false
        override suspend fun complete(tokenId: String, outbox: OutboxEntity?) = false
    }

    private companion object {
        const val ROOT = "disk:/stories/book"
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ID = "22222222-2222-2222-2222-222222222222"
    }
}
