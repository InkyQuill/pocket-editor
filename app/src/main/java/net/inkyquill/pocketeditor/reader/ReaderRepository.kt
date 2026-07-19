package net.inkyquill.pocketeditor.reader

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.anchor.AnchorResolver
import net.inkyquill.pocketeditor.anchor.Resolved
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.EditValidator
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.storage.LocalRevision
import net.inkyquill.pocketeditor.sync.SyncMetadataStore
import net.inkyquill.pocketeditor.sync.SyncScheduler
import net.inkyquill.pocketeditor.sync.SyncStatus
import net.inkyquill.pocketeditor.sync.SyncTrigger

interface ReaderBookStore {
    fun observeReadingPosition(bookId: String): Flow<ReadingPositionEntity?>
    suspend fun saveReadingPosition(position: ReadingPositionEntity)
    suspend fun root(bookId: String): BookRootEntity?
}

class RoomReaderBookStore(private val dao: BookDao) : ReaderBookStore {
    override fun observeReadingPosition(bookId: String): Flow<ReadingPositionEntity?> =
        dao.observeReadingPosition(bookId)

    override suspend fun saveReadingPosition(position: ReadingPositionEntity) = dao.upsertReadingPosition(position)

    override suspend fun root(bookId: String): BookRootEntity? = dao.getRoot(bookId)
}

fun interface ReaderSyncScheduler {
    fun enqueue(bookId: String, remoteRootPath: String, trigger: SyncTrigger)
}

class DefaultReaderSyncScheduler(private val scheduler: SyncScheduler) : ReaderSyncScheduler {
    override fun enqueue(bookId: String, remoteRootPath: String, trigger: SyncTrigger) =
        scheduler.enqueue(bookId, remoteRootPath, trigger)
}

class PendingDeletion internal constructor(
    internal val bookId: String,
    internal val chapterId: String,
    internal val sourcePath: String,
    internal val previous: ReviewDocument,
    internal val deletedRevision: LocalRevision,
)

