package net.inkyquill.pocketeditor

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.Configuration
import androidx.work.WorkManager
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import net.inkyquill.pocketeditor.reader.DefaultReaderSyncScheduler
import net.inkyquill.pocketeditor.reader.ReadingPositionCoordinator
import net.inkyquill.pocketeditor.reader.ReaderRepository
import net.inkyquill.pocketeditor.reader.RoomReaderBookStore
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.storage.LibraryStartupRecovery
import net.inkyquill.pocketeditor.storage.RecoveryScanner
import net.inkyquill.pocketeditor.storage.ImportDraftStore
import net.inkyquill.pocketeditor.sync.AtomicSyncBaseStore
import net.inkyquill.pocketeditor.sync.InMemoryConflictRepository
import net.inkyquill.pocketeditor.sync.RoomPendingDeletionStore
import net.inkyquill.pocketeditor.sync.RoomSyncMetadataStore
import net.inkyquill.pocketeditor.sync.SharedPreferencesRetryGenerationStore
import net.inkyquill.pocketeditor.sync.SyncEngine
import net.inkyquill.pocketeditor.sync.SyncScheduler
import net.inkyquill.pocketeditor.sync.SyncWorkQueue
import net.inkyquill.pocketeditor.sync.SyncWorkRequest
import net.inkyquill.pocketeditor.sync.SyncWorkerFactory
import net.inkyquill.pocketeditor.sync.WorkManagerSyncWorkQueue
import net.inkyquill.pocketeditor.ui.books.RoomYandexBookLibraryData
import net.inkyquill.pocketeditor.ui.books.LibraryTransaction
import net.inkyquill.pocketeditor.ui.review.ReviewDraftStore
import net.inkyquill.pocketeditor.ui.review.RoomReviewDraftPersistence
import net.inkyquill.pocketeditor.yandex.DefaultYandexAuth
import net.inkyquill.pocketeditor.yandex.OkHttpYandexDiskGateway
import net.inkyquill.pocketeditor.yandex.SyncLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class PocketEditorApp : Application(), Configuration.Provider {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer.create(this) }

    override fun onCreate() {
        super.onCreate()
        container
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(container.workerFactory).build()
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
    val workQueue: SyncWorkQueue = object : SyncWorkQueue {
        override fun enqueue(request: SyncWorkRequest) {
            WorkManagerSyncWorkQueue(WorkManager.getInstance(applicationContext)).enqueue(request)
        }

        override fun cancel(uniqueName: String) {
            WorkManagerSyncWorkQueue(WorkManager.getInstance(applicationContext)).cancel(uniqueName)
        }
    }
    val syncScheduler = SyncScheduler(workQueue, retryGenerations)
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
    )
    val workerFactory = SyncWorkerFactory(syncEngine::syncBook, workQueue, retryGenerations)
    val readerRepository = ReaderRepository(
        bookStore = bookStore,
        books = RoomReaderBookStore(database.bookDao()),
        metadata = metadata,
        scheduler = DefaultReaderSyncScheduler(syncScheduler),
        syncStatus = syncEngine::status,
        mutations = reviewMutations,
        deletions = pendingDeletions,
        contentChanges = contentChanges,
    )
    val readingPositions = ReadingPositionCoordinator(applicationScope, readerRepository::saveReadingPosition)
    val reviewDraftStore = ReviewDraftStore(RoomReviewDraftPersistence(database.draftDao()))
    val libraryData = RoomYandexBookLibraryData(
        gateway = gateway,
        store = bookStore,
        paths = bookPaths,
        books = database.bookDao(),
        sync = database.syncDao(),
        drafts = database.draftDao(),
        importDraftsDao = database.importDraftDao(),
        importDraftStore = importDraftStore,
        search = sourceSearch,
        scheduler = syncScheduler,
        preferences = applicationContext.getSharedPreferences("device_preferences", Context.MODE_PRIVATE),
        baseStore = syncBaseStore,
        conflicts = conflicts,
        transaction = LibraryTransaction { block -> database.withTransaction { block() } },
        startupRecovery = startupRecovery,
    )

    companion object {
        fun create(context: Context) = AppContainer(context)
    }
}
