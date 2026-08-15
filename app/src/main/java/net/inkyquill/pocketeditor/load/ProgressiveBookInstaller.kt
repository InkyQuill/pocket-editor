package net.inkyquill.pocketeditor.load

import java.io.File
import java.nio.file.Files
import java.util.UUID
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterTitleExtractor
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.ProgressiveLoadDao
import net.inkyquill.pocketeditor.database.ProgressiveLoadJobEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.SyncDao
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.InstallJournalEntry
import net.inkyquill.pocketeditor.storage.InstallPhase
import net.inkyquill.pocketeditor.storage.InstallRecoveryJournal
import net.inkyquill.pocketeditor.storage.StrictUtf8
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.sync.SyncBaseStore
import net.inkyquill.pocketeditor.ui.books.LibraryInstallCheckpoint
import net.inkyquill.pocketeditor.ui.books.LibraryTransaction

class ProgressiveBookInstaller(
    private val paths: BookPaths,
    private val store: AtomicBookStore,
    private val books: BookDao,
    private val sync: SyncDao,
    private val loads: ProgressiveLoadDao,
    private val search: SourceSearch,
    private val baseStore: SyncBaseStore,
    private val transaction: LibraryTransaction,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val checkpoint: (LibraryInstallCheckpoint) -> Unit = {},
) : ProgressiveSeedInstaller {
    private val journal = InstallRecoveryJournal(paths, books)

    suspend fun install(seed: ProgressiveBookSeed): ProgressiveLoadSnapshot = install(seed, emptyMap())

    override suspend fun install(
        seed: ProgressiveBookSeed,
        cachedSources: Map<String, ByteArray>,
    ): ProgressiveLoadSnapshot {
        validateProgressiveSeed(seed)
        val bookId = seed.manifest.bookId
        require(cachedSources.keys.all { path -> seed.files.any { it.path == path } })
        cachedSources.forEach { (path, bytes) -> StrictUtf8.decode(bytes, "Chapter $path") }

        Files.createDirectories(paths.root.toPath())
        val stageRoot = File(paths.root, ".install-${UUID.randomUUID()}")
        val stagePaths = BookPaths(stageRoot)
        val stageStore = AtomicBookStore(stagePaths)
        try {
            if (seed.rawBinder) {
                stageStore.writeManifest(bookId, seed.manifest)
            } else {
                val remote = requireNotNull(seed.remoteManifest)
                require(BookManifest.decode(StrictUtf8.decode(remote.bytes, "Book manifest")) == seed.manifest)
                stageStore.replaceDownloadedManifest(bookId, remote.bytes)
            }
            cachedSources.forEach { (path, bytes) -> stageStore.replaceDownloadedSource(bookId, path, bytes) }
        } catch (failure: Throwable) {
            stageRoot.deleteRecursively()
            throw failure
        }

        val stagedBook = stagePaths.bookDirectory(bookId)
        val finalBook = paths.bookDirectory(bookId)
        check(books.getRoot(bookId) == null)
        check(!finalBook.exists())
        val entry = InstallJournalEntry(bookId, stageRoot.name, InstallPhase.PREPARED)
        var databaseCommitted = false
        var manifestBaseWritten = false
        try {
            journal.write(entry)
            journal.write(entry.copy(phase = InstallPhase.OLD_MOVED))
            journal.moveIntoLibrary(stagedBook, finalBook)
            journal.write(entry.copy(phase = InstallPhase.SWAPPED))
            checkpoint(LibraryInstallCheckpoint.FILESYSTEM_SWAP)

            val remoteManifest = seed.remoteManifest
            if (!seed.rawBinder && remoteManifest != null) {
                baseStore.write(bookId, BookPaths.MANIFEST_NAME, remoteManifest.bytes, remoteManifest.revision)
                manifestBaseWritten = true
            }
            val now = currentTimeMillis()
            val cachedCount = cachedSources.size
            transaction.run {
                books.upsertRoot(BookRootEntity(bookId, seed.remoteRootPath, finalBook.absolutePath, now))
                loads.insertJob(
                    ProgressiveLoadJobEntity(
                        bookId,
                        seed.remoteRootPath,
                        when {
                            cachedCount == seed.files.size -> ProgressiveLoadPhase.COMPLETE
                            seed.files.sortedBy { it.spineIndex }.take(minOf(3, seed.files.size))
                                .all { it.path in cachedSources } -> ProgressiveLoadPhase.BACKGROUND
                            else -> ProgressiveLoadPhase.INITIAL
                        },
                        seed.files.size,
                        cachedCount,
                        null,
                        0,
                        null,
                        0,
                        paused = false,
                        cancelled = false,
                        lastErrorCategory = null,
                    ),
                )
                loads.insertFiles(seed.files.map { row ->
                    row.copy(
                        state = if (row.path in cachedSources) ProgressiveLoadFileState.CACHED else ProgressiveLoadFileState.PENDING,
                        sha256 = cachedSources[row.path]?.sha256(),
                    )
                })
                seed.files.forEach { row ->
                    cachedSources[row.path]?.let { bytes ->
                        sync.upsertRemoteRevision(
                            RemoteRevisionEntity(bookId, row.path, row.expectedRevision, bytes.sha256()),
                        )
                    }
                }
                if (seed.rawBinder) {
                    val localBytes = BookManifest.encode(seed.manifest).encodeToByteArray()
                    sync.upsertOutbox(
                        OutboxEntity(bookId, BookPaths.MANIFEST_NAME, localBytes.sha256(), null, OutboxState.PENDING),
                    )
                } else {
                    requireNotNull(remoteManifest)
                    val hash = remoteManifest.bytes.sha256()
                    sync.upsertRemoteRevision(
                        RemoteRevisionEntity(bookId, BookPaths.MANIFEST_NAME, remoteManifest.revision, hash),
                    )
                    sync.upsertMergeBase(
                        MergeBaseEntity(bookId, BookPaths.MANIFEST_NAME, hash, remoteManifest.revision),
                    )
                }
                seed.manifest.chapters.forEach { chapter ->
                    cachedSources[chapter.path]?.let { bytes ->
                        search.replaceChapter(bookId, chapter.id, ChapterTitleExtractor.extract(chapter.path, bytes).title, bytes)
                    }
                }
            }
            databaseCommitted = true
            checkpoint(LibraryInstallCheckpoint.ROOT)
            journal.write(entry.copy(phase = InstallPhase.DATABASE_COMMITTED))
            journal.removeTree(stageRoot)
            journal.delete(bookId)
            return requireNotNull(loads.snapshot(bookId))
        } catch (failure: Throwable) {
            // An Error injected at a checkpoint models abrupt process death: leave the
            // journaled state intact so startup recovery exercises the real crash path.
            if (!databaseCommitted && failure !is Error) {
                journal.removeTree(finalBook)
                journal.removeTree(stageRoot)
                journal.delete(bookId)
                if (manifestBaseWritten) baseStore.delete(bookId, BookPaths.MANIFEST_NAME)
            }
            throw failure
        }
    }
}

internal fun validateProgressiveSeed(seed: ProgressiveBookSeed) {
    val orderedRows = seed.files.sortedBy { it.spineIndex }
    require(orderedRows.size == seed.manifest.chapters.size) { "Seed rows must match every manifest chapter" }
    require(orderedRows.map { it.spineIndex } == orderedRows.indices.toList()) { "Seed spine indices must be unique and contiguous" }
    require(orderedRows.map { it.path }.distinct().size == orderedRows.size) { "Seed paths must be unique" }
    require(orderedRows.map { it.chapterId }.distinct().size == orderedRows.size) { "Seed chapter IDs must be unique" }
    orderedRows.zip(seed.manifest.chapters).forEach { (row, chapter) ->
        require(row.bookId == seed.manifest.bookId) { "Seed row book ID must match manifest" }
        require(row.path == chapter.path && row.chapterId == chapter.id) { "Seed row must match manifest order and identity" }
        require(
            row.remoteName.isNotEmpty() && row.remoteName != "." && row.remoteName != ".." &&
                '/' !in row.remoteName && '\\' !in row.remoteName && '\u0000' !in row.remoteName,
        ) {
            "Seed remote name must be a direct child"
        }
    }
}
