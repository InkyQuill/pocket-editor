package net.inkyquill.pocketeditor

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.WorkManager
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.inkyquill.pocketeditor.load.LegacyImportDraftAdapter
import net.inkyquill.pocketeditor.load.ProgressiveBookInstaller
import net.inkyquill.pocketeditor.load.ProgressiveBookLoader
import net.inkyquill.pocketeditor.load.ProgressiveLoadRetryPolicy
import net.inkyquill.pocketeditor.load.ProgressiveLoadScheduler
import net.inkyquill.pocketeditor.load.ProgressiveLoadWorkerFactory
import net.inkyquill.pocketeditor.load.RoomProgressiveLoadScheduleStore
import net.inkyquill.pocketeditor.load.WorkManagerProgressiveLoadQueue
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import net.inkyquill.pocketeditor.reader.DefaultReaderSyncScheduler
import net.inkyquill.pocketeditor.reader.ReadingPositionCoordinator
import net.inkyquill.pocketeditor.reader.ReaderRepository
import net.inkyquill.pocketeditor.reader.RoomChapterAvailability
import net.inkyquill.pocketeditor.reader.RoomReaderBookStore
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.storage.LibraryStartupRecovery
import net.inkyquill.pocketeditor.storage.RecoveryScanner
import net.inkyquill.pocketeditor.storage.ImportDraftStore
import net.inkyquill.pocketeditor.storage.InstallRecoveryJournal
import net.inkyquill.pocketeditor.storage.InstallRecoveryCoordinator
import net.inkyquill.pocketeditor.sync.AtomicSyncBaseStore
import net.inkyquill.pocketeditor.sync.InMemoryConflictRepository
import net.inkyquill.pocketeditor.sync.RoomPendingDeletionStore
import net.inkyquill.pocketeditor.sync.RoomSyncMetadataStore
import net.inkyquill.pocketeditor.sync.SharedPreferencesRetryGenerationStore
import net.inkyquill.pocketeditor.sync.SyncEngine
import net.inkyquill.pocketeditor.sync.SyncScheduler
import net.inkyquill.pocketeditor.sync.BookSyncMonitor
import net.inkyquill.pocketeditor.sync.NetworkConnectivityObserver
import net.inkyquill.pocketeditor.sync.PocketEditorWorkerFactory
import net.inkyquill.pocketeditor.sync.RemoteRevisionProbe
import net.inkyquill.pocketeditor.sync.SyncWorkQueue
import net.inkyquill.pocketeditor.sync.SyncWorkRequest
import net.inkyquill.pocketeditor.sync.SyncWorkerFactory
import net.inkyquill.pocketeditor.sync.AndroidNetworkAvailability
import net.inkyquill.pocketeditor.sync.WorkManagerSyncWorkQueue
import net.inkyquill.pocketeditor.sync.SyncEligibility
import net.inkyquill.pocketeditor.ui.books.RoomYandexBookLibraryData
import net.inkyquill.pocketeditor.ui.books.LibraryTransaction
import net.inkyquill.pocketeditor.ui.review.ReviewDraftStore
import net.inkyquill.pocketeditor.ui.review.RoomReviewDraftPersistence
import net.inkyquill.pocketeditor.yandex.DefaultYandexAuth
import net.inkyquill.pocketeditor.yandex.OkHttpYandexDiskGateway
import net.inkyquill.pocketeditor.yandex.SyncLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class PocketEditorApp : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer.create(this) }

    override fun onCreate() {
        super.onCreate()
        val value = container
        WorkManager.initialize(
            this,
            androidx.work.Configuration.Builder().setWorkerFactory(value.workerFactory).build(),
        )
        value.attachWorkManager(WorkManager.getInstance(this))
        value.start()
    }
}

internal suspend fun recoverAppState(
    installRecovery: InstallRecoveryCoordinator,
    recoverLibrary: suspend () -> Unit,
    promoteLegacy: suspend () -> Unit,
    reconcileProgressiveRequests: suspend () -> Unit = {},
) {
    installRecovery.recoverOnce()
    recoverLibrary()
    promoteLegacy()
    reconcileProgressiveRequests()
}

internal class LateBoundSyncWorkQueue : SyncWorkQueue {
    private var delegate: SyncWorkQueue? = null
    fun bind(value: SyncWorkQueue) {
        check(delegate == null) { "Sync WorkManager queue is already bound" }
        delegate = value
    }
    override fun enqueue(request: SyncWorkRequest) = requireNotNull(delegate) { "WorkManager is not initialized" }.enqueue(request)
    override fun cancel(uniqueName: String) = requireNotNull(delegate) { "WorkManager is not initialized" }.cancel(uniqueName)
}

