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
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.SyncDao
import net.inkyquill.pocketeditor.database.DraftDao
import net.inkyquill.pocketeditor.database.ImportDraftDao
import net.inkyquill.pocketeditor.search.SearchChapterSource
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.storage.InstallPhase
import net.inkyquill.pocketeditor.storage.InstallJournalEntry
import net.inkyquill.pocketeditor.storage.InstallRecoveryJournal
import net.inkyquill.pocketeditor.storage.ImportDraftStore
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.storage.StrictUtf8
import net.inkyquill.pocketeditor.storage.PlatformDirectoryFsync
import net.inkyquill.pocketeditor.storage.LibraryStartupRecovery
import net.inkyquill.pocketeditor.storage.DirectoryFsync
import net.inkyquill.pocketeditor.review.ReviewJson
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

class RoomYandexBookLibraryData(
    private val gateway: YandexDiskGateway,
    private val store: AtomicBookStore,
    private val paths: BookPaths,
    private val books: BookDao,
    private val sync: SyncDao,
    private val drafts: DraftDao,
    private val importDraftsDao: ImportDraftDao,
    private val importDraftStore: ImportDraftStore,
    private val search: SourceSearch,
    private val scheduler: SyncScheduler,
    private val preferences: SharedPreferences,
    private val baseStore: SyncBaseStore,
    private val conflicts: ConflictRepository,
    private val transaction: LibraryTransaction,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val installCheckpoint: (LibraryInstallCheckpoint) -> Unit = {},
    private val installPhaseObserver: (InstallPhase) -> Unit = {},
    private val installDirectorySync: (File) -> DirectorySyncStatus = PlatformDirectoryFsync::sync,
    private val installMoveObserver: () -> Unit = {},
    private val startupRecovery: LibraryStartupRecovery? = null,
    private val repairCleanupCheckpoint: (RepairCleanupCheckpoint) -> Unit = {},
    private val contentChanges: ContentChangeNotifier = ContentChangeNotifier(),
) : BookLibraryData {
    private val discovery = BookDiscovery()
    private val importRepository = ImportDraftRepository(
        gateway = gateway,
        drafts = importDraftsDao,
        store = importDraftStore,
        discovery = discovery,
        currentTimeMillis = currentTimeMillis,
    )
    private val installJournal = InstallRecoveryJournal(paths, books, DirectoryFsync(installDirectorySync))
    private val installMutex = Mutex()

    override suspend fun books(): List<BookSummary> = installMutex.withLock {
        recoverRepairs()
        startupRecovery?.recover()
        installJournal.recover()
        books.getRoots().map { root ->
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

    override suspend fun importDrafts(): List<ImportDraftSummary> = importRepository.all()

    override suspend fun resumeImport(bookId: String): ImportDraft = importRepository.resume(bookId)

    override suspend fun updateImport(draft: ImportDraft) = importRepository.update(draft)

    override suspend fun discardImport(bookId: String) = importRepository.discard(bookId)

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

    override suspend fun propose(path: String): ImportDraft = importRepository.createOrResume(path)

    override suspend fun existingRoot(path: String): BookSummary? {
        val manifestEntry = gateway.listFolder(path).singleOrNull {
            it.type == "file" && it.name == BookPaths.MANIFEST_NAME
        } ?: return null
        val manifest = BookManifest.decode(StrictUtf8.decode(gateway.download(manifestEntry.path).bytes, "Book manifest"))
        if (manifest.chapters.isEmpty()) {
            throw BookLibraryUserError("В существующем манифесте книги нет глав")
        }
        return manifest.previewSummary(path)
    }

    override suspend fun installExisting(path: String): BookSummary = installMutex.withLock {
        installJournal.recover()
        registeredSummary(remoteRootPath = path)?.let { return@withLock it }
        val entries = gateway.listFolder(path)
        val manifestEntry = entries.singleOrNull { it.type == "file" && it.name == BookPaths.MANIFEST_NAME }
            ?: throw BookLibraryUserError("Существующий манифест книги больше недоступен")
        val remoteManifest = gateway.download(manifestEntry.path)
        val manifest = BookManifest.decode(StrictUtf8.decode(remoteManifest.bytes, "Book manifest"))
        if (manifest.chapters.isEmpty()) {
            throw BookLibraryUserError("В существующем манифесте книги нет глав")
        }
        registeredSummary(remoteRootPath = path, bookId = manifest.bookId)?.let { return@withLock it }
        val filesByName = entries.filter { it.type == "file" }.associateBy { it.name }
        val downloads = manifest.chapters.map { chapter ->
            if (!chapter.path.isOrdinaryMarkdown()) {
                throw BookLibraryUserError("Глава в манифесте не является обычным файлом Markdown: ${chapter.path}")
            }
            val entry = filesByName[chapter.path]
                ?: throw BookLibraryUserError("На диске отсутствует глава: ${chapter.path}")
            val remote = gateway.download(entry.path)
            validateUtf8(remote.bytes, chapter.path)
            chapter to remote
        }
        val reviews = manifest.chapters.mapNotNull { chapter ->
            val relative = chapter.path + BookPaths.REVIEW_SUFFIX
            val entry = filesByName[relative] ?: return@mapNotNull null
            val remote = gateway.download(entry.path)
            val text = validateUtf8(remote.bytes, relative)
            ReviewJson.decode(text, chapter.id, chapter.path)
            relative to remote
        }
        val staged = stageBook(manifest.bookId) { _, stageStore ->
            stageStore.replaceDownloadedManifest(manifest.bookId, remoteManifest.bytes)
            downloads.forEach { (chapter, remote) ->
                stageStore.replaceDownloadedSource(manifest.bookId, chapter.path, remote.bytes)
            }
            reviews.forEach { (relative, remote) ->
                stageStore.replaceDownloadedReview(manifest.bookId, relative, remote.bytes)
            }
            stageStore.readManifest(manifest.bookId)
            reviews.forEach { (relative, _) -> stageStore.readReview(manifest.bookId, relative) }
        }
        val trustedMetadata = buildList {
            add(BookPaths.MANIFEST_NAME to remoteManifest)
            downloads.forEach { (chapter, remote) -> add(chapter.path to remote) }
            reviews.forEach { (relative, remote) -> add(relative to remote) }
        }
        installStaged(manifest.bookId, staged, trustedMetadata.filter { (relative, _) ->
            relative == BookPaths.MANIFEST_NAME || relative.endsWith(BookPaths.REVIEW_SUFFIX)
        }) {
            installCheckpoint(LibraryInstallCheckpoint.METADATA)
            sync.deleteOutbox(manifest.bookId)
            sync.deleteMergeBases(manifest.bookId)
            sync.deleteRemoteRevisions(manifest.bookId)
            trustedMetadata.forEach { (relative, remote) ->
                sync.upsertRemoteRevision(RemoteRevisionEntity(manifest.bookId, relative, remote.revision, remote.bytes.sha256()))
                if (relative == BookPaths.MANIFEST_NAME || relative.endsWith(BookPaths.REVIEW_SUFFIX)) {
                    sync.upsertMergeBase(MergeBaseEntity(manifest.bookId, relative, remote.bytes.sha256(), remote.revision))
                }
            }
            installCheckpoint(LibraryInstallCheckpoint.SEARCH)
            search.rebuildBook(
                manifest.bookId,
                downloads.map { (chapter, remote) ->
                    SearchChapterSource(chapter.id, ChapterTitleExtractor.extract(chapter.path, remote.bytes).title, remote.bytes)
                },
            )
            installCheckpoint(LibraryInstallCheckpoint.ROOT)
            books.upsertRoot(BookRootEntity(manifest.bookId, path, paths.bookDirectory(manifest.bookId).absolutePath, currentTimeMillis()))
        }
        manifest.summary(path)
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

    override suspend fun import(draft: ImportDraft): BookSummary = installMutex.withLock {
        installJournal.recover()
        registeredSummary(remoteRootPath = draft.remoteRootPath)?.let { return@withLock it }
        importRepository.update(draft)
        val selected = importRepository.cachedChapters(draft.bookId).filter(CachedImportChapter::included)
        if (selected.isEmpty()) {
            throw BookLibraryUserError("Добавьте хотя бы одну главу")
        }
        val bookId = draft.bookId
        val manifest = BookManifest(
            bookId = bookId,
            title = draft.title.trim(),
            chapters = selected.map { ChapterEntry(it.id, it.path) },
        )
        val staged = stageBook(bookId) { _, stageStore ->
            selected.forEach { chapter ->
                validateUtf8(chapter.bytes, chapter.path)
                stageStore.replaceDownloadedSource(bookId, chapter.path, chapter.bytes)
            }
            stageStore.writeManifest(bookId, manifest)
        }
        val manifestBytes = BookManifest.encode(manifest).encodeToByteArray()
        installStaged(bookId, staged, emptyList()) {
            installCheckpoint(LibraryInstallCheckpoint.SEARCH)
            search.rebuildBook(
                bookId,
                manifest.chapters.mapIndexed { index, chapter ->
                    SearchChapterSource(
                        chapter.id,
                        ChapterTitleExtractor.extract(chapter.path, selected[index].bytes).title,
                        selected[index].bytes,
                    )
                },
            )
            installCheckpoint(LibraryInstallCheckpoint.OUTBOX)
            sync.upsertOutbox(OutboxEntity(bookId, BookPaths.MANIFEST_NAME, manifestBytes.sha256(), null, OutboxState.PENDING))
            installCheckpoint(LibraryInstallCheckpoint.ROOT)
            books.upsertRoot(
                BookRootEntity(bookId, draft.remoteRootPath, paths.bookDirectory(bookId).absolutePath, currentTimeMillis()),
            )
            importDraftsDao.delete(bookId)
        }
        importRepository.removePromotedCache(bookId)
        scheduler.enqueue(bookId, draft.remoteRootPath, SyncTrigger.LOCAL_CHANGE)
        BookSummary(
            bookId,
            manifest.title,
            draft.remoteRootPath,
            manifest.chapters.mapIndexed { index, chapter ->
                BookChapter(chapter.id, ChapterTitleExtractor.extract(chapter.path, selected[index].bytes).title)
            },
        )
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

    override suspend fun add(bookId: String, path: String, title: String, position: Int) {
        if (!path.isOrdinaryMarkdown()) {
            throw BookLibraryUserError("Главой может быть только обычный файл Markdown из папки книги")
        }
        if (title.isBlank()) {
            throw BookLibraryUserError("Название главы не может быть пустым")
        }
        val root = requireNotNull(books.getRoot(bookId))
        val remoteRoot = requireNotNull(root.remoteRootPath)
        val bytes = gateway.download(childPath(remoteRoot, path)).bytes
        val manifest = store.readManifest(bookId)
        val proposal = discovery.propose(listOf(DiscoveryFile(path, bytes)), manifest).proposals.singleOrNull()
            ?: throw BookLibraryUserError("Выбранный файл Markdown уже добавлен в книгу")
        val updated = discovery.add(manifest, proposal, UUID.randomUUID().toString(), position)
        store.replaceDownloadedSource(bookId, path, bytes)
        persistManifestMutation(root, updated)
    }

    override suspend fun ignore(bookId: String, path: String) {
        val root = requireNotNull(books.getRoot(bookId))
        val manifest = discovery.ignore(store.readManifest(bookId), path)
        val revision = store.writeManifest(bookId, manifest)
        sync.upsertOutbox(OutboxEntity(bookId, BookPaths.MANIFEST_NAME, revision.sha256, sync.getMergeBase(bookId, BookPaths.MANIFEST_NAME)?.sha256, OutboxState.PENDING))
        root.remoteRootPath?.let { scheduler.enqueue(bookId, it, SyncTrigger.LOCAL_CHANGE) }
    }

    override suspend fun updatePath(bookId: String, chapterId: String, path: String, requireSameHash: Boolean) {
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
        store.replaceDownloadedSource(bookId, path, selected.bytes)
        persistManifestMutation(root, updated)
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

    override suspend fun removeChapter(bookId: String, chapterId: String) {
        val root = requireNotNull(books.getRoot(bookId))
        persistManifestMutation(root, discovery.remove(store.readManifest(bookId), chapterId))
    }

    override suspend fun forget(bookId: String) {
        val directory = paths.bookDirectory(bookId)
        require(directory.parentFile?.canonicalFile == paths.root.canonicalFile) { "Refusing to remove an unexpected cache path" }
        check(!directory.exists() || directory.deleteRecursively()) { "Could not remove the local book cache" }
        search.clearBook(bookId)
        drafts.deleteBook(bookId)
        sync.deletePendingDeletions(bookId)
        sync.deleteOutbox(bookId)
        sync.deleteMergeBases(bookId)
        sync.deleteRemoteRevisions(bookId)
        books.deleteReadingPosition(bookId)
        books.deleteRoot(bookId)
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

    private suspend fun persistManifestMutation(root: BookRootEntity, manifest: BookManifest) {
        val revision = store.writeManifest(root.bookId, manifest)
        sync.upsertOutbox(
            OutboxEntity(
                root.bookId,
                BookPaths.MANIFEST_NAME,
                revision.sha256,
                sync.getMergeBase(root.bookId, BookPaths.MANIFEST_NAME)?.sha256,
                OutboxState.PENDING,
            ),
        )
        search.rebuildBook(
            root.bookId,
            manifest.chapters.map { chapter ->
                store.readSource(root.bookId, chapter.path).let { source ->
                    SearchChapterSource(chapter.id, ChapterTitleExtractor.extract(chapter.path, source).title, source)
                }
            },
        )
        root.remoteRootPath?.let { scheduler.enqueue(root.bookId, it, SyncTrigger.LOCAL_CHANGE) }
    }

    private suspend fun stageBook(
        bookId: String,
        populate: suspend (BookPaths, AtomicBookStore) -> Unit,
    ): File {
        Files.createDirectories(paths.root.toPath())
        val stageRoot = File(paths.root, ".install-${UUID.randomUUID()}")
        val stagePaths = BookPaths(stageRoot)
        try {
            populate(stagePaths, AtomicBookStore(stagePaths))
            return stagePaths.bookDirectory(bookId)
        } catch (error: Throwable) {
            stageRoot.deleteRecursively()
            throw error
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
            if (databaseCommitted) throw error
            if (newMoved && finalBook.exists()) finalBook.deleteRecursively()
            if (oldMoved && backup.exists()) Files.move(backup.toPath(), finalBook.toPath(), ATOMIC_MOVE)
            stageRoot.deleteRecursively()
            if (backup.exists()) backup.deleteRecursively()
            deleteRepairJournal(bookId)
            installDirectorySync(paths.root)
            throw error
        }
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

    private suspend fun installStaged(
        bookId: String,
        stagedBook: File,
        trustedBases: List<Pair<String, net.inkyquill.pocketeditor.yandex.RemoteFile>>,
        commit: suspend () -> Unit,
    ) {
        val finalBook = paths.bookDirectory(bookId)
        require(stagedBook.parentFile?.parentFile?.canonicalFile == paths.root.canonicalFile)
        check(books.getRoot(bookId) == null) { "Registered books cannot enter the first-install protocol" }
        check(!finalBook.exists()) { "Existing cache cannot enter the first-install protocol" }
        val journalEntry = InstallJournalEntry(
            bookId = bookId,
            stageRootName = requireNotNull(stagedBook.parentFile).name,
            phase = InstallPhase.PREPARED,
        )
        var databaseCommitted = false
        try {
            installJournal.write(journalEntry)
            installPhaseObserver(InstallPhase.PREPARED)
            installJournal.write(journalEntry.copy(phase = InstallPhase.OLD_MOVED))
            installPhaseObserver(InstallPhase.OLD_MOVED)
            installJournal.moveIntoLibrary(stagedBook, finalBook)
            installMoveObserver()
            installJournal.write(journalEntry.copy(phase = InstallPhase.SWAPPED))
            installPhaseObserver(InstallPhase.SWAPPED)
            installCheckpoint(LibraryInstallCheckpoint.FILESYSTEM_SWAP)
            trustedBases.forEach { (relative, remote) -> baseStore.write(bookId, relative, remote.bytes, remote.revision) }
            transaction.run(commit)
            databaseCommitted = true
            installJournal.write(journalEntry.copy(phase = InstallPhase.DATABASE_COMMITTED))
            installPhaseObserver(InstallPhase.DATABASE_COMMITTED)
            installJournal.removeTree(requireNotNull(stagedBook.parentFile))
            installJournal.delete(bookId)
        } catch (error: Exception) {
            if (databaseCommitted) throw error
            installJournal.removeTree(finalBook)
            trustedBases.forEach { (relative, _) -> baseStore.delete(bookId, relative) }
            installJournal.removeTree(requireNotNull(stagedBook.parentFile))
            installJournal.delete(bookId)
            throw error
        }
    }

    private suspend fun registeredSummary(remoteRootPath: String, bookId: String? = null): BookSummary? {
        val normalized = remoteRootPath.normalizedRemotePath()
        val root = books.getRoots().firstOrNull { candidate ->
            candidate.remoteRootPath.orEmpty().normalizedRemotePath() == normalized ||
                (bookId != null && candidate.bookId == bookId)
        } ?: return null
        return runCatching { root.summaryFromCache() }.getOrNull()
    }

    private suspend fun BookRootEntity.summaryFromCache(): BookSummary {
        val manifest = store.readManifest(bookId)
        require(manifest.bookId == bookId) { "Book identity does not match its cache directory" }
        manifest.chapters.forEach { chapter ->
            validateUtf8(store.readSource(bookId, chapter.path), chapter.path)
        }
        return BookSummary(
            bookId,
            manifest.title,
            remoteRootPath.orEmpty(),
            manifest.chapters.map { chapter ->
                BookChapter(
                    chapter.id,
                    ChapterTitleExtractor.extract(chapter.path, store.readSource(bookId, chapter.path)).title,
                )
            },
            availableOffline = true,
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
                ChapterTitleExtractor.extract(chapter.path, store.readSource(bookId, chapter.path)).title,
            )
        },
        availableOffline,
    )

    private fun BookManifest.previewSummary(remoteRoot: String) = BookSummary(
        bookId,
        title,
        remoteRoot,
        // This unsynchronized pre-install probe has no chapter bytes. Installed summaries replace these fallbacks.
        chapters.map { chapter -> BookChapter(chapter.id, chapter.path.removeSuffix(".md")) },
        availableOffline = false,
    )

    private companion object {
        const val REPAIR_JOURNAL_PREFIX = ".repair-journal-"
        const val REPAIR_COMMIT_PREFIX = ".repair-commit-"
        const val KEY_LAST_BOOK = "last_book_id"
        const val KEY_DARK = "dark_theme"
        const val KEY_TEXT_SCALE = "reader_text_scale"
    }
}
