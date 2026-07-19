package net.inkyquill.pocketeditor.ui.books

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.sync.InMemoryRetryGenerationStore
import net.inkyquill.pocketeditor.sync.AtomicSyncBaseStore
import net.inkyquill.pocketeditor.sync.SyncScheduler
import net.inkyquill.pocketeditor.sync.SyncTrigger
import net.inkyquill.pocketeditor.sync.SyncWorkQueue
import net.inkyquill.pocketeditor.sync.SyncWorkRequest
import net.inkyquill.pocketeditor.storage.InstallPhase
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.storage.sha256
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
    private lateinit var paths: BookPaths
    private lateinit var bases: AtomicSyncBaseStore
    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PocketEditorDatabase::class.java).build()
        cacheRoot = File(context.cacheDir, "room-yandex-library-${UUID.randomUUID()}")
        paths = BookPaths(cacheRoot)
        store = AtomicBookStore(paths)
        bases = AtomicSyncBaseStore(File(cacheRoot.parentFile, "bases-${UUID.randomUUID()}"))
        gateway = RecordingGateway()
        queue = RecordingQueue()
        preferences = context.getSharedPreferences("library-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        data = createData()
    }

    private fun createData(
        checkpoint: (LibraryInstallCheckpoint) -> Unit = {},
        transaction: LibraryTransaction = LibraryTransaction { block -> database.withTransaction { block() } },
        phaseObserver: (InstallPhase) -> Unit = {},
    ) = RoomYandexBookLibraryData(
            gateway,
            store,
            paths,
            database.bookDao(),
            database.syncDao(),
            database.draftDao(),
            SourceSearch(database.searchDao()),
            SyncScheduler(queue, InMemoryRetryGenerationStore(), Duration.ZERO),
            preferences,
            baseStore = bases,
            transaction = transaction,
            installCheckpoint = checkpoint,
            installPhaseObserver = phaseObserver,
        )

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
    fun existingInstallCachesRemoteReviewAndTrustedMetadataForOfflineUse() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        val reviewPath = "old.md${BookPaths.REVIEW_SUFFIX}"
        val reviewBytes = net.inkyquill.pocketeditor.review.ReviewJson.encode(
            ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Offline note"),
        ).encodeToByteArray()
        gateway.files["$ROOT/$reviewPath"] = reviewBytes

        data.installExisting(ROOT)
        gateway.files.clear()

        assertEquals("Offline note", store.readReview(BOOK_ID, reviewPath)?.chapterNote)
        val revisions = database.syncDao().observeRemoteRevisions(BOOK_ID).first().associateBy { it.path }
        val mergeBases = database.syncDao().observeMergeBases(BOOK_ID).first().associateBy { it.path }
        assertTrue(BookPaths.MANIFEST_NAME in revisions)
        assertTrue("old.md" in revisions)
        assertTrue(reviewPath in revisions)
        assertEquals(revisions.getValue(reviewPath).remoteRevision, mergeBases.getValue(reviewPath).remoteRevision)
        assertArrayEquals(reviewBytes, bases.read(BOOK_ID, reviewPath)?.bytes)
    }

    @Test
    fun installFailuresAtEveryCommitBoundaryLeavePriorCacheAndRegistrationUsable() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        val originalRoot = database.bookDao().getRoot(BOOK_ID)

        listOf(
            LibraryInstallCheckpoint.FILESYSTEM_SWAP,
            LibraryInstallCheckpoint.METADATA,
            LibraryInstallCheckpoint.SEARCH,
            LibraryInstallCheckpoint.ROOT,
        ).forEach { failurePoint ->
            gateway.publish(MANIFEST, mapOf("old.md" to "changed-$failurePoint".encodeToByteArray(), "gone.md" to GONE))
            val failing = createData(checkpoint = { if (it == failurePoint) error("injected $it") })

            assertThrows(IllegalStateException::class.java) { runBlocking { failing.installExisting(ROOT) } }
            assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
            assertEquals(originalRoot, database.bookDao().getRoot(BOOK_ID))
        }
    }

    @Test
    fun newImportOutboxAndTransactionFailuresExposeNoRootOrPartialCache() = runBlocking {
        gateway.files["$ROOT/new.md"] = "# New\n\nText".encodeToByteArray()
        val draft = ImportDraft(ROOT, "New", listOf(ImportChapterDraft("new.md", "New", true)))
        val beforeDirectories = cacheRoot.listFiles().orEmpty().map(File::getName).toSet()
        val outboxFailure = createData(checkpoint = { if (it == LibraryInstallCheckpoint.OUTBOX) error("outbox") })
        assertThrows(IllegalStateException::class.java) { runBlocking { outboxFailure.import(draft) } }
        assertTrue(database.bookDao().getRoots().isEmpty())
        assertEquals(beforeDirectories, cacheRoot.listFiles().orEmpty().map(File::getName).toSet())

        val transactionFailure = createData(
            transaction = LibraryTransaction { block -> database.withTransaction { block(); error("transaction") } },
        )
        assertThrows(IllegalStateException::class.java) { runBlocking { transactionFailure.import(draft) } }
        assertTrue(database.bookDao().getRoots().isEmpty())
        assertEquals(beforeDirectories, cacheRoot.listFiles().orEmpty().map(File::getName).toSet())
    }

    @Test
    fun processDeathAtEveryInstallPhaseConvergesOnStartupWithoutOrphansOrMismatchedRoot() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        InstallPhase.entries.forEach { crashPhase ->
            val crashing = createData(phaseObserver = { if (it == crashPhase) throw SimulatedProcessDeath() })

            assertThrows(SimulatedProcessDeath::class.java) { runBlocking { crashing.installExisting(ROOT) } }
            val visible = createData().books()

            if (crashPhase == InstallPhase.DATABASE_COMMITTED) {
                assertEquals(listOf(BOOK_ID), visible.map(BookSummary::bookId))
                assertEquals(MANIFEST, store.readManifest(BOOK_ID))
            } else {
                assertTrue(visible.isEmpty())
                assertFalse(paths.bookDirectory(BOOK_ID).exists())
            }
            assertTrue(cacheRoot.listFiles().orEmpty().none {
                it.name.startsWith(".install-") || it.name.startsWith(".backup-")
            })
            database.clearAllTables()
            paths.bookDirectory(BOOK_ID).deleteRecursively()
        }
    }

    @Test
    fun addingSameRegisteredFolderPreservesLocalReviewAndPendingSyncMetadata() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        val reviewPath = "old.md${BookPaths.REVIEW_SUFFIX}"
        val localReview = ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Local only")
        val reviewRevision = store.writeReview(BOOK_ID, reviewPath, localReview)
        val baseBytes = net.inkyquill.pocketeditor.review.ReviewJson.encode(localReview.copy(chapterNote = "Base")).encodeToByteArray()
        val base = bases.write(BOOK_ID, reviewPath, baseBytes, "base-rev")
        database.syncDao().upsertMergeBase(MergeBaseEntity(BOOK_ID, reviewPath, base.sha256, "base-rev"))
        database.syncDao().upsertRemoteRevision(RemoteRevisionEntity(BOOK_ID, reviewPath, "remote-rev", base.sha256))
        database.syncDao().upsertOutbox(
            OutboxEntity(BOOK_ID, reviewPath, reviewRevision.sha256, base.sha256, OutboxState.PENDING),
        )
        val controller = BookLibraryController(data, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        controller.openFolder("$ROOT/")

        assertEquals("Local only", store.readReview(BOOK_ID, reviewPath)?.chapterNote)
        assertEquals(reviewRevision.sha256, database.syncDao().getOutbox(BOOK_ID, reviewPath)?.localSha256)
        assertEquals(base.sha256, database.syncDao().getMergeBase(BOOK_ID, reviewPath)?.sha256)
        assertEquals("remote-rev", database.syncDao().observeRemoteRevisions(BOOK_ID).first().single { it.path == reviewPath }.remoteRevision)
        assertEquals(listOf(SyncTrigger.OPEN), queue.requests.map { it.trigger })
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

    private class SimulatedProcessDeath : Error()

    private class RecordingGateway : YandexDiskGateway {
        val files = linkedMapOf<String, ByteArray>()
        val lastPublished = linkedMapOf<String, ByteArray>()
        var remoteMutationCount = 0

        fun publish(manifest: BookManifest, chapters: Map<String, ByteArray>) {
            files.clear()
            files["$ROOT/${BookPaths.MANIFEST_NAME}"] = BookManifest.encode(manifest).encodeToByteArray()
            chapters.forEach { (name, bytes) -> files["$ROOT/$name"] = bytes }
            lastPublished.clear()
            lastPublished.putAll(files)
        }

        override suspend fun listFolder(path: String): List<RemoteEntry> = files.map { (filePath, bytes) ->
            RemoteEntry(filePath.substringAfterLast('/'), filePath, "file", bytes.size.toLong(), "rev-${bytes.contentHashCode()}")
        }

        override suspend fun download(path: String) = RemoteFile(path, requireNotNull(files[path]), "rev-${requireNotNull(files[path]).contentHashCode()}")
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
