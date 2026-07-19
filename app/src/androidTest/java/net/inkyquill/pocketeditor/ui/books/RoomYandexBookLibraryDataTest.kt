package net.inkyquill.pocketeditor.ui.books

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.sync.InMemoryRetryGenerationStore
import net.inkyquill.pocketeditor.sync.SyncScheduler
import net.inkyquill.pocketeditor.sync.SyncTrigger
import net.inkyquill.pocketeditor.sync.SyncWorkQueue
import net.inkyquill.pocketeditor.sync.SyncWorkRequest
import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoomYandexBookLibraryDataTest {
    private lateinit var database: PocketEditorDatabase
    private lateinit var cacheRoot: File
    private lateinit var store: AtomicBookStore
    private lateinit var gateway: RecordingGateway
    private lateinit var queue: RecordingQueue
    private lateinit var data: RoomYandexBookLibraryData

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PocketEditorDatabase::class.java).build()
        cacheRoot = File(context.cacheDir, "room-yandex-library-${UUID.randomUUID()}")
        val paths = BookPaths(cacheRoot)
        store = AtomicBookStore(paths)
        gateway = RecordingGateway()
        queue = RecordingQueue()
        data = RoomYandexBookLibraryData(
            gateway,
            store,
            paths,
            database.bookDao(),
            database.syncDao(),
            database.draftDao(),
            SourceSearch(database.searchDao()),
            SyncScheduler(queue, InMemoryRetryGenerationStore(), Duration.ZERO),
            context.getSharedPreferences("library-test-${UUID.randomUUID()}", Context.MODE_PRIVATE),
        )
    }

    @After
    fun tearDown() {
        database.close()
        cacheRoot.deleteRecursively()
    }

    @Test
    fun existingManifestDownloadsEveryChapterBeforeRegistrationAndNeverUploadsCanonicalText() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))

        val candidate = data.existingRoot(ROOT)
        assertEquals(BOOK_ID, candidate?.bookId)
        assertFalse(candidate!!.availableOffline)
        val installed = data.installExisting(ROOT)

        assertEquals(BOOK_ID, installed.bookId)
        assertEquals(listOf("old.md", "gone.md"), store.readManifest(BOOK_ID).chapters.map { it.path })
        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
        assertArrayEquals(GONE, store.readSource(BOOK_ID, "gone.md"))
        assertEquals(0, gateway.remoteMutationCount)
        data.opened(BOOK_ID)
        assertEquals(SyncTrigger.OPEN, queue.requests.single().trigger)
    }

    @Test
    fun invalidRemoteManifestPreservesPreviouslyInstalledCacheAndIsRetryable() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        gateway.files["$ROOT/${BookPaths.MANIFEST_NAME}"] = "{\"schema_version\":99}".encodeToByteArray()
        gateway.files["$ROOT/old.md"] = "changed".encodeToByteArray()

        assertThrows(IllegalArgumentException::class.java) { runBlocking { data.existingRoot(ROOT) } }

        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
        assertEquals(0, gateway.remoteMutationCount)
    }

    @Test
    fun discoveryMutationsUseLocalManifestOutboxRetainCacheAndMigrateReviewWithoutRemoteDeletion() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        store.writeReview(
            BOOK_ID,
            "old.md${BookPaths.REVIEW_SUFFIX}",
            ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Keep this"),
        )
        gateway.publish(
            MANIFEST,
            mapOf("renamed.md" to OLD, "found.md" to FOUND, "bonus.md" to BONUS, "ignore.md" to IGNORE),
        )

        val notices = data.discover(BOOK_ID)
        val renamed = notices.filterIsInstance<DiscoveryNotice.MissingFile>().single { it.chapterId == CHAPTER_OLD }
        val missing = notices.filterIsInstance<DiscoveryNotice.MissingFile>().single { it.chapterId == CHAPTER_GONE }
        assertEquals("renamed.md", renamed.sameHashRenamePath)
        assertEquals(null, missing.sameHashRenamePath)

        data.add(BOOK_ID, "bonus.md", "Afterword", 2)
        data.ignore(BOOK_ID, "ignore.md")
        assertTrue(data.discover(BOOK_ID).none { it is DiscoveryNotice.NewFile && it.path == "ignore.md" })
        data.updatePath(BOOK_ID, CHAPTER_OLD, "renamed.md", requireSameHash = true)
        data.updatePath(BOOK_ID, CHAPTER_GONE, "found.md", requireSameHash = false)
        data.removeChapter(BOOK_ID, CHAPTER_GONE)

        val finalManifest = store.readManifest(BOOK_ID)
        assertEquals(listOf("renamed.md", "bonus.md"), finalManifest.chapters.map { it.path })
        assertEquals(listOf("ignore.md"), finalManifest.ignoredFiles)
        assertEquals("Keep this", store.readReview(BOOK_ID, "renamed.md${BookPaths.REVIEW_SUFFIX}")?.chapterNote)
        assertArrayEquals(GONE, store.readSource(BOOK_ID, "gone.md"))
        assertEquals(OutboxState.PENDING, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME)?.state)
        assertEquals(0, gateway.remoteMutationCount)
    }

    private class RecordingQueue : SyncWorkQueue {
        val requests = mutableListOf<SyncWorkRequest>()
        override fun enqueue(request: SyncWorkRequest) { requests += request }
        override fun cancel(uniqueName: String) = Unit
    }

    private class RecordingGateway : YandexDiskGateway {
        val files = linkedMapOf<String, ByteArray>()
        var remoteMutationCount = 0

        fun publish(manifest: BookManifest, chapters: Map<String, ByteArray>) {
            files.clear()
            files["$ROOT/${BookPaths.MANIFEST_NAME}"] = BookManifest.encode(manifest).encodeToByteArray()
            chapters.forEach { (name, bytes) -> files["$ROOT/$name"] = bytes }
        }

        override suspend fun listFolder(path: String): List<RemoteEntry> = files.map { (filePath, bytes) ->
            RemoteEntry(filePath.substringAfterLast('/'), filePath, "file", bytes.size.toLong(), "rev-${bytes.contentHashCode()}")
        }

        override suspend fun download(path: String) = RemoteFile(path, requireNotNull(files[path]), "rev")
        override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock {
            remoteMutationCount++
            error("Unexpected remote mutation")
        }
        override suspend fun readLock(rootPath: String): SyncLock = error("Unexpected lock read")
        override suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock): String {
            remoteMutationCount++
            error("Unexpected upload")
        }
        override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) { remoteMutationCount++ }
        override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) { remoteMutationCount++ }
    }

    private companion object {
        const val ROOT = "disk:/stories/book"
        const val BOOK_ID = "00000000-0000-0000-0000-000000000301"
        const val CHAPTER_OLD = "00000000-0000-0000-0000-000000000302"
        const val CHAPTER_GONE = "00000000-0000-0000-0000-000000000303"
        val OLD = "# Old\n\nSame source".encodeToByteArray()
        val GONE = "# Gone\n\nRetained source".encodeToByteArray()
        val FOUND = "# Found\n\nDifferent source".encodeToByteArray()
        val BONUS = "# Bonus\n\nNew source".encodeToByteArray()
        val IGNORE = "# Ignore\n".encodeToByteArray()
        val MANIFEST = BookManifest(
            bookId = BOOK_ID,
            title = "Existing story",
            chapters = listOf(
                ChapterEntry(CHAPTER_OLD, "old.md", "Old"),
                ChapterEntry(CHAPTER_GONE, "gone.md", "Gone"),
            ),
        )
    }
}
