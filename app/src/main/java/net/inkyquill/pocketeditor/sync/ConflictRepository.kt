package net.inkyquill.pocketeditor.sync

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.merge.RecordConflict
import net.inkyquill.pocketeditor.merge.RecordValue
import net.inkyquill.pocketeditor.merge.CHAPTER_NOTE_RECORD_ID
import net.inkyquill.pocketeditor.review.ReviewDocument

enum class ConflictChoice { KEEP_MINE, KEEP_YANDEX }

sealed interface SyncConflict {
    val path: String
    val identity: String

    data class Review(
        override val path: String,
        val partial: ReviewDocument,
        val records: List<RecordConflict>,
        val remoteBytes: ByteArray = byteArrayOf(),
        val remoteRevision: String = "",
        val remoteDeleted: Boolean = false,
        override val identity: String = UUID.randomUUID().toString(),
    ) : SyncConflict

    data class Manifest(
        override val path: String,
        val local: BookManifest,
        val remote: BookManifest,
        val remoteBytes: ByteArray = byteArrayOf(),
        val remoteRevision: String = "",
        val allowedChoices: Set<ConflictChoice> = ConflictChoice.entries.toSet(),
        override val identity: String = UUID.randomUUID().toString(),
    ) : SyncConflict

    data class MissingBase(
        override val path: String,
        val detail: String,
        override val identity: String = UUID.randomUUID().toString(),
    ) : SyncConflict
}

interface ConflictRepository {
    fun conflicts(bookId: String): Flow<List<SyncConflict>>
    fun conflict(bookId: String, path: String): SyncConflict?
    fun replace(bookId: String, conflict: SyncConflict)
    fun remove(bookId: String, path: String)
    fun removeIfCurrent(bookId: String, conflict: SyncConflict): Boolean
    fun previewReviewResolution(
        bookId: String,
        conflict: SyncConflict.Review,
        choices: Map<String, ConflictChoice>,
    ): ReviewDocument
    fun previewManifestResolution(bookId: String, conflict: SyncConflict.Manifest, choice: ConflictChoice): BookManifest
}

class InMemoryConflictRepository : ConflictRepository {
    private val state = MutableStateFlow<Map<String, List<SyncConflict>>>(emptyMap())

    override fun conflicts(bookId: String): Flow<List<SyncConflict>> = state.map { it[bookId].orEmpty() }

    override fun conflict(bookId: String, path: String): SyncConflict? = state.value[bookId].orEmpty().singleOrNull { it.path == path }

    override fun replace(bookId: String, conflict: SyncConflict) {
        state.update { current ->
            current + (bookId to (current[bookId].orEmpty().filterNot { it.path == conflict.path } + conflict))
        }
    }

    override fun remove(bookId: String, path: String) {
        state.update { current -> current + (bookId to current[bookId].orEmpty().filterNot { it.path == path }) }
    }

    override fun removeIfCurrent(bookId: String, conflict: SyncConflict): Boolean {
        while (true) {
            val current = state.value
            val stored = current[bookId].orEmpty().singleOrNull { it.path == conflict.path }
            if (stored?.identity != conflict.identity) return false
            val updated = current + (bookId to current[bookId].orEmpty().filterNot { it.path == conflict.path })
            if (state.compareAndSet(current, updated)) return true
        }
    }

    override fun previewReviewResolution(
        bookId: String,
        conflict: SyncConflict.Review,
        choices: Map<String, ConflictChoice>,
    ): ReviewDocument {
        check(state.value[bookId].orEmpty().singleOrNull { it.path == conflict.path }?.identity == conflict.identity) {
            "Review conflict was replaced"
        }
        require(choices.keys == conflict.records.map(RecordConflict::id).toSet()) {
            "Every review record conflict requires an explicit choice"
        }
        if (conflict.remoteDeleted) {
            return when (choices.getValue(REMOTE_REVIEW_DELETION_RECORD_ID)) {
                ConflictChoice.KEEP_MINE -> conflict.partial
                ConflictChoice.KEEP_YANDEX -> conflict.partial.copy(
                    chapterNote = "",
                    signals = emptyList(),
                    edits = emptyList(),
                )
            }
        }
        val records = buildMap<String, RecordValue> {
            put(CHAPTER_NOTE_RECORD_ID, RecordValue.ChapterNoteValue(conflict.partial.chapterNote))
            conflict.partial.signals.forEach { put(it.id, RecordValue.SignalValue(it)) }
            conflict.partial.edits.forEach { put(it.id, RecordValue.EditValue(it)) }
        }.toMutableMap()
        conflict.records.forEach { record ->
            val selected = when (choices.getValue(record.id)) {
                ConflictChoice.KEEP_MINE -> record.local
                ConflictChoice.KEEP_YANDEX -> record.remote
            }
            if (selected == null) records.remove(record.id) else records[record.id] = selected
        }
        val note = (records.getValue(CHAPTER_NOTE_RECORD_ID) as RecordValue.ChapterNoteValue).note
        val resolved = conflict.partial.copy(
            chapterNote = note,
            signals = records.values.filterIsInstance<RecordValue.SignalValue>().map { it.signal }.sortedBy { it.id },
            edits = records.values.filterIsInstance<RecordValue.EditValue>().map { it.edit }.sortedBy { it.id },
        )
        return resolved
    }

    override fun previewManifestResolution(
        bookId: String,
        conflict: SyncConflict.Manifest,
        choice: ConflictChoice,
    ): BookManifest {
        check(state.value[bookId].orEmpty().singleOrNull { it.path == conflict.path }?.identity == conflict.identity) {
            "Manifest conflict was replaced"
        }
        require(choice in conflict.allowedChoices) { "This resolution would discard pending local review work" }
        val resolved = when (choice) {
            ConflictChoice.KEEP_MINE -> conflict.local
            ConflictChoice.KEEP_YANDEX -> conflict.remote
        }
        return resolved
    }
}

internal const val REMOTE_REVIEW_DELETION_RECORD_ID = "remote-review-deletion"
