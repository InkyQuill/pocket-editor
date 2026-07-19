package net.inkyquill.pocketeditor.sync

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.SyncDao
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.merge.MergeResult
import net.inkyquill.pocketeditor.merge.ReviewMerge
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.storage.SourceCache
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway

sealed interface SyncStatus {
    data object Saved : SyncStatus
    data object WaitingToSync : SyncStatus
    data object Syncing : SyncStatus
    data object SignInRequired : SyncStatus
    data class ActionRequired(val reason: String, val lock: SyncLock? = null) : SyncStatus
}

interface SyncMetadataStore {
    suspend fun outbox(bookId: String): List<OutboxEntity>
    suspend fun mergeBase(bookId: String, path: String): MergeBaseEntity?
    suspend fun recordRemote(value: RemoteRevisionEntity)
    suspend fun recordBase(value: MergeBaseEntity)
    suspend fun recordOutbox(value: OutboxEntity)
    suspend fun removeOutbox(bookId: String, path: String)
}

class RoomSyncMetadataStore(private val dao: SyncDao) : SyncMetadataStore {
    override suspend fun outbox(bookId: String): List<OutboxEntity> =
        dao.observeOutbox().first().filter { it.bookId == bookId }

    override suspend fun mergeBase(bookId: String, path: String): MergeBaseEntity? = dao.getMergeBase(bookId, path)

    override suspend fun recordRemote(value: RemoteRevisionEntity) = dao.upsertRemoteRevision(value)

    override suspend fun recordBase(value: MergeBaseEntity) = dao.upsertMergeBase(value)

    override suspend fun recordOutbox(value: OutboxEntity) = dao.upsertOutbox(value)

    override suspend fun removeOutbox(bookId: String, path: String) = dao.deleteOutbox(bookId, path)
}

