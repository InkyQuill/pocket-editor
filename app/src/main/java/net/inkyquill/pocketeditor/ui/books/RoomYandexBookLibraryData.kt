package net.inkyquill.pocketeditor.ui.books

import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import net.inkyquill.pocketeditor.book.BookDiscovery
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.book.ChapterTitleExtractor
import net.inkyquill.pocketeditor.book.DiscoveryFile
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.PendingDeletionEntity
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.SyncDao
import net.inkyquill.pocketeditor.database.DraftDao
import net.inkyquill.pocketeditor.database.ProgressiveLoadDao
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadRequestDao
import net.inkyquill.pocketeditor.load.ProgressiveBookLoader
import net.inkyquill.pocketeditor.load.ProgressiveLoadScheduler
import net.inkyquill.pocketeditor.load.ProgressiveLoadSnapshot
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.load.toSnapshot
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.reader.ReadingPositionClamp
import net.inkyquill.pocketeditor.search.SearchChapterSource
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.storage.InstallJournalEntry
import net.inkyquill.pocketeditor.storage.InstallRecoveryJournal
import net.inkyquill.pocketeditor.storage.InstallRecoveryCoordinator
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.storage.StrictUtf8
import net.inkyquill.pocketeditor.storage.PlatformDirectoryFsync
import net.inkyquill.pocketeditor.storage.LibraryStartupRecovery
import net.inkyquill.pocketeditor.storage.DirectoryFsync
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.sync.SyncBaseStore
import net.inkyquill.pocketeditor.sync.ConflictRepository
import net.inkyquill.pocketeditor.sync.SyncConflict
import net.inkyquill.pocketeditor.merge.MergeResult
import net.inkyquill.pocketeditor.merge.ReviewMerge
import net.inkyquill.pocketeditor.sync.SyncScheduler
import net.inkyquill.pocketeditor.sync.SyncTrigger
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway
import org.json.JSONObject

fun interface LibraryTransaction {
    suspend fun run(block: suspend () -> Unit)
}

enum class LibraryInstallCheckpoint { FILESYSTEM_SWAP, METADATA, SEARCH, OUTBOX, ROOT }
enum class RepairCleanupCheckpoint { MARKER_DELETED, BEFORE_DIRECTORY_SYNC }
enum class ReplacementCheckpoint {
    SOURCE_STAGED,
    MANIFEST_STAGED,
    REVIEW_STAGED,
    FILESYSTEM_SWAPPED,
    OUTBOX_STAGED,
    POSITION_STAGED,
    SEARCH_STAGED,
}
enum class ReorderCheckpoint { STAGED, FILESYSTEM_SWAPPED, METADATA_COMMITTED, DATABASE_COMMITTED }
enum class ReorderBaseRefreshCheckpoint { BEFORE_CONFLICT_REPLACE, BEFORE_METADATA_COMMIT }