internal class LateBoundProgressiveLoadQueue : net.inkyquill.pocketeditor.load.ProgressiveLoadWorkQueue {
    private var delegate: net.inkyquill.pocketeditor.load.ProgressiveLoadWorkQueue? = null
    fun bind(value: net.inkyquill.pocketeditor.load.ProgressiveLoadWorkQueue) {
        check(delegate == null) { "Progressive WorkManager queue is already bound" }
        delegate = value
    }
    override suspend fun enqueue(request: net.inkyquill.pocketeditor.load.ProgressiveLoadWorkRequest) =
        requireNotNull(delegate) { "WorkManager is not initialized" }.enqueue(request)
    override fun cancel(uniqueName: String) = requireNotNull(delegate) { "WorkManager is not initialized" }.cancel(uniqueName)
}

class AppContainer private constructor(context: Context) {
    val applicationContext: Context = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: PocketEditorDatabase = Room.databaseBuilder(
        applicationContext,
        PocketEditorDatabase::class.java,
        "pocket-editor.db",
    ).addMigrations(
        PocketEditorDatabase.MIGRATION_1_2,
        PocketEditorDatabase.MIGRATION_2_3,
        PocketEditorDatabase.MIGRATION_3_4,
        PocketEditorDatabase.MIGRATION_4_5,
        PocketEditorDatabase.MIGRATION_5_6,
        PocketEditorDatabase.MIGRATION_6_7,
    ).build()
    val bookPaths = BookPaths(File(applicationContext.noBackupFilesDir, "books"))
    val bookStore = AtomicBookStore(bookPaths)
    val importDraftStore = ImportDraftStore(File(applicationContext.noBackupFilesDir, "import-drafts"))
    val auth = DefaultYandexAuth.create(applicationContext)
    private val httpClient = OkHttpClient.Builder().build()
    val gateway = OkHttpYandexDiskGateway(
        httpClient,
        "https://cloud-api.yandex.net/v1/disk/".toHttpUrl(),
        accessToken = auth::accessToken,
    )
    val reviewMutations = ReviewMutationCoordinator()
    val contentChanges = ContentChangeNotifier()
    val pendingDeletions = RoomPendingDeletionStore(database.syncDao())
    val metadata = RoomSyncMetadataStore(database.syncDao())
    val conflicts = InMemoryConflictRepository()
    val retryGenerations = SharedPreferencesRetryGenerationStore(applicationContext)
    private val lateSyncQueue = LateBoundSyncWorkQueue()
    val workQueue: SyncWorkQueue = lateSyncQueue
    val syncScheduler = SyncScheduler(workQueue, retryGenerations)
    val syncEligibility = SyncEligibility { bookId ->
        progressiveLoads.getJob(bookId)?.phase.let { phase -> phase == null || phase == net.inkyquill.pocketeditor.load.ProgressiveLoadPhase.COMPLETE }
    }
    val syncMonitor = BookSyncMonitor(
        applicationScope,
        RemoteRevisionProbe(gateway, bookStore, metadata, syncEligibility),
        syncScheduler::enqueue,
    )
    val connectivityObserver = NetworkConnectivityObserver(applicationContext)
    private val holderId: String = applicationContext.getSharedPreferences("device_identity", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("holder_id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("holder_id", it).commit())
        }
    }
    val sourceSearch = SourceSearch(database.searchDao())
    val startupRecovery = LibraryStartupRecovery(
        RecoveryScanner(bookPaths, database.bookDao(), database.syncDao()),
        database.bookDao(),
        bookStore,
        sourceSearch,
    )
    val syncBaseStore = AtomicSyncBaseStore(File(applicationContext.noBackupFilesDir, "sync-bases"))
    val progressiveLoads = database.progressiveLoadDao()
    val progressiveLoadRequests = database.progressiveLoadRequestDao()
    private val lateProgressiveQueue = LateBoundProgressiveLoadQueue()
    val progressiveLoadQueue: net.inkyquill.pocketeditor.load.ProgressiveLoadWorkQueue = lateProgressiveQueue
    val progressiveLoadScheduleStore = RoomProgressiveLoadScheduleStore(database, progressiveLoads, progressiveLoadRequests)
    val progressiveLoadScheduler = ProgressiveLoadScheduler(progressiveLoadQueue, progressiveLoadScheduleStore)
    val progressiveInstaller = ProgressiveBookInstaller(
        bookPaths,
        bookStore,
        database.bookDao(),
        database.syncDao(),
        progressiveLoads,
        sourceSearch,
        syncBaseStore,
        LibraryTransaction { block -> database.withTransaction { block() } },
    )
    val progressiveLoader = ProgressiveBookLoader.create(
        gateway,
        progressiveLoads,
        progressiveInstaller,
        bookStore,
        database.syncDao(),
        sourceSearch,
        reviewMutations,
        contentChanges,
        LibraryTransaction { block -> database.withTransaction { block() } },
        progressiveLoadScheduler,
        ProgressiveLoadRetryPolicy(),
        LegacyImportDraftAdapter(database.importDraftDao(), importDraftStore),
        database.importDraftDao(),
        importDraftStore,
        books = database.bookDao(),
        requests = net.inkyquill.pocketeditor.load.RoomDiscoveryRequestStore(progressiveLoadRequests),
    )
    val syncEngine = SyncEngine(
        gateway = gateway,
        bookStore = bookStore,
        sourceCache = bookStore,
        metadata = metadata,
        baseStore = syncBaseStore,
        conflicts = conflicts,
        reviewMutations = reviewMutations,
        pendingDeletions = pendingDeletions,
        contentChanges = contentChanges,
        holderId = holderId,
        lockFactory = {
            SyncLock(SyncLock.SCHEMA_VERSION, UUID.randomUUID().toString(), holderId, Instant.now())
        },
        sourceIndexUpdater = { bookId, chapters ->
            sourceSearch.rebuildBook(
                bookId,
                chapters.map { chapter ->
                    net.inkyquill.pocketeditor.search.SearchChapterSource(chapter.chapterId, chapter.title, chapter.bytes)
                },
            )
        },
        eligibility = syncEligibility,
    )
    val workerFactory = PocketEditorWorkerFactory(
        SyncWorkerFactory(
            syncEngine::syncBook,
            workQueue,
            retryGenerations,
            AndroidNetworkAvailability(applicationContext),
        ),
        ProgressiveLoadWorkerFactory(
            progressiveLoader,
            progressiveLoadScheduler,
            progressiveLoadScheduleStore,
            AndroidNetworkAvailability(applicationContext),
        ),
    )
    val readerRepository = ReaderRepository(
        bookStore = bookStore,
        books = RoomReaderBookStore(database.bookDao()),
        metadata = metadata,
        scheduler = DefaultReaderSyncScheduler(syncScheduler),
        syncStatus = syncEngine::status,
        mutations = reviewMutations,
        deletions = pendingDeletions,
        contentChanges = contentChanges,
        chapterAvailability = RoomChapterAvailability(database.progressiveLoadDao()),
    )