class SyncEngine internal constructor(
    private val gateway: YandexDiskGateway,
    private val bookStore: BookStore,
    private val sourceCache: SourceCache,
    private val metadata: SyncMetadataStore,
    private val baseStore: SyncBaseStore,
    private val conflicts: ConflictRepository,
    private val holderId: String,
    private val lockFactory: () -> SyncLock,
) {
    private val statuses = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())

    fun status(bookId: String): Flow<SyncStatus> = statuses
        .map { it[bookId] ?: SyncStatus.Saved }
        .distinctUntilChanged()

    suspend fun resolveReviewConflict(
        bookId: String,
        path: String,
        choices: Map<String, ConflictChoice>,
    ) {
        val conflict = conflicts.conflict(bookId, path) as? SyncConflict.Review
            ?: throw IllegalArgumentException("Review conflict was not found")
        require(conflict.remoteRevision.isNotBlank() && conflict.remoteBytes.isNotEmpty()) {
            "Review conflict has no confirmed remote base"
        }
        val resolved = conflicts.previewReviewResolution(bookId, path, choices)
        val remoteBase = writeDurableBase(bookId, path, conflict.remoteBytes, conflict.remoteRevision)
        metadata.recordBase(MergeBaseEntity(bookId, path, remoteBase.sha256, conflict.remoteRevision))
        metadata.recordRemote(RemoteRevisionEntity(bookId, path, conflict.remoteRevision, remoteBase.sha256))
        val local = bookStore.writeReview(bookId, path, resolved)
        if (local.sha256 == remoteBase.sha256) {
            metadata.removeOutbox(bookId, path)
        } else {
            metadata.recordOutbox(
                OutboxEntity(bookId, path, local.sha256, remoteBase.sha256, net.inkyquill.pocketeditor.database.OutboxState.PENDING),
            )
        }
        conflicts.remove(bookId, path)
    }

    suspend fun resolveManifestConflict(bookId: String, choice: ConflictChoice) {
        val conflict = conflicts.conflict(bookId, MANIFEST_PATH) as? SyncConflict.Manifest
            ?: throw IllegalArgumentException("Manifest conflict was not found")
        require(conflict.remoteRevision.isNotBlank() && conflict.remoteBytes.isNotEmpty()) {
            "Manifest conflict has no confirmed remote base"
        }
        val resolved = conflicts.previewManifestResolution(bookId, choice)
        val remoteBase = writeDurableBase(bookId, MANIFEST_PATH, conflict.remoteBytes, conflict.remoteRevision)
        metadata.recordBase(MergeBaseEntity(bookId, MANIFEST_PATH, remoteBase.sha256, conflict.remoteRevision))
        metadata.recordRemote(RemoteRevisionEntity(bookId, MANIFEST_PATH, conflict.remoteRevision, remoteBase.sha256))
        val local = bookStore.writeManifest(bookId, resolved)
        if (choice == ConflictChoice.KEEP_YANDEX || local.sha256 == remoteBase.sha256) {
            metadata.removeOutbox(bookId, MANIFEST_PATH)
        } else {
            metadata.recordOutbox(
                OutboxEntity(
                    bookId,
                    MANIFEST_PATH,
                    local.sha256,
                    remoteBase.sha256,
                    net.inkyquill.pocketeditor.database.OutboxState.PENDING,
                ),
            )
        }
        conflicts.remove(bookId, MANIFEST_PATH)
    }

    suspend fun syncBook(
        bookId: String,
        remoteRootPath: String,
        breakObservedLock: SyncLock? = null,
    ): SyncStatus {
        setStatus(bookId, SyncStatus.Syncing)
        var ownedLock: SyncLock? = null
        var result: SyncStatus? = null
        var primaryFailure: Throwable? = null
        var handledFailure: Throwable? = null
        try {
            if (breakObservedLock != null) {
                gateway.breakObservedLock(remoteRootPath, breakObservedLock)
            }
            val requestedLock = lockFactory().also { lock ->
                require(lock.holderId == holderId) { "Lock factory must use this device holder ID" }
            }
            ownedLock = gateway.tryAcquireLock(remoteRootPath, requestedLock)
            result = synchronizeUnderLock(bookId, remoteRootPath, ownedLock)
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            throw cancelled
        } catch (error: YandexDiskError.CandidateCleanupUnconfirmed) {
            handledFailure = error
            result = SyncStatus.ActionRequired(
                reason = "Candidate lock ${error.candidateLock.lockId} cleanup is unconfirmed; " +
                    "verification: ${failureDetail(error.verificationFailure)}; " +
                    "cleanup: ${failureDetail(error.cleanupFailure)}",
                lock = error.candidateLock,
            )
        } catch (error: YandexDiskError.Offline) {
            handledFailure = error
            result = SyncStatus.WaitingToSync
        } catch (error: YandexDiskError.Unauthorized) {
            handledFailure = error
            result = SyncStatus.SignInRequired
        } catch (error: YandexDiskError.LockHeld) {
            handledFailure = error
            val observed = runCatching { gateway.readLock(remoteRootPath) }.getOrNull()
            result = SyncStatus.ActionRequired("Cooperative lock is held", observed)
        } catch (error: YandexDiskError.LockLost) {
            handledFailure = error
            result = SyncStatus.ActionRequired("Cooperative lock ownership was lost")
        } catch (error: YandexDiskError.RateLimited) {
            handledFailure = error
            result = SyncStatus.WaitingToSync
        } catch (error: YandexDiskError.ServerFailure) {
            handledFailure = error
            result = SyncStatus.WaitingToSync
        } catch (error: Exception) {
            handledFailure = error
            result = SyncStatus.ActionRequired(error.message ?: "Remote state is invalid")
        } finally {
            ownedLock?.let { lock ->
                val releaseFailure = runCatching {
                    withContext(NonCancellable) { gateway.releaseOwnedLock(remoteRootPath, lock) }
                }.exceptionOrNull()
                if (releaseFailure != null && primaryFailure != null) {
                    var cancellation: Throwable? = primaryFailure
                    while (cancellation != null) {
                        cancellation.addSuppressed(releaseFailure)
                        cancellation = cancellation.cause
                    }
                } else if (releaseFailure != null) {
                    val original = handledFailure?.let(::failureDetail)
                        ?: (result as? SyncStatus.ActionRequired)?.reason
                        ?: result?.toString()
                        ?: "synchronization did not complete"
                    result = SyncStatus.ActionRequired(
                        reason = "Lock ${lock.lockId} could not be released after $original: ${failureDetail(releaseFailure)}",
                        lock = lock,
                    )
                }
            }
        }
        return requireNotNull(result).also { setStatus(bookId, it) }
    }

    private suspend fun synchronizeUnderLock(
        bookId: String,
        rootPath: String,
        lock: SyncLock,
    ): SyncStatus {
        val entries = gateway.listFolder(rootPath)
            .filter { it.type == "file" }
            .associateBy { it.name }
        val pending = metadata.outbox(bookId).associateBy(OutboxEntity::path)

        // Download and validate every metadata document before replacing any last-valid cache file.
        val remoteManifestFile = entries[MANIFEST_PATH]?.let { gateway.download(it.path) }
        val remoteManifest = remoteManifestFile?.let { BookManifest.decode(it.bytes.decodeToString()) }
        val localManifest = bookStore.readManifest(bookId)
        require((remoteManifest ?: localManifest).bookId == bookId) {
            "Remote manifest book_id does not match the registered book"
        }

        var blocked = false
        if (remoteManifestFile != null && remoteManifest != null) {
            blocked = processManifest(
                bookId,
                remoteManifestFile,
                remoteManifest,
                localManifest,
                pending[MANIFEST_PATH],
                rootPath,
                lock,
            ) || blocked
        } else if (pending[MANIFEST_PATH] != null) {
            uploadNewManifest(bookId, localManifest, pending.getValue(MANIFEST_PATH), rootPath, lock)
        }

        val sourcePaths = (localManifest.chapters + remoteManifest?.chapters.orEmpty())
            .map { it.path }
            .distinct()
        sourcePaths.forEach { path ->
            entries[path]?.let { entry ->
                val file = gateway.download(entry.path)
                sourceCache.replaceDownloadedSource(bookId, path, file.bytes)
                metadata.recordRemote(RemoteRevisionEntity(bookId, path, file.revision, sha256(file.bytes)))
            }
        }

        // A manifest conflict freezes sidecar projection: remote identities may no longer match the local cache.
        if (blocked) return SyncStatus.ActionRequired("Resolve synchronization conflicts")

        val activeManifest = bookStore.readManifest(bookId)
        val remoteReviews = buildMap<String, Pair<RemoteFile, ReviewDocument>> {
            activeManifest.chapters.forEach { chapter ->
                val reviewPath = chapter.path + REVIEW_SUFFIX
                entries[reviewPath]?.let { entry ->
                    val file = gateway.download(entry.path)
                    put(reviewPath, file to ReviewJson.decode(file.bytes.decodeToString(), chapter.id, chapter.path))
                }
            }
        }
        activeManifest.chapters.forEach { chapter ->
            val path = chapter.path + REVIEW_SUFFIX
            val remote = remoteReviews[path]
            val outbox = pending[path]
            when {
                remote != null -> {
                    blocked = processReview(
                        bookId,
                        path,
                        remote.first,
                        remote.second,
                        outbox,
                        rootPath,
                        lock,
                    ) || blocked
                }
                outbox != null -> uploadNewReview(bookId, path, outbox, rootPath, lock)
            }
        }
        val remaining = metadata.outbox(bookId)
        return when {
            blocked -> SyncStatus.ActionRequired("Resolve synchronization conflicts")
            remaining.isNotEmpty() -> SyncStatus.ActionRequired("Pending metadata could not be synchronized safely")
            else -> SyncStatus.Saved
        }
    }

    private suspend fun processManifest(
        bookId: String,
        remoteFile: RemoteFile,
        remote: BookManifest,
        local: BookManifest,
        outbox: OutboxEntity?,
        rootPath: String,
        lock: SyncLock,
    ): Boolean {
        if (outbox == null) {
            bookStore.writeManifest(bookId, remote)
            confirmRemote(bookId, MANIFEST_PATH, remoteFile.bytes, remoteFile.revision)
            conflicts.remove(bookId, MANIFEST_PATH)
            return false
        }
        val localBytes = BookManifest.encode(local).encodeToByteArray()
        if (sha256(localBytes) != outbox.localSha256) return missingBase(bookId, MANIFEST_PATH, "Local manifest changed outside outbox")
        val base = trustedBase(bookId, MANIFEST_PATH, outbox) ?: return missingBase(
            bookId,
            MANIFEST_PATH,
            "Exact manifest merge base is unavailable",
        )
        val remoteHash = sha256(remoteFile.bytes)
        return when {
            remoteHash == base.sha256 -> {
                uploadConfirmed(bookId, MANIFEST_PATH, localBytes, rootPath, lock)
                false
            }
            outbox.localSha256 == base.sha256 -> {
                bookStore.writeManifest(bookId, remote)
                confirmRemote(bookId, MANIFEST_PATH, remoteFile.bytes, remoteFile.revision)
                metadata.removeOutbox(bookId, MANIFEST_PATH)
                false
            }
            else -> {
                conflicts.replace(
                    bookId,
                    SyncConflict.Manifest(
                        MANIFEST_PATH,
                        local,
                        remote,
                        remoteFile.bytes,
                        remoteFile.revision,
                    ),
                )
                true
            }
        }
    }

    private suspend fun processReview(
        bookId: String,
        path: String,
        remoteFile: RemoteFile,
        remote: ReviewDocument,
        outbox: OutboxEntity?,
        rootPath: String,
        lock: SyncLock,
    ): Boolean {
        if (outbox == null) {
            bookStore.writeReview(bookId, path, remote)
            confirmRemote(bookId, path, remoteFile.bytes, remoteFile.revision)
            conflicts.remove(bookId, path)
            return false
        }
        val local = bookStore.readReview(bookId, path)
            ?: return missingBase(bookId, path, "Pending review is missing from the local cache")
        val localBytes = ReviewJson.encode(local).encodeToByteArray()
        if (sha256(localBytes) != outbox.localSha256) return missingBase(bookId, path, "Local review changed outside outbox")
        val base = trustedBase(bookId, path, outbox)
            ?: return missingBase(bookId, path, "Exact review merge base is unavailable")
        val remoteHash = sha256(remoteFile.bytes)
        if (remoteHash == base.sha256) {
            uploadConfirmed(bookId, path, localBytes, rootPath, lock)
            return false
        }
        if (outbox.localSha256 == base.sha256) {
            bookStore.writeReview(bookId, path, remote)
            confirmRemote(bookId, path, remoteFile.bytes, remoteFile.revision)
            metadata.removeOutbox(bookId, path)
            return false
        }
        val baseDocument = ReviewJson.decode(base.bytes.decodeToString(), remote.chapterId, remote.sourcePath)
        return when (val merge = ReviewMerge.merge(baseDocument, local, remote)) {
            is MergeResult.Conflicted -> {
                conflicts.replace(
                    bookId,
                    SyncConflict.Review(path, merge.partial, merge.conflicts, remoteFile.bytes, remoteFile.revision),
                )
                true
            }
            is MergeResult.Merged -> {
                val revision = bookStore.writeReview(bookId, path, merge.document)
                metadata.recordOutbox(outbox.copy(localSha256 = revision.sha256, baseSha256 = base.sha256))
                uploadConfirmed(bookId, path, ReviewJson.encode(merge.document).encodeToByteArray(), rootPath, lock)
                false
            }
        }
    }

    private suspend fun uploadNewManifest(
        bookId: String,
        local: BookManifest,
        outbox: OutboxEntity,
        rootPath: String,
        lock: SyncLock,
    ) {
        val bytes = BookManifest.encode(local).encodeToByteArray()
        require(sha256(bytes) == outbox.localSha256) { "Local manifest changed outside outbox" }
        uploadConfirmed(bookId, MANIFEST_PATH, bytes, rootPath, lock)
    }

    private suspend fun uploadNewReview(
        bookId: String,
        path: String,
        outbox: OutboxEntity,
        rootPath: String,
        lock: SyncLock,
    ) {
        val local = requireNotNull(bookStore.readReview(bookId, path)) { "Pending review is missing from the local cache" }
        val bytes = ReviewJson.encode(local).encodeToByteArray()
        require(sha256(bytes) == outbox.localSha256) { "Local review changed outside outbox" }
        uploadConfirmed(bookId, path, bytes, rootPath, lock)
    }

    private suspend fun uploadConfirmed(
        bookId: String,
        path: String,
        bytes: ByteArray,
        rootPath: String,
        lock: SyncLock,
    ) {
        val revision = gateway.uploadGuarded(rootPath, path, bytes, lock)
        confirmRemote(bookId, path, bytes, revision)
        metadata.removeOutbox(bookId, path)
        conflicts.remove(bookId, path)
    }

    private suspend fun confirmRemote(bookId: String, path: String, bytes: ByteArray, revision: String) {
        val base = writeDurableBase(bookId, path, bytes, revision)
        metadata.recordBase(MergeBaseEntity(bookId, path, base.sha256, revision))
        metadata.recordRemote(RemoteRevisionEntity(bookId, path, revision, base.sha256))
    }

    private fun writeDurableBase(bookId: String, path: String, bytes: ByteArray, revision: String): SyncBase {
        val base = baseStore.write(bookId, path, bytes, revision)
        require(base.directorySyncStatus == DirectorySyncStatus.SYNCED) {
            "Sync base directory durability is unsupported; remote confirmation remains pending"
        }
        return base
    }

    private fun failureDetail(error: Throwable): String = buildList {
        var current: Throwable? = error
        while (current != null) {
            current.message?.takeIf(String::isNotBlank)?.let(::add)
            current = current.cause
        }
    }.distinct().joinToString(": ").ifBlank { error::class.simpleName ?: "unknown error" }

    private suspend fun trustedBase(bookId: String, path: String, outbox: OutboxEntity): SyncBase? {
        val metadataBase = metadata.mergeBase(bookId, path) ?: return null
        val base = baseStore.read(bookId, path) ?: return null
        return base.takeIf {
            outbox.baseSha256 == it.sha256 &&
                metadataBase.sha256 == it.sha256 &&
                metadataBase.remoteRevision == it.remoteRevision
        }
    }

    private fun missingBase(bookId: String, path: String, detail: String): Boolean {
        conflicts.replace(bookId, SyncConflict.MissingBase(path, detail))
        return true
    }

    private fun setStatus(bookId: String, status: SyncStatus) {
        statuses.update { current -> current + (bookId to status) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST_PATH = ".pocket-editor.json"
        const val REVIEW_SUFFIX = ".review.json"
    }
}
