package net.inkyquill.pocketeditor.sync

import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.book.isOrdinaryMarkdownFile
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway

fun interface RevisionProbe {
    suspend fun shouldSync(bookId: String, remoteRootPath: String): Boolean
}

interface RemoteRevisionMetadata {
    suspend fun confirmedRevisions(bookId: String): List<RemoteRevisionEntity>
    suspend fun outbox(bookId: String): List<OutboxEntity>
    suspend fun pendingPublicationPaths(bookId: String): List<String>
}

fun interface SyncEligibility {
    suspend fun allowsSync(bookId: String): Boolean
}

class RemoteRevisionProbe(
    private val gateway: YandexDiskGateway,
    private val bookStore: BookStore,
    private val metadata: RemoteRevisionMetadata,
    private val eligibility: SyncEligibility = SyncEligibility { true },
) : RevisionProbe {
    override suspend fun shouldSync(bookId: String, remoteRootPath: String): Boolean {
        if (!eligibility.allowsSync(bookId)) return false
        if (metadata.outbox(bookId).isNotEmpty()) return true
        if (metadata.pendingPublicationPaths(bookId).isNotEmpty()) return true

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
        val untrackedMarkdown = remote.keys.any { path ->
            path.isOrdinaryMarkdownFile() && path !in tracked && path !in manifest.ignoredFiles
        }
        return untrackedMarkdown || tracked.any { path -> confirmed[path]?.remoteRevision != remote[path]?.revision }
    }

}
