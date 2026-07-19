package net.inkyquill.pocketeditor.reader

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import net.inkyquill.pocketeditor.anchor.AnchorResolver
import net.inkyquill.pocketeditor.anchor.Resolved
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.PendingDeletionEntity
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.EditValidator
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.storage.ContentKey
import net.inkyquill.pocketeditor.storage.LocalRevision
import net.inkyquill.pocketeditor.sync.SyncMetadataStore
import net.inkyquill.pocketeditor.sync.PendingDeletionStore
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

data class PendingDeletion(val tokenId: String)

class ReaderRepository(
    private val bookStore: BookStore,
    private val books: ReaderBookStore,
    private val metadata: SyncMetadataStore,
    private val scheduler: ReaderSyncScheduler,
    private val syncStatus: (String) -> Flow<SyncStatus>,
    private val mutations: ReviewMutationCoordinator,
    private val deletions: PendingDeletionStore,
    private val contentChanges: ContentChangeNotifier,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
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
        val sourcePath: String,
    )

    fun observeChapter(bookId: String, chapterId: String, reviewEnabled: Boolean): Flow<ReaderState> {
        val content = flow {
            var observed = contentChanges.versions.value
            var loaded = withContext(ioDispatcher) { loadContent(bookId, chapterId, reviewEnabled) }
            emit(loaded)
            contentChanges.versions.collect { current ->
                if (loaded.relevantVersions(current, reviewEnabled) != loaded.relevantVersions(observed, reviewEnabled)) {
                    loaded = withContext(ioDispatcher) { loadContent(bookId, chapterId, reviewEnabled) }
                    emit(loaded)
                }
                observed = current
            }
        }
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

    suspend fun pendingDeletions(bookId: String): List<PendingDeletion> = withContext(ioDispatcher) {
        deletions.pendingForBook(bookId).map { PendingDeletion(it.tokenId) }
    }

    suspend fun finalizeDeletion(deletion: PendingDeletion) = withContext(ioDispatcher) {
        val hint = deletions.get(deletion.tokenId) ?: error("Deletion token was already consumed")
        var contentWritten = false
        mutations.withReview(hint.bookId, hint.reviewPath) {
            val pending = deletions.get(deletion.tokenId) ?: error("Deletion token was already consumed")
            val current = requireNotNull(bookStore.readReview(pending.bookId, pending.reviewPath))
            val deleted = pending.deletedRecord()
            val existing = current.record(deleted.id)
            check(existing == null || existing == deleted) { "Record ID was reused after deletion" }
            val finalized = if (existing == null) current else current.without(deleted.id)
            val revisionSha = if (finalized == current) {
                sha256(ReviewJson.encode(current).encodeToByteArray())
            } else {
                contentWritten = true
                bookStore.writeReview(pending.bookId, pending.reviewPath, finalized).sha256
            }
            val base = metadata.mergeBase(pending.bookId, pending.reviewPath)
            val outbox = OutboxEntity(
                pending.bookId,
                pending.reviewPath,
                revisionSha,
                base?.sha256,
                OutboxState.PENDING,
            )
            check(deletions.complete(pending.tokenId, outbox)) { "Deletion token was replaced" }
        }
        if (contentWritten) contentChanges.changed(hint.bookId, hint.reviewPath)
        runCatching { schedule(hint.bookId, SyncTrigger.LOCAL_CHANGE) }
    }

    suspend fun commitDeletion(deletion: PendingDeletion) = finalizeDeletion(deletion)

    suspend fun undoDeletion(deletion: PendingDeletion) = withContext(ioDispatcher) {
        val hint = deletions.get(deletion.tokenId) ?: error("Deletion token was already consumed")
        var shouldSchedule = false
        var contentWritten = false
        mutations.withReview(hint.bookId, hint.reviewPath) {
            val pending = deletions.get(deletion.tokenId) ?: error("Deletion token was already consumed")
            val current = requireNotNull(bookStore.readReview(pending.bookId, pending.reviewPath))
            val deleted = pending.deletedRecord()
            val existing = current.record(deleted.id)
            check(existing == null || existing == deleted) { "Record ID was reused after deletion" }
            val restored = if (existing == null) current.withRecord(deleted) else current
            val currentOutbox = metadata.outbox(pending.bookId).singleOrNull { it.path == pending.reviewPath }
            val revisionSha = if (restored == current) {
                sha256(ReviewJson.encode(current).encodeToByteArray())
            } else {
                contentWritten = true
                bookStore.writeReview(pending.bookId, pending.reviewPath, restored).sha256
            }
            val updatedOutbox = currentOutbox?.copy(localSha256 = revisionSha, state = OutboxState.PENDING)
            shouldSchedule = updatedOutbox != null
            check(deletions.complete(pending.tokenId, updatedOutbox)) { "Deletion token was replaced" }
        }
        if (contentWritten) contentChanges.changed(hint.bookId, hint.reviewPath)
        if (shouldSchedule) runCatching { schedule(hint.bookId, SyncTrigger.LOCAL_CHANGE) }
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
            chapter.path,
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
        contentChanges.changed(bookId, path)
    }

    private suspend fun deleteRecord(
        bookId: String,
        chapterId: String,
        transform: (ReviewDocument) -> Pair<DeletedRecord, ReviewDocument>,
    ): PendingDeletion = withContext(ioDispatcher) {
        val chapter = chapter(bookId, chapterId)
        val path = chapter.path + BookPaths.REVIEW_SUFFIX
        val token = mutations.withReview(bookId, path) {
            val current = requireNotNull(bookStore.readReview(bookId, path))
            val (record, updated) = transform(current)
            val tokenId = UUID.randomUUID().toString()
            val pending = record.toPendingDeletion(
                tokenId,
                bookId,
                chapterId,
                path,
                updated,
                currentTimeMillis(),
            )
            deletions.put(pending)
            try {
                bookStore.writeReview(bookId, path, updated)
            } catch (failure: Throwable) {
                runCatching { check(deletions.remove(tokenId)) { "Prepared deletion marker could not be removed" } }
                    .onFailure(failure::addSuppressed)
                throw failure
            }
            PendingDeletion(tokenId)
        }
        contentChanges.changed(bookId, path)
        token
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

    private fun ReaderContent.relevantVersions(
        versions: Map<ContentKey, Long>,
        reviewEnabled: Boolean,
    ): List<Long> = buildList {
        add(versions[ContentKey(bookId, BookPaths.MANIFEST_NAME)] ?: 0L)
        add(versions[ContentKey(bookId, sourcePath)] ?: 0L)
        if (reviewEnabled) add(versions[ContentKey(bookId, sourcePath + BookPaths.REVIEW_SUFFIX)] ?: 0L)
    }

    private fun validateSignal(signal: Signal, source: ByteArray) {
        require(signal.anchor.sourceSha256 == sha256(source)) { "Signal anchor source does not match canonical source" }
        require(AnchorResolver.resolve(source, signal.anchor, signal.selectedText) is Resolved) {
            "Signal anchor does not resolve exactly against canonical source"
        }
    }

    private fun DeletedRecord.toPendingDeletion(
        tokenId: String,
        bookId: String,
        chapterId: String,
        reviewPath: String,
        updated: ReviewDocument,
        createdAt: Long,
    ): PendingDeletionEntity {
        val payload = when (this) {
            is DeletedRecord.SignalRecord -> ReviewJson.encode(
                ReviewDocument(chapterId = chapterId, sourcePath = updated.sourcePath, signals = listOf(value)),
            )
            is DeletedRecord.EditRecord -> ReviewJson.encode(
                ReviewDocument(chapterId = chapterId, sourcePath = updated.sourcePath, edits = listOf(value)),
            )
        }
        return PendingDeletionEntity(
            tokenId,
            bookId,
            chapterId,
            reviewPath,
            id,
            if (this is DeletedRecord.SignalRecord) "signal" else "edit",
            payload,
            createdAt,
        )
    }

    private fun PendingDeletionEntity.deletedRecord(): DeletedRecord {
        val sourcePath = reviewPath.removeSuffix(BookPaths.REVIEW_SUFFIX)
        val payload = ReviewJson.decode(recordPayload, chapterId, sourcePath)
        return when (recordType) {
            "signal" -> DeletedRecord.SignalRecord(payload.signals.single())
            "edit" -> DeletedRecord.EditRecord(payload.edits.single())
            else -> error("Unsupported pending deletion record type: $recordType")
        }
    }

    private fun ReviewDocument.record(id: String): DeletedRecord? =
        signals.singleOrNull { it.id == id }?.let(DeletedRecord::SignalRecord)
            ?: edits.singleOrNull { it.id == id }?.let(DeletedRecord::EditRecord)

    private fun ReviewDocument.without(id: String) = copy(
        signals = signals.filterNot { it.id == id },
        edits = edits.filterNot { it.id == id },
    )

    private fun ReviewDocument.withRecord(record: DeletedRecord) = when (record) {
        is DeletedRecord.SignalRecord -> copy(signals = (signals + record.value).sortedBy(Signal::id))
        is DeletedRecord.EditRecord -> copy(edits = (edits + record.value).sortedBy(Edit::id))
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
