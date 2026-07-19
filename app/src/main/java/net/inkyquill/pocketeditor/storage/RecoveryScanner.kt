package net.inkyquill.pocketeditor.storage

import java.io.File
import java.util.UUID
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.SyncDao
import net.inkyquill.pocketeditor.review.ReviewJson

data class RecoveryReport(
    val recoveredRegistrations: Int,
    val recreatedPendingWork: Int,
    val invalidFiles: List<File>,
)

class RecoveryScanner(
    private val paths: BookPaths,
    private val bookDao: BookDao,
    private val syncDao: SyncDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile(): RecoveryReport {
        var recoveredRegistrations = 0
        var recreatedPendingWork = 0
        val invalidFiles = mutableListOf<File>()

        paths.root.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .sortedBy(File::getName)
            .forEach { directory ->
                val manifestFile = File(directory, BookPaths.MANIFEST_NAME)
                if (!manifestFile.isFile) return@forEach
                val manifest = runCatching {
                    BookManifest.decode(StrictUtf8.decode(manifestFile.readBytes(), "Book manifest"))
                }.getOrElse {
                    invalidFiles += manifestFile
                    registerInvalidArtifact(directory)?.let { recoveredRegistrations++ }
                    return@forEach
                }
                if (manifest.bookId != directory.name) {
                    invalidFiles += manifestFile
                    registerInvalidArtifact(directory)?.let { recoveredRegistrations++ }
                    return@forEach
                }

                val existing = bookDao.getRoots().any { it.bookId == manifest.bookId }
                if (!existing) {
                    bookDao.upsertRoot(
                        BookRootEntity(
                            bookId = manifest.bookId,
                            remoteRootPath = null,
                            localDirectory = directory.absolutePath,
                            registeredAt = now(),
                        ),
                    )
                    recoveredRegistrations++
                }

                val durableFiles = directory.listFiles()
                    .orEmpty()
                    .filter { file -> file.isFile && (file.name == BookPaths.MANIFEST_NAME || file.name.endsWith(BookPaths.REVIEW_SUFFIX)) }
                    .sortedBy(File::getName)
                val deferredReviewPaths = syncDao.pendingDeletions(manifest.bookId).mapTo(mutableSetOf()) { it.reviewPath }
                durableFiles.forEach { file ->
                    val bytes = file.readBytes()
                    if (file.name.endsWith(BookPaths.REVIEW_SUFFIX) && !isValidReview(file, bytes, manifest)) {
                        invalidFiles += file
                        syncDao.deleteOutbox(manifest.bookId, file.name)
                        return@forEach
                    }
                    if (file.name in deferredReviewPaths) return@forEach
                    val localHash = bytes.sha256()
                    val base = syncDao.getMergeBase(manifest.bookId, file.name)
                    if (base?.sha256 == localHash) {
                        syncDao.deleteOutbox(manifest.bookId, file.name)
                        return@forEach
                    }
                    val currentOutbox = syncDao.getOutbox(manifest.bookId, file.name)
                    val requiredState = if (base == null) OutboxState.NEEDS_REMOTE_COMPARE else OutboxState.PENDING
                    val currentStateIsSafe = when {
                        base == null -> currentOutbox?.state == OutboxState.NEEDS_REMOTE_COMPARE
                        else -> currentOutbox?.state == OutboxState.PENDING || currentOutbox?.state == OutboxState.RETRY
                    }
                    if (
                        currentOutbox?.localSha256 == localHash &&
                        currentOutbox.baseSha256 == base?.sha256 &&
                        currentStateIsSafe
                    ) {
                        return@forEach
                    }
                    syncDao.upsertOutbox(
                        OutboxEntity(
                            bookId = manifest.bookId,
                            path = file.name,
                            localSha256 = localHash,
                            baseSha256 = base?.sha256,
                            state = requiredState,
                        ),
                    )
                    recreatedPendingWork++
                }
            }

        return RecoveryReport(recoveredRegistrations, recreatedPendingWork, invalidFiles)
    }

    private suspend fun registerInvalidArtifact(directory: File): Unit? {
        val bookId = runCatching { UUID.fromString(directory.name).toString() }.getOrNull() ?: return null
        if (bookDao.getRoot(bookId) != null) return null
        bookDao.upsertRoot(BookRootEntity(bookId, null, directory.absolutePath, now()))
        return Unit
    }

    private fun isValidReview(file: File, bytes: ByteArray, manifest: BookManifest): Boolean {
        val sourcePath = file.name.removeSuffix(BookPaths.REVIEW_SUFFIX)
        val chapter = manifest.chapters.singleOrNull { it.path == sourcePath } ?: return false
        return runCatching {
            ReviewJson.decode(StrictUtf8.decode(bytes, "Review ${file.name}"), chapter.id, sourcePath)
        }.isSuccess
    }
}
