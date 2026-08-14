package net.inkyquill.pocketeditor.ui.books

import java.util.UUID
import net.inkyquill.pocketeditor.book.BookDiscovery
import net.inkyquill.pocketeditor.book.ChapterTitleExtractor
import net.inkyquill.pocketeditor.book.DiscoveryFile
import net.inkyquill.pocketeditor.book.ImportDraftChapter
import net.inkyquill.pocketeditor.book.ImportDraftDocument
import net.inkyquill.pocketeditor.book.ImportDraftError
import net.inkyquill.pocketeditor.book.ImportDraftErrorCategory
import net.inkyquill.pocketeditor.book.ImportDraftPhase
import net.inkyquill.pocketeditor.database.ImportDraftDao
import net.inkyquill.pocketeditor.database.ImportDraftEntity
import net.inkyquill.pocketeditor.storage.ImportDraftStore
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway

class ImportDraftRepository(
    private val gateway: YandexDiskGateway,
    private val drafts: ImportDraftDao,
    private val store: ImportDraftStore,
    private val discovery: BookDiscovery = BookDiscovery(),
    private val bookIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val chapterIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun createOrResume(
        remoteRootPath: String,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportDraft {
        val root = remoteRootPath.normalizedRemoteRoot()
        val existing = drafts.getByRemoteRoot(root)?.document()
        if (existing?.phase == ImportDraftPhase.READY) return existing.toUi()
        var document = existing ?: ImportDraftDocument(
            bookId = bookIdFactory(),
            remoteRootPath = root,
            title = root.substringAfterLast('/').ifBlank { "Книга без названия" },
            phase = ImportDraftPhase.DOWNLOADING,
            chapters = emptyList(),
        ).also { persist(it) }

        document = document.copy(phase = ImportDraftPhase.DOWNLOADING, lastError = null)
        persist(document)
        try {
            val entries = gateway.listFolder(root)
                .filter { it.type == "file" && it.name.isOrdinaryMarkdownForImport() }
                .sortedBy { it.name.lowercase() }
            if (entries.isEmpty()) throw BookLibraryUserError("В этой папке нет обычных файлов Markdown")
            onProgress(ImportProgress(document.chapters.size.coerceAtMost(entries.size), entries.size, document.phase))

            val completed = document.chapters.associateBy(ImportDraftChapter::path).toMutableMap()
            val usedIds = completed.values.mapTo(mutableSetOf(), ImportDraftChapter::id)
            entries.forEachIndexed { index, entry ->
                val previous = completed[entry.name]
                val cached = previous?.takeIf { it.remoteRevision == entry.revision }?.let {
                    store.readMatchingSource(document.bookId, entry.name, entry.revision, it.sha256)
                }
                if (cached == null) {
                    val remote = gateway.download(entry.path)
                    val bytes = remote.bytes
                    val sha256 = bytes.sha256()
                    store.writeSource(document.bookId, entry.name, bytes, remote.revision)
                    val proposedTitle = discovery.propose(listOf(DiscoveryFile(entry.name, bytes)))
                        .proposals.single().suggestedTitle
                    val chapterId = previous?.id ?: uniqueChapterId(usedIds)
                    completed[entry.name] = ImportDraftChapter(
                        id = chapterId,
                        path = entry.name,
                        title = previous?.title ?: proposedTitle,
                        included = previous?.included ?: true,
                        remoteRevision = remote.revision,
                        sha256 = sha256,
                        byteSize = bytes.size.toLong(),
                    )
                }
                document = document.copy(
                    chapters = document.chapters
                        .filterNot { it.path == entry.name } + requireNotNull(completed[entry.name]),
                )
                persist(document)
                onProgress(ImportProgress(index + 1, entries.size, document.phase))
            }

            val remoteNames = entries.mapTo(linkedSetOf()) { it.name }
            val missing = document.chapters.filterNot { it.path in remoteNames }
            if (missing.isNotEmpty()) throw YandexDiskError.NotFound()
            val cachedFiles = entries.map { entry ->
                DiscoveryFile(entry.name, store.readSource(document.bookId, entry.name))
            }
            val naturalOrder = discovery.propose(cachedFiles).proposals.map { it.path }
            val previousOrder = existing?.chapters.orEmpty().map { it.path }.filter { it in remoteNames }
            val finalOrder = previousOrder + naturalOrder.filterNot { it in previousOrder }
            document = document.copy(
                phase = ImportDraftPhase.READY,
                chapters = finalOrder.map(completed::getValue),
                lastError = null,
            )
            persist(document)
            onProgress(ImportProgress(entries.size, entries.size, ImportDraftPhase.READY))
            return document.toUi()
        } catch (failure: Throwable) {
            document = document.copy(
                phase = ImportDraftPhase.FAILED,
                lastError = failure.toDraftError(),
            )
            persist(document)
            throw failure
        }
    }

    suspend fun all(): List<ImportDraftSummary> = drafts.getAll().map { entity ->
        val document = entity.document()
        ImportDraftSummary(
            bookId = document.bookId,
            remoteRootPath = document.remoteRootPath,
            title = document.title,
            downloadedChapters = document.chapters.size,
            phase = document.phase,
        )
    }

    suspend fun resume(bookId: String): ImportDraft =
        requireNotNull(drafts.getByBookId(bookId)) { "Import draft does not exist" }.document().toUi()

    suspend fun update(draft: ImportDraft) {
        val document = requireNotNull(drafts.getByBookId(draft.bookId)) { "Import draft does not exist" }.document()
        val storedByPath = document.chapters.associateBy(ImportDraftChapter::path)
        require(draft.chapters.map(ImportChapterDraft::path).toSet() == storedByPath.keys) {
            "Edited draft chapters must match cached chapters"
        }
        persist(
            document.copy(
                title = draft.title,
                chapters = draft.chapters.map { edited ->
                    storedByPath.getValue(edited.path).copy(
                        title = edited.title,
                        included = edited.included,
                    )
                },
            ),
        )
    }

    suspend fun discard(bookId: String) {
        requireNotNull(drafts.getByBookId(bookId)) { "Import draft does not exist" }
        store.delete(bookId)
        drafts.delete(bookId)
    }

    internal suspend fun cachedChapters(bookId: String): List<CachedImportChapter> {
        val document = requireNotNull(drafts.getByBookId(bookId)) { "Import draft does not exist" }.document()
        require(document.phase == ImportDraftPhase.READY) { "Import draft is not fully cached" }
        return document.chapters.map { chapter ->
            val bytes = checkNotNull(
                store.readMatchingSource(
                    document.bookId,
                    chapter.path,
                    chapter.remoteRevision,
                    chapter.sha256,
                ),
            ) { "Cached chapter no longer matches its durable metadata" }
            CachedImportChapter(
                id = chapter.id,
                path = chapter.path,
                title = ChapterTitleExtractor.extract(chapter.path, bytes).title,
                included = chapter.included,
                bytes = bytes,
            )
        }
    }

    internal suspend fun removePromotedCache(bookId: String) {
        store.delete(bookId)
    }

    private suspend fun persist(document: ImportDraftDocument) {
        drafts.upsert(
            ImportDraftEntity(
                bookId = document.bookId,
                remoteRootPath = document.remoteRootPath,
                localDirectory = store.directory(document.bookId).absolutePath,
                documentJson = ImportDraftDocument.encode(document),
                updatedAt = currentTimeMillis(),
            ),
        )
    }

    private fun uniqueChapterId(usedIds: MutableSet<String>): String {
        var candidate: String
        do {
            candidate = chapterIdFactory()
        } while (!usedIds.add(candidate))
        return candidate
    }
}

internal data class CachedImportChapter(
    val id: String,
    val path: String,
    val title: String,
    val included: Boolean,
    val bytes: ByteArray,
)

private fun ImportDraftEntity.document(): ImportDraftDocument =
    ImportDraftDocument.decode(documentJson).also { document ->
        require(document.bookId == bookId)
        require(document.remoteRootPath == remoteRootPath)
    }

private fun ImportDraftDocument.toUi() = ImportDraft(
    remoteRootPath = remoteRootPath,
    title = title,
    chapters = chapters.map { ImportChapterDraft(it.path, it.title, it.included) },
    bookId = bookId,
    phase = phase,
)

private fun String.normalizedRemoteRoot(): String {
    val normalized = trim()
    require(normalized.startsWith("disk:/")) { "Remote root must be an absolute Yandex Disk path" }
    return if (normalized == "disk:/") normalized else normalized.trimEnd('/')
}

private fun String.isOrdinaryMarkdownForImport(): Boolean =
    endsWith(".md", ignoreCase = true) &&
        !startsWith('.') &&
        '/' !in this &&
        '\\' !in this

private fun Throwable.toDraftError(): ImportDraftError = when (this) {
    is YandexDiskError.Offline -> ImportDraftError(ImportDraftErrorCategory.OFFLINE, retryable = true)
    is YandexDiskError.Unauthorized -> ImportDraftError(ImportDraftErrorCategory.UNAUTHORIZED, retryable = false)
    is YandexDiskError.NotFound -> ImportDraftError(ImportDraftErrorCategory.NOT_FOUND, retryable = true)
    is YandexDiskError.RateLimited -> ImportDraftError(ImportDraftErrorCategory.RATE_LIMITED, retryable = true)
    is YandexDiskError.ServerFailure -> ImportDraftError(ImportDraftErrorCategory.SERVER, retryable = true)
    else -> ImportDraftError(ImportDraftErrorCategory.UNKNOWN, retryable = true)
}
