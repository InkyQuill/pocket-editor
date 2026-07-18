package net.inkyquill.pocketeditor.merge

import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.Signal

internal const val CHAPTER_NOTE_RECORD_ID = "chapter-note"

sealed interface RecordValue {
    data class SignalValue(val signal: Signal) : RecordValue

    data class EditValue(val edit: Edit) : RecordValue

    data class ChapterNoteValue(val note: String) : RecordValue
}

data class RecordConflict(
    val id: String,
    val base: RecordValue?,
    val local: RecordValue?,
    val remote: RecordValue?,
)

sealed interface MergeResult {
    data class Merged(val document: ReviewDocument) : MergeResult

    data class Conflicted(
        val partial: ReviewDocument,
        val conflicts: List<RecordConflict>,
    ) : MergeResult
}

object ReviewMerge {
    fun merge(
        base: ReviewDocument,
        local: ReviewDocument,
        remote: ReviewDocument,
    ): MergeResult {
        requireSameIdentity(base, local, remote)

        val baseRecords = base.toRecordMap()
        val localRecords = local.toRecordMap()
        val remoteRecords = remote.toRecordMap()
        val mergedRecords = mutableMapOf<String, RecordValue>()
        val conflicts = mutableListOf<RecordConflict>()

        (baseRecords.keys + localRecords.keys + remoteRecords.keys)
            .toSortedSet()
            .forEach { id ->
                val baseValue = baseRecords[id]
                val localValue = localRecords[id]
                val remoteValue = remoteRecords[id]

                val merged = when {
                    localValue == remoteValue -> localValue
                    localValue == baseValue -> remoteValue
                    remoteValue == baseValue -> localValue
                    else -> {
                        conflicts += RecordConflict(id, baseValue, localValue, remoteValue)
                        baseValue
                    }
                }
                if (merged != null) mergedRecords[id] = merged
            }

        val partial = base.fromRecordMap(mergedRecords)
        return if (conflicts.isEmpty()) {
            MergeResult.Merged(partial)
        } else {
            MergeResult.Conflicted(partial, conflicts)
        }
    }

    private fun requireSameIdentity(
        base: ReviewDocument,
        local: ReviewDocument,
        remote: ReviewDocument,
    ) {
        val identities = listOf(base, local, remote).map { document ->
            Triple(document.schemaVersion, document.chapterId, document.sourcePath)
        }
        require(identities.distinct().size == 1) {
            "Review documents must have the same schema version, chapter ID, and source path"
        }
    }

    private fun ReviewDocument.toRecordMap(): Map<String, RecordValue> {
        val ids = signals.map(Signal::id) + edits.map(Edit::id)
        require(ids.distinct().size == ids.size) { "Review record IDs must be unique" }
        require(CHAPTER_NOTE_RECORD_ID !in ids) { "Review record ID is reserved: $CHAPTER_NOTE_RECORD_ID" }

        return buildMap {
            put(CHAPTER_NOTE_RECORD_ID, RecordValue.ChapterNoteValue(chapterNote))
            signals.forEach { signal -> put(signal.id, RecordValue.SignalValue(signal)) }
            edits.forEach { edit -> put(edit.id, RecordValue.EditValue(edit)) }
        }
    }

    private fun ReviewDocument.fromRecordMap(records: Map<String, RecordValue>): ReviewDocument {
        val chapterNote = (records.getValue(CHAPTER_NOTE_RECORD_ID) as RecordValue.ChapterNoteValue).note
        val signals = records.values
            .filterIsInstance<RecordValue.SignalValue>()
            .map(RecordValue.SignalValue::signal)
            .sortedBy(Signal::id)
        val edits = records.values
            .filterIsInstance<RecordValue.EditValue>()
            .map(RecordValue.EditValue::edit)
            .sortedBy(Edit::id)
        return copy(chapterNote = chapterNote, signals = signals, edits = edits)
    }
}
