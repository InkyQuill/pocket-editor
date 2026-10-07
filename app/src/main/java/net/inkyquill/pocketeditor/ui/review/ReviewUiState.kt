package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.sync.ConflictChoice

enum class NoteSaveStatus { SAVED, SAVING, WAITING, ERROR }

enum class ReviewErrorCode { OPERATION_FAILED, DRAFT_RESTORE_FAILED, DELETE_FAILED, STALE_CONFLICT, UNCHANGED_EDIT, OVERLAPPING_EDIT }

data class ReviewUiError(
    val message: String,
    val retryable: Boolean = true,
    val code: ReviewErrorCode = ReviewErrorCode.OPERATION_FAILED,
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
    val error: ReviewUiError? = null,
) {
    val pendingDeletion: String? get() = pendingDeletions.lastOrNull()
}
