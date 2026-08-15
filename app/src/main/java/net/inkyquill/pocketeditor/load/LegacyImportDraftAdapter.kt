package net.inkyquill.pocketeditor.load

import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.book.ImportDraftDocument
import net.inkyquill.pocketeditor.database.ImportDraftDao
import net.inkyquill.pocketeditor.database.ImportDraftEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.storage.ImportDraftStore

data class LegacyProgressiveSeed(
    val manifest: BookManifest,
    val remoteRootPath: String,
    val files: List<ProgressiveLoadFileEntity>,
    val cachedSources: Map<String, ByteArray>,
) {
    val readyWithoutNetwork: Boolean get() = files.all { it.state == ProgressiveLoadFileState.CACHED }
}

class LegacyImportDraftAdapter internal constructor(
    private val rows: suspend () -> List<ImportDraftEntity>,
    private val matchingSource: suspend (String, String, String, String) -> ByteArray?,
) {
    constructor(dao: ImportDraftDao, store: ImportDraftStore) : this(
        rows = dao::getAll,
        matchingSource = store::readMatchingSource,
    )

    suspend fun seeds(): List<LegacyProgressiveSeed> = rows().map { entity ->
        val document = ImportDraftDocument.decode(entity.documentJson)
        require(document.bookId == entity.bookId && document.remoteRootPath == entity.remoteRootPath)
        val cached = linkedMapOf<String, ByteArray>()
        val files = document.chapters.mapIndexed { index, chapter ->
            val bytes = matchingSource(document.bookId, chapter.path, chapter.remoteRevision, chapter.sha256)
            if (bytes != null) cached[chapter.path] = bytes
            ProgressiveLoadFileEntity(
                document.bookId, chapter.path, chapter.id, index, chapter.remoteRevision,
                chapter.byteSize, bytes?.let { chapter.sha256 },
                if (bytes == null) ProgressiveLoadFileState.PENDING else ProgressiveLoadFileState.CACHED,
                initialPriority(index),
            )
        }
        LegacyProgressiveSeed(
            BookManifest(
                bookId = document.bookId,
                title = document.title.trim().ifBlank { entity.remoteRootPath.substringAfterLast('/') },
                chapters = document.chapters.map { ChapterEntry(it.id, it.path) },
            ),
            entity.remoteRootPath,
            files,
            cached,
        )
    }
}
