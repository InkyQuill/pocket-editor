package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.reader.PendingDeletion
import net.inkyquill.pocketeditor.reader.ReaderRepository
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.sync.ConflictChoice
import net.inkyquill.pocketeditor.sync.SyncEngine

class ReaderRepositoryEditorialActions(
    private val repository: ReaderRepository,
    private val syncEngine: SyncEngine,
    private val bookId: String,
    private val chapterId: String,
) : EditorialReviewActions {
    override suspend fun saveSignal(signal: Signal) = repository.saveSignal(bookId, chapterId, signal)
    override suspend fun saveEdit(edit: Edit) = repository.saveEdit(bookId, chapterId, edit)
    override suspend fun saveChapterNote(text: String) = repository.saveChapterNote(bookId, chapterId, text)
    override suspend fun deleteSignal(id: String): PendingDeletion = repository.deleteSignal(bookId, chapterId, id)
    override suspend fun deleteEdit(id: String): PendingDeletion = repository.deleteEdit(bookId, chapterId, id)
    override suspend fun pendingDeletions(): List<PendingDeletion> = repository.pendingDeletions(bookId)
    override suspend fun undoDeletion(token: PendingDeletion) = repository.undoDeletion(token)
    override suspend fun finalizeDeletion(token: PendingDeletion) {
        repository.finalizeDeletion(token)
    }

    override suspend fun resolveReview(path: String, expectedIdentity: String, choices: Map<String, ConflictChoice>) =
        syncEngine.resolveReviewConflict(bookId, path, expectedIdentity, choices)

    override suspend fun resolveManifest(expectedIdentity: String, choice: ConflictChoice) =
        syncEngine.resolveManifestConflict(bookId, expectedIdentity, choice)
}
