package net.inkyquill.pocketeditor.ui.books

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.reader.ReaderRepository
import net.inkyquill.pocketeditor.reader.RoomReaderBookStore
import net.inkyquill.pocketeditor.reader.ReaderSyncScheduler
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.sync.InMemoryRetryGenerationStore
import net.inkyquill.pocketeditor.sync.AtomicSyncBaseStore
import net.inkyquill.pocketeditor.sync.SyncBase
import net.inkyquill.pocketeditor.sync.SyncBaseStore
import net.inkyquill.pocketeditor.sync.SyncScheduler
import net.inkyquill.pocketeditor.sync.SyncTrigger
import net.inkyquill.pocketeditor.sync.SyncStatus
import net.inkyquill.pocketeditor.sync.RoomSyncMetadataStore
import net.inkyquill.pocketeditor.sync.RoomPendingDeletionStore
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.sync.SyncWorkQueue
import net.inkyquill.pocketeditor.sync.SyncWorkRequest
import net.inkyquill.pocketeditor.sync.InMemoryConflictRepository
import net.inkyquill.pocketeditor.sync.SyncConflict
import net.inkyquill.pocketeditor.storage.InstallPhase
import net.inkyquill.pocketeditor.storage.ImportDraftStore
import net.inkyquill.pocketeditor.storage.InstallRecoveryJournal
import net.inkyquill.pocketeditor.storage.LibraryStartupRecovery
import net.inkyquill.pocketeditor.storage.RecoveryScanner
import net.inkyquill.pocketeditor.storage.StartupSearchIndex
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.database.PendingDeletionEntity
import net.inkyquill.pocketeditor.load.ProgressiveBookInstaller
import net.inkyquill.pocketeditor.load.ProgressiveBookSeed
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.load.initialPriority
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
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
    private lateinit var importDraftStore: ImportDraftStore
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var conflicts: InMemoryConflictRepository
    private lateinit var reviewMutations: ReviewMutationCoordinator
    private lateinit var contentChanges: ContentChangeNotifier
    private var diskDatabaseName: String? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PocketEditorDatabase::class.java).build()
        cacheRoot = File(context.cacheDir, "room-yandex-library-${UUID.randomUUID()}")
        paths = BookPaths(cacheRoot)
        store = AtomicBookStore(paths)
        bases = AtomicSyncBaseStore(File(cacheRoot.parentFile, "bases-${UUID.randomUUID()}"))
        importDraftStore = ImportDraftStore(File(cacheRoot.parentFile, "import-drafts-${UUID.randomUUID()}"))
        gateway = RecordingGateway()
        queue = RecordingQueue()
        preferences = context.getSharedPreferences("library-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        conflicts = InMemoryConflictRepository()
        reviewMutations = ReviewMutationCoordinator()
        contentChanges = ContentChangeNotifier()
        data = createData()
    }

    private fun createData(
        checkpoint: (LibraryInstallCheckpoint) -> Unit = {},
        transaction: LibraryTransaction = LibraryTransaction { block -> database.withTransaction { block() } },
        phaseObserver: (InstallPhase) -> Unit = {},
        directorySync: (File) -> DirectorySyncStatus = { DirectorySyncStatus.SYNCED },
        moveObserver: () -> Unit = {},
        startupRecovery: LibraryStartupRecovery? = null,
        repairCleanupCheckpoint: (RepairCleanupCheckpoint) -> Unit = {},
        replacementCheckpoint: (ReplacementCheckpoint) -> Unit = {},
        reorderCheckpoint: (ReorderCheckpoint) -> Unit = {},
        reorderBaseRefreshCheckpoint: (ReorderBaseRefreshCheckpoint) -> Unit = {},
        baseStore: SyncBaseStore = bases,
    ) = RoomYandexBookLibraryData(
            gateway,
            store,
            paths,
            database.bookDao(),
            database.syncDao(),
            database.draftDao(),
            database.importDraftDao(),
            database.progressiveLoadDao(),
            importDraftStore,
            SourceSearch(database.searchDao()),
            SyncScheduler(queue, InMemoryRetryGenerationStore(), Duration.ZERO),
            preferences,
            baseStore = baseStore,
            conflicts = conflicts,
            transaction = transaction,
            reviewMutations = reviewMutations,
            contentChanges = contentChanges,
            installCheckpoint = checkpoint,
            installPhaseObserver = phaseObserver,
            installDirectorySync = directorySync,
            installMoveObserver = moveObserver,
            startupRecovery = startupRecovery,
            repairCleanupCheckpoint = repairCleanupCheckpoint,
            replacementCheckpoint = replacementCheckpoint,
            reorderCheckpoint = reorderCheckpoint,
            reorderBaseRefreshCheckpoint = reorderBaseRefreshCheckpoint,
        )

    @After
    fun tearDown() {
        database.close()
        diskDatabaseName?.let { ApplicationProvider.getApplicationContext<Context>().deleteDatabase(it) }
        cacheRoot.deleteRecursively()
    }

    @Test
    fun manifestSeedRegistersCompleteSpineBeforeSourceDownloads() = runBlocking {
        val manifest = BookManifest(bookId = BOOK_ID, title = "Partial", chapters = (0 until 5).map { index ->
            ChapterEntry("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", "chapter-$index.md")
        })
        val bytes = BookManifest.encode(manifest).encodeToByteArray()
        val seed = ProgressiveBookSeed(
            manifest,
            ROOT,
            manifest.chapters.mapIndexed { index, chapter ->
                ProgressiveLoadFileEntity(BOOK_ID, chapter.path, chapter.id, index, "r$index", 10, null, ProgressiveLoadFileState.PENDING, initialPriority(index))
            },
            rawBinder = false,
            remoteManifest = RemoteFile("$ROOT/.pocket-editor.json", bytes, "manifest-r"),
        )

        progressiveInstaller().install(seed)

        assertTrue(database.bookDao().getRoot(BOOK_ID) != null)
        assertEquals(manifest, store.readManifest(BOOK_ID))
        assertEquals(5, database.progressiveLoadDao().getFiles(BOOK_ID).size)
        assertEquals(0, database.progressiveLoadDao().getJob(BOOK_ID)?.completedFiles)
        assertEquals("manifest-r", database.syncDao().getRemoteRevisions(BOOK_ID).single { it.path == BookPaths.MANIFEST_NAME }.remoteRevision)
        assertTrue(database.syncDao().getOutbox(BOOK_ID).none { it.path == BookPaths.MANIFEST_NAME })
    }

    @Test
    fun rawSeedCreatesOneSchemaV2ManifestOutboxMutation() = runBlocking {
        val manifest = BookManifest(bookId = BOOK_ID, title = "Raw", chapters = (0 until 4).map { index ->
            ChapterEntry("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", "chapter-$index.md")
        })
        val seed = ProgressiveBookSeed(
            manifest,
            ROOT,
            manifest.chapters.mapIndexed { index, chapter ->
                ProgressiveLoadFileEntity(BOOK_ID, chapter.path, chapter.id, index, "r$index", 10, null, ProgressiveLoadFileState.PENDING, initialPriority(index))
            },
            rawBinder = true,
            remoteManifest = null,
        )

        progressiveInstaller().install(seed)

        assertEquals(2, store.readManifest(BOOK_ID).schemaVersion)
        val outbox = database.syncDao().getOutbox(BOOK_ID).single()
        assertEquals(BookPaths.MANIFEST_NAME, outbox.path)
        assertEquals(null, outbox.baseSha256)
        assertEquals(OutboxState.PENDING, outbox.state)
    }

    @Test
    fun reorderPublishesOneVerifiedManifestAndReordersPendingSpineWithoutDownloading() = runBlocking {
        progressiveInstaller().install(progressiveSeed())
        store.replaceDownloadedSource(BOOK_ID, "old.md", OLD)
        val oldRow = database.progressiveLoadDao().getFiles(BOOK_ID).single { it.chapterId == CHAPTER_OLD }
        database.progressiveLoadDao().updateFile(
            oldRow.copy(state = ProgressiveLoadFileState.CACHED, sha256 = OLD.sha256()),
        )
        val downloadsBefore = gateway.downloadCount

        data.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD))

        val reordered = store.readManifest(BOOK_ID)
        assertEquals(listOf(CHAPTER_GONE, CHAPTER_OLD), reordered.chapters.map(ChapterEntry::id))
        assertEquals(mapOf(CHAPTER_OLD to "old.md", CHAPTER_GONE to "gone.md"), reordered.chapters.associate { it.id to it.path })
        assertEquals(listOf(CHAPTER_GONE, CHAPTER_OLD), database.progressiveLoadDao().getFiles(BOOK_ID).map { it.chapterId })
        val outbox = database.syncDao().getOutbox(BOOK_ID).single { it.path == BookPaths.MANIFEST_NAME }
        assertEquals(BookManifest.encode(reordered).encodeToByteArray().sha256(), outbox.localSha256)
        assertEquals(database.syncDao().getMergeBase(BOOK_ID, BookPaths.MANIFEST_NAME)?.sha256, outbox.baseSha256)
        assertEquals(listOf(CHAPTER_OLD), SourceSearch(database.searchDao()).query(BOOK_ID, "Same source").first().map { it.chapterId }.distinct())
        assertEquals(downloadsBefore, gateway.downloadCount)
        assertEquals(1, queue.requests.count { it.trigger == SyncTrigger.LOCAL_CHANGE })
    }

    @Test
    fun reorderRejectsIncompleteDuplicateAndForeignIdSetsBeforeWriting() = runBlocking {
        progressiveInstaller().install(progressiveSeed())
        val before = paths.manifest(BOOK_ID).readBytes()

        listOf(
            listOf(CHAPTER_OLD),
            listOf(CHAPTER_OLD, CHAPTER_OLD),
            listOf(CHAPTER_OLD, "00000000-0000-0000-0000-000000000999"),
        ).forEach { ids ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { data.reorder(BOOK_ID, ids) } }
            assertArrayEquals(before, paths.manifest(BOOK_ID).readBytes())
        }
        assertTrue(database.syncDao().getOutbox(BOOK_ID).isEmpty())
    }

    @Test
    fun reorderBaseDriftLeavesOrderUntouchedAndCanRefreshThenRetry() = runBlocking {
        progressiveInstaller().install(progressiveSeed())
        database.syncDao().upsertRemoteRevision(
            RemoteRevisionEntity(BOOK_ID, BookPaths.MANIFEST_NAME, "newer-remote", "different-sha"),
        )
        val nonCanonicalRemote = """{"title":"Existing story","chapters":[{"path":"old.md","id":"$CHAPTER_OLD"},{"path":"gone.md","id":"$CHAPTER_GONE"}],"book_id":"$BOOK_ID","schema_version":2}"""
            .encodeToByteArray()
        gateway.files["$ROOT/${BookPaths.MANIFEST_NAME}"] = nonCanonicalRemote
        gateway.files["$ROOT/old.md"] = OLD
        gateway.files["$ROOT/gone.md"] = GONE
        val localBefore = paths.manifest(BOOK_ID).readBytes()
        database.syncDao().upsertOutbox(
            OutboxEntity(BOOK_ID, BookPaths.MANIFEST_NAME, localBefore.sha256(), "old-base", OutboxState.RETRY),
        )

        val failure = assertThrows(BookLibraryUserError::class.java) {
            runBlocking { data.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD)) }
        }

        assertEquals("Порядок не сохранён: сначала обновите основу книги", failure.message)
        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
        assertEquals(null, conflicts.conflict(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertEquals("old-base", database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME)?.baseSha256)

        data.refreshReorderBase(BOOK_ID)
        data.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD))

        val reordered = store.readManifest(BOOK_ID)
        val outbox = requireNotNull(database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertEquals(listOf(CHAPTER_GONE, CHAPTER_OLD), reordered.chapters.map(ChapterEntry::id))
        assertEquals(OutboxState.PENDING, outbox.state)
        assertEquals(nonCanonicalRemote.sha256(), outbox.baseSha256)
        assertEquals(BookManifest.encode(reordered).encodeToByteArray().sha256(), outbox.localSha256)
        assertEquals(nonCanonicalRemote.sha256(), bases.read(BOOK_ID, BookPaths.MANIFEST_NAME)?.sha256)
        assertEquals(1, gateway.downloadCount)
    }

    @Test
    fun reorderBaseRefreshRejectsUnsyncedDirectoryAndRestoresPriorBaseWithoutMetadataPublication() = runBlocking {
        progressiveInstaller().install(progressiveSeed())
        gateway.files["$ROOT/${BookPaths.MANIFEST_NAME}"] = BookManifest.encode(MANIFEST).encodeToByteArray()
        database.syncDao().upsertRemoteRevision(
            RemoteRevisionEntity(BOOK_ID, BookPaths.MANIFEST_NAME, "drift", "drift"),
        )
        val previousBase = requireNotNull(bases.read(BOOK_ID, BookPaths.MANIFEST_NAME))
        val mergeBefore = database.syncDao().getMergeBase(BOOK_ID, BookPaths.MANIFEST_NAME)
        val revisionsBefore = database.syncDao().getRemoteRevisions(BOOK_ID)
        val outboxBefore = database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME)
        var writes = 0
        val unsyncedOnce = object : SyncBaseStore {
            override fun read(bookId: String, path: String): SyncBase? = bases.read(bookId, path)

            override fun write(bookId: String, path: String, bytes: ByteArray, remoteRevision: String): SyncBase {
                val written = bases.write(bookId, path, bytes, remoteRevision)
                writes += 1
                return if (writes == 1) written.copy(directorySyncStatus = DirectorySyncStatus.UNSUPPORTED) else written
            }

            override fun delete(bookId: String, path: String): DirectorySyncStatus = bases.delete(bookId, path)
        }
        val refusing = createData(baseStore = unsyncedOnce)
        val controller = BookLibraryController(refusing, CoroutineScope(Dispatchers.Unconfined), Dispatchers.IO)
        controller.start()
        controller.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD))

        controller.retryReorder()

        assertEquals(
            "Порядок не сохранён: основу книги не удалось записать надёжно. Повторите попытку.",
            controller.state.value.error,
        )
        assertTrue(controller.state.value.reorderRecoveryAvailable)
        assertEquals(2, writes)
        val restored = requireNotNull(bases.read(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertArrayEquals(previousBase.bytes, restored.bytes)
        assertEquals(previousBase.sha256, restored.sha256)
        assertEquals(previousBase.remoteRevision, restored.remoteRevision)
        assertEquals(mergeBefore, database.syncDao().getMergeBase(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertEquals(revisionsBefore, database.syncDao().getRemoteRevisions(BOOK_ID))
        assertEquals(outboxBefore, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
    }

    @Test
    fun newerSaveWaitsAcrossConflictPublicationAndRemainsRecoverable() = runBlocking {
        progressiveInstaller().install(progressiveSeed())
        database.syncDao().upsertRemoteRevision(
            RemoteRevisionEntity(BOOK_ID, BookPaths.MANIFEST_NAME, "drift", "drift"),
        )
        gateway.files["$ROOT/${BookPaths.MANIFEST_NAME}"] = BookManifest.encode(
            MANIFEST.copy(chapters = MANIFEST.chapters.reversed()),
        ).encodeToByteArray()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = createData(reorderBaseRefreshCheckpoint = { checkpoint ->
            if (checkpoint == ReorderBaseRefreshCheckpoint.BEFORE_CONFLICT_REPLACE) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        val controller = BookLibraryController(blocking, CoroutineScope(Dispatchers.Unconfined), Dispatchers.IO)
        controller.start()
        controller.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD))

        val retrying = async(Dispatchers.IO) { controller.retryReorder() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val newer = async(Dispatchers.IO) { controller.reorder(BOOK_ID, listOf(CHAPTER_OLD, CHAPTER_GONE)) }
        assertEquals(null, withTimeoutOrNull(100) { newer.await() })
        release.countDown()
        retrying.await()
        newer.await()

        assertTrue(conflicts.conflict(BOOK_ID, BookPaths.MANIFEST_NAME) is SyncConflict.Manifest)
        assertTrue(controller.state.value.reorderRecoveryAvailable)
        assertEquals(MANIFEST, store.readManifest(BOOK_ID))

        conflicts.remove(BOOK_ID, BookPaths.MANIFEST_NAME)
        gateway.files["$ROOT/${BookPaths.MANIFEST_NAME}"] = BookManifest.encode(MANIFEST).encodeToByteArray()
        controller.retryReorder()

        assertEquals(listOf(CHAPTER_OLD, CHAPTER_GONE), store.readManifest(BOOK_ID).chapters.map(ChapterEntry::id))
        assertFalse(controller.state.value.reorderRecoveryAvailable)
    }

    @Test
    fun newerSaveWaitsAcrossMetadataCommitAndOwnsFinalManifestAndOutbox() = runBlocking {
        progressiveInstaller().install(progressiveSeed())
        database.syncDao().upsertRemoteRevision(
            RemoteRevisionEntity(BOOK_ID, BookPaths.MANIFEST_NAME, "drift", "drift"),
        )
        val remoteBytes = BookManifest.encode(MANIFEST).encodeToByteArray()
        gateway.files["$ROOT/${BookPaths.MANIFEST_NAME}"] = remoteBytes
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = createData(reorderBaseRefreshCheckpoint = { checkpoint ->
            if (checkpoint == ReorderBaseRefreshCheckpoint.BEFORE_METADATA_COMMIT) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        val controller = BookLibraryController(blocking, CoroutineScope(Dispatchers.Unconfined), Dispatchers.IO)
        controller.start()
        controller.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD))

        val retrying = async(Dispatchers.IO) { controller.retryReorder() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val newer = async(Dispatchers.IO) { controller.reorder(BOOK_ID, listOf(CHAPTER_OLD, CHAPTER_GONE)) }
        assertEquals(null, withTimeoutOrNull(100) { newer.await() })
        release.countDown()
        retrying.await()
        newer.await()

        val finalBytes = paths.manifest(BOOK_ID).readBytes()
        val outbox = requireNotNull(database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertEquals(listOf(CHAPTER_OLD, CHAPTER_GONE), store.readManifest(BOOK_ID).chapters.map(ChapterEntry::id))
        assertEquals(finalBytes.sha256(), outbox.localSha256)
        assertEquals(remoteBytes.sha256(), outbox.baseSha256)
        assertEquals(null, conflicts.conflict(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertFalse(controller.state.value.reorderRecoveryAvailable)
    }

    @Test
    fun reorderRejectsMissingObservedManifestRevisionWithoutAnyMutation() = runBlocking {
        progressiveInstaller().install(progressiveSeed())
        database.syncDao().deleteRemoteRevision(BOOK_ID, BookPaths.MANIFEST_NAME)
        val before = paths.manifest(BOOK_ID).readBytes()

        assertThrows(BookLibraryUserError::class.java) {
            runBlocking { data.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD)) }
        }

        assertArrayEquals(before, paths.manifest(BOOK_ID).readBytes())
        assertEquals(listOf(CHAPTER_OLD, CHAPTER_GONE), database.progressiveLoadDao().getFiles(BOOK_ID).map { it.chapterId })
        assertTrue(database.syncDao().getOutbox(BOOK_ID).isEmpty())
        assertEquals(null, conflicts.conflict(BOOK_ID, BookPaths.MANIFEST_NAME))
    }

    @Test
    fun reorderRecoveryRollsBackFilesystemSwapBeforeRoomCommit() = runBlocking {
        prepareCachedPartialReorderFixture()
        val downloadsBefore = gateway.downloadCount
        val crashing = createData(reorderCheckpoint = { checkpoint ->
            if (checkpoint == ReorderCheckpoint.FILESYSTEM_SWAPPED) throw SimulatedProcessDeath()
        })

        assertThrows(SimulatedProcessDeath::class.java) {
            runBlocking { crashing.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD)) }
        }
        createData().books()

        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
        assertEquals(listOf(CHAPTER_OLD, CHAPTER_GONE), database.progressiveLoadDao().getFiles(BOOK_ID).map { it.chapterId })
        assertTrue(database.syncDao().getOutbox(BOOK_ID).isEmpty())
        assertEquals(listOf(CHAPTER_OLD), SourceSearch(database.searchDao()).query(BOOK_ID, "Same source").first().map { it.chapterId }.distinct())
        assertEquals(downloadsBefore, gateway.downloadCount)
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".repair-") })
    }

    @Test
    fun reorderRecoveryKeepsManifestAndRoomPublicationAfterRoomCommit() = runBlocking {
        prepareCachedPartialReorderFixture()
        val downloadsBefore = gateway.downloadCount
        val crashing = createData(reorderCheckpoint = { checkpoint ->
            if (checkpoint == ReorderCheckpoint.DATABASE_COMMITTED) throw SimulatedProcessDeath()
        })

        assertThrows(SimulatedProcessDeath::class.java) {
            runBlocking { crashing.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD)) }
        }
        createData().books()

        val manifest = store.readManifest(BOOK_ID)
        assertEquals(listOf(CHAPTER_GONE, CHAPTER_OLD), manifest.chapters.map(ChapterEntry::id))
        assertEquals(listOf(CHAPTER_GONE, CHAPTER_OLD), database.progressiveLoadDao().getFiles(BOOK_ID).map { it.chapterId })
        val outbox = requireNotNull(database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertEquals(BookManifest.encode(manifest).encodeToByteArray().sha256(), outbox.localSha256)
        assertEquals(listOf(CHAPTER_OLD), SourceSearch(database.searchDao()).query(BOOK_ID, "Same source").first().map { it.chapterId }.distinct())
        assertEquals(downloadsBefore, gateway.downloadCount)
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".repair-") })
    }

    @Test
    fun reorderWaitsForSharedPublicationAndThenPublishesDurableOrder() = runBlocking {
        progressiveInstaller().install(progressiveSeed())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publication = async(Dispatchers.Default) {
            reviewMutations.withBookShared(BOOK_ID) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val reordering = async(Dispatchers.Default) { data.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD)) }
        assertEquals(null, withTimeoutOrNull(100) { reordering.await() })
        release.complete(Unit)
        publication.await()
        reordering.await()

        assertEquals(listOf(CHAPTER_GONE, CHAPTER_OLD), store.readManifest(BOOK_ID).chapters.map(ChapterEntry::id))
    }

    @Test
    fun sharedPublicationWaitsWhileReorderOwnsExclusiveLease() = runBlocking {
        progressiveInstaller().install(progressiveSeed())
        val reorderEntered = CountDownLatch(1)
        val releaseReorder = CountDownLatch(1)
        val exclusiveData = createData(reorderCheckpoint = { checkpoint ->
            if (checkpoint == ReorderCheckpoint.STAGED) {
                reorderEntered.countDown()
                check(releaseReorder.await(5, TimeUnit.SECONDS))
            }
        })
        val reordering = async(Dispatchers.IO) {
            exclusiveData.reorder(BOOK_ID, listOf(CHAPTER_GONE, CHAPTER_OLD))
        }
        assertTrue(reorderEntered.await(5, TimeUnit.SECONDS))

        val sharedEntered = CompletableDeferred<Unit>()
        val publication = async(Dispatchers.Default) {
            reviewMutations.withBookShared(BOOK_ID) { sharedEntered.complete(Unit) }
        }
        assertEquals(null, withTimeoutOrNull(100) { sharedEntered.await() })
        releaseReorder.countDown()
        reordering.await()
        publication.await()

        assertTrue(sharedEntered.isCompleted)
    }

    @Test
    fun progressiveInstallRecoveryRemovesFilesystemSwapWithoutDatabaseRoot() = runBlocking {
        val seed = progressiveSeed()
        val crashing = progressiveInstaller { checkpoint ->
            if (checkpoint == LibraryInstallCheckpoint.FILESYSTEM_SWAP) throw SimulatedProcessDeath()
        }

        assertThrows(SimulatedProcessDeath::class.java) { runBlocking { crashing.install(seed) } }
        assertTrue(paths.bookDirectory(BOOK_ID).exists())
        assertEquals(null, database.bookDao().getRoot(BOOK_ID))
        assertTrue(cacheRoot.listFiles().orEmpty().any { it.name.startsWith(".install-journal-") })

        InstallRecoveryJournal(paths, database.bookDao()).recover()

        assertFalse(paths.bookDirectory(BOOK_ID).exists())
        assertTrue(cacheRoot.listFiles().orEmpty().none {
            it.name.startsWith(".install-") || it.name.startsWith(".install-journal-")
        })
    }

    @Test
    fun progressiveInstallRecoveryKeepsCommittedRootAfterCrashBeforeJournalCommit() = runBlocking {
        val seed = progressiveSeed()
        val crashing = progressiveInstaller { checkpoint ->
            if (checkpoint == LibraryInstallCheckpoint.ROOT) throw SimulatedProcessDeath()
        }

        assertThrows(SimulatedProcessDeath::class.java) { runBlocking { crashing.install(seed) } }
        assertEquals(BOOK_ID, database.bookDao().getRoot(BOOK_ID)?.bookId)
        assertTrue(paths.bookDirectory(BOOK_ID).exists())
        assertTrue(cacheRoot.listFiles().orEmpty().any { it.name.startsWith(".install-journal-") })

        InstallRecoveryJournal(paths, database.bookDao()).recover()

        assertEquals(BOOK_ID, database.bookDao().getRoot(BOOK_ID)?.bookId)
        assertTrue(paths.bookDirectory(BOOK_ID).exists())
        assertEquals(2, database.progressiveLoadDao().getFiles(BOOK_ID).size)
        assertTrue(cacheRoot.listFiles().orEmpty().none {
            it.name.startsWith(".install-") || it.name.startsWith(".install-journal-")
        })
    }

    private fun progressiveSeed(): ProgressiveBookSeed = ProgressiveBookSeed(
        MANIFEST,
        ROOT,
        MANIFEST.chapters.mapIndexed { index, chapter ->
            ProgressiveLoadFileEntity(
                BOOK_ID, chapter.path, chapter.id, index, "r$index", 10, null,
                ProgressiveLoadFileState.PENDING, initialPriority(index),
            )
        },
        rawBinder = false,
        remoteManifest = RemoteFile(
            "$ROOT/${BookPaths.MANIFEST_NAME}",
            BookManifest.encode(MANIFEST).encodeToByteArray(),
            "manifest-r",
        ),
    )

    private suspend fun prepareCachedPartialReorderFixture() {
        progressiveInstaller().install(progressiveSeed())
        store.replaceDownloadedSource(BOOK_ID, "old.md", OLD)
        val oldRow = database.progressiveLoadDao().getFiles(BOOK_ID).single { it.chapterId == CHAPTER_OLD }
        database.progressiveLoadDao().updateFile(
            oldRow.copy(state = ProgressiveLoadFileState.CACHED, sha256 = OLD.sha256()),
        )
        SourceSearch(database.searchDao()).rebuildBook(
            BOOK_ID,
            listOf(net.inkyquill.pocketeditor.search.SearchChapterSource(CHAPTER_OLD, "Old", OLD)),
        )
    }

    private fun progressiveInstaller(checkpoint: (LibraryInstallCheckpoint) -> Unit = {}) = ProgressiveBookInstaller(
        paths,
        store,
        database.bookDao(),
        database.syncDao(),
        database.progressiveLoadDao(),
        SourceSearch(database.searchDao()),
        bases,
        LibraryTransaction { block -> database.withTransaction { block() } },
        checkpoint = checkpoint,
    )

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
        assertTrue(queue.requests.isEmpty())
    }

    @Test
    fun `installed summary and search derive the same title from the remote source snapshot`() = runBlocking {
        val refreshed = "---\ntitle: Frontmatter\n---\n# Heading\n\nsearchable body".encodeToByteArray()
        gateway.publish(MANIFEST, mapOf("old.md" to refreshed, "gone.md" to GONE))

        val summary = data.installExisting(ROOT)
        val summaryTitle = summary.chapters.single { it.id == CHAPTER_OLD }.title
        val indexedTitle = SourceSearch(database.searchDao())
            .query(BOOK_ID, "searchable")
            .first()
            .single { it.chapterId == CHAPTER_OLD }
            .title

        assertEquals("Frontmatter", summaryTitle)
        assertEquals(summaryTitle, indexedTitle)
    }

    @Test
    fun newBookProposalDownloadsEachChapterOnceAndConfirmationUsesOnlyCachedBytes() = runBlocking {
        gateway.files["$ROOT/01.md"] = "# One\n\nFirst".encodeToByteArray()
        gateway.files["$ROOT/02.md"] = "# Two\n\nSecond".encodeToByteArray()

        val draft = data.propose(ROOT)
        assertEquals(2, gateway.downloadCount)

        val imported = data.import(draft)

        assertEquals(2, gateway.downloadCount)
        assertEquals(draft.bookId, imported.bookId)
        assertArrayEquals(gateway.files.getValue("$ROOT/01.md"), store.readSource(imported.bookId, "01.md"))
        assertArrayEquals(gateway.files.getValue("$ROOT/02.md"), store.readSource(imported.bookId, "02.md"))
    }

    @Test
    fun firstLibraryLoadRecoversDurableBookOutboxAndSearchFromEmptyRoomOnce() = runBlocking {
        store.writeManifest(BOOK_ID, MANIFEST)
        store.replaceDownloadedSource(BOOK_ID, "old.md", OLD)
        store.replaceDownloadedSource(BOOK_ID, "gone.md", GONE)
        val review = ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Durable note")
        store.writeReview(BOOK_ID, "old.md${BookPaths.REVIEW_SUFFIX}", review)
        val startup = LibraryStartupRecovery(
            RecoveryScanner(paths, database.bookDao(), database.syncDao()),
            database.bookDao(),
            store,
            SourceSearch(database.searchDao()),
        )
        data = createData(startupRecovery = startup)

        val loads = List(8) { async(Dispatchers.Default) { data.books().single() } }.awaitAll()
        val first = loads.first()

        assertTrue(loads.all { it == first })
        assertTrue(first.availableOffline)
        assertTrue(first.needsRelink)
        assertEquals("Durable note", store.readReview(BOOK_ID, "old.md${BookPaths.REVIEW_SUFFIX}")?.chapterNote)
        assertEquals(
            OutboxState.NEEDS_REMOTE_COMPARE,
            database.syncDao().getOutbox(BOOK_ID, "old.md${BookPaths.REVIEW_SUFFIX}")?.state,
        )
        assertEquals(CHAPTER_OLD, SourceSearch(database.searchDao()).query(BOOK_ID, "Old").first().single().chapterId)
        assertEquals(1, database.bookDao().getRoots().size)

        database.syncDao().deleteOutbox(BOOK_ID, "old.md${BookPaths.REVIEW_SUFFIX}")
        data.books()
        assertEquals(null, database.syncDao().getOutbox(BOOK_ID, "old.md${BookPaths.REVIEW_SUFFIX}"))
    }

    @Test
    fun productionRoomDeletionIsRebuiltFromDurableBookOnNextLibraryLoad() = runBlocking {
        store.writeManifest(BOOK_ID, MANIFEST)
        store.replaceDownloadedSource(BOOK_ID, "old.md", OLD)
        store.replaceDownloadedSource(BOOK_ID, "gone.md", GONE)
        val reviewPath = "old.md${BookPaths.REVIEW_SUFFIX}"
        val review = ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Still here")
        store.writeReview(BOOK_ID, reviewPath, review)

        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "recreated-library-${UUID.randomUUID()}.db".also { diskDatabaseName = it }
        val erased = Room.databaseBuilder(context, PocketEditorDatabase::class.java, dbName).build()
        try {
            erased.bookDao().upsertRoot(
                net.inkyquill.pocketeditor.database.BookRootEntity(
                    BOOK_ID,
                    ROOT,
                    paths.bookDirectory(BOOK_ID).absolutePath,
                    1L,
                ),
            )
        } finally {
            erased.close()
        }
        assertTrue(context.deleteDatabase(dbName))
        database = Room.databaseBuilder(context, PocketEditorDatabase::class.java, dbName).build()
        data = createData(
            startupRecovery = LibraryStartupRecovery(
                RecoveryScanner(paths, database.bookDao(), database.syncDao()),
                database.bookDao(),
                store,
                SourceSearch(database.searchDao()),
            ),
        )

        val recovered = data.books().single()

        assertTrue(recovered.needsRelink)
        assertEquals("Still here", store.readReview(BOOK_ID, reviewPath)?.chapterNote)
        assertEquals(OutboxState.NEEDS_REMOTE_COMPARE, database.syncDao().getOutbox(BOOK_ID, reviewPath)?.state)
        assertEquals(CHAPTER_OLD, SourceSearch(database.searchDao()).query(BOOK_ID, "Same source").first().single().chapterId)
    }

    @Test
    fun startupIndexFailureIsRetriedInsteadOfBeingMarkedComplete() = runBlocking {
        store.writeManifest(BOOK_ID, MANIFEST)
        store.replaceDownloadedSource(BOOK_ID, "old.md", OLD)
        store.replaceDownloadedSource(BOOK_ID, "gone.md", GONE)
        val realSearch = SourceSearch(database.searchDao())
        var attempts = 0
        val startup = LibraryStartupRecovery(
            RecoveryScanner(paths, database.bookDao(), database.syncDao()),
            database.bookDao(),
            store,
            StartupSearchIndex { bookId, chapters ->
                attempts++
                if (attempts == 1) error("index unavailable")
                realSearch.rebuildBook(bookId, chapters)
            },
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { startup.recover() } }
        startup.recover()

        assertEquals(2, attempts)
        assertEquals(CHAPTER_OLD, realSearch.query(BOOK_ID, "Old").first().single().chapterId)
    }

    @Test
    fun firstLibraryLoadSurfacesInvalidDurableManifestAsRecoveryEntry() = runBlocking {
        paths.manifest(BOOK_ID).also {
            it.parentFile?.mkdirs()
            it.writeText("{")
        }
        store.replaceDownloadedSource(BOOK_ID, "old.md", OLD)
        store.replaceDownloadedSource(BOOK_ID, "gone.md", GONE)
        data = createData(
            startupRecovery = LibraryStartupRecovery(
                RecoveryScanner(paths, database.bookDao(), database.syncDao()),
                database.bookDao(),
                store,
                SourceSearch(database.searchDao()),
            ),
        )

        val recovered = data.books().single()

        assertEquals(BOOK_ID, recovered.bookId)
        assertTrue(recovered.needsRelink)
        assertFalse(recovered.availableOffline)
        assertTrue(recovered.recoveryError?.isNotBlank() == true)
    }

    @Test
    fun firstLibraryLoadDoesNotOpenManifestFromMismatchedCacheDirectory() = runBlocking {
        paths.manifest(BOOK_ID).also {
            it.parentFile?.mkdirs()
            it.writeText(BookManifest.encode(MANIFEST.copy(bookId = "00000000-0000-0000-0000-000000000399")))
        }
        data = createData(
            startupRecovery = LibraryStartupRecovery(
                RecoveryScanner(paths, database.bookDao(), database.syncDao()),
                database.bookDao(),
                store,
                SourceSearch(database.searchDao()),
            ),
        )

        val recovered = data.books().single()

        assertEquals(BOOK_ID, recovered.bookId)
        assertFalse(recovered.availableOffline)
        assertEquals("Локальный кеш повреждён и требует восстановления.", recovered.recoveryError)
    }

    @Test
    fun relinkMatchingRemoteRootPreservesDurableReviewWithoutBypassingRevisionProbe() = runBlocking {
        store.writeManifest(BOOK_ID, MANIFEST)
        store.replaceDownloadedSource(BOOK_ID, "old.md", OLD)
        store.replaceDownloadedSource(BOOK_ID, "gone.md", GONE)
        val reviewPath = "old.md${BookPaths.REVIEW_SUFFIX}"
        val review = ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Do not overwrite")
        val revision = store.writeReview(BOOK_ID, reviewPath, review)
        RecoveryScanner(paths, database.bookDao(), database.syncDao()).reconcile()
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data = createData()

        val linked = data.relinkRegistered(BOOK_ID, ROOT)

        assertEquals(ROOT, linked.remoteRootPath)
        assertFalse(linked.needsRelink)
        assertEquals(ROOT, database.bookDao().getRoot(BOOK_ID)?.remoteRootPath)
        assertEquals(review, store.readReview(BOOK_ID, reviewPath))
        assertEquals(revision.sha256, database.syncDao().getOutbox(BOOK_ID, reviewPath)?.localSha256)
        assertTrue(queue.requests.isEmpty())
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
    fun firstInstallFailuresAtEveryCommitBoundaryExposeNoCacheOrRegistration() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        listOf(
            LibraryInstallCheckpoint.FILESYSTEM_SWAP,
            LibraryInstallCheckpoint.METADATA,
            LibraryInstallCheckpoint.SEARCH,
            LibraryInstallCheckpoint.ROOT,
        ).forEach { failurePoint ->
            gateway.publish(MANIFEST, mapOf("old.md" to "changed-$failurePoint".encodeToByteArray(), "gone.md" to GONE))
            val failing = createData(checkpoint = { if (it == failurePoint) error("injected $it") })

            assertThrows(IllegalStateException::class.java) { runBlocking { failing.installExisting(ROOT) } }
            assertFalse(paths.bookDirectory(BOOK_ID).exists())
            assertEquals(null, database.bookDao().getRoot(BOOK_ID))
            assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".install-") })
        }
    }

    @Test
    fun newImportOutboxAndTransactionFailuresExposeNoRootOrPartialCache() = runBlocking {
        gateway.files["$ROOT/new.md"] = "# New\n\nText".encodeToByteArray()
        val draft = data.propose(ROOT).copy(title = "New")
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
        val filesBefore = cacheRoot.walkTopDown().filter(File::isFile).associate { file ->
            file.relativeTo(cacheRoot).path to file.readBytes()
        }
        val downloadsBefore = gateway.downloadCount

        val existing = data.installExisting("$ROOT/")

        assertEquals(BOOK_ID, existing.bookId)
        assertEquals("Local only", store.readReview(BOOK_ID, reviewPath)?.chapterNote)
        assertEquals(reviewRevision.sha256, database.syncDao().getOutbox(BOOK_ID, reviewPath)?.localSha256)
        assertEquals(base.sha256, database.syncDao().getMergeBase(BOOK_ID, reviewPath)?.sha256)
        assertEquals("remote-rev", database.syncDao().observeRemoteRevisions(BOOK_ID).first().single { it.path == reviewPath }.remoteRevision)
        assertEquals(downloadsBefore, gateway.downloadCount)
        val filesAfter = cacheRoot.walkTopDown().filter(File::isFile).associate { file ->
            file.relativeTo(cacheRoot).path to file.readBytes()
        }
        assertEquals(filesBefore.keys, filesAfter.keys)
        filesBefore.forEach { (path, bytes) -> assertArrayEquals(path, bytes, filesAfter.getValue(path)) }
        assertTrue(queue.requests.isEmpty())
    }

    @Test
    fun concurrentDataLayerInstallCallsPerformOneInstallAndReturnOneExistingRoot() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        val prepared = AtomicInteger()
        data = createData(phaseObserver = { if (it == InstallPhase.PREPARED) prepared.incrementAndGet() })

        val results = listOf(
            async(Dispatchers.Default) { data.installExisting(ROOT) },
            async(Dispatchers.Default) { data.installExisting("$ROOT/") },
        ).awaitAll()

        assertEquals(listOf(BOOK_ID, BOOK_ID), results.map(BookSummary::bookId))
        assertEquals(1, prepared.get())
        assertEquals(listOf(BOOK_ID), database.bookDao().getRoots().map { it.bookId })
        assertEquals(3, gateway.downloadCount)
        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
    }

    @Test
    fun concurrentNewImportsPerformOneInstallAndReturnTheRegisteredRoot() = runBlocking {
        gateway.files["$ROOT/new.md"] = "# New\n\nText".encodeToByteArray()
        val draft = data.propose(ROOT).copy(title = "New")
        val prepared = AtomicInteger()
        data = createData(phaseObserver = { if (it == InstallPhase.PREPARED) prepared.incrementAndGet() })

        val results = listOf(
            async(Dispatchers.Default) { data.import(draft) },
            async(Dispatchers.Default) { data.import(draft) },
        ).awaitAll()

        assertEquals(1, results.map(BookSummary::bookId).distinct().size)
        assertEquals(1, prepared.get())
        assertEquals(1, gateway.downloadCount)
        assertEquals(1, database.bookDao().getRoots().size)
        assertEquals(listOf(SyncTrigger.LOCAL_CHANGE), queue.requests.map { it.trigger })
    }

    @Test
    fun installJournalAndLibraryRenamesSyncTheirContainingDirectory() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        val events = mutableListOf<String>()
        data = createData(
            phaseObserver = { events += it.name },
            directorySync = { directory ->
                assertEquals(cacheRoot.canonicalFile, directory.canonicalFile)
                events += "FSYNC"
                DirectorySyncStatus.SYNCED
            },
        )

        data.installExisting(ROOT)

        assertEquals(
            listOf(
                "FSYNC", "PREPARED",
                "FSYNC", "OLD_MOVED",
                "FSYNC", "FSYNC", "SWAPPED",
                "FSYNC", "DATABASE_COMMITTED",
                "FSYNC", "FSYNC",
            ),
            events,
        )
    }

    @Test
    fun postCommitDirectorySyncFailureIsReportedAndStartupRecoveryKeepsTheMatchingRoot() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        val syncCalls = AtomicInteger()
        data = createData(directorySync = {
            if (syncCalls.incrementAndGet() == 5) error("directory fsync failed")
            DirectorySyncStatus.SYNCED
        })

        assertThrows(IllegalStateException::class.java) { runBlocking { data.installExisting(ROOT) } }
        val recovered = createData().books()

        assertEquals(listOf(BOOK_ID), recovered.map(BookSummary::bookId))
        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".install-") })
    }

    @Test
    fun processDeathAfterRoomCommitWhileJournalStillSaysSwappedKeepsMatchingVisibleRoot() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data = createData(
            transaction = LibraryTransaction { block ->
                database.withTransaction { block() }
                throw SimulatedProcessDeath()
            },
        )

        assertThrows(SimulatedProcessDeath::class.java) { runBlocking { data.installExisting(ROOT) } }
        val recovered = createData().books()

        assertEquals(listOf(BOOK_ID), recovered.map(BookSummary::bookId))
        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".install-") })
    }

    @Test
    fun processDeathAfterFinalRenameBeforeSwappedMarkerRemovesOrphanAndAllowsRetry() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data = createData(moveObserver = { throw SimulatedProcessDeath() })

        assertThrows(SimulatedProcessDeath::class.java) { runBlocking { data.installExisting(ROOT) } }
        assertTrue(paths.bookDirectory(BOOK_ID).exists())
        assertEquals(null, database.bookDao().getRoot(BOOK_ID))

        val restarted = createData()
        assertTrue(restarted.books().isEmpty())
        assertFalse(paths.bookDirectory(BOOK_ID).exists())
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".install-") })

        val retried = restarted.installExisting(ROOT)
        assertEquals(BOOK_ID, retried.bookId)
        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
        assertEquals(BOOK_ID, database.bookDao().getRoot(BOOK_ID)?.bookId)
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
    fun registeredRepairRestoresDamagedCanonicalCacheAndPreservesDirtyReviewState() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        val reviewPath = "old.md${BookPaths.REVIEW_SUFFIX}"
        val baseReview = ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Base")
        val localReview = baseReview.copy(chapterNote = "Local draft")
        val baseBytes = net.inkyquill.pocketeditor.review.ReviewJson.encode(baseReview).encodeToByteArray()
        val base = bases.write(BOOK_ID, reviewPath, baseBytes, "review-base")
        store.writeReview(BOOK_ID, reviewPath, localReview)
        val localBytes = net.inkyquill.pocketeditor.review.ReviewJson.encode(localReview).encodeToByteArray()
        val outbox = OutboxEntity(BOOK_ID, reviewPath, localBytes.sha256(), base.sha256, OutboxState.PENDING)
        database.syncDao().upsertOutbox(outbox)
        database.syncDao().upsertMergeBase(MergeBaseEntity(BOOK_ID, reviewPath, base.sha256, "review-base"))
        database.syncDao().upsertRemoteRevision(RemoteRevisionEntity(BOOK_ID, reviewPath, "review-base", base.sha256))
        paths.manifest(BOOK_ID).writeText("damaged")
        paths.source(BOOK_ID, "old.md").writeText("damaged")

        val repaired = data.repairRegistered(BOOK_ID)

        assertEquals(BOOK_ID, repaired.bookId)
        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
        assertEquals(localReview, store.readReview(BOOK_ID, reviewPath))
        assertEquals(outbox, database.syncDao().getOutbox(BOOK_ID, reviewPath))
        assertEquals(base.sha256, database.syncDao().getMergeBase(BOOK_ID, reviewPath)?.sha256)
        assertEquals("review-base", database.syncDao().observeRemoteRevisions(BOOK_ID).first().single { it.path == reviewPath }.remoteRevision)
        assertEquals(0, gateway.remoteMutationCount)
    }

    @Test
    fun registeredRepairRecordsConflictWhenLocalAndRemoteReviewDiverge() = runBlocking {
        val reviewPath = "old.md${BookPaths.REVIEW_SUFFIX}"
        val baseReview = ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Base")
        val baseBytes = net.inkyquill.pocketeditor.review.ReviewJson.encode(baseReview).encodeToByteArray()
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        gateway.files["$ROOT/$reviewPath"] = baseBytes
        data.installExisting(ROOT)
        val local = baseReview.copy(chapterNote = "Mine")
        val localRevision = store.writeReview(BOOK_ID, reviewPath, local)
        val base = requireNotNull(bases.read(BOOK_ID, reviewPath))
        database.syncDao().upsertOutbox(OutboxEntity(BOOK_ID, reviewPath, localRevision.sha256, base.sha256, OutboxState.PENDING))
        val remote = baseReview.copy(chapterNote = "Yandex")
        gateway.files["$ROOT/$reviewPath"] = net.inkyquill.pocketeditor.review.ReviewJson.encode(remote).encodeToByteArray()
        paths.source(BOOK_ID, "old.md").writeText("damaged")

        data.repairRegistered(BOOK_ID)

        assertEquals(local, store.readReview(BOOK_ID, reviewPath))
        val conflict = conflicts.conflict(BOOK_ID, reviewPath) as SyncConflict.Review
        assertEquals("Base", conflict.partial.chapterNote)
        assertEquals(listOf(net.inkyquill.pocketeditor.merge.CHAPTER_NOTE_RECORD_ID), conflict.records.map { it.id })
        assertEquals(
            "Mine",
            (conflict.records.single().local as net.inkyquill.pocketeditor.merge.RecordValue.ChapterNoteValue).note,
        )
        assertEquals("Yandex", net.inkyquill.pocketeditor.review.ReviewJson.decode(conflict.remoteBytes.decodeToString(), CHAPTER_OLD, "old.md").chapterNote)
        assertEquals(base.sha256, database.syncDao().getMergeBase(BOOK_ID, reviewPath)?.sha256)
    }

    @Test
    fun registeredRepairRejectsInvalidRemoteSnapshotWithoutChangingDamagedCacheOrLeakingStages() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        val damaged = "locally damaged".encodeToByteArray()
        paths.source(BOOK_ID, "old.md").writeBytes(damaged)
        gateway.files["$ROOT/gone.md"] = byteArrayOf(0xC3.toByte())
        val before = cacheRoot.walkTopDown().filter(File::isFile).associate { it.relativeTo(cacheRoot).path to it.readBytes() }

        assertThrows(IllegalArgumentException::class.java) { runBlocking { data.repairRegistered(BOOK_ID) } }

        val after = cacheRoot.walkTopDown().filter(File::isFile).associate { it.relativeTo(cacheRoot).path to it.readBytes() }
        assertEquals(before.keys, after.keys)
        before.forEach { (path, bytes) -> assertArrayEquals(path, bytes, after.getValue(path)) }
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".repair-") })

        gateway.files["$ROOT/gone.md"] = GONE
        gateway.files["$ROOT/${BookPaths.MANIFEST_NAME}"] = "{\"schema_version\":99}".encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { data.repairRegistered(BOOK_ID) } }
        assertArrayEquals(damaged, store.readSource(BOOK_ID, "old.md"))
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".repair-") })
    }

    @Test
    fun registeredRepairRollsBackFilesystemAndRoomWhenCommitFailsWithoutLeakingStages() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        val damaged = "damaged before repair".encodeToByteArray()
        paths.source(BOOK_ID, "old.md").writeBytes(damaged)
        val metadataBefore = database.syncDao().observeRemoteRevisions(BOOK_ID).first()
        val failing = createData(checkpoint = { if (it == LibraryInstallCheckpoint.SEARCH) error("repair commit") })

        assertThrows(IllegalStateException::class.java) { runBlocking { failing.repairRegistered(BOOK_ID) } }

        assertArrayEquals(damaged, store.readSource(BOOK_ID, "old.md"))
        assertEquals(metadataBefore, database.syncDao().observeRemoteRevisions(BOOK_ID).first())
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".repair-") })
    }

    @Test
    fun postCommitRepairMarkerCleanupFailureKeepsCommittedRepairAndRecoversArtifacts() = runBlocking {
        verifyPostCommitRepairCleanupFailure(RepairCleanupCheckpoint.MARKER_DELETED)
    }

    @Test
    fun postCommitRepairDirectorySyncFailureKeepsCommittedRepairAndRecoversArtifacts() = runBlocking {
        verifyPostCommitRepairCleanupFailure(RepairCleanupCheckpoint.BEFORE_DIRECTORY_SYNC)
    }

    private suspend fun verifyPostCommitRepairCleanupFailure(failurePoint: RepairCleanupCheckpoint) {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        val reviewPath = "old.md${BookPaths.REVIEW_SUFFIX}"
        val baseReview = ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Base")
        val localReview = baseReview.copy(chapterNote = "Dirty local review")
        val baseBytes = net.inkyquill.pocketeditor.review.ReviewJson.encode(baseReview).encodeToByteArray()
        val base = bases.write(BOOK_ID, reviewPath, baseBytes, "review-base")
        val localRevision = store.writeReview(BOOK_ID, reviewPath, localReview)
        val outbox = OutboxEntity(BOOK_ID, reviewPath, localRevision.sha256, base.sha256, OutboxState.PENDING)
        database.syncDao().upsertOutbox(outbox)
        database.syncDao().upsertMergeBase(MergeBaseEntity(BOOK_ID, reviewPath, base.sha256, "review-base"))
        paths.source(BOOK_ID, "old.md").writeText("damaged")
        val failing = createData(
            repairCleanupCheckpoint = { point -> if (point == failurePoint) error("post-commit cleanup") },
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { failing.repairRegistered(BOOK_ID) } }

        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
        assertEquals(localReview, store.readReview(BOOK_ID, reviewPath))
        assertEquals(outbox, database.syncDao().getOutbox(BOOK_ID, reviewPath))
        assertEquals(base.sha256, database.syncDao().getMergeBase(BOOK_ID, reviewPath)?.sha256)
        assertEquals(BOOK_ID, database.bookDao().getRoot(BOOK_ID)?.bookId)
        assertTrue(cacheRoot.listFiles().orEmpty().any { it.name.startsWith(".repair-journal-") })
        assertTrue(cacheRoot.listFiles().orEmpty().any { it.name.startsWith(".repair-backup-") })

        val recovered = createData().books().single()

        assertTrue(recovered.availableOffline)
        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
        assertEquals(localReview, store.readReview(BOOK_ID, reviewPath))
        assertEquals(outbox, database.syncDao().getOutbox(BOOK_ID, reviewPath))
        assertEquals(base.sha256, database.syncDao().getMergeBase(BOOK_ID, reviewPath)?.sha256)
        assertEquals(CHAPTER_OLD, SourceSearch(database.searchDao()).query(BOOK_ID, "Same source").first().single().chapterId)
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".repair-") })
    }

    @Test
    fun malformedUtf8ManifestCannotReplaceInstalledBook() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        gateway.files["$ROOT/${BookPaths.MANIFEST_NAME}"] =
            byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte())

        assertThrows(IllegalArgumentException::class.java) { runBlocking { data.existingRoot(ROOT) } }

        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
        assertEquals(BOOK_ID, database.bookDao().getRoot(BOOK_ID)?.bookId)
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

        data.add(BOOK_ID, "bonus.md", 2)
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

    @Test
    fun replacementPreservesIdentityCopiesReviewClampsPositionAndQueuesExactBaseMutations() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        val manifestBase = requireNotNull(database.syncDao().getMergeBase(BOOK_ID, BookPaths.MANIFEST_NAME))
        val oldReviewPath = "old.md${BookPaths.REVIEW_SUFFIX}"
        val newReviewPath = "replacement.md${BookPaths.REVIEW_SUFFIX}"
        val review = ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Keep this")
        store.writeReview(BOOK_ID, oldReviewPath, review)
        database.syncDao().upsertPendingDeletion(
            PendingDeletionEntity(
                tokenId = "replacement-token",
                bookId = BOOK_ID,
                chapterId = CHAPTER_OLD,
                reviewPath = oldReviewPath,
                recordId = "record",
                recordType = "signal",
                recordPayload = ReviewJson.encode(
                    ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md"),
                ),
                createdAt = 1,
            ),
        )
        database.bookDao().upsertReadingPosition(
            ReadingPositionEntity(BOOK_ID, CHAPTER_OLD, blockIndex = 99, byteOffset = 99_999, updatedAt = 123),
        )
        gateway.files["$ROOT/replacement.md"] = REPLACEMENT
        val changed = async(Dispatchers.Unconfined) { data.bookChanges().first() }

        data.replace(BOOK_ID, CHAPTER_OLD, "replacement.md")

        val manifest = store.readManifest(BOOK_ID)
        assertEquals(
            listOf(ChapterEntry(CHAPTER_OLD, "replacement.md"), ChapterEntry(CHAPTER_GONE, "gone.md")),
            manifest.chapters,
        )
        assertTrue("old.md" in manifest.ignoredFiles)
        assertFalse("replacement.md" in manifest.ignoredFiles)
        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
        assertArrayEquals(REPLACEMENT, store.readSource(BOOK_ID, "replacement.md"))
        assertEquals(review, store.readReview(BOOK_ID, oldReviewPath))
        assertEquals(review.copy(sourcePath = "replacement.md"), store.readReview(BOOK_ID, newReviewPath))
        val migratedDeletion = requireNotNull(database.syncDao().getPendingDeletion("replacement-token"))
        assertEquals(newReviewPath, migratedDeletion.reviewPath)
        assertEquals(
            "replacement.md",
            ReviewJson.decode(migratedDeletion.recordPayload, CHAPTER_OLD, "replacement.md").sourcePath,
        )
        val reviewBytes = paths.review(BOOK_ID, newReviewPath).readBytes()
        val manifestBytes = paths.manifest(BOOK_ID).readBytes()
        assertTrue(reviewBytes.decodeToString().contains("\"schema_version\": 1"))
        assertTrue(manifestBytes.decodeToString().contains("\"schema_version\": 2"))
        assertEquals(CHAPTER_OLD, database.bookDao().getReadingPosition(BOOK_ID)?.chapterId)
        assertEquals(1, database.bookDao().getReadingPosition(BOOK_ID)?.blockIndex)
        assertEquals(REPLACEMENT.size, database.bookDao().getReadingPosition(BOOK_ID)?.byteOffset)
        assertEquals(null, database.syncDao().getOutbox(BOOK_ID, newReviewPath)?.baseSha256)
        assertEquals(OutboxState.PENDING, database.syncDao().getOutbox(BOOK_ID, newReviewPath)?.state)
        assertEquals(reviewBytes.sha256(), database.syncDao().getOutbox(BOOK_ID, newReviewPath)?.localSha256)
        assertEquals(manifestBase.sha256, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME)?.baseSha256)
        assertEquals(OutboxState.PENDING, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME)?.state)
        assertEquals(manifestBytes.sha256(), database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME)?.localSha256)
        assertEquals(BOOK_ID, changed.await())
        assertEquals(CHAPTER_OLD, SourceSearch(database.searchDao()).query(BOOK_ID, "replacement body").first().single().chapterId)
        assertEquals(SyncTrigger.LOCAL_CHANGE, queue.requests.last().trigger)
        assertEquals(0, gateway.remoteMutationCount)
    }

    @Test
    fun malformedReplacementSourceCannotMutateInstalledBook() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        gateway.files["$ROOT/replacement.md"] = byteArrayOf(0xc3.toByte(), 0x28)

        assertThrows(Exception::class.java) {
            runBlocking { data.replace(BOOK_ID, CHAPTER_OLD, "replacement.md") }
        }

        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
        assertFalse(paths.source(BOOK_ID, "replacement.md").exists())
        assertEquals(null, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertEquals(0, gateway.remoteMutationCount)
    }

    @Test
    fun replacementRequiresAStoredManifestBaseBeforeMutating() = runBlocking {
        assertReplacementBaseFailure { bases.delete(BOOK_ID, BookPaths.MANIFEST_NAME) }
    }

    @Test
    fun replacementRequiresManifestBaseMetadataBeforeMutating() = runBlocking {
        assertReplacementBaseFailure { database.syncDao().deleteMergeBase(BOOK_ID, BookPaths.MANIFEST_NAME) }
    }

    @Test
    fun replacementFailureAtEveryPrecommitBoundaryRestoresLiveCacheAndRoomMetadata() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        val oldReviewPath = "old.md${BookPaths.REVIEW_SUFFIX}"
        val newReviewPath = "replacement.md${BookPaths.REVIEW_SUFFIX}"
        val review = ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Keep this")
        store.writeReview(BOOK_ID, oldReviewPath, review)
        val originalPosition = ReadingPositionEntity(BOOK_ID, CHAPTER_OLD, 1, 7, 123)
        database.bookDao().upsertReadingPosition(originalPosition)
        gateway.files["$ROOT/replacement.md"] = REPLACEMENT

        ReplacementCheckpoint.entries.forEach { failurePoint ->
            val failing = createData(replacementCheckpoint = { point ->
                if (point == failurePoint) error("replacement $failurePoint")
            })

            assertThrows(IllegalStateException::class.java) {
                runBlocking { failing.replace(BOOK_ID, CHAPTER_OLD, "replacement.md") }
            }

            assertEquals(MANIFEST, store.readManifest(BOOK_ID))
            assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
            assertFalse(paths.source(BOOK_ID, "replacement.md").exists())
            assertEquals(review, store.readReview(BOOK_ID, oldReviewPath))
            assertFalse(paths.review(BOOK_ID, newReviewPath).exists())
            assertEquals(originalPosition, database.bookDao().getReadingPosition(BOOK_ID))
            assertEquals(null, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME))
            assertEquals(null, database.syncDao().getOutbox(BOOK_ID, newReviewPath))
            assertEquals(CHAPTER_OLD, SourceSearch(database.searchDao()).query(BOOK_ID, "Same source").first().single().chapterId)
            assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".repair-") })
            assertTrue(queue.requests.isEmpty())
        }
    }

    @Test
    fun replacementCrashAfterFilesystemSwapIsRolledBackByStartupRecovery() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        gateway.files["$ROOT/replacement.md"] = REPLACEMENT
        val crashing = createData(replacementCheckpoint = { point ->
            if (point == ReplacementCheckpoint.FILESYSTEM_SWAPPED) throw SimulatedProcessDeath()
        })

        assertThrows(SimulatedProcessDeath::class.java) {
            runBlocking { crashing.replace(BOOK_ID, CHAPTER_OLD, "replacement.md") }
        }

        createData().books()
        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
        assertArrayEquals(OLD, store.readSource(BOOK_ID, "old.md"))
        assertFalse(paths.source(BOOK_ID, "replacement.md").exists())
        assertEquals(null, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".repair-") })
    }

    @Test
    fun replacementQuarantinesAStaleDestinationReviewWithoutUploadingIt() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        val destinationReviewPath = "replacement.md${BookPaths.REVIEW_SUFFIX}"
        val staleBytes = ReviewJson.encode(
            ReviewDocument(chapterId = CHAPTER_GONE, sourcePath = "replacement.md", chapterNote = "Stale"),
        ).encodeToByteArray()
        paths.review(BOOK_ID, destinationReviewPath).writeBytes(staleBytes)
        gateway.files["$ROOT/replacement.md"] = REPLACEMENT

        data.replace(BOOK_ID, CHAPTER_OLD, "replacement.md")

        assertEquals(null, store.readReview(BOOK_ID, destinationReviewPath))
        assertEquals(null, database.syncDao().getOutbox(BOOK_ID, destinationReviewPath))
        val quarantined = File(paths.bookDirectory(BOOK_ID), ".review-quarantine")
            .walkTopDown().filter(File::isFile).toList().single()
        assertArrayEquals(staleBytes, quarantined.readBytes())
        assertEquals(0, gateway.remoteMutationCount)
    }

    @Test
    fun schedulerFailureAfterReplacementCommitRemainsSuccessfulAndPending() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        gateway.files["$ROOT/replacement.md"] = REPLACEMENT
        queue.failure = IllegalStateException("scheduler unavailable")

        data.replace(BOOK_ID, CHAPTER_OLD, "replacement.md")

        assertEquals("replacement.md", store.readManifest(BOOK_ID).chapters.first().path)
        assertEquals(OutboxState.PENDING, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME)?.state)
        assertEquals(CHAPTER_OLD, SourceSearch(database.searchDao()).query(BOOK_ID, "replacement body").first().single().chapterId)
    }

    @Test
    fun readerReviewMutationStartedDuringReplacementTargetsTheCommittedChapterPath() = runBlocking {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        store.writeReview(
            BOOK_ID,
            "old.md${BookPaths.REVIEW_SUFFIX}",
            ReviewDocument(chapterId = CHAPTER_OLD, sourcePath = "old.md", chapterNote = "Before"),
        )
        gateway.files["$ROOT/replacement.md"] = REPLACEMENT
        val replacementEntered = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val replacingData = createData(replacementCheckpoint = { point ->
            if (point == ReplacementCheckpoint.SOURCE_STAGED) {
                replacementEntered.countDown()
                check(releaseReplacement.await(5, TimeUnit.SECONDS))
            }
        })
        val reader = ReaderRepository(
            store,
            RoomReaderBookStore(database.bookDao()),
            RoomSyncMetadataStore(database.syncDao()),
            ReaderSyncScheduler { _, _, _ -> },
            { flowOf(SyncStatus.Saved) },
            reviewMutations,
            RoomPendingDeletionStore(database.syncDao()),
            ContentChangeNotifier(),
        )

        val replacing = async(Dispatchers.IO) { replacingData.replace(BOOK_ID, CHAPTER_OLD, "replacement.md") }
        assertTrue(replacementEntered.await(5, TimeUnit.SECONDS))
        val mutating = async(Dispatchers.IO) { reader.saveChapterNote(BOOK_ID, CHAPTER_OLD, "During") }
        assertEquals(null, withTimeoutOrNull(100) { mutating.await() })

        releaseReplacement.countDown()
        replacing.await()
        mutating.await()

        assertEquals(
            "During",
            store.readReview(BOOK_ID, "replacement.md${BookPaths.REVIEW_SUFFIX}")?.chapterNote,
        )
        assertEquals(
            "During",
            database.syncDao().getOutbox(BOOK_ID, "replacement.md${BookPaths.REVIEW_SUFFIX}")
                ?.let { store.readReview(BOOK_ID, it.path) }
                ?.chapterNote,
        )
    }

    @Test
    fun postCommitCleanupFailuresStillPublishAndScheduleReplacement() = runBlocking {
        listOf(RepairCleanupCheckpoint.MARKER_DELETED, RepairCleanupCheckpoint.BEFORE_DIRECTORY_SYNC).forEach { failurePoint ->
            gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
            data.installExisting(ROOT)
            gateway.files["$ROOT/replacement.md"] = REPLACEMENT
            val changed = async(Dispatchers.Unconfined) { data.bookChanges().first() }
            val failing = createData(repairCleanupCheckpoint = { point ->
                if (point == failurePoint) throw java.io.IOException("cleanup $failurePoint")
            })

            failing.replace(BOOK_ID, CHAPTER_OLD, "replacement.md")

            assertEquals("replacement.md", store.readManifest(BOOK_ID).chapters.first().path)
            assertEquals(BOOK_ID, changed.await())
            assertEquals(SyncTrigger.LOCAL_CHANGE, queue.requests.last().trigger)
            assertEquals(OutboxState.PENDING, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME)?.state)
            createData().books()
            assertTrue(cacheRoot.listFiles().orEmpty().none { it.name.startsWith(".repair-") })
            data.forget(BOOK_ID)
            queue.requests.clear()
        }
    }

    private suspend fun assertReplacementBaseFailure(removeBase: suspend () -> Unit) {
        gateway.publish(MANIFEST, mapOf("old.md" to OLD, "gone.md" to GONE))
        data.installExisting(ROOT)
        removeBase()
        gateway.files["$ROOT/replacement.md"] = REPLACEMENT

        assertThrows(IllegalStateException::class.java) {
            runBlocking { data.replace(BOOK_ID, CHAPTER_OLD, "replacement.md") }
        }

        assertEquals(MANIFEST, store.readManifest(BOOK_ID))
        assertFalse(paths.source(BOOK_ID, "replacement.md").exists())
        assertEquals(null, database.syncDao().getOutbox(BOOK_ID, BookPaths.MANIFEST_NAME))
        assertEquals(0, gateway.remoteMutationCount)
    }

    private class RecordingQueue : SyncWorkQueue {
        val requests = mutableListOf<SyncWorkRequest>()
        var failure: Throwable? = null
        override fun enqueue(request: SyncWorkRequest) {
            failure?.let { throw it }
            requests += request
        }
        override fun cancel(uniqueName: String) = Unit
    }

    private class SimulatedProcessDeath : Error()

    private class RecordingGateway : YandexDiskGateway {
        val files = linkedMapOf<String, ByteArray>()
        val lastPublished = linkedMapOf<String, ByteArray>()
        var remoteMutationCount = 0
        val downloadCount get() = downloads.get()
        private val downloads = AtomicInteger()

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

        override suspend fun download(path: String): RemoteFile {
            downloads.incrementAndGet()
            return RemoteFile(path, requireNotNull(files[path]), "rev-${requireNotNull(files[path]).contentHashCode()}")
        }
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
        val REPLACEMENT = "# Replacement\n\nreplacement body".encodeToByteArray()
        val MANIFEST = BookManifest(
            bookId = BOOK_ID,
            title = "Existing story",
            chapters = listOf(
                ChapterEntry(CHAPTER_OLD, "old.md"),
                ChapterEntry(CHAPTER_GONE, "gone.md"),
            ),
        )
    }
}