class ReaderRepository(
    private val bookStore: BookStore,
    private val books: ReaderBookStore,
    private val metadata: SyncMetadataStore,
    private val scheduler: ReaderSyncScheduler,
    private val syncStatus: (String) -> Flow<SyncStatus>,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val invalidations = MutableStateFlow(0L)

    fun observeChapter(bookId: String, chapterId: String, reviewEnabled: Boolean): Flow<ReaderState> =
        combine(
            invalidations,
            books.observeReadingPosition(bookId),
            syncStatus(bookId),
        ) { _, position, status ->
            loadState(bookId, chapterId, reviewEnabled, position, status)
        }

    suspend fun chapterAtOffset(bookId: String, chapterId: String, offset: Int): ReaderChapter? {
        val chapters = bookStore.readManifest(bookId).chapters
        val index = chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) return null
        return chapters.getOrNull(index + offset)?.asReaderChapter()
    }

    suspend fun saveChapterNote(bookId: String, chapterId: String, text: String) {
        mutateAndEnqueue(bookId, chapterId) { it.copy(chapterNote = text) }
    }

    suspend fun saveSignal(bookId: String, chapterId: String, signal: Signal) {
        mutateAndEnqueue(bookId, chapterId) { review ->
            val signals = review.signals.filterNot { it.id == signal.id } + signal
            review.copy(signals = signals.sortedBy(Signal::id))
        }
    }

    suspend fun saveEdit(bookId: String, chapterId: String, edit: Edit) {
        val chapter = chapter(bookId, chapterId)
        val source = bookStore.readSource(bookId, chapter.path)
        mutateAndEnqueue(bookId, chapterId) { review ->
            val otherEdits = review.edits.filterNot { it.id == edit.id }
            EditValidator.validate(edit, otherEdits, source)
            review.copy(edits = (otherEdits + edit).sortedBy(Edit::id))
        }
    }

    suspend fun deleteSignal(bookId: String, chapterId: String, signalId: String): PendingDeletion =
        deleteLocally(bookId, chapterId) { review ->
            require(review.signals.any { it.id == signalId }) { "Unknown signal: $signalId" }
            review.copy(signals = review.signals.filterNot { it.id == signalId })
        }

    suspend fun deleteEdit(bookId: String, chapterId: String, editId: String): PendingDeletion =
        deleteLocally(bookId, chapterId) { review ->
            require(review.edits.any { it.id == editId }) { "Unknown edit: $editId" }
            review.copy(edits = review.edits.filterNot { it.id == editId })
        }

    suspend fun commitDeletion(deletion: PendingDeletion) {
        val path = deletion.sourcePath + BookPaths.REVIEW_SUFFIX
        val current = requireNotNull(bookStore.readReview(deletion.bookId, path))
        require(sha256(ReviewJson.encode(current).encodeToByteArray()) == deletion.deletedRevision.sha256) {
            "Review changed after deletion; refusing to enqueue a stale delete"
        }
        enqueueRevision(deletion.bookId, path, deletion.deletedRevision)
    }

    suspend fun undoDeletion(deletion: PendingDeletion) {
        bookStore.writeReview(
            deletion.bookId,
            deletion.sourcePath + BookPaths.REVIEW_SUFFIX,
            deletion.previous,
        )
        invalidations.update(Long::inc)
    }

    suspend fun reanchorSignal(bookId: String, chapterId: String, signalId: String, anchor: Anchor) {
        val chapter = chapter(bookId, chapterId)
        val source = bookStore.readSource(bookId, chapter.path)
        mutateAndEnqueue(bookId, chapterId) { review ->
            val signal = review.signals.singleOrNull { it.id == signalId }
                ?: throw IllegalArgumentException("Unknown signal: $signalId")
            require(AnchorResolver.resolve(source, anchor, signal.selectedText) is Resolved) {
                "Signal anchor does not resolve against canonical source"
            }
            review.copy(signals = review.signals.map { if (it.id == signalId) signal.copy(anchor = anchor) else it })
        }
    }

    suspend fun reanchorEdit(bookId: String, chapterId: String, editId: String, anchor: Anchor) {
        val chapter = chapter(bookId, chapterId)
        val source = bookStore.readSource(bookId, chapter.path)
        mutateAndEnqueue(bookId, chapterId) { review ->
            val edit = review.edits.singleOrNull { it.id == editId }
                ?: throw IllegalArgumentException("Unknown edit: $editId")
            val reanchored = edit.copy(anchor = anchor)
            EditValidator.validate(reanchored, review.edits.filterNot { it.id == editId }, source)
            review.copy(edits = review.edits.map { if (it.id == editId) reanchored else it })
        }
    }

    suspend fun saveReadingPosition(bookId: String, chapterId: String, blockIndex: Int, byteOffset: Int) {
        require(blockIndex >= 0 && byteOffset >= 0)
        books.saveReadingPosition(
            ReadingPositionEntity(bookId, chapterId, blockIndex, byteOffset, currentTimeMillis()),
        )
    }

    suspend fun syncNow(bookId: String) {
        schedule(bookId, SyncTrigger.SYNC_NOW)
    }

    private suspend fun loadState(
        bookId: String,
        chapterId: String,
        reviewEnabled: Boolean,
        position: ReadingPositionEntity?,
        status: SyncStatus,
    ): ReaderState {
        val manifest = bookStore.readManifest(bookId)
        val index = manifest.chapters.indexOfFirst { it.id == chapterId }
        require(index >= 0) { "Unknown chapter: $chapterId" }
        val chapter = manifest.chapters[index]
        val source = bookStore.readSource(bookId, chapter.path).decodeToString()
        val rendered = MarkdownParser.parse(source)
        val review = if (reviewEnabled) {
            bookStore.readReview(bookId, chapter.path + BookPaths.REVIEW_SUFFIX)
        } else {
            null
        }
        return ReaderState(
            bookId = bookId,
            chapterId = chapterId,
            title = chapter.title,
            document = ReviewProjector.project(rendered, review, reviewEnabled),
            reviewEnabled = reviewEnabled,
            chapterNote = review?.chapterNote,
            reviewItems = review?.let { document ->
                ReaderReviewItems(
                    signals = document.signals.map { signal ->
                        ReaderSignalItem(signal.id, signal.type, signal.selectedText, signal.comment)
                    },
                    edits = document.edits.map { edit -> ReaderEditItem(edit.id, edit.before, edit.after) },
                )
            },
            previousChapter = manifest.chapters.getOrNull(index - 1)?.asReaderChapter(),
            nextChapter = manifest.chapters.getOrNull(index + 1)?.asReaderChapter(),
            readingPosition = position?.takeIf { it.chapterId == chapterId },
            syncStatus = status,
        )
    }

    private suspend fun mutateAndEnqueue(
        bookId: String,
        chapterId: String,
        transform: (ReviewDocument) -> ReviewDocument,
    ) {
        val chapter = chapter(bookId, chapterId)
        val path = chapter.path + BookPaths.REVIEW_SUFFIX
        val current = bookStore.readReview(bookId, path) ?: ReviewDocument(chapterId = chapter.id, sourcePath = chapter.path)
        val revision = bookStore.writeReview(bookId, path, transform(current))
        invalidations.update(Long::inc)
        enqueueRevision(bookId, path, revision)
    }

    private suspend fun deleteLocally(
        bookId: String,
        chapterId: String,
        transform: (ReviewDocument) -> ReviewDocument,
    ): PendingDeletion {
        val chapter = chapter(bookId, chapterId)
        val path = chapter.path + BookPaths.REVIEW_SUFFIX
        val current = requireNotNull(bookStore.readReview(bookId, path)) { "No review exists for chapter" }
        val revision = bookStore.writeReview(bookId, path, transform(current))
        invalidations.update(Long::inc)
        return PendingDeletion(bookId, chapterId, chapter.path, current, revision)
    }

    private suspend fun enqueueRevision(bookId: String, path: String, revision: LocalRevision) {
        val base = metadata.mergeBase(bookId, path)
        metadata.recordOutbox(
            OutboxEntity(
                bookId = bookId,
                path = path,
                localSha256 = revision.sha256,
                baseSha256 = base?.sha256,
                state = OutboxState.PENDING,
            ),
        )
        schedule(bookId, SyncTrigger.LOCAL_CHANGE)
    }

    private suspend fun schedule(bookId: String, trigger: SyncTrigger) {
        val remoteRoot = books.root(bookId)?.remoteRootPath ?: return
        scheduler.enqueue(bookId, remoteRoot, trigger)
    }

    private suspend fun chapter(bookId: String, chapterId: String): ChapterEntry =
        bookStore.readManifest(bookId).chapters.singleOrNull { it.id == chapterId }
            ?: throw IllegalArgumentException("Unknown chapter: $chapterId")

    private fun ChapterEntry.asReaderChapter() = ReaderChapter(id, path, title)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
