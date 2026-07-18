package net.inkyquill.pocketeditor.storage

import java.io.File
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.SyncDao

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
                val manifest = runCatching { BookManifest.decode(manifestFile.readText()) }.getOrElse {
                    invalidFiles += manifestFile
                    return@forEach
                }
                if (manifest.bookId != directory.name) {
                    invalidFiles += manifestFile
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
                durableFiles.forEach { file ->
                    val localHash = file.readBytes().sha256()
                    val currentOutbox = syncDao.getOutbox(manifest.bookId, file.name)
                    if (currentOutbox?.localSha256 == localHash) return@forEach
                    val base = syncDao.getMergeBase(manifest.bookId, file.name)
                    if (base?.sha256 == localHash) return@forEach
                    syncDao.upsertOutbox(
                        OutboxEntity(
                            bookId = manifest.bookId,
                            path = file.name,
                            localSha256 = localHash,
                            baseSha256 = base?.sha256,
                            state = if (base == null) OutboxState.NEEDS_REMOTE_COMPARE else OutboxState.PENDING,
                        ),
                    )
                    recreatedPendingWork++
                }
            }

        return RecoveryReport(recoveredRegistrations, recreatedPendingWork, invalidFiles)
    }
}
