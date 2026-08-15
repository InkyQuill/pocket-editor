package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.sync.ConflictChoice

enum class NoteSaveStatus { SAVED, SAVING, WAITING, ERROR }

data class ReviewUiError(
    val message: String,
    val retryable: Boolean = true,
)

data class ConflictCard(
    val key: String,
    val path: String,
    val recordId: String,
    val identity: String,
    val localPreview: String,
    val yandexPreview: String,
    val selectedChoice: ConflictChoice? = null,
    val manifest: Boolean = false,
    val allowedChoices: Set<ConflictChoice> = ConflictChoice.entries.toSet(),
)

data class ReviewUiState(
    val draftSession: ReviewDraftSession = ReviewDraftSession(),
    val chapterNote: String = "",
    val noteSaveStatus: NoteSaveStatus = NoteSaveStatus.SAVED,
    val pendingDeletions: List<String> = emptyList(),
    val conflicts: List<ConflictCard> = emptyList(),
    val reanchorRecordId: String? = null,
    val error: ReviewUiError? = null,
) {
    val pendingDeletion: String? get() = pendingDeletions.lastOrNull()
}
