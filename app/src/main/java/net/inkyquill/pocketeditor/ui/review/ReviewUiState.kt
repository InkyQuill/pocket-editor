package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.sync.ConflictChoice

enum class NoteSaveStatus { SAVED, WAITING }

data class ConflictCard(
    val path: String,
    val recordId: String,
    val localPreview: String,
    val yandexPreview: String,
    val selectedChoice: ConflictChoice? = null,
    val manifest: Boolean = false,
)

data class ReviewUiState(
    val draftSession: ReviewDraftSession = ReviewDraftSession(),
    val chapterNote: String = "",
    val noteSaveStatus: NoteSaveStatus = NoteSaveStatus.SAVED,
    val pendingDeletion: String? = null,
    val conflicts: List<ConflictCard> = emptyList(),
    val reanchorRecordId: String? = null,
)
