package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.merge.RecordValue
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.sync.SyncConflict

data class ManifestSpineDiff(
    val added: List<ChapterEntry>,
    val removed: List<ChapterEntry>,
    val repointed: List<Pair<ChapterEntry, ChapterEntry>>,
    val orderChanged: Boolean,
)

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
            is SyncConflict.Manifest -> {
                val previews = manifestPreviews(conflict.local, conflict.remote)
                listOf(ConflictCard(
                    key = "manifest:${BookPaths.MANIFEST_NAME}",
                    path = BookPaths.MANIFEST_NAME,
                    recordId = BookPaths.MANIFEST_NAME,
                    identity = conflict.identity,
                    localPreview = previews.first,
                    yandexPreview = previews.second,
                    manifest = true,
                    allowedChoices = conflict.allowedChoices,
                ))
            }
            is SyncConflict.MissingBase -> emptyList()
        }
    }

    private fun manifestPreviews(local: BookManifest, remote: BookManifest): Pair<String, String> {
        val diff = spineDiff(local, remote)
        val localCommon = local.chapters.filter { chapter -> remote.chapters.any { it.id == chapter.id } }
        val remoteCommon = remote.chapters.filter { chapter -> local.chapters.any { it.id == chapter.id } }
        val localLines = buildList {
            if (local.title != remote.title) add("Название локально: ${local.title}")
            if (diff.removed.isNotEmpty()) add("Только локально: ${diff.removed.entriesPreview()}")
            if (diff.added.isNotEmpty()) add("Нет локально: ${diff.added.entriesPreview()}")
            diff.repointed.forEach { (mine, _) -> add("Локальный путь: ${mine.entryPreview()}") }
            if (diff.orderChanged) add("Порядок локально: ${localCommon.entriesPreview(" → ")}")
            if (isEmpty()) add("Название локально: ${local.title}")
        }
        val remoteLines = buildList {
            if (local.title != remote.title) add("Название на Яндекс Диске: ${remote.title}")
            if (diff.added.isNotEmpty()) add("Только на Яндекс Диске: ${diff.added.entriesPreview()}")
            if (diff.removed.isNotEmpty()) add("Нет на Яндекс Диске: ${diff.removed.entriesPreview()}")
            diff.repointed.forEach { (_, yandex) -> add("Путь на Яндекс Диске: ${yandex.entryPreview()}") }
            if (diff.orderChanged) add("Порядок на Яндекс Диске: ${remoteCommon.entriesPreview(" → ")}")
            if (isEmpty()) add("Название на Яндекс Диске: ${remote.title}")
        }
        return localLines.joinToString("\n") to remoteLines.joinToString("\n")
    }

    internal fun spineDiff(local: BookManifest, remote: BookManifest): ManifestSpineDiff {
        val localById = local.chapters.associateBy(ChapterEntry::id)
        val remoteById = remote.chapters.associateBy(ChapterEntry::id)
        val commonIds = localById.keys intersect remoteById.keys
        return ManifestSpineDiff(
            added = remote.chapters.filter { it.id !in localById },
            removed = local.chapters.filter { it.id !in remoteById },
            repointed = local.chapters.mapNotNull { mine ->
                remoteById[mine.id]?.takeIf { it.path != mine.path }?.let { mine to it }
            },
            orderChanged = local.chapters.map(ChapterEntry::id).filter(commonIds::contains) !=
                remote.chapters.map(ChapterEntry::id).filter(commonIds::contains),
        )
    }

    private fun List<ChapterEntry>.entriesPreview(separator: String = ", ") = joinToString(separator) { it.entryPreview() }
    private fun ChapterEntry.entryPreview() = "$id → $path"

    private fun preview(value: RecordValue?): String = when (value) {
        null -> "Удалено"
        is RecordValue.ChapterNoteValue -> value.note.ifBlank { "Пустая заметка к главе" }
        is RecordValue.SignalValue -> value.signal.comment.ifBlank { value.signal.selectedText }
        is RecordValue.EditValue -> value.edit.after.ifBlank { "Текст удалён" }
    }.take(240)
}
