package net.inkyquill.pocketeditor.ui.books

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.inkyquill.pocketeditor.book.BookDiscovery
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
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
import net.inkyquill.pocketeditor.search.SearchChapterSource
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.storage.InstallPhase
import net.inkyquill.pocketeditor.storage.InstallJournalEntry
import net.inkyquill.pocketeditor.storage.InstallRecoveryJournal
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.storage.StrictUtf8
import net.inkyquill.pocketeditor.storage.PlatformDirectoryFsync
import net.inkyquill.pocketeditor.storage.DirectoryFsync
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.sync.SyncBaseStore
import net.inkyquill.pocketeditor.sync.SyncScheduler
import net.inkyquill.pocketeditor.sync.SyncTrigger
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway

fun interface LibraryTransaction {
    suspend fun run(block: suspend () -> Unit)
}

enum class LibraryInstallCheckpoint { FILESYSTEM_SWAP, METADATA, SEARCH, OUTBOX, ROOT }

class RoomYandexBookLibraryData(
    private val gateway: YandexDiskGateway,
    private val store: AtomicBookStore,
    private val paths: BookPaths,
    private val books: BookDao,
    private val sync: SyncDao,
    private val drafts: DraftDao,
    private val search: SourceSearch,
    private val scheduler: SyncScheduler,
    private val preferences: SharedPreferences,
    private val baseStore: SyncBaseStore,
    private val transaction: LibraryTransaction,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val installCheckpoint: (LibraryInstallCheckpoint) -> Unit = {},
    private val installPhaseObserver: (InstallPhase) -> Unit = {},
    private val installDirectorySync: (File) -> DirectorySyncStatus = PlatformDirectoryFsync::sync,
    private val installMoveObserver: () -> Unit = {},
) : BookLibraryData {
    private val discovery = BookDiscovery()
    private val installJournal = InstallRecoveryJournal(paths, books, DirectoryFsync(installDirectorySync))
    private val installMutex = Mutex()

    override suspend fun books(): List<BookSummary> = installMutex.withLock {
        installJournal.recover()
        books.getRoots().map { root ->
            runCatching { root.summaryFromCache() }.getOrElse { failure ->
                BookSummary(
                    root.bookId,
                    root.remoteRootPath?.substringAfterLast('/')?.ifBlank { "Recover book" } ?: "Recover book",
                    root.remoteRootPath.orEmpty(),
                    emptyList(),
                    availableOffline = false,
                    recoveryError = failure.message ?: "Local cache is incomplete",
                )
            }
        }
    }

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
        )
    }

    override suspend fun propose(path: String): ImportDraft {
        val entries = gateway.listFolder(path)
            .filter { it.type == "file" && it.name.isOrdinaryMarkdown() }
        val files = entries.map { entry ->
            val remote = gateway.download(entry.path)
            DiscoveryFile(entry.name, remote.bytes)
        }
        val proposals = discovery.propose(files).proposals
        require(proposals.isNotEmpty()) { "This folder contains no ordinary Markdown files" }
        return ImportDraft(
            remoteRootPath = path,
            title = path.trimEnd('/').substringAfterLast('/').ifBlank { "Untitled book" },
            chapters = proposals.map { ImportChapterDraft(it.path, it.suggestedTitle, included = true) },
        )
    }

    override suspend fun existingRoot(path: String): BookSummary? {
        val manifestEntry = gateway.listFolder(path).singleOrNull {
            it.type == "file" && it.name == BookPaths.MANIFEST_NAME
        } ?: return null
        val manifest = BookManifest.decode(StrictUtf8.decode(gateway.download(manifestEntry.path).bytes, "Book manifest"))
        require(manifest.chapters.isNotEmpty()) { "The existing book manifest has no chapters" }
        return manifest.summary(path, availableOffline = false)
    }

    override suspend fun installExisting(path: String): BookSummary = installMutex.withLock {
        installJournal.recover()
        registeredSummary(remoteRootPath = path)?.let { return@withLock it }
        val entries = gateway.listFolder(path)
        val manifestEntry = entries.singleOrNull { it.type == "file" && it.name == BookPaths.MANIFEST_NAME }
            ?: error("The existing book manifest is no longer available")
        val remoteManifest = gateway.download(manifestEntry.path)
        val manifest = BookManifest.decode(StrictUtf8.decode(remoteManifest.bytes, "Book manifest"))
        require(manifest.chapters.isNotEmpty()) { "The existing book manifest has no chapters" }
        registeredSummary(remoteRootPath = path, bookId = manifest.bookId)?.let { return@withLock it }
        val filesByName = entries.filter { it.type == "file" }.associateBy { it.name }
        val downloads = manifest.chapters.map { chapter ->
            require(chapter.path.isOrdinaryMarkdown()) { "Manifest chapter is not an ordinary Markdown file: ${chapter.path}" }
            val entry = filesByName[chapter.path] ?: error("Missing remote chapter: ${chapter.path}")
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
                downloads.map { (chapter, remote) -> SearchChapterSource(chapter.id, chapter.title, remote.bytes) },
            )
            installCheckpoint(LibraryInstallCheckpoint.ROOT)
            books.upsertRoot(BookRootEntity(manifest.bookId, path, paths.bookDirectory(manifest.bookId).absolutePath, currentTimeMillis()))
        }
        manifest.summary(path)
    }

    override suspend fun import(draft: ImportDraft): BookSummary = installMutex.withLock {
        installJournal.recover()
        registeredSummary(remoteRootPath = draft.remoteRootPath)?.let { return@withLock it }
        val selected = draft.chapters.filter(ImportChapterDraft::included)
        require(selected.isNotEmpty()) { "Include at least one chapter" }
        val downloads = selected.map { chapter ->
            val bytes = gateway.download(childPath(draft.remoteRootPath, chapter.path)).bytes
            validateUtf8(bytes, chapter.path)
            chapter to bytes
        }
        val bookId = UUID.randomUUID().toString()
        val manifest = BookManifest(
            bookId = bookId,
            title = draft.title.trim(),
            chapters = selected.map { ChapterEntry(UUID.randomUUID().toString(), it.path, it.title.trim()) },
        )
        val staged = stageBook(bookId) { _, stageStore ->
            downloads.forEach { (chapter, bytes) -> stageStore.replaceDownloadedSource(bookId, chapter.path, bytes) }
            stageStore.writeManifest(bookId, manifest)
        }
        val manifestBytes = BookManifest.encode(manifest).encodeToByteArray()
        installStaged(bookId, staged, emptyList()) {
            installCheckpoint(LibraryInstallCheckpoint.SEARCH)
            search.rebuildBook(
                bookId,
                manifest.chapters.mapIndexed { index, chapter ->
                    SearchChapterSource(chapter.id, chapter.title, downloads[index].second)
                },
            )
            installCheckpoint(LibraryInstallCheckpoint.OUTBOX)
            sync.upsertOutbox(OutboxEntity(bookId, BookPaths.MANIFEST_NAME, manifestBytes.sha256(), null, OutboxState.PENDING))
            installCheckpoint(LibraryInstallCheckpoint.ROOT)
            books.upsertRoot(
                BookRootEntity(bookId, draft.remoteRootPath, paths.bookDirectory(bookId).absolutePath, currentTimeMillis()),
            )
        }
        scheduler.enqueue(bookId, draft.remoteRootPath, SyncTrigger.LOCAL_CHANGE)
        BookSummary(
            bookId,
            manifest.title,
            draft.remoteRootPath,
            manifest.chapters.map { BookChapter(it.id, it.title) },
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

    override suspend fun opened(bookId: String) {
        val remoteRoot = books.getRoot(bookId)?.remoteRootPath ?: return
        scheduler.enqueue(bookId, remoteRoot, SyncTrigger.OPEN)
    }

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
                        missing.chapter.title,
                        missing.chapter.path,
                        missing.sameHashRenamePath,
                    ),
                )
            }
        }
    }

    override suspend fun add(bookId: String, path: String, title: String, position: Int) {
        require(path.isOrdinaryMarkdown()) { "Only ordinary direct-child Markdown files can be chapters" }
        require(title.isNotBlank()) { "Chapter title cannot be blank" }
        val root = requireNotNull(books.getRoot(bookId))
        val remoteRoot = requireNotNull(root.remoteRootPath)
        val bytes = gateway.download(childPath(remoteRoot, path)).bytes
        val manifest = store.readManifest(bookId)
        val proposal = discovery.propose(listOf(DiscoveryFile(path, bytes)), manifest).proposals.singleOrNull()
            ?: error("The selected Markdown file is already handled by this book")
        val updated = discovery.add(manifest, proposal, UUID.randomUUID().toString(), title, position)
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
        val selected = remoteFiles.singleOrNull { it.path == path } ?: error("The selected Markdown file is unavailable")
        if (requireSameHash) {
            val expectedHash = store.readSource(bookId, old.path).sha256()
            val result = discovery.propose(remoteFiles, manifest, mapOf(old.path to expectedHash))
            val exact = result.missing.singleOrNull { it.chapter.id == chapterId }?.sameHashRenamePath
            require(exact == path) { "The rename candidate no longer has the same content" }
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
                SearchChapterSource(chapter.id, chapter.title, store.readSource(root.bookId, chapter.path))
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
        return BookSummary(
            bookId,
            manifest.title,
            remoteRootPath.orEmpty(),
            manifest.chapters.map { BookChapter(it.id, it.title) },
            availableOffline = manifest.chapters.all { paths.source(bookId, it.path).isFile },
        )
    }

    private fun String.normalizedRemotePath() = trim().trimEnd('/')

    private fun validateUtf8(bytes: ByteArray, path: String): String = StrictUtf8.decode(bytes, path)

    private fun BookManifest.summary(remoteRoot: String, availableOffline: Boolean = true) = BookSummary(
        bookId,
        title,
        remoteRoot,
        chapters.map { BookChapter(it.id, it.title) },
        availableOffline,
    )

    private companion object {
        const val KEY_LAST_BOOK = "last_book_id"
        const val KEY_DARK = "dark_theme"
        const val KEY_TEXT_SCALE = "reader_text_scale"
    }
}
