package net.inkyquill.pocketeditor.reader

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import net.inkyquill.pocketeditor.anchor.AnchorResolver
import net.inkyquill.pocketeditor.anchor.Resolved
import net.inkyquill.pocketeditor.book.ChapterEntry
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
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
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
    override fun observeReadingPosition(bookId: String): Flow<ReadingPositionEntity?> = dao.observeReadingPosition(bookId)
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

internal sealed interface DeletedRecord {
    val id: String
    data class SignalRecord(val value: Signal) : DeletedRecord { override val id: String = value.id }
    data class EditRecord(val value: Edit) : DeletedRecord { override val id: String = value.id }
}

class PendingDeletion internal constructor(
    internal val bookId: String,
    internal val chapterId: String,
    internal val sourcePath: String,
    private val record: DeletedRecord,
) {
    private enum class State { ACTIVE, IN_PROGRESS, CONSUMED }
    private val state = AtomicReference(State.ACTIVE)
    internal fun beginUndo(): Boolean = state.compareAndSet(State.ACTIVE, State.IN_PROGRESS)
    internal fun completeUndo() = check(state.compareAndSet(State.IN_PROGRESS, State.CONSUMED))
    internal fun retryUndo() = check(state.compareAndSet(State.IN_PROGRESS, State.ACTIVE))
    internal fun finalize(): Boolean = state.compareAndSet(State.ACTIVE, State.CONSUMED)
    internal fun deletedRecord(): DeletedRecord = record
}

