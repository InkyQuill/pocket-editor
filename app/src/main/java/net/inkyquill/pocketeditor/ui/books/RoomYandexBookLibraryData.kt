package net.inkyquill.pocketeditor.ui.books

import android.content.SharedPreferences
import java.io.File
import java.util.UUID
import net.inkyquill.pocketeditor.book.BookDiscovery
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.book.DiscoveryFile
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.database.SyncDao
import net.inkyquill.pocketeditor.database.DraftDao
import net.inkyquill.pocketeditor.search.SearchChapterSource
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.sync.SyncScheduler
import net.inkyquill.pocketeditor.sync.SyncTrigger
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway

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
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : BookLibraryData {
    private val discovery = BookDiscovery()

    override suspend fun books(): List<BookSummary> = books.getRoots().mapNotNull { root ->
        runCatching {
            val manifest = store.readManifest(root.bookId)
            BookSummary(
                root.bookId,
                manifest.title,
                root.remoteRootPath.orEmpty(),
                manifest.chapters.map { BookChapter(it.id, it.title) },
                availableOffline = manifest.chapters.all { paths.source(root.bookId, it.path).isFile },
            )
        }.getOrNull()
    }

    override suspend fun resumeLocation(): ResumeLocation? {
        val bookId = preferences.getString(KEY_LAST_BOOK, null) ?: return null
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
        val manifest = BookManifest.decode(gateway.download(manifestEntry.path).bytes.decodeToString())
        require(manifest.chapters.isNotEmpty()) { "The existing book manifest has no chapters" }
        return manifest.summary(path, availableOffline = false)
    }

    override suspend fun installExisting(path: String): BookSummary {
        val entries = gateway.listFolder(path)
        val manifestEntry = entries.singleOrNull { it.type == "file" && it.name == BookPaths.MANIFEST_NAME }
            ?: error("The existing book manifest is no longer available")
        val remoteManifest = gateway.download(manifestEntry.path)
        val manifest = BookManifest.decode(remoteManifest.bytes.decodeToString())
        require(manifest.chapters.isNotEmpty()) { "The existing book manifest has no chapters" }
        val filesByName = entries.filter { it.type == "file" }.associateBy { it.name }
        val downloads = manifest.chapters.map { chapter ->
            require(chapter.path.isOrdinaryMarkdown()) { "Manifest chapter is not an ordinary Markdown file: ${chapter.path}" }
            val entry = filesByName[chapter.path] ?: error("Missing remote chapter: ${chapter.path}")
            chapter to gateway.download(entry.path).bytes
        }

        // Nothing local becomes visible until the remote manifest and every source have validated and downloaded.
        downloads.forEach { (chapter, bytes) -> store.replaceDownloadedSource(manifest.bookId, chapter.path, bytes) }
        store.writeManifest(manifest.bookId, manifest)
        books.upsertRoot(BookRootEntity(manifest.bookId, path, paths.bookDirectory(manifest.bookId).absolutePath, currentTimeMillis()))
        search.rebuildBook(
            manifest.bookId,
            downloads.map { (chapter, bytes) -> SearchChapterSource(chapter.id, chapter.title, bytes) },
        )
        return manifest.summary(path)
    }

    override suspend fun import(draft: ImportDraft): BookSummary {
        val selected = draft.chapters.filter(ImportChapterDraft::included)
        require(selected.isNotEmpty()) { "Include at least one chapter" }
        val downloads = selected.map { chapter ->
            chapter to gateway.download(childPath(draft.remoteRootPath, chapter.path)).bytes
        }
        val bookId = UUID.randomUUID().toString()
        val manifest = BookManifest(
            bookId = bookId,
            title = draft.title.trim(),
            chapters = selected.map { ChapterEntry(UUID.randomUUID().toString(), it.path, it.title.trim()) },
        )
        downloads.forEach { (chapter, bytes) -> store.replaceDownloadedSource(bookId, chapter.path, bytes) }
        val manifestRevision = store.writeManifest(bookId, manifest)
        books.upsertRoot(
            BookRootEntity(bookId, draft.remoteRootPath, paths.bookDirectory(bookId).absolutePath, currentTimeMillis()),
        )
        sync.upsertOutbox(OutboxEntity(bookId, BookPaths.MANIFEST_NAME, manifestRevision.sha256, null, OutboxState.PENDING))
        search.rebuildBook(
            bookId,
            manifest.chapters.mapIndexed { index, chapter ->
                SearchChapterSource(chapter.id, chapter.title, downloads[index].second)
            },
        )
        scheduler.enqueue(bookId, draft.remoteRootPath, SyncTrigger.LOCAL_CHANGE)
        return BookSummary(
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