class RoomYandexBookLibraryData(
    private val gateway: YandexDiskGateway,
    private val store: AtomicBookStore,
    private val paths: BookPaths,
    private val books: BookDao,
    private val sync: SyncDao,
    private val drafts: DraftDao,
    private val progressiveLoads: ProgressiveLoadDao,
    private val search: SourceSearch,
    private val scheduler: SyncScheduler,
    private val preferences: SharedPreferences,
    private val baseStore: SyncBaseStore,
    private val conflicts: ConflictRepository,
    private val transaction: LibraryTransaction,
    private val reviewMutations: ReviewMutationCoordinator,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val installCheckpoint: (LibraryInstallCheckpoint) -> Unit = {},
    private val installDirectorySync: (File) -> DirectorySyncStatus = PlatformDirectoryFsync::sync,
    private val startupRecovery: LibraryStartupRecovery? = null,
    private val repairCleanupCheckpoint: (RepairCleanupCheckpoint) -> Unit = {},
    private val replacementCheckpoint: (ReplacementCheckpoint) -> Unit = {},
    private val reorderCheckpoint: (ReorderCheckpoint) -> Unit = {},
    private val reorderBaseRefreshCheckpoint: (ReorderBaseRefreshCheckpoint) -> Unit = {},
    private val contentChanges: ContentChangeNotifier = ContentChangeNotifier(),
    private val progressiveLoader: ProgressiveBookLoader? = null,
    private val progressiveLoadScheduler: ProgressiveLoadScheduler? = null,
    private val progressiveRequests: ProgressiveLoadRequestDao? = null,
    private val installRecovery: InstallRecoveryCoordinator = InstallRecoveryCoordinator(
        InstallRecoveryJournal(paths, books, DirectoryFsync(installDirectorySync)),
    ),
) : BookLibraryData {
    private val discovery = BookDiscovery()
    private val installJournal = InstallRecoveryJournal(paths, books, DirectoryFsync(installDirectorySync))
    private val installMutex = Mutex()

    override suspend fun books(): List<BookSummary> = installMutex.withLock {
        recoverRepairs()
        installRecovery.recoverOnce()
        startupRecovery?.recover()
        val roots = books.getRoots()
        roots.forEach { root ->
            val remoteRoot = root.remoteRootPath
            if (progressiveLoads.getJob(root.bookId) == null) {
                // A database upgraded from the pre-progressive format can already own a
                // complete local cache. Adopt it through the loader's registered-root path,
                // which reads local manifest/source bytes only and creates durable rows.
                if (remoteRoot != null) {
                    progressiveLoader?.start(remoteRoot)
                } else {
                    progressiveLoader?.adoptRegistered(root.bookId)
                }
            }
        }
        roots.map { root ->
            runCatching { root.summaryFromCache() }.getOrElse {
                BookSummary(
                    root.bookId,
                    root.remoteRootPath?.substringAfterLast('/')?.ifBlank { "Восстановить книгу" } ?: "Восстановить книгу",
                    root.remoteRootPath.orEmpty(),
                    emptyList(),
                    availableOffline = false,
                    recoveryError = "Локальный кеш повреждён и требует восстановления.",
                    needsRelink = root.remoteRootPath == null,
                )
            }
        }
    }

    override fun bookChanges(): Flow<String> = flow {
        var previous = contentChanges.bookVersions.value
        contentChanges.bookVersions.collect { current ->
            current.forEach { (bookId, version) ->
                if (previous[bookId] != version) emit(bookId)
            }
            previous = current
        }
    }

    override fun loadChanges(): Flow<List<ProgressiveLoadSnapshot>> = progressiveRequests?.let { requests ->
        combine(progressiveLoads.observeAll(), requests.observeAll()) { loads, discovery ->
            loads.map { it.toSnapshot() } + discovery.map { it.toSnapshot() }
        }
    } ?: progressiveLoads.observeAll().map { values -> values.map { it.toSnapshot() } }

    override suspend fun currentLoads(): List<ProgressiveLoadSnapshot> =
        progressiveLoads.observeAll().first().map { it.toSnapshot() } +
            progressiveRequests?.getAll().orEmpty().map { it.toSnapshot() }

    override suspend fun startLoad(path: String): ProgressiveLoadSnapshot =
        requireNotNull(progressiveLoader) { "Progressive loader is not configured" }.request(path)

    override suspend fun prioritizeChapter(bookId: String, path: String) {
        if (progressiveLoads.prioritize(bookId, path) > 0) {
            requireNotNull(progressiveLoadScheduler) { "Progressive scheduler is not configured" }.replaceNow(bookId)
        }
    }

    override suspend fun pauseLoad(bookId: String) =
        requireNotNull(progressiveLoadScheduler) { "Progressive scheduler is not configured" }.pause(bookId)

    override suspend fun continueLoad(bookId: String) {
        requireNotNull(progressiveLoadScheduler) { "Progressive scheduler is not configured" }.continueLoad(bookId)
    }

    override suspend fun cancelLoad(bookId: String) =
        requireNotNull(progressiveLoadScheduler) { "Progressive scheduler is not configured" }.cancel(bookId)

    override suspend fun resumeLocation(): ResumeLocation? {
        val bookId = preferences.getString(KEY_LAST_BOOK, null) ?: return null
        return resumeLocation(bookId)
    }

    override suspend fun resumeLocation(bookId: String): ResumeLocation? {
        val position = books.getReadingPosition(bookId) ?: return null
        return ResumeLocation(bookId, position.chapterId, position.blockIndex, position.byteOffset)
    }

    override suspend fun appearance() = AppearancePreference(
        dark = preferences.getBoolean(KEY_DARK, true),
        textScale = preferences.getFloat(KEY_TEXT_SCALE, 1f),
    )

    override suspend fun browse(path: String): FolderListing {
        val entries = gateway.listFolder(path)
        return FolderListing(
            path = path,
            folders = entries.filter { it.type == "dir" }.sortedBy { it.name.lowercase() }
                .map { RemoteFolder(it.path, it.name) },
            markdown = entries.filter { it.type == "file" && it.name.isOrdinaryMarkdown() }.map { it.name }.sorted(),
            otherFiles = entries.count { it.type != "dir" && !(it.type == "file" && it.name.isOrdinaryMarkdown()) },
        )
    }

    /** Repairs only a registered local cache from a fully validated, read-only remote snapshot. */
    override suspend fun repairRegistered(bookId: String): BookSummary = installMutex.withLock {
        recoverRepairs()
        installJournal.recover()
        val root = books.getRoot(bookId) ?: throw BookLibraryUserError("Книга не зарегистрирована")
        val remoteRoot = root.remoteRootPath
            ?: throw BookLibraryUserError("У зарегистрированной книги нет удалённой папки")
        val entries = gateway.listFolder(remoteRoot).filter { it.type == "file" }.associateBy { it.name }
        val manifestEntry = entries[BookPaths.MANIFEST_NAME]
            ?: throw BookLibraryUserError("Удалённый манифест недоступен")
        val remoteManifestFile = gateway.download(manifestEntry.path)
        val remoteManifest = BookManifest.decode(StrictUtf8.decode(remoteManifestFile.bytes, "Book manifest"))
        if (remoteManifest.bookId != bookId) {
            throw BookLibraryUserError("Выбранная папка относится к другой книге")
        }
        if (remoteManifest.chapters.isEmpty()) {
            throw BookLibraryUserError("В удалённом манифесте нет глав")
        }
        val pending = sync.observeOutbox().first().filter { it.bookId == bookId }.associateBy { it.path }
        val manifestOutbox = pending[BookPaths.MANIFEST_NAME]
        val localManifest = if (manifestOutbox != null) runCatching { store.readManifest(bookId) }.getOrNull() else null
        val activeManifest = localManifest ?: remoteManifest
        val manifestConflict = if (manifestOutbox == null) {
            null
        } else if (localManifest == null || BookManifest.encode(localManifest).encodeToByteArray().sha256() != manifestOutbox.localSha256) {
            SyncConflict.MissingBase(BookPaths.MANIFEST_NAME, "Pending manifest is missing or changed outside outbox")
        } else {
            val base = baseStore.read(bookId, BookPaths.MANIFEST_NAME)
            when {
                base == null || base.sha256 != manifestOutbox.baseSha256 ->
                    SyncConflict.MissingBase(BookPaths.MANIFEST_NAME, "Exact manifest merge base is unavailable")
                remoteManifestFile.bytes.sha256() == base.sha256 -> null
                else -> SyncConflict.Manifest(
                    BookPaths.MANIFEST_NAME,
                    localManifest,
                    remoteManifest,
                    remoteManifestFile.bytes,
                    remoteManifestFile.revision,
                )
            }
        }

        val remoteSources = activeManifest.chapters.associate { chapter ->
            if (!chapter.path.isOrdinaryMarkdown()) {
                throw BookLibraryUserError("Глава в манифесте не является обычным файлом Markdown: ${chapter.path}")
            }
            val entry = entries[chapter.path]
                ?: throw BookLibraryUserError("На диске отсутствует глава: ${chapter.path}")
            val remote = gateway.download(entry.path)
            validateUtf8(remote.bytes, chapter.path)
            chapter.path to remote
        }
        val remoteReviews = activeManifest.chapters.mapNotNull { chapter ->
            val relative = chapter.path + BookPaths.REVIEW_SUFFIX
            val entry = entries[relative] ?: return@mapNotNull null
            val remote = gateway.download(entry.path)
            val decoded = ReviewJson.decode(validateUtf8(remote.bytes, relative), chapter.id, chapter.path)
            relative to (remote to decoded)
        }.toMap()

        val staged = stageRepair(bookId)
        val stageStore = AtomicBookStore(BookPaths(requireNotNull(staged.parentFile)))
        val metadataUpdates = mutableListOf<RepairMetadata>()
        val deferredConflicts = mutableListOf<SyncConflict>().apply { manifestConflict?.let(::add) }
        try {
            if (localManifest == null) {
                stageStore.replaceDownloadedManifest(bookId, remoteManifestFile.bytes)
            } else {
                stageStore.writeManifest(bookId, localManifest)
            }
            remoteSources.forEach { (path, remote) -> stageStore.replaceDownloadedSource(bookId, path, remote.bytes) }
            remoteReviews.forEach { (path, pair) ->
                val (remote, remoteDocument) = pair
                val outbox = pending[path]
                if (outbox == null) {
                    // Repair never overwrites a local sidecar merely because Room has no pending row.
                    // Normal sync owns clean-sidecar adoption and its durable base transition.
                    Unit
                } else {
                    val local = stageStore.readReview(bookId, path)
                    val localBytes = local?.let { ReviewJson.encode(it).encodeToByteArray() }
                    val base = baseStore.read(bookId, path)
                    when {
                        local == null || localBytes!!.sha256() != outbox.localSha256 ->
                            deferredConflicts += SyncConflict.MissingBase(path, "Pending review is missing or changed outside outbox")
                        base == null || base.sha256 != outbox.baseSha256 ->
                            deferredConflicts += SyncConflict.MissingBase(path, "Exact review merge base is unavailable")
                        remote.bytes.sha256() == base.sha256 -> Unit
                        else -> {
                            val baseDocument = ReviewJson.decode(
                                StrictUtf8.decode(base.bytes, "Review repair base"),
                                remoteDocument.chapterId,
                                remoteDocument.sourcePath,
                            )
                            when (val merge = ReviewMerge.merge(baseDocument, local, remoteDocument)) {
                                is MergeResult.Conflicted -> deferredConflicts += SyncConflict.Review(
                                    path,
                                    merge.partial,
                                    merge.conflicts,
                                    remote.bytes,
                                    remote.revision,
                                )
                                is MergeResult.Merged -> {
                                    val revision = stageStore.writeReview(bookId, path, merge.document)
                                    metadataUpdates += RepairMetadata(path, remote, outbox.copy(localSha256 = revision.sha256))
                                }
                            }
                        }
                    }
                }
            }
            stageStore.readManifest(bookId)
            activeManifest.chapters.forEach { stageStore.readSource(bookId, it.path) }
            remoteReviews.keys.forEach { stageStore.readReview(bookId, it) }

            val canonicalMetadata = buildList {
                add(
                    RepairMetadata(
                        BookPaths.MANIFEST_NAME,
                        remoteManifestFile,
                        confirmRemote = false,
                    ),
                )
                remoteSources.forEach { (path, remote) -> add(RepairMetadata(path, remote)) }
            }
            repairSwap(bookId, staged, canonicalMetadata + metadataUpdates) {
                installCheckpoint(LibraryInstallCheckpoint.SEARCH)
                search.rebuildBook(
                    bookId,
                    activeManifest.chapters.map { chapter ->
                        remoteSources.getValue(chapter.path).bytes.let { source ->
                            SearchChapterSource(
                                chapter.id,
                                ChapterTitleExtractor.extract(chapter.path, source).title,
                                source,
                            )
                        }
                    },
                )
            }
            deferredConflicts.forEach { conflicts.replace(bookId, it) }
            activeManifest.summary(remoteRoot)
        } catch (error: Exception) {
            staged.parentFile?.deleteRecursively()
            throw error
        }
    }

    override suspend fun relinkRegistered(bookId: String, path: String): BookSummary = installMutex.withLock {
        installJournal.recover()
        val root = books.getRoot(bookId) ?: throw BookLibraryUserError("Книга не зарегистрирована")
        val manifestEntry = gateway.listFolder(path).singleOrNull {
            it.type == "file" && it.name == BookPaths.MANIFEST_NAME
        } ?: throw BookLibraryUserError("Существующий манифест книги больше недоступен")
        val remoteManifest = BookManifest.decode(
            StrictUtf8.decode(gateway.download(manifestEntry.path).bytes, "Book manifest"),
        )
        if (remoteManifest.bookId != bookId) {
            throw BookLibraryUserError("Выбранная папка относится к другой книге")
        }
        val localManifest = store.readManifest(bookId)
        if (localManifest.bookId != remoteManifest.bookId) {
            throw BookLibraryUserError("Локальная копия относится к другой книге")
        }
        books.upsertRoot(root.copy(remoteRootPath = path))
        root.copy(remoteRootPath = path).summaryFromCache()
    }

    override suspend fun persistResume(location: ResumeLocation) {
        check(preferences.edit().putString(KEY_LAST_BOOK, location.bookId).commit())
        books.upsertReadingPosition(
            ReadingPositionEntity(
                location.bookId,
                location.chapterId,
                location.blockIndex.coerceAtLeast(0),
                location.byteOffset.coerceAtLeast(0),
                currentTimeMillis(),
            ),
        )
    }

    override suspend fun opened(bookId: String) = Unit

    override suspend fun discover(bookId: String): List<DiscoveryNotice> {
        val root = requireNotNull(books.getRoot(bookId))
        val remoteRoot = requireNotNull(root.remoteRootPath)
        val manifest = store.readManifest(bookId)
        val remoteFiles = downloadOrdinaryMarkdown(remoteRoot)
        val cachedHashes = manifest.chapters.associate { chapter ->
            chapter.path to store.readSource(bookId, chapter.path).sha256()
        }
        val result = discovery.propose(remoteFiles, manifest, cachedHashes)
        return buildList {
            result.proposals.forEach { proposal ->
                add(
                    DiscoveryNotice.NewFile(
                        bookId,
                        proposal.path,
                        proposal.suggestedTitle,
                        suggestedPosition = manifest.chapters.size,
                        maxPosition = manifest.chapters.size,
                    ),
                )
            }
            result.missing.forEach { missing ->
                add(
                    DiscoveryNotice.MissingFile(
                        bookId,
                        missing.chapter.id,
                        try {
                            store.readSource(bookId, missing.chapter.path)
                        } catch (_: IOException) {
                            null
                        }?.let { source ->
                            ChapterTitleExtractor.extract(missing.chapter.path, source).title
                        },
                        missing.chapter.path,
                        missing.sameHashRenamePath,
                    ),
                )
            }
        }
    }

    override suspend fun add(bookId: String, path: String, position: Int) {
        if (!path.isOrdinaryMarkdown()) {
            throw BookLibraryUserError("Главой может быть только обычный файл Markdown из папки книги")
        }
        reviewMutations.withBookExclusive(bookId) {
            val root = requireNotNull(books.getRoot(bookId))
            val remoteRoot = requireNotNull(root.remoteRootPath)
            val remote = gateway.download(childPath(remoteRoot, path))
            StrictUtf8.decode(remote.bytes, "Chapter source $path")
            val manifest = store.readManifest(bookId)
            val proposal = discovery.propose(listOf(DiscoveryFile(path, remote.bytes)), manifest).proposals.singleOrNull()
                ?: throw BookLibraryUserError("Выбранный файл Markdown уже добавлен в книгу")
            val updated = discovery.add(manifest, proposal, UUID.randomUUID().toString(), position)
            val added = updated.chapters.single { it.path == path }
            persistManifestMutation(root, updated, mapOf(added.id to remote))
        }
    }

    override suspend fun replace(bookId: String, chapterId: String, path: String) {
        if (!path.isOrdinaryMarkdown()) {
            throw BookLibraryUserError("Главой может быть только обычный файл Markdown из папки книги")
        }
        val root = requireNotNull(books.getRoot(bookId))
        val remoteRoot = requireNotNull(root.remoteRootPath)
        val remote = gateway.download(childPath(remoteRoot, path))
        val source = StrictUtf8.decode(remote.bytes, "Replacement source $path")
        val rendered = MarkdownParser.parse(source)
        var reviewPath: String? = null
        reviewMutations.withBookExclusive(bookId) {
            val manifest = store.readManifest(bookId)
            val old = manifest.chapters.single { it.id == chapterId }
            val updated = discovery.replace(manifest, chapterId, path)
            val existingReview = store.readReview(bookId, old.path + BookPaths.REVIEW_SUFFIX)
            val position = books.getReadingPosition(bookId)?.takeIf { it.chapterId == chapterId }
            val manifestBase = checkNotNull(sync.getMergeBase(bookId, BookPaths.MANIFEST_NAME)) {
                "Exact manifest merge base is unavailable"
            }
            val durableManifestBase = checkNotNull(baseStore.read(bookId, BookPaths.MANIFEST_NAME)) {
                "Exact manifest merge base is unavailable"
            }
            check(
                manifestBase.sha256 == durableManifestBase.sha256 &&
                    manifestBase.remoteRevision == durableManifestBase.remoteRevision,
            ) { "Exact manifest merge base is unavailable" }
            val stagedBook = stageRepair(bookId)
            val stageRoot = requireNotNull(stagedBook.parentFile)
            val stagePaths = BookPaths(stageRoot)
            try {
                val stageStore = AtomicBookStore(stagePaths)
                stageStore.replaceDownloadedSource(bookId, path, remote.bytes)
                replacementCheckpoint(ReplacementCheckpoint.SOURCE_STAGED)
                val manifestRevision = stageStore.writeManifest(bookId, updated)
                replacementCheckpoint(ReplacementCheckpoint.MANIFEST_STAGED)
                quarantineDestinationReview(stagedBook, path + BookPaths.REVIEW_SUFFIX)
                val reviewRevision = existingReview?.copy(sourcePath = path)?.let { copied ->
                    val copiedPath = path + BookPaths.REVIEW_SUFFIX
                    reviewPath = copiedPath
                    stageStore.writeReview(bookId, copiedPath, copied)
                }
                replacementCheckpoint(ReplacementCheckpoint.REVIEW_STAGED)
                repairSwap(
                    bookId = bookId,
                    stagedBook = stagedBook,
                    metadata = emptyList(),
                    afterFilesystemSwap = { replacementCheckpoint(ReplacementCheckpoint.FILESYSTEM_SWAPPED) },
                ) {
                    migratePendingDeletions(bookId, chapterId, old.path, path)
                    if (reviewRevision != null) {
                        sync.upsertOutbox(
                            OutboxEntity(bookId, requireNotNull(reviewPath), reviewRevision.sha256, null, OutboxState.PENDING),
                        )
                    }
                    sync.upsertOutbox(
                        OutboxEntity(
                            bookId,
                            BookPaths.MANIFEST_NAME,
                            manifestRevision.sha256,
                            manifestBase.sha256,
                            OutboxState.PENDING,
                        ),
                    )
                    val existingRows = progressiveLoads.getFiles(bookId).associateBy(ProgressiveLoadFileEntity::chapterId)
                    if (progressiveLoads.getJob(bookId) != null) {
                        progressiveLoads.replaceManifestSpine(
                            bookId,
                            updated.chapters.mapIndexed { index, chapter ->
                                if (chapter.id == chapterId) {
                                    ProgressiveLoadFileEntity(
                                        bookId = bookId,
                                        path = path,
                                        chapterId = chapterId,
                                        spineIndex = index,
                                        expectedRevision = remote.revision,
                                        expectedSize = remote.bytes.size.toLong(),
                                        sha256 = remote.bytes.sha256(),
                                        state = ProgressiveLoadFileState.CACHED,
                                        priority = net.inkyquill.pocketeditor.load.BACKGROUND_PRIORITY,
                                        remoteName = path,
                                    )
                                } else {
                                    requireNotNull(existingRows[chapter.id]).copy(spineIndex = index)
                                }
                            },
                        )
                    }
                    sync.deleteRemoteRevision(bookId, old.path)
                    sync.upsertRemoteRevision(
                        RemoteRevisionEntity(bookId, path, remote.revision, remote.bytes.sha256()),
                    )
                    replacementCheckpoint(ReplacementCheckpoint.OUTBOX_STAGED)
                    position?.let { books.upsertReadingPosition(ReadingPositionClamp.clamp(it, rendered)) }
                    replacementCheckpoint(ReplacementCheckpoint.POSITION_STAGED)
                    val cachedIds = progressiveLoads.getFiles(bookId)
                        .filter { it.state == ProgressiveLoadFileState.CACHED }
                        .map(ProgressiveLoadFileEntity::chapterId).toSet()
                    search.rebuildBook(
                        bookId,
                        updated.chapters.mapNotNull { chapter ->
                            if (progressiveLoads.getJob(bookId) != null && chapter.id !in cachedIds) return@mapNotNull null
                            store.readSource(bookId, chapter.path).let { chapterSource ->
                                SearchChapterSource(
                                    chapter.id,
                                    ChapterTitleExtractor.extract(chapter.path, chapterSource).title,
                                    chapterSource,
                                )
                            }
                        },
                    )
                    replacementCheckpoint(ReplacementCheckpoint.SEARCH_STAGED)
                }
            } catch (error: Throwable) {
                stageRoot.deleteRecursively()
                throw error
            }
        }
        contentChanges.changed(
            bookId,
            buildSet {
                add(BookPaths.MANIFEST_NAME)
                add(path)
                reviewPath?.let(::add)
            },
        )
        contentChanges.bookChanged(bookId)
        if (progressiveLoads.getJob(bookId)?.phase != net.inkyquill.pocketeditor.load.ProgressiveLoadPhase.COMPLETE) {
            progressiveLoadScheduler?.continueLoad(bookId)
        }
        // The durable PENDING outbox remains observable and will be retried by a later monitor probe.
        runCatching { scheduler.enqueue(bookId, remoteRoot, SyncTrigger.LOCAL_CHANGE) }
    }

    private suspend fun migratePendingDeletions(
        bookId: String,
        chapterId: String,
        oldSourcePath: String,
        newSourcePath: String,
    ) {
        val oldReviewPath = oldSourcePath + BookPaths.REVIEW_SUFFIX
        val newReviewPath = newSourcePath + BookPaths.REVIEW_SUFFIX
        sync.pendingDeletions(bookId)
            .filter { it.chapterId == chapterId && it.reviewPath == oldReviewPath }
            .forEach { pending ->
                val payload = ReviewJson.decode(pending.recordPayload, chapterId, oldSourcePath)
                    .copy(sourcePath = newSourcePath)
                sync.upsertPendingDeletion(
                    PendingDeletionEntity(
                        tokenId = pending.tokenId,
                        bookId = pending.bookId,
                        chapterId = pending.chapterId,
                        reviewPath = newReviewPath,
                        recordId = pending.recordId,
                        recordType = pending.recordType,
                        recordPayload = ReviewJson.encode(payload),
                        createdAt = pending.createdAt,
                    ),
                )
            }
    }

    override suspend fun ignore(bookId: String, path: String) {
        val root = requireNotNull(books.getRoot(bookId))
        val manifest = discovery.ignore(store.readManifest(bookId), path)
        val revision = store.writeManifest(bookId, manifest)
        sync.upsertOutbox(OutboxEntity(bookId, BookPaths.MANIFEST_NAME, revision.sha256, sync.getMergeBase(bookId, BookPaths.MANIFEST_NAME)?.sha256, OutboxState.PENDING))
        root.remoteRootPath?.let { scheduler.enqueue(bookId, it, SyncTrigger.LOCAL_CHANGE) }
    }

    override suspend fun updatePath(bookId: String, chapterId: String, path: String, requireSameHash: Boolean) {
        reviewMutations.withBookExclusive(bookId) {
            val root = requireNotNull(books.getRoot(bookId))
            val remoteRoot = requireNotNull(root.remoteRootPath)
            val manifest = store.readManifest(bookId)
            val old = manifest.chapters.single { it.id == chapterId }
            val remoteFiles = downloadOrdinaryMarkdown(remoteRoot)
            val selected = remoteFiles.singleOrNull { it.path == path }
                ?: throw BookLibraryUserError("Выбранный файл Markdown недоступен")
            if (requireSameHash) {
                val expectedHash = store.readSource(bookId, old.path).sha256()
                val result = discovery.propose(remoteFiles, manifest, mapOf(old.path to expectedHash))
                val exact = result.missing.singleOrNull { it.chapter.id == chapterId }?.sameHashRenamePath
                if (exact != path) {
                    throw BookLibraryUserError("Содержимое найденного файла изменилось")
                }
            }
            val existingReview = store.readReview(bookId, old.path + BookPaths.REVIEW_SUFFIX)
            val updated = discovery.locate(manifest, chapterId, path)
            val remote = gateway.download(childPath(remoteRoot, path))
            check(remote.bytes.contentEquals(selected.bytes)) { "Selected remote source changed during path update" }
            persistManifestMutation(root, updated, mapOf(chapterId to remote))
            if (existingReview != null) {
                val revision = store.writeReview(
                    bookId,
                    path + BookPaths.REVIEW_SUFFIX,
                    existingReview.copy(sourcePath = path),
                )
                sync.upsertOutbox(
                    OutboxEntity(bookId, path + BookPaths.REVIEW_SUFFIX, revision.sha256, null, OutboxState.PENDING),
                )
            }
        }
    }

    override suspend fun removeChapter(bookId: String, chapterId: String) {
        reviewMutations.withBookExclusive(bookId) {
            val root = requireNotNull(books.getRoot(bookId))
            persistManifestMutation(root, discovery.remove(store.readManifest(bookId), chapterId))
        }
    }

    override suspend fun reorder(bookId: String, orderedChapterIds: List<String>) {
        var remoteRoot: String? = null
        reviewMutations.withBookExclusive(bookId) {
            val root = books.getRoot(bookId)
                ?: throw BookLibraryUserError("Книга не зарегистрирована")
            remoteRoot = root.remoteRootPath
            if (conflicts.conflict(bookId, BookPaths.MANIFEST_NAME) != null) {
                throw BookLibraryUserError("Порядок не сохранён: сначала разрешите конфликт книги")
            }
            val current = store.readManifest(bookId)
            val byId = current.chapters.associateBy(ChapterEntry::id)
            require(
                orderedChapterIds.size == current.chapters.size &&
                    orderedChapterIds.distinct().size == orderedChapterIds.size &&
                    orderedChapterIds.toSet() == byId.keys,
            ) { "Reorder must contain the complete unique spine" }
            if (orderedChapterIds == current.chapters.map(ChapterEntry::id)) return@withBookExclusive

            val currentBytes = paths.manifest(bookId).readBytes()
            val baseSha = verifiedManifestBaseSha(bookId, currentBytes)
            val updated = current.copy(chapters = orderedChapterIds.map(byId::getValue))
            val stagedBook = stageRepair(bookId)
            val stageRoot = requireNotNull(stagedBook.parentFile)
            try {
                val revision = AtomicBookStore(BookPaths(stageRoot)).writeManifest(bookId, updated)
                reorderCheckpoint(ReorderCheckpoint.STAGED)
                val loadFiles = progressiveLoads.getFiles(bookId)
                val cachedIds = if (loadFiles.isEmpty()) {
                    updated.chapters.mapNotNull { chapter ->
                        runCatching { store.readSource(bookId, chapter.path) }.getOrNull()?.let { chapter.id }
                    }.toSet()
                } else {
                    loadFiles.filter { it.state == ProgressiveLoadFileState.CACHED }
                        .map(ProgressiveLoadFileEntity::chapterId).toSet()
                }
                repairSwap(
                    bookId = bookId,
                    stagedBook = stagedBook,
                    metadata = emptyList(),
                    afterFilesystemSwap = { reorderCheckpoint(ReorderCheckpoint.FILESYSTEM_SWAPPED) },
                    afterDatabaseCommit = { reorderCheckpoint(ReorderCheckpoint.DATABASE_COMMITTED) },
                ) {
                    progressiveLoads.reorderSpine(bookId, orderedChapterIds)
                    sync.upsertOutbox(
                        OutboxEntity(
                            bookId = bookId,
                            path = BookPaths.MANIFEST_NAME,
                            localSha256 = revision.sha256,
                            baseSha256 = baseSha,
                            state = OutboxState.PENDING,
                        ),
                    )
                    search.rebuildBook(
                        bookId,
                        updated.chapters.mapNotNull { chapter ->
                            if (chapter.id !in cachedIds) return@mapNotNull null
                            val source = store.readSource(bookId, chapter.path)
                            SearchChapterSource(
                                chapter.id,
                                ChapterTitleExtractor.extract(chapter.path, source).title,
                                source,
                            )
                        },
                    )
                    reorderCheckpoint(ReorderCheckpoint.METADATA_COMMITTED)
                }
            } catch (error: Throwable) {
                if (stageRoot.exists()) stageRoot.deleteRecursively()
                throw error
            }
        }
        contentChanges.changed(bookId, BookPaths.MANIFEST_NAME)
        contentChanges.bookChanged(bookId)
        if (progressiveLoads.getJob(bookId)?.phase != net.inkyquill.pocketeditor.load.ProgressiveLoadPhase.COMPLETE) {
            progressiveLoadScheduler?.continueLoad(bookId)
        }
        remoteRoot?.let { root -> runCatching { scheduler.enqueue(bookId, root, SyncTrigger.LOCAL_CHANGE) } }
    }

    override suspend fun refreshReorderBase(bookId: String, isCurrent: () -> Boolean) {
        reviewMutations.withBookExclusive(bookId) {
            fun requireCurrent() {
                if (!isCurrent()) throw kotlinx.coroutines.CancellationException("Reorder recovery was superseded")
            }
            val root = books.getRoot(bookId) ?: throw BookLibraryUserError("Книга не зарегистрирована")
            val remoteRoot = root.remoteRootPath
                ?: throw BookLibraryUserError("У книги нет папки на Яндекс Диске")
            val manifestEntry = gateway.listFolder(remoteRoot)
                .singleOrNull { it.type == "file" && it.name == BookPaths.MANIFEST_NAME }
                ?: throw BookLibraryUserError("Манифест книги недоступен на Яндекс Диске")
            requireCurrent()
            val remote = gateway.download(manifestEntry.path)
            requireCurrent()
            val remoteManifest = BookManifest.decode(StrictUtf8.decode(remote.bytes, "Book manifest"))
            val localManifest = store.readManifest(bookId)
            require(remoteManifest.bookId == bookId) { "Remote manifest belongs to another book" }
            if (remoteManifest != localManifest) {
                requireCurrent()
                val conflict = SyncConflict.Manifest(
                    path = BookPaths.MANIFEST_NAME,
                    local = localManifest,
                    remote = remoteManifest,
                    remoteBytes = remote.bytes,
                    remoteRevision = remote.revision,
                )
                reorderBaseRefreshCheckpoint(ReorderBaseRefreshCheckpoint.BEFORE_CONFLICT_REPLACE)
                conflicts.replace(bookId, conflict)
                if (!isCurrent()) {
                    conflicts.removeIfCurrent(bookId, conflict)
                    throw kotlinx.coroutines.CancellationException("Reorder recovery was superseded")
                }
                throw BookLibraryUserError("Порядок не сохранён: разрешите конфликт содержимого книги")
            }
            val localBytes = paths.manifest(bookId).readBytes()
            val localSha = localBytes.sha256()
            val previousBase = baseStore.read(bookId, BookPaths.MANIFEST_NAME)
            requireCurrent()
            val durable = baseStore.write(bookId, BookPaths.MANIFEST_NAME, remote.bytes, remote.revision)
            if (durable.directorySyncStatus != DirectorySyncStatus.SYNCED) {
                restoreReorderBase(bookId, previousBase)
                throw BookLibraryUserError(
                    "Порядок не сохранён: основу книги не удалось записать надёжно. Повторите попытку.",
                )
            }
            try {
                requireCurrent()
                transaction.run {
                    reorderBaseRefreshCheckpoint(ReorderBaseRefreshCheckpoint.BEFORE_METADATA_COMMIT)
                    sync.upsertMergeBase(MergeBaseEntity(bookId, BookPaths.MANIFEST_NAME, durable.sha256, remote.revision))
                    sync.upsertRemoteRevision(RemoteRevisionEntity(bookId, BookPaths.MANIFEST_NAME, remote.revision, durable.sha256))
                    if (localSha == durable.sha256) {
                        sync.deleteOutbox(bookId, BookPaths.MANIFEST_NAME)
                    } else {
                        sync.upsertOutbox(
                            OutboxEntity(
                                bookId,
                                BookPaths.MANIFEST_NAME,
                                localSha,
                                durable.sha256,
                                OutboxState.PENDING,
                            ),
                        )
                    }
                }
            } catch (failure: Throwable) {
                restoreReorderBase(bookId, previousBase)
                throw failure
            }
            conflicts.remove(bookId, BookPaths.MANIFEST_NAME)
        }
    }

    private fun restoreReorderBase(bookId: String, previousBase: net.inkyquill.pocketeditor.sync.SyncBase?) {
        val status = if (previousBase == null) {
            baseStore.delete(bookId, BookPaths.MANIFEST_NAME)
        } else {
            baseStore.write(
                bookId,
                BookPaths.MANIFEST_NAME,
                previousBase.bytes,
                previousBase.remoteRevision,
            ).directorySyncStatus
        }
        if (status != DirectorySyncStatus.SYNCED) {
            throw BookLibraryUserError(
                "Порядок не сохранён: предыдущую основу книги не удалось восстановить надёжно. Повторите попытку.",
            )
        }
    }

    private suspend fun verifiedManifestBaseSha(bookId: String, currentBytes: ByteArray): String? {
        return try {
            val currentSha = currentBytes.sha256()
            val outbox = sync.getOutbox(bookId, BookPaths.MANIFEST_NAME)
            if (outbox != null) {
                check(outbox.localSha256 == currentSha) { "Manifest outbox no longer matches local base" }
                val baseSha = outbox.baseSha256
                if (baseSha != null) {
                    verifyDurableManifestBase(bookId, baseSha)
                } else {
                    check(sync.getMergeBase(bookId, BookPaths.MANIFEST_NAME) == null)
                    check(baseStore.read(bookId, BookPaths.MANIFEST_NAME) == null)
                    check(sync.getRemoteRevisions(bookId).none { it.path == BookPaths.MANIFEST_NAME })
                }
                baseSha
            } else {
                val mergeBase = requireNotNull(sync.getMergeBase(bookId, BookPaths.MANIFEST_NAME))
                check(currentSha == mergeBase.sha256) { "Local manifest no longer matches merge base" }
                verifyDurableManifestBase(bookId, mergeBase.sha256)
                mergeBase.sha256
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw BookLibraryUserError("Порядок не сохранён: сначала обновите основу книги")
        }
    }

    private suspend fun verifyDurableManifestBase(bookId: String, expectedSha: String) {
        val mergeBase = requireNotNull(sync.getMergeBase(bookId, BookPaths.MANIFEST_NAME))
        val durableBase = requireNotNull(baseStore.read(bookId, BookPaths.MANIFEST_NAME))
        check(
            mergeBase.sha256 == expectedSha &&
                durableBase.sha256 == mergeBase.sha256 &&
                durableBase.remoteRevision == mergeBase.remoteRevision,
        ) { "Exact manifest merge base is unavailable" }
        val observed = sync.getRemoteRevisions(bookId).filter { it.path == BookPaths.MANIFEST_NAME }
        check(observed.size == 1) { "Observed remote manifest revision is unavailable" }
        observed.single().let { remote ->
            check(remote.sha256 == mergeBase.sha256 && remote.remoteRevision == mergeBase.remoteRevision) {
                "Remote manifest base changed"
            }
        }
    }

    override suspend fun forget(bookId: String) {
        if (progressiveLoads.getJob(bookId) != null) {
            progressiveLoadScheduler?.cancel(bookId)
        }
        reviewMutations.withBookExclusive(bookId) {
            val directory = paths.bookDirectory(bookId)
            require(directory.parentFile?.canonicalFile == paths.root.canonicalFile) { "Refusing to remove an unexpected cache path" }
            check(!directory.exists() || directory.deleteRecursively()) { "Could not remove the local book cache" }
            search.clearBook(bookId)
            transaction.run {
                drafts.deleteBook(bookId)
                sync.deletePendingDeletions(bookId)
                sync.deletePendingPublications(bookId)
                sync.deleteOutbox(bookId)
                sync.deleteMergeBases(bookId)
                sync.deleteRemoteRevisions(bookId)
                books.deleteReadingPosition(bookId)
                progressiveLoads.deleteFiles(bookId)
                progressiveLoads.deleteJob(bookId)
                books.deleteRoot(bookId)
            }
        }
        if (preferences.getString(KEY_LAST_BOOK, null) == bookId) {
            check(preferences.edit().remove(KEY_LAST_BOOK).commit())
        }
    }

    override suspend fun saveAppearance(value: AppearancePreference) {
        check(
            preferences.edit()
                .putBoolean(KEY_DARK, value.dark)
                .putFloat(KEY_TEXT_SCALE, value.textScale)
                .commit(),
        ) { "Appearance could not be saved" }
    }

    private fun String.isOrdinaryMarkdown() = endsWith(".md", ignoreCase = false) && !startsWith('.') && '/' !in this && '\\' !in this
    private fun childPath(root: String, name: String) = "${root.trimEnd('/')}/$name"

    private suspend fun downloadOrdinaryMarkdown(remoteRoot: String): List<DiscoveryFile> = gateway.listFolder(remoteRoot)
        .filter { it.type == "file" && it.name.isOrdinaryMarkdown() }
        .map { entry ->
            val remote = gateway.download(entry.path)
            DiscoveryFile(entry.name, remote.bytes, remote.bytes.sha256())
        }

    private suspend fun persistManifestMutation(
        root: BookRootEntity,
        manifest: BookManifest,
        downloadedByChapterId: Map<String, net.inkyquill.pocketeditor.yandex.RemoteFile> = emptyMap(),
    ) {
        val bookId = root.bookId
        val before = store.readManifest(bookId)
        val stagedBook = stageRepair(bookId)
        val stageRoot = requireNotNull(stagedBook.parentFile)
        try {
            val stageStore = AtomicBookStore(BookPaths(stageRoot))
            downloadedByChapterId.forEach { (chapterId, remote) ->
                val chapter = manifest.chapters.single { it.id == chapterId }
                StrictUtf8.decode(remote.bytes, "Chapter ${chapter.path}")
                stageStore.replaceDownloadedSource(bookId, chapter.path, remote.bytes)
            }
            val revision = stageStore.writeManifest(bookId, manifest)
            repairSwap(bookId, stagedBook, emptyList()) {
                val oldRows = progressiveLoads.getFiles(bookId).associateBy(ProgressiveLoadFileEntity::chapterId)
                if (progressiveLoads.getJob(bookId) != null) {
                    val replacement = manifest.chapters.mapIndexed { index, chapter ->
                        val downloaded = downloadedByChapterId[chapter.id]
                        val existing = oldRows[chapter.id]
                        if (downloaded != null) {
                            ProgressiveLoadFileEntity(
                                bookId = bookId,
                                path = chapter.path,
                                chapterId = chapter.id,
                                spineIndex = index,
                                expectedRevision = downloaded.revision,
                                expectedSize = downloaded.bytes.size.toLong(),
                                sha256 = downloaded.bytes.sha256(),
                                state = ProgressiveLoadFileState.CACHED,
                                priority = net.inkyquill.pocketeditor.load.BACKGROUND_PRIORITY,
                                remoteName = chapter.path,
                            )
                        } else {
                            requireNotNull(existing) { "Manifest mutation introduced a chapter without durable source" }
                                .copy(spineIndex = index, path = chapter.path)
                        }
                    }
                    progressiveLoads.replaceManifestSpine(bookId, replacement)
                }
                val removedPaths = before.chapters.map(ChapterEntry::path).toSet() - manifest.chapters.map(ChapterEntry::path).toSet()
                removedPaths.forEach { sync.deleteRemoteRevision(bookId, it) }
                downloadedByChapterId.forEach { (chapterId, remote) ->
                    val path = manifest.chapters.single { it.id == chapterId }.path
                    sync.upsertRemoteRevision(RemoteRevisionEntity(bookId, path, remote.revision, remote.bytes.sha256()))
                }
                sync.upsertOutbox(
                    OutboxEntity(
                        bookId,
                        BookPaths.MANIFEST_NAME,
                        revision.sha256,
                        sync.getMergeBase(bookId, BookPaths.MANIFEST_NAME)?.sha256,
                        OutboxState.PENDING,
                    ),
                )
                val cachedIds = progressiveLoads.getFiles(bookId)
                    .filter { it.state == ProgressiveLoadFileState.CACHED }
                    .map(ProgressiveLoadFileEntity::chapterId)
                    .toSet()
                search.rebuildBook(
                    bookId,
                    manifest.chapters.mapNotNull { chapter ->
                        if (progressiveLoads.getJob(bookId) != null && chapter.id !in cachedIds) return@mapNotNull null
                        val source = store.readSource(bookId, chapter.path)
                        SearchChapterSource(chapter.id, ChapterTitleExtractor.extract(chapter.path, source).title, source)
                    },
                )
            }
            contentChanges.changed(bookId, BookPaths.MANIFEST_NAME)
            contentChanges.bookChanged(bookId)
            val partial = progressiveLoads.getJob(bookId)?.phase != null &&
                progressiveLoads.getJob(bookId)?.phase != net.inkyquill.pocketeditor.load.ProgressiveLoadPhase.COMPLETE
            if (partial) progressiveLoadScheduler?.continueLoad(bookId)
            root.remoteRootPath?.let { scheduler.enqueue(bookId, it, SyncTrigger.LOCAL_CHANGE) }
        } catch (failure: Throwable) {
            if (stageRoot.exists()) stageRoot.deleteRecursively()
            throw failure
        }
    }

    private fun stageRepair(bookId: String): File {
        Files.createDirectories(paths.root.toPath())
        val stageRoot = File(paths.root, ".repair-stage-${UUID.randomUUID()}")
        val stagedBook = BookPaths(stageRoot).bookDirectory(bookId)
        try {
            check(paths.bookDirectory(bookId).copyRecursively(stagedBook, overwrite = true)) {
                "Could not stage the registered cache"
            }
            return stagedBook
        } catch (error: Throwable) {
            stageRoot.deleteRecursively()
            throw error
        }
    }

    private suspend fun repairSwap(
        bookId: String,
        stagedBook: File,
        metadata: List<RepairMetadata>,
        afterFilesystemSwap: () -> Unit = {},
        afterDatabaseCommit: () -> Unit = {},
        commit: suspend () -> Unit,
    ) {
        val finalBook = paths.bookDirectory(bookId)
        check(books.getRoot(bookId) != null) { "Only registered books can enter the repair protocol" }
        check(finalBook.isDirectory && stagedBook.isDirectory) { "Repair requires existing and staged caches" }
        val stageRoot = requireNotNull(stagedBook.parentFile)
        require(stageRoot.parentFile?.canonicalFile == paths.root.canonicalFile)
        val backup = File(paths.root, ".repair-backup-${UUID.randomUUID()}")
        val token = UUID.randomUUID().toString()
        val markerPath = "$REPAIR_COMMIT_PREFIX$token"
        var journal = RepairJournal(bookId, stageRoot.name, backup.name, markerPath, databaseCommitted = false)
        writeRepairJournal(journal)
        var oldMoved = false
        var newMoved = false
        var databaseCommitted = false
        try {
            Files.move(finalBook.toPath(), backup.toPath(), ATOMIC_MOVE)
            oldMoved = true
            installDirectorySync(paths.root)
            Files.move(stagedBook.toPath(), finalBook.toPath(), ATOMIC_MOVE)
            newMoved = true
            installDirectorySync(paths.root)
            installCheckpoint(LibraryInstallCheckpoint.FILESYSTEM_SWAP)
            afterFilesystemSwap()
            transaction.run {
                installCheckpoint(LibraryInstallCheckpoint.METADATA)
                metadata.forEach { item ->
                    if (item.confirmRemote) {
                        val hash = item.remote.bytes.sha256()
                        sync.upsertRemoteRevision(RemoteRevisionEntity(bookId, item.path, item.remote.revision, hash))
                    }
                    item.updatedOutbox?.let { sync.upsertOutbox(it) }
                }
                commit()
                sync.upsertRemoteRevision(RemoteRevisionEntity(bookId, markerPath, token, token))
            }
            databaseCommitted = true
            afterDatabaseCommit()
            journal = journal.copy(databaseCommitted = true)
            writeRepairJournal(journal)
            sync.deleteRemoteRevision(bookId, markerPath)
            repairCleanupCheckpoint(RepairCleanupCheckpoint.MARKER_DELETED)
            repairCleanupCheckpoint(RepairCleanupCheckpoint.BEFORE_DIRECTORY_SYNC)
            installDirectorySync(paths.root)
            check(!backup.exists() || backup.deleteRecursively()) { "Could not remove repaired-cache backup" }
            check(!stageRoot.exists() || stageRoot.deleteRecursively()) { "Could not remove repair staging directory" }
            deleteRepairJournal(bookId)
        } catch (error: Exception) {
            // The filesystem swap and Room transaction are already authoritative. Leave the
            // durable journal/backup for startup recovery and let the caller publish the change.
            if (databaseCommitted) return
            if (newMoved && finalBook.exists()) finalBook.deleteRecursively()
            if (oldMoved && backup.exists()) Files.move(backup.toPath(), finalBook.toPath(), ATOMIC_MOVE)
            stageRoot.deleteRecursively()
            if (backup.exists()) backup.deleteRecursively()
            deleteRepairJournal(bookId)
            installDirectorySync(paths.root)
            throw error
        }
    }

    private fun quarantineDestinationReview(stagedBook: File, reviewPath: String) {
        val destination = File(stagedBook, reviewPath)
        if (!destination.exists()) return
        val quarantine = File(stagedBook, REVIEW_QUARANTINE_DIRECTORY)
        Files.createDirectories(quarantine.toPath())
        Files.move(
            destination.toPath(),
            File(quarantine, "${UUID.randomUUID()}-${destination.name}").toPath(),
            ATOMIC_MOVE,
        )
        installDirectorySync(stagedBook)
        installDirectorySync(quarantine)
    }

    private suspend fun recoverRepairs() {
        paths.root.listFiles().orEmpty().filter { it.name.startsWith(REPAIR_JOURNAL_PREFIX) }.forEach { file ->
            val value = JSONObject(StrictUtf8.decode(file.readBytes(), "Repair journal"))
            val journal = RepairJournal(
                value.getString("book_id"),
                value.getString("stage_root"),
                value.getString("backup"),
                value.getString("marker_path"),
                value.optBoolean("database_committed", false),
            )
            val finalBook = paths.bookDirectory(journal.bookId)
            val stageRoot = File(paths.root, journal.stageRootName)
            val backup = File(paths.root, journal.backupName)
            val committed = journal.databaseCommitted ||
                sync.observeRemoteRevisions(journal.bookId).first().any { it.path == journal.markerPath }
            if (committed) {
                check(!backup.exists() || backup.deleteRecursively()) { "Could not remove committed repair backup" }
            } else if (backup.exists()) {
                finalBook.deleteRecursively()
                Files.move(backup.toPath(), finalBook.toPath(), ATOMIC_MOVE)
            }
            check(!stageRoot.exists() || stageRoot.deleteRecursively()) { "Could not remove recovered repair stage" }
            check(!backup.exists() || backup.deleteRecursively()) { "Could not remove recovered repair backup" }
            check(file.delete()) { "Could not remove recovered repair journal" }
            sync.deleteRemoteRevision(journal.bookId, journal.markerPath)
            installDirectorySync(paths.root)
        }
        books.getRoots().forEach { root ->
            sync.observeRemoteRevisions(root.bookId).first()
                .filter { it.path.startsWith(REPAIR_COMMIT_PREFIX) }
                .forEach { sync.deleteRemoteRevision(root.bookId, it.path) }
        }
        val referenced = paths.root.listFiles().orEmpty().filter { it.name.startsWith(REPAIR_JOURNAL_PREFIX) }
            .mapNotNull { runCatching { JSONObject(it.readText()).getString("stage_root") }.getOrNull() }
            .toSet()
        paths.root.listFiles().orEmpty().filter { it.name.startsWith(".repair-stage-") && it.name !in referenced }
            .forEach(File::deleteRecursively)
    }

    private fun writeRepairJournal(value: RepairJournal) {
        val target = File(paths.root, "$REPAIR_JOURNAL_PREFIX${value.bookId}.json")
        val temporary = File(paths.root, ".${target.name}.${UUID.randomUUID()}.tmp")
        val bytes = JSONObject()
            .put("book_id", value.bookId)
            .put("stage_root", value.stageRootName)
            .put("backup", value.backupName)
            .put("marker_path", value.markerPath)
            .put("database_committed", value.databaseCommitted)
            .toString()
            .encodeToByteArray()
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
            installDirectorySync(paths.root)
        } finally {
            temporary.delete()
        }
    }

    private fun deleteRepairJournal(bookId: String) {
        val journal = File(paths.root, "$REPAIR_JOURNAL_PREFIX$bookId.json")
        check(!journal.exists() || journal.delete()) { "Could not remove repair journal" }
        installDirectorySync(paths.root)
    }

    private data class RepairMetadata(
        val path: String,
        val remote: net.inkyquill.pocketeditor.yandex.RemoteFile,
        val updatedOutbox: OutboxEntity? = null,
        val confirmRemote: Boolean = updatedOutbox == null,
    )

    private data class RepairJournal(
        val bookId: String,
        val stageRootName: String,
        val backupName: String,
        val markerPath: String,
        val databaseCommitted: Boolean,
    )

    private suspend fun BookRootEntity.summaryFromCache(): BookSummary {
        val manifest = store.readManifest(bookId)
        require(manifest.bookId == bookId) { "Book identity does not match its cache directory" }
        val rowsByPath = progressiveLoads.getFiles(bookId).associateBy { it.path }
        return BookSummary(
            bookId,
            manifest.title,
            remoteRootPath.orEmpty(),
            manifest.chapters.map { chapter ->
                val row = rowsByPath[chapter.path]
                val cachedBytes = row?.takeIf { it.state == net.inkyquill.pocketeditor.load.ProgressiveLoadFileState.CACHED }
                    ?.let { runCatching { store.readSource(bookId, chapter.path) }.getOrNull() }
                BookChapter(
                    chapter.id,
                    chapter.path,
                    cachedBytes?.let { ChapterTitleExtractor.extract(chapter.path, it).title }
                        ?: chapter.path.removeSuffix(".md"),
                    cached = cachedBytes != null,
                )
            },
            needsRelink = remoteRootPath == null,
        )
    }

    private fun String.normalizedRemotePath() = trim().trimEnd('/')

    private fun validateUtf8(bytes: ByteArray, path: String): String = StrictUtf8.decode(bytes, path)

    private suspend fun BookManifest.summary(remoteRoot: String, availableOffline: Boolean = true) = BookSummary(
        bookId,
        title,
        remoteRoot,
        chapters.map { chapter ->
            BookChapter(
                chapter.id,
                chapter.path,
                ChapterTitleExtractor.extract(chapter.path, store.readSource(bookId, chapter.path)).title,
                cached = availableOffline,
            )
        },
        availableOffline,
    )

    private companion object {
        const val REPAIR_JOURNAL_PREFIX = ".repair-journal-"
        const val REPAIR_COMMIT_PREFIX = ".repair-commit-"
        const val REVIEW_QUARANTINE_DIRECTORY = ".review-quarantine"
        const val KEY_LAST_BOOK = "last_book_id"
        const val KEY_DARK = "dark_theme"
        const val KEY_TEXT_SCALE = "reader_text_scale"
    }
}
