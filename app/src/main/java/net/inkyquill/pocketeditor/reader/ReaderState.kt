package net.inkyquill.pocketeditor.reader

import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.markdown.RenderedDocument
import java.time.Instant

data class ReaderChapter(
    val id: String,
    val title: String,
)

data class ReaderPosition(val blockIndex: Int, val byteOffset: Int)

enum class ReaderSyncState { SAVED, WAITING_TO_SYNC, SYNCING, SIGN_IN_REQUIRED, ACTION_REQUIRED }

data class ReaderObservedLock(val schemaVersion: Int, val lockId: String, val holderId: String, val createdAt: Instant)

data class ReaderSignalItem(
    val id: String,
    val type: SignalType,
    val selectedText: String,
    val comment: String,
    val anchor: Anchor? = null,
)

data class ReaderEditItem(
    val id: String,
    val before: String,
    val after: String,
    val anchor: Anchor? = null,
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
    val syncReason: String? = null,
    val observedSyncLock: ReaderObservedLock? = null,
    val selectionDocument: RenderedDocument? = null,
)