class ReaderRepository(
    private val bookStore: BookStore,
    private val books: ReaderBookStore,
    private val metadata: SyncMetadataStore,
    private val scheduler: ReaderSyncScheduler,
    private val syncStatus: (String) -> Flow<SyncStatus>,
    private val mutations: ReviewMutationCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private data class ContentKey(val bookId: String, val chapterId: String, val reviewEnabled: Boolean)
    private data class ReaderContent(
        val bookId: String,
        val chapterId: String,
        val title: String,
        val document: ReaderDocument,
        val reviewEnabled: Boolean,
        val chapterNote: String?,
        val reviewItems: ReaderReviewItems?,
        val previousChapter: ReaderChapter?,
        val nextChapter: ReaderChapter?,
    )

    private val invalidations = MutableStateFlow<Map<ContentKey, Long>>(emptyMap())

    fun observeChapter(bookId: String, chapterId: String, reviewEnabled: Boolean): Flow<ReaderState> {
        val key = ContentKey(bookId, chapterId, reviewEnabled)
        val content = invalidations
            .map { it[key] ?: 0L }
            .distinctUntilChanged()
            .map { withContext(ioDispatcher) { loadContent(bookId, chapterId, reviewEnabled) } }
        return combine(content, books.observeReadingPosition(bookId), syncStatus(bookId)) { loaded, position, status ->
            loaded.toState(position, status)
        }
    }

    suspend fun chapterAtOffset(bookId: String, chapterId: String, offset: Int): ReaderChapter? = withContext(ioDispatcher) {
        val chapters = bookStore.readManifest(bookId).chapters
        val index = chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) null else chapters.getOrNull(index + offset)?.asReaderChapter()
    }

    suspend fun saveChapterNote(bookId: String, chapterId: String, text: String) =
        mutateAndEnqueue(bookId, chapterId) { it.copy(chapterNote = text) }

    suspend fun saveSignal(bookId: String, chapterId: String, signal: Signal) = withContext(ioDispatcher) {
        val chapter = chapter(bookId, chapterId)
        val source = bookStore.readSource(bookId, chapter.path)
        validateSignal(signal, source)
        mutateAndEnqueue(bookId, chapterId) { review ->
            review.copy(signals = (review.signals.filterNot { it.id == signal.id } + signal).sortedBy(Signal::id))
        }
    }

    suspend fun saveEdit(bookId: String, chapterId: String, edit: Edit) = withContext(ioDispatcher) {
        val chapter = chapter(bookId, chapterId)
        val source = bookStore.readSource(bookId, chapter.path)
        mutateAndEnqueue(bookId, chapterId) { review ->
            val others = review.edits.filterNot { it.id == edit.id }
            EditValidator.validate(edit, others, source)
            review.copy(edits = (others + edit).sortedBy(Edit::id))
        }
    }

    suspend fun deleteSignal(bookId: String, chapterId: String, signalId: String): PendingDeletion =
        deleteRecord(bookId, chapterId) { review ->
            val value = review.signals.singleOrNull { it.id == signalId }
                ?: throw IllegalArgumentException("Unknown signal: $signalId")
            DeletedRecord.SignalRecord(value) to review.copy(signals = review.signals.filterNot { it.id == signalId })
        }

    suspend fun deleteEdit(bookId: String, chapterId: String, editId: String): PendingDeletion =
        deleteRecord(bookId, chapterId) { review ->
            val value = review.edits.singleOrNull { it.id == editId }
                ?: throw IllegalArgumentException("Unknown edit: $editId")
            DeletedRecord.EditRecord(value) to review.copy(edits = review.edits.filterNot { it.id == editId })
        }

    fun finalizeDeletion(deletion: PendingDeletion) {
        check(deletion.finalize()) { "Deletion token was already consumed" }
    }

    fun commitDeletion(deletion: PendingDeletion) = finalizeDeletion(deletion)

    suspend fun undoDeletion(deletion: PendingDeletion) = withContext(ioDispatcher) {
        check(deletion.beginUndo()) { "Deletion token was already consumed" }
        try {
            val path = deletion.sourcePath + BookPaths.REVIEW_SUFFIX
            mutations.withReview(deletion.bookId, path) {
                val current = requireNotNull(bookStore.readReview(deletion.bookId, path))
                val restored = when (val deleted = deletion.deletedRecord()) {
                    is DeletedRecord.SignalRecord -> {
                        check(current.signals.none { it.id == deleted.id }) { "Signal ID was reused after deletion" }
                        current.copy(signals = (current.signals + deleted.value).sortedBy(Signal::id))
                    }
                    is DeletedRecord.EditRecord -> {
                        check(current.edits.none { it.id == deleted.id }) { "Edit ID was reused after deletion" }
                        current.copy(edits = (current.edits + deleted.value).sortedBy(Edit::id))
                    }
                }
                persistMutation(deletion.bookId, path, current, restored)
            }
            invalidate(deletion.bookId, deletion.chapterId)
            deletion.completeUndo()
        } catch (error: Throwable) {
            deletion.retryUndo()
            throw error
        }
    }

    suspend fun reanchorSignal(bookId: String, chapterId: String, signalId: String, anchor: Anchor) =
        withContext(ioDispatcher) {
            val chapter = chapter(bookId, chapterId)
            val source = bookStore.readSource(bookId, chapter.path)
            mutateAndEnqueue(bookId, chapterId) { review ->
                val signal = review.signals.singleOrNull { it.id == signalId }
                    ?: throw IllegalArgumentException("Unknown signal: $signalId")
                validateSignal(signal.copy(anchor = anchor), source)
                review.copy(signals = review.signals.map { if (it.id == signalId) signal.copy(anchor = anchor) else it })
            }
        }

    suspend fun reanchorEdit(bookId: String, chapterId: String, editId: String, anchor: Anchor) =
        withContext(ioDispatcher) {
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

    suspend fun saveReadingPosition(bookId: String, chapterId: String, blockIndex: Int, byteOffset: Int) =
        withContext(ioDispatcher) {
            require(blockIndex >= 0 && byteOffset >= 0)
            books.saveReadingPosition(ReadingPositionEntity(bookId, chapterId, blockIndex, byteOffset, currentTimeMillis()))
        }

    suspend fun syncNow(bookId: String) = withContext(ioDispatcher) { schedule(bookId, SyncTrigger.SYNC_NOW) }

    private suspend fun loadContent(bookId: String, chapterId: String, reviewEnabled: Boolean): ReaderContent {
        val manifest = bookStore.readManifest(bookId)
        val index = manifest.chapters.indexOfFirst { it.id == chapterId }
        require(index >= 0) { "Unknown chapter: $chapterId" }
        val chapter = manifest.chapters[index]
        val rendered = MarkdownParser.parse(bookStore.readSource(bookId, chapter.path).decodeToString())
        val review = if (reviewEnabled) bookStore.readReview(bookId, chapter.path + BookPaths.REVIEW_SUFFIX) else null
        return ReaderContent(
            bookId, chapterId, chapter.title, ReviewProjector.project(rendered, review, reviewEnabled), reviewEnabled,
            review?.chapterNote,
            review?.let { document ->
                ReaderReviewItems(
                    document.signals.map { ReaderSignalItem(it.id, it.type, it.selectedText, it.comment) },
                    document.edits.map { ReaderEditItem(it.id, it.before, it.after) },
                )
            },
            manifest.chapters.getOrNull(index - 1)?.asReaderChapter(),
            manifest.chapters.getOrNull(index + 1)?.asReaderChapter(),
        )
    }

    private fun ReaderContent.toState(position: ReadingPositionEntity?, status: SyncStatus) = ReaderState(
        bookId, chapterId, title, document, reviewEnabled, chapterNote, reviewItems, previousChapter, nextChapter,
        position?.takeIf { it.chapterId == chapterId }?.let { ReaderPosition(it.blockIndex, it.byteOffset) },
        status.toReaderState(),
    )

    private suspend fun mutateAndEnqueue(
        bookId: String,
        chapterId: String,
        transform: (ReviewDocument) -> ReviewDocument,
    ) = withContext(ioDispatcher) {
        val chapter = chapter(bookId, chapterId)
        val path = chapter.path + BookPaths.REVIEW_SUFFIX
        mutations.withReview(bookId, path) {
            val current = bookStore.readReview(bookId, path)
                ?: ReviewDocument(chapterId = chapter.id, sourcePath = chapter.path)
            persistMutation(bookId, path, current, transform(current))
        }
        invalidate(bookId, chapterId)
    }

    private suspend fun deleteRecord(
        bookId: String,
        chapterId: String,
        transform: (ReviewDocument) -> Pair<DeletedRecord, ReviewDocument>,
    ): PendingDeletion = withContext(ioDispatcher) {
        val chapter = chapter(bookId, chapterId)
        val path = chapter.path + BookPaths.REVIEW_SUFFIX
        val deleted = mutations.withReview(bookId, path) {
            val current = requireNotNull(bookStore.readReview(bookId, path))
            val (record, updated) = transform(current)
            persistMutation(bookId, path, current, updated)
            record
        }
        invalidate(bookId, chapterId)
        PendingDeletion(bookId, chapterId, chapter.path, deleted)
    }

    private suspend fun persistMutation(bookId: String, path: String, previous: ReviewDocument, updated: ReviewDocument) {
        val oldOutbox = metadata.outbox(bookId).singleOrNull { it.path == path }
        val revision = bookStore.writeReview(bookId, path, updated)
        try {
            val base = metadata.mergeBase(bookId, path)
            metadata.recordOutbox(OutboxEntity(bookId, path, revision.sha256, base?.sha256, OutboxState.PENDING))
        } catch (failure: Throwable) {
            runCatching { bookStore.writeReview(bookId, path, previous) }.onFailure(failure::addSuppressed)
            runCatching {
                if (oldOutbox == null) metadata.removeOutbox(bookId, path) else metadata.recordOutbox(oldOutbox)
            }.onFailure(failure::addSuppressed)
            throw failure
        }
        runCatching { schedule(bookId, SyncTrigger.LOCAL_CHANGE) }
    }

    private suspend fun schedule(bookId: String, trigger: SyncTrigger) {
        val remoteRoot = books.root(bookId)?.remoteRootPath ?: return
        scheduler.enqueue(bookId, remoteRoot, trigger)
    }

    private suspend fun chapter(bookId: String, chapterId: String): ChapterEntry =
        bookStore.readManifest(bookId).chapters.singleOrNull { it.id == chapterId }
            ?: throw IllegalArgumentException("Unknown chapter: $chapterId")

    private fun invalidate(bookId: String, chapterId: String) {
        invalidations.update { current ->
            current + listOf(false, true).associate { enabled ->
                val key = ContentKey(bookId, chapterId, enabled)
                key to (current[key] ?: 0L) + 1L
            }
        }
    }

    private fun validateSignal(signal: Signal, source: ByteArray) {
        require(signal.anchor.sourceSha256 == sha256(source)) { "Signal anchor source does not match canonical source" }
        require(AnchorResolver.resolve(source, signal.anchor, signal.selectedText) is Resolved) {
            "Signal anchor does not resolve exactly against canonical source"
        }
    }

    private fun ChapterEntry.asReaderChapter() = ReaderChapter(id, title)

    private fun SyncStatus.toReaderState() = when (this) {
        SyncStatus.Saved -> ReaderSyncState.SAVED
        SyncStatus.WaitingToSync -> ReaderSyncState.WAITING_TO_SYNC
        SyncStatus.Syncing -> ReaderSyncState.SYNCING
        SyncStatus.SignInRequired -> ReaderSyncState.SIGN_IN_REQUIRED
        is SyncStatus.ActionRequired -> ReaderSyncState.ACTION_REQUIRED
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
