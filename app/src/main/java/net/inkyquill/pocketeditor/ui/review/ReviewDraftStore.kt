package net.inkyquill.pocketeditor.ui.review

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.inkyquill.pocketeditor.database.DraftDao
import net.inkyquill.pocketeditor.database.DraftEntity
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.review.SignalType

interface ReviewDraftPersistence {
    suspend fun put(draft: DraftEntity)
    suspend fun get(bookId: String, chapterId: String, draftType: String, recordKey: String): DraftEntity?
    suspend fun delete(bookId: String, chapterId: String, draftType: String, recordKey: String)
}

class RoomReviewDraftPersistence(private val dao: DraftDao) : ReviewDraftPersistence {
    override suspend fun put(draft: DraftEntity) = dao.upsert(draft)
    override suspend fun get(bookId: String, chapterId: String, draftType: String, recordKey: String) =
        dao.get(bookId, chapterId, draftType, recordKey)
    override suspend fun delete(bookId: String, chapterId: String, draftType: String, recordKey: String) =
        dao.delete(bookId, chapterId, draftType, recordKey)
}

class ReviewDraftStore(
    private val persistence: ReviewDraftPersistence,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun save(bookId: String, chapterId: String, session: ReviewDraftSession) {
        val draft = requireNotNull(session.draft) { "Only an active composer is persisted" }
        persistence.put(
            DraftEntity(
                bookId = bookId,
                chapterId = chapterId,
                draftType = TYPE,
                recordId = draft.recordId,
                text = json.encodeToString(session.toPayload()),
                selectionStart = draft.selection.rawRange.startByte,
                selectionEnd = draft.selection.rawRange.endByte,
                updatedAt = currentTimeMillis(),
                recordKey = KEY,
            ),
        )
    }

    suspend fun load(bookId: String, chapterId: String): ReviewDraftSession? =
        persistence.get(bookId, chapterId, TYPE, KEY)?.let { json.decodeFromString<DraftPayload>(it.text).toSession() }

    suspend fun clear(bookId: String, chapterId: String) = persistence.delete(bookId, chapterId, TYPE, KEY)

    private companion object {
        const val TYPE = "review_composer"
        const val KEY = "active"
        val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    }
}

@Serializable
private data class SelectionPayload(
    val blockIndex: Int,
    val renderedStart: Int,
    val renderedEnd: Int,
    val rawStart: Int,
    val rawEnd: Int,
    val selectedText: String,
) {
    fun toSelection() = ReviewSelection(blockIndex, renderedStart, renderedEnd, RawRange(rawStart, rawEnd), selectedText)
}

@Serializable
private data class RangePayload(val start: Int, val end: Int) {
    fun toRange() = RawRange(start, end)
}

@Serializable
private data class DraftPayload(
    val kind: String,
    val recordId: String?,
    val selection: SelectionPayload,
    val signalType: SignalType? = null,
    val comment: String? = null,
    val savedSignalType: SignalType? = null,
    val savedComment: String? = null,
    val after: String? = null,
    val savedAfter: String? = null,
    val rawStart: Int? = null,
    val rawEnd: Int? = null,
    val occupied: List<RangePayload> = emptyList(),
) {
    fun toSession(): ReviewDraftSession {
        val restoredSelection = selection.toSelection()
        val draft = when (kind) {
            "signal" -> ReviewDraft.Signal(
                recordId, restoredSelection, requireNotNull(signalType), comment.orEmpty(), savedSignalType, savedComment,
            )
            "edit" -> ReviewDraft.Edit(
                recordId, restoredSelection, requireNotNull(after), savedAfter,
                RawRange(requireNotNull(rawStart), requireNotNull(rawEnd)),
            )
            else -> error("Unknown review draft kind: $kind")
        }
        return ReviewDraftSession(draft = draft, occupiedEditRanges = occupied.map(RangePayload::toRange))
    }
}

private fun ReviewDraftSession.toPayload(): DraftPayload {
    val value = requireNotNull(draft)
    val selection = value.selection.let {
        SelectionPayload(it.blockIndex, it.renderedStart, it.renderedEnd, it.rawRange.startByte, it.rawRange.endByte, it.selectedText)
    }
    val occupied = occupiedEditRanges.map { RangePayload(it.startByte, it.endByte) }
    return when (value) {
        is ReviewDraft.Signal -> DraftPayload(
            "signal", value.recordId, selection, value.type, value.comment, value.savedType, value.savedComment,
            occupied = occupied,
        )
        is ReviewDraft.Edit -> DraftPayload(
            "edit", value.recordId, selection, after = value.after, savedAfter = value.savedAfter,
            rawStart = value.rawRange.startByte, rawEnd = value.rawRange.endByte, occupied = occupied,
        )
    }
}
