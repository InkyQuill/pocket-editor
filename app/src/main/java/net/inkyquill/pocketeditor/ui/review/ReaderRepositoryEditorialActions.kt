package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.reader.PendingDeletion
import net.inkyquill.pocketeditor.reader.ReaderRepository
import net.inkyquill.pocketeditor.reader.ReviewRecordKind
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.sync.ConflictChoice
import net.inkyquill.pocketeditor.sync.ConflictRepository
import net.inkyquill.pocketeditor.sync.SyncConflict
import net.inkyquill.pocketeditor.sync.SyncEngine

class ReaderRepositoryEditorialActions(
    private val repository: ReaderRepository,
    private val syncEngine: SyncEngine,
    private val conflicts: ConflictRepository,
    private val bookId: String,
    private val chapterId: String,
    private val recordKind: (String) -> ReviewRecordKind,
) : EditorialReviewActions {
    override suspend fun saveSignal(signal: Signal) = repository.saveSignal(bookId, chapterId, signal)
    override suspend fun saveEdit(edit: Edit) = repository.saveEdit(bookId, chapterId, edit)
    override suspend fun saveChapterNote(text: String) = repository.saveChapterNote(bookId, chapterId, text)
    override suspend fun deleteSignal(id: String): PendingDeletion = repository.deleteSignal(bookId, chapterId, id)
    override suspend fun deleteEdit(id: String): PendingDeletion = repository.deleteEdit(bookId, chapterId, id)
    override suspend fun undoDeletion(token: PendingDeletion) = repository.undoDeletion(token)
    override suspend fun finalizeDeletion(token: PendingDeletion) {
        repository.finalizeDeletion(token)
    }
    override suspend fun reanchor(recordId: String, anchor: Anchor) = when (recordKind(recordId)) {
        ReviewRecordKind.SIGNAL -> repository.reanchorSignal(bookId, chapterId, recordId, anchor)
        ReviewRecordKind.EDIT -> repository.reanchorEdit(bookId, chapterId, recordId, anchor)
    }

    override suspend fun resolveReview(path: String, choices: Map<String, ConflictChoice>) {
        val conflict = conflicts.conflict(bookId, path) as? SyncConflict.Review
            ?: throw IllegalArgumentException("Review conflict was replaced")
        syncEngine.resolveReviewConflict(bookId, path, conflict.identity, choices)
    }

    override suspend fun resolveManifest(choice: ConflictChoice) {
        val conflict = conflicts.conflict(bookId, ".pocket-editor-book.json") as? SyncConflict.Manifest
            ?: throw IllegalArgumentException("Manifest conflict was replaced")
        syncEngine.resolveManifestConflict(bookId, conflict.identity, choice)
    }
}
