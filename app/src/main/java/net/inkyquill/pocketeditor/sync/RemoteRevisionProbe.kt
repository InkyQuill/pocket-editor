package net.inkyquill.pocketeditor.sync

import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway

fun interface RevisionProbe {
    suspend fun shouldSync(bookId: String, remoteRootPath: String): Boolean
}

interface RemoteRevisionMetadata {
    suspend fun confirmedRevisions(bookId: String): List<RemoteRevisionEntity>
    suspend fun outbox(bookId: String): List<OutboxEntity>
}

class RemoteRevisionProbe(
    private val gateway: YandexDiskGateway,
    private val bookStore: BookStore,
    private val metadata: RemoteRevisionMetadata,
) : RevisionProbe {
    override suspend fun shouldSync(bookId: String, remoteRootPath: String): Boolean {
        if (metadata.outbox(bookId).isNotEmpty()) return true

        val manifest = bookStore.readManifest(bookId)
        val tracked = buildSet {
            add(BookPaths.MANIFEST_NAME)
            manifest.chapters.forEach { chapter ->
                add(chapter.path)
                add(chapter.path + BookPaths.REVIEW_SUFFIX)
            }
        }
        val confirmed = metadata.confirmedRevisions(bookId).associateBy(RemoteRevisionEntity::path)
        val remote = gateway.listFolder(remoteRootPath)
            .filter { it.type == "file" }
            .associateBy { it.name }
        return tracked.any { path -> confirmed[path]?.remoteRevision != remote[path]?.revision }
    }
}
