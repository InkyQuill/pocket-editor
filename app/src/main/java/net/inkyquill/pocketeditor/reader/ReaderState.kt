package net.inkyquill.pocketeditor.reader

import net.inkyquill.pocketeditor.review.SignalType

data class ReaderChapter(
    val id: String,
    val title: String,
)

data class ReaderPosition(val blockIndex: Int, val byteOffset: Int)

enum class ReaderSyncState { SAVED, WAITING_TO_SYNC, SYNCING, SIGN_IN_REQUIRED, ACTION_REQUIRED }

data class ReaderSignalItem(
    val id: String,
    val type: SignalType,
    val selectedText: String,
    val comment: String,
)

data class ReaderEditItem(
    val id: String,
    val before: String,
    val after: String,
)

data class ReaderReviewItems(
    val signals: List<ReaderSignalItem>,
    val edits: List<ReaderEditItem>,
)

data class ReaderState(
    val bookId: String,
    val chapterId: String,
    val title: String,
    val document: ReaderDocument,
    val reviewEnabled: Boolean,
    val chapterNote: String?,
    val reviewItems: ReaderReviewItems?,
    val previousChapter: ReaderChapter?,
    val nextChapter: ReaderChapter?,
    val readingPosition: ReaderPosition?,
    val syncState: ReaderSyncState,
)