    private val installRecovery = InstallRecoveryCoordinator(InstallRecoveryJournal(bookPaths, database.bookDao()))

    fun attachWorkManager(workManager: WorkManager) {
        lateSyncQueue.bind(WorkManagerSyncWorkQueue(workManager))
        lateProgressiveQueue.bind(WorkManagerProgressiveLoadQueue(workManager))
    }

    fun start() {
        applicationScope.launch {
            recoverAppState(
                installRecovery = installRecovery,
                recoverLibrary = startupRecovery::recover,
                promoteLegacy = progressiveLoader::migrateLegacyDrafts,
                reconcileProgressiveRequests = progressiveLoader::reconcileDiscoveryRequests,
            )
        }
    }
    val readingPositions = ReadingPositionCoordinator(applicationScope, readerRepository::saveReadingPosition)
    val reviewDraftStore = ReviewDraftStore(RoomReviewDraftPersistence(database.draftDao()))
    val libraryData = RoomYandexBookLibraryData(
        gateway = gateway,
        store = bookStore,
        paths = bookPaths,
        books = database.bookDao(),
        sync = database.syncDao(),
        drafts = database.draftDao(),
        progressiveLoads = database.progressiveLoadDao(),
        search = sourceSearch,
        scheduler = syncScheduler,
        preferences = applicationContext.getSharedPreferences("device_preferences", Context.MODE_PRIVATE),
        baseStore = syncBaseStore,
        conflicts = conflicts,
        transaction = LibraryTransaction { block -> database.withTransaction { block() } },
        reviewMutations = reviewMutations,
        startupRecovery = startupRecovery,
        contentChanges = contentChanges,
        progressiveLoader = progressiveLoader,
        progressiveLoadScheduler = progressiveLoadScheduler,
        progressiveRequests = progressiveLoadRequests,
        installRecovery = installRecovery,
    )

    companion object {
        fun create(context: Context) = AppContainer(context)
    }
}
