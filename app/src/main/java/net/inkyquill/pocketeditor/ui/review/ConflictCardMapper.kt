package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.merge.RecordValue
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.sync.SyncConflict

object ConflictCardMapper {
    fun map(conflicts: List<SyncConflict>): List<ConflictCard> = conflicts.flatMap { conflict ->
        when (conflict) {
            is SyncConflict.Review -> conflict.records.map { record ->
                ConflictCard(
                    key = "review:${conflict.path}:${record.id}",
                    path = conflict.path,
                    recordId = record.id,
                    identity = conflict.identity,
                    localPreview = preview(record.local),
                    yandexPreview = preview(record.remote),
                )
            }
            is SyncConflict.Manifest -> listOf(
                ConflictCard(
                    key = "manifest:${BookPaths.MANIFEST_NAME}",
                    path = BookPaths.MANIFEST_NAME,
                    recordId = BookPaths.MANIFEST_NAME,
                    identity = conflict.identity,
                    localPreview = conflict.local.title,
                    yandexPreview = conflict.remote.title,
                    manifest = true,
                ),
            )
            is SyncConflict.MissingBase -> emptyList()
        }
    }

    private fun preview(value: RecordValue?): String = when (value) {
        null -> "Удалено"
        is RecordValue.ChapterNoteValue -> value.note.ifBlank { "Пустая заметка к главе" }
        is RecordValue.SignalValue -> value.signal.comment.ifBlank { value.signal.selectedText }
        is RecordValue.EditValue -> value.edit.after.ifBlank { "Текст удалён" }
    }.take(240)
}
