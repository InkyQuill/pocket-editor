package net.inkyquill.pocketeditor.yandex

import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

data class RemoteEntry(
    val name: String,
    val path: String,
    val type: String,
    val size: Long?,
    val revision: String,
)

class RemoteFile(
    val path: String,
    val bytes: ByteArray,
    val revision: String,
) {
    override fun equals(other: Any?): Boolean =
        other is RemoteFile && path == other.path && bytes.contentEquals(other.bytes) && revision == other.revision

    override fun hashCode(): Int = 31 * (31 * path.hashCode() + bytes.contentHashCode()) + revision.hashCode()
}

data class SyncLock(
    val schemaVersion: Int,
    val lockId: String,
    val holderId: String,
    val createdAt: Instant,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
        require(runCatching { UUID.fromString(lockId).toString() == lockId }.getOrDefault(false))
        require(holderId.isNotBlank())
    }

    fun json(): String = buildJsonObject {
        put("schema_version", JsonPrimitive(schemaVersion))
        put("lock_id", JsonPrimitive(lockId))
        put("holder_id", JsonPrimitive(holderId))
        put("created_at", JsonPrimitive(createdAt.toString()))
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1
        private val fields = setOf("schema_version", "lock_id", "holder_id", "created_at")

        fun fromJson(value: String): SyncLock = try {
            val objectValue = Json.parseToJsonElement(value) as? JsonObject
                ?: throw IllegalArgumentException("Lock must be an object")
            require(objectValue.keys == fields)
            val schema = objectValue.getValue("schema_version").jsonPrimitive
            require(!schema.isString && schema.content == SCHEMA_VERSION.toString())
            val lockId = objectValue.requiredString("lock_id")
            val holderId = objectValue.requiredString("holder_id")
            val createdAtText = objectValue.requiredString("created_at")
            require(createdAtText.endsWith('Z'))
            val createdAt = Instant.parse(createdAtText)
            require(createdAt.toString() == createdAtText)
            SyncLock(
                schemaVersion = schema.int,
                lockId = lockId,
                holderId = holderId,
                createdAt = createdAt,
            )
        } catch (error: YandexDiskError.InvalidRemote) {
            throw error
        } catch (error: Exception) {
            throw YandexDiskError.InvalidRemote("Invalid cooperative lock", error)
        }
    }
}

private fun JsonObject.requiredString(name: String): String {
    val primitive = getValue(name).jsonPrimitive
    require(primitive.isString)
    return primitive.content
}

sealed class YandexDiskError(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Offline(cause: IOException) : YandexDiskError("Yandex Disk is offline", cause)
    class Unauthorized : YandexDiskError("Yandex authorization is required")
    class NotFound : YandexDiskError("Remote resource was not found")
    class LockHeld : YandexDiskError("The cooperative lock is already held")
    class LockLost : YandexDiskError("The cooperative lock is no longer owned")
    class AlreadyExists : YandexDiskError("Remote resource already exists")
    class RateLimited(val retryAfterSeconds: Long?) : YandexDiskError("Yandex Disk rate limit reached")
    class InvalidRemote(message: String, cause: Throwable? = null) : YandexDiskError(message, cause)
    class ServerFailure(
        val statusCode: Int,
        val retryAfterSeconds: Long? = null,
    ) : YandexDiskError("Yandex Disk server failure ($statusCode)")
    class UploadIncomplete : YandexDiskError("Accepted upload did not become observable in time")
    class ConcurrentRemoteChange(val observed: RemoteFile?) :
        YandexDiskError("Remote resource changed during conditional publication")
    class PublicationPreconditionFailed : YandexDiskError("Manifest publication precondition changed")
    class CandidateCleanupUnconfirmed(
        val candidateLock: SyncLock,
        val verificationFailure: Throwable,
        val cleanupFailure: Throwable,
    ) : YandexDiskError(
        "Cleanup of candidate lock ${candidateLock.lockId} could not be confirmed",
        verificationFailure,
    ) {
        init {
            addSuppressed(cleanupFailure)
        }
    }
}

interface YandexDiskGateway {
    suspend fun listFolder(path: String): List<RemoteEntry>
    suspend fun download(path: String): RemoteFile
    suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock
    suspend fun readLock(rootPath: String): SyncLock
    suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock): String
    /**
     * Publishes the binder without overwriting a canonical remote resource. [beforeTransaction]
     * is the last feasible source-file revalidation seam, but Yandex Disk does not offer one
     * transaction spanning those source files and the binder; a later external source change is
     * therefore detected by the next sync rather than claimed as part of this publication.
     */
    suspend fun uploadManifestConditionally(
        rootPath: String,
        bytes: ByteArray,
        expected: RemoteFile?,
        ownedLock: SyncLock,
        beforeTransaction: suspend () -> Boolean = { true },
    ): String
    suspend fun recoverManifestPublication(rootPath: String, ownedLock: SyncLock)
    suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock)
    suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock)
}

class OkHttpYandexDiskGateway(
    client: OkHttpClient,
    apiBaseUrl: HttpUrl,
    private val completionAttempts: Int = DEFAULT_COMPLETION_ATTEMPTS,
    private val completionDelay: suspend () -> Unit = { delay(DEFAULT_COMPLETION_DELAY_MILLIS) },
    now: () -> Instant = Instant::now,
    accessToken: suspend () -> SecretToken,
) : YandexDiskGateway {
    init {
        require(completionAttempts > 0)
    }

    private val api = YandexDiskApi(client, apiBaseUrl, accessToken, now)

    override suspend fun listFolder(path: String): List<RemoteEntry> {
        val entries = mutableListOf<RemoteEntry>()
        var offset = 0
        do {
            val page = api.listFolder(path, offset)
            entries += page.items.map { item ->
                RemoteEntry(
                    item.name,
                    item.path.requireField("path"),
                    item.type,
                    item.size,
                    item.revision?.content.requireField("revision"),
                )
            }
            if (page.items.isEmpty() && offset < page.total) {
                throw YandexDiskError.InvalidRemote("Folder pagination did not advance")
            }
            offset += page.items.size
        } while (offset < page.total)
        return entries
    }

    override suspend fun download(path: String): RemoteFile {
        val metadata = api.metadata(path)
        val link = api.downloadLink(path)
        return RemoteFile(
            path = metadata.path.requireField("path"),
            bytes = api.download(link),
            revision = metadata.revision?.content.requireField("revision"),
        )
    }

    override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock =
        tryAcquireLock(rootPath, lock, allowReclaim = true)

    /**
     * [allowReclaim] bounds the self-reclaim behavior to a single retry: an orphaned lock
     * belonging to this same device (matching [SyncLock.holderId]) is not a real conflict and is
     * taken over automatically, but only once per call, to avoid looping if the remote path keeps
     * producing conflicts.
     */
    private suspend fun tryAcquireLock(rootPath: String, lock: SyncLock, allowReclaim: Boolean): SyncLock {
        val lockPath = lockPath(rootPath)
        var candidatePutStarted = false
        try {
            val link = api.uploadLink(lockPath, overwrite = false, lockAcquisition = true)
            val result = api.upload(
                link,
                lock.json().toByteArray(),
                lockAcquisition = true,
                onRequestStarted = { candidatePutStarted = true },
            )
            val remote = if (result == TransferResult.ACCEPTED) {
                awaitLock(rootPath, lock)
            } else {
                readLock(rootPath)
            }
            if (remote.lockId != lock.lockId) throw YandexDiskError.LockLost()
            return remote
        } catch (failure: Exception) {
            if (failure is YandexDiskError.LockHeld) {
                if (allowReclaim && reclaimIfOwnStaleLock(rootPath, lock)) {
                    return tryAcquireLock(rootPath, lock, allowReclaim = false)
                }
                throw failure
            }
            if (!candidatePutStarted) throw failure
            return throwAfterCandidateRecovery(rootPath, lock, failure, allowReclaim)
        }
    }

    /**
     * Returns true when the path is safe to retry: either nothing is there anymore, or the lock
     * that is there belongs to this same device (and has just been removed). A lock belonging to
     * a different device is left untouched and this returns false.
     */
    private suspend fun reclaimIfOwnStaleLock(rootPath: String, candidate: SyncLock): Boolean =
        withContext(NonCancellable) {
            val existing = try {
                readLock(rootPath)
            } catch (_: YandexDiskError.NotFound) {
                return@withContext true
            } catch (_: Throwable) {
                return@withContext false
            }
            if (existing.holderId != candidate.holderId) return@withContext false
            try {
                api.delete(lockPath(rootPath))
                true
            } catch (_: Throwable) {
                false
            }
        }

    private suspend fun throwAfterCandidateRecovery(
        rootPath: String,
        candidate: SyncLock,
        failure: Throwable,
        allowReclaim: Boolean,
    ): SyncLock {
        val recovery = withContext(NonCancellable) {
            val observed = try {
                readLock(rootPath)
            } catch (_: YandexDiskError.NotFound) {
                return@withContext CandidateRecovery.Absent
            } catch (error: Throwable) {
                return@withContext CandidateRecovery.Uncertain(error)
            }
            if (observed.lockId != candidate.lockId) {
                if (observed.holderId != candidate.holderId) return@withContext CandidateRecovery.Foreign
                return@withContext try {
                    api.delete(lockPath(rootPath))
                    CandidateRecovery.OwnStaleReclaimed
                } catch (error: Throwable) {
                    CandidateRecovery.Uncertain(error)
                }
            }
            try {
                releaseOwnedLock(rootPath, candidate)
                CandidateRecovery.Cleaned
            } catch (error: Throwable) {
                CandidateRecovery.Uncertain(error)
            }
        }
        return when (recovery) {
            CandidateRecovery.Absent,
            CandidateRecovery.Cleaned,
            -> throw failure
            CandidateRecovery.OwnStaleReclaimed -> {
                if (!allowReclaim || failure is CancellationException) throw failure
                tryAcquireLock(rootPath, candidate, allowReclaim = false)
            }
            CandidateRecovery.Foreign -> {
                if (failure is CancellationException) throw failure
                throw YandexDiskError.LockHeld().also { it.addSuppressed(failure) }
            }
            is CandidateRecovery.Uncertain -> {
                val actionable = YandexDiskError.CandidateCleanupUnconfirmed(candidate, failure, recovery.error)
                if (failure is CancellationException) {
                    failure.addSuppressed(actionable)
                    throw failure
                }
                throw actionable
            }
        }
    }

    private sealed interface CandidateRecovery {
        data object Absent : CandidateRecovery
        data object Foreign : CandidateRecovery
        data object Cleaned : CandidateRecovery
        data object OwnStaleReclaimed : CandidateRecovery
        data class Uncertain(val error: Throwable) : CandidateRecovery
    }

    override suspend fun readLock(rootPath: String): SyncLock =
        SyncLock.fromJson(download(lockPath(rootPath)).bytes.toString(Charsets.UTF_8))

    override suspend fun uploadGuarded(
        rootPath: String,
        relativePath: String,
        bytes: ByteArray,
        ownedLock: SyncLock,
    ): String {
        requireCanonicalWritePath(relativePath)
        val remotePath = childPath(rootPath, relativePath)
        val baselineRevision = captureBaselineRevision(remotePath)
        verifyOwnership(rootPath, ownedLock)
        val link = api.uploadLink(remotePath, overwrite = true, lockAcquisition = false)
        return if (api.upload(link, bytes, lockAcquisition = false) == TransferResult.ACCEPTED) {
            awaitUploadedFile(remotePath, bytes, baselineRevision)
        } else {
            api.metadata(remotePath).revision?.content.requireField("revision")
        }
    }

    override suspend fun uploadManifestConditionally(
        rootPath: String,
        bytes: ByteArray,
        expected: RemoteFile?,
        ownedLock: SyncLock,
        beforeTransaction: suspend () -> Boolean,
    ): String {
        val manifestPath = childPath(rootPath, MANIFEST_NAME)
        if (expected == null) {
            recoverManifestPublication(rootPath, ownedLock)
            if (!beforeTransaction()) throw YandexDiskError.PublicationPreconditionFailed()
            return try {
                uploadCreateOnly(manifestPath, bytes)
            } catch (_: YandexDiskError.AlreadyExists) {
                throw YandexDiskError.ConcurrentRemoteChange(downloadOrNull(manifestPath))
            }
        }

        val transactionId = manifestTransactionId(expected.bytes, bytes)
        val candidatePath = childPath(rootPath, ".pocket-editor.manifest.next.$transactionId")
        val retiredCandidatePath = childPath(
            rootPath,
            ".pocket-editor.manifest.retired.$transactionId.${manifestContentDigest(bytes)}",
        )
        val backupPath = childPath(rootPath, ".pocket-editor.manifest.previous.$transactionId")
        val recovery = recoverManifestPublication(rootPath, ownedLock, resumableTransactionId = transactionId)
        var resumedTransaction = recovery.resumable
        var publicationSource = recovery.retiredCandidate?.also { retired ->
            if (!retired.bytes.contentEquals(bytes)) throw YandexDiskError.UploadIncomplete()
        }?.path
        try {
            if (publicationSource == null) {
                try {
                    uploadCreateOnly(candidatePath, bytes)
                } catch (_: YandexDiskError.AlreadyExists) {
                    val existing = downloadOrNull(candidatePath)
                    if (existing == null || !existing.bytes.contentEquals(bytes)) throw YandexDiskError.UploadIncomplete()
                    resumedTransaction = true
                }
                publicationSource = candidatePath
            }
            verifyOwnership(rootPath, ownedLock)
            val beforeMove = downloadOrNull(manifestPath)
            if (beforeMove != expected) {
                if (!resumedTransaction) deleteStrict(candidatePath)
                throw YandexDiskError.ConcurrentRemoteChange(beforeMove)
            }
            if (!beforeTransaction()) {
                if (!resumedTransaction) deleteStrict(candidatePath)
                throw YandexDiskError.PublicationPreconditionFailed()
            }
            val afterPrecondition = downloadOrNull(manifestPath)
            if (afterPrecondition != expected) {
                if (!resumedTransaction) deleteStrict(candidatePath)
                throw YandexDiskError.ConcurrentRemoteChange(afterPrecondition)
            }

            val existingBackup = if (resumedTransaction) downloadOrNull(backupPath) else null
            if (resumedTransaction && publicationSource == candidatePath) {
                moveCreateOnlyAndFence(candidatePath, retiredCandidatePath, bytes)
                publicationSource = retiredCandidatePath
            }
            if (existingBackup != null) {
                if (!existingBackup.bytes.contentEquals(expected.bytes)) throw YandexDiskError.UploadIncomplete()
                revalidateCanonical(manifestPath, expected)
                val transitionPath = nextTransitionPath(rootPath, transactionId, manifestContentDigest(expected.bytes))
                val transitioned = moveCreateOnlyAndObserve(manifestPath, transitionPath)
                if (!transitioned.bytes.contentEquals(expected.bytes)) {
                    val authenticatedTransitionPath = nextTransitionPath(
                        rootPath,
                        transactionId,
                        manifestContentDigest(transitioned.bytes),
                    )
                    val authenticatedTransition = moveCreateOnlyAndFence(
                        transitionPath,
                        authenticatedTransitionPath,
                        transitioned.bytes,
                    )
                    val restoredRevision = uploadCreateOnly(manifestPath, authenticatedTransition.bytes)
                    throw YandexDiskError.ConcurrentRemoteChange(
                        RemoteFile(manifestPath, authenticatedTransition.bytes, restoredRevision),
                    )
                }
            } else {
                moveCreateOnlyAndVerify(manifestPath, backupPath, expected.bytes)
            }
            val published = try {
                moveCreateOnlyAndVerify(checkNotNull(publicationSource), manifestPath, bytes)
            } catch (_: YandexDiskError.AlreadyExists) {
                throw YandexDiskError.ConcurrentRemoteChange(downloadOrNull(manifestPath))
            }
            if (!resumedTransaction) deleteStrict(backupPath)
            return published.revision
        } catch (failure: Throwable) {
            val recoveryFailure = runCatching {
                withContext(NonCancellable) { recoverManifestPublication(rootPath, ownedLock) }
            }.exceptionOrNull()
            if (recoveryFailure != null) failure.addSuppressed(recoveryFailure)
            throw failure
        }
    }

    override suspend fun recoverManifestPublication(rootPath: String, ownedLock: SyncLock) {
        recoverManifestPublication(rootPath, ownedLock, resumableTransactionId = null)
    }

    private suspend fun recoverManifestPublication(
        rootPath: String,
        ownedLock: SyncLock,
        resumableTransactionId: String?,
    ): ManifestRecoveryResult {
        verifyOwnership(rootPath, ownedLock)
        val manifestPath = childPath(rootPath, MANIFEST_NAME)
        repeat(completionAttempts) { attempt ->
            val entries = listFolder(rootPath).filter { it.type == "file" }
            val transactions = entries.mapNotNull { authenticatedArtifactName(rootPath, it) }
                .groupBy({ it.first }, { it.second })
                .toSortedMap()
            if (transactions.isEmpty()) return ManifestRecoveryResult.NotResumable
            val activeTransactions = transactions.filterValues { artifacts ->
                artifacts.any { it is TransactionArtifact.Next || it is TransactionArtifact.Retired }
            }
            val canonicalEntry = entries.singleOrNull { it.name == MANIFEST_NAME }
            if (activeTransactions.isEmpty()) {
                if (canonicalEntry != null) return ManifestRecoveryResult.NotResumable
                throw YandexDiskError.UploadIncomplete()
            }
            if (activeTransactions.size != 1) throw YandexDiskError.UploadIncomplete()

            val (transactionId, artifacts) = activeTransactions.entries.single()
            if (resumableTransactionId != null && transactionId != resumableTransactionId) {
                throw YandexDiskError.UploadIncomplete()
            }
            val previousPath = artifacts.filterIsInstance<TransactionArtifact.Previous>().singleOrNull()?.path
            val nextPath = artifacts.filterIsInstance<TransactionArtifact.Next>().singleOrNull()?.path
            val retiredArtifact = artifacts.filterIsInstance<TransactionArtifact.Retired>().singleOrNull()
            val transitions = artifacts.filterIsInstance<TransactionArtifact.Transition>().sortedBy { it.index }
            val canonical = canonicalEntry?.let { download(it.path) }
            val previous = previousPath?.let { download(it) }
            val next = nextPath?.let { download(it) }
            val retired = retiredArtifact?.let { artifact ->
                download(artifact.path).also { file ->
                    if (manifestContentDigest(file.bytes) != artifact.contentDigest) {
                        throw YandexDiskError.UploadIncomplete()
                    }
                }
            }
            if (next != null && retired != null) throw YandexDiskError.UploadIncomplete()
            val candidate = retired ?: next
            val transitionFiles = transitions.map { transition -> transition to download(transition.path) }
            if (transitionFiles.any { (artifact, file) ->
                    manifestContentDigest(file.bytes) != artifact.contentDigest
                }
            ) {
                throw YandexDiskError.UploadIncomplete()
            }
            val authenticated = when {
                previous != null && candidate != null -> manifestTransactionId(previous.bytes, candidate.bytes) == transactionId
                canonical != null && previous != null -> manifestTransactionId(previous.bytes, canonical.bytes) == transactionId
                canonical != null && candidate != null -> manifestTransactionId(canonical.bytes, candidate.bytes) == transactionId
                else -> false
            }
            if (!authenticated) throw YandexDiskError.UploadIncomplete()
            if (
                canonical != null && previous != null && candidate != null &&
                !canonical.bytes.contentEquals(previous.bytes) &&
                !canonical.bytes.contentEquals(candidate.bytes) &&
                transitionFiles.none { (_, file) -> canonical.bytes.contentEquals(file.bytes) }
            ) {
                throw YandexDiskError.UploadIncomplete()
            }

            if (canonical == null) {
                if (previous != null && candidate != null) {
                    val restorationSource = transitionFiles.lastOrNull()?.second ?: previous
                    try {
                        uploadCreateOnly(manifestPath, restorationSource.bytes)
                    } catch (_: YandexDiskError.AlreadyExists) {
                        if (downloadOrNull(manifestPath) == null) throw YandexDiskError.UploadIncomplete()
                    }
                    revalidateCanonicalBytes(manifestPath, restorationSource.bytes)
                    return ManifestRecoveryResult(
                        resumable = transactionId == resumableTransactionId,
                        retiredCandidate = retired,
                    )
                }
                throw YandexDiskError.UploadIncomplete()
            }

            if (previous == null && candidate != null) {
                if (transactionId == resumableTransactionId) {
                    return ManifestRecoveryResult(resumable = true, retiredCandidate = retired)
                }
                if (attempt + 1 < completionAttempts) {
                    completionDelay()
                    return@repeat
                }
                return ManifestRecoveryResult.NotResumable
            }
            return ManifestRecoveryResult(
                resumable = transactionId == resumableTransactionId,
                retiredCandidate = retired,
            )
        }
        throw YandexDiskError.UploadIncomplete()
    }

    override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) {
        verifyOwnership(rootPath, ownedLock)
        api.delete(lockPath(rootPath))
    }

    override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) {
        val current = try {
            readLock(rootPath)
        } catch (_: YandexDiskError.NotFound) {
            throw YandexDiskError.LockLost()
        }
        if (current.lockId != observedLock.lockId) throw YandexDiskError.LockLost()
        api.delete(lockPath(rootPath))
    }

    private suspend fun verifyOwnership(rootPath: String, ownedLock: SyncLock) {
        val remoteLock = try {
            readLock(rootPath)
        } catch (_: YandexDiskError.NotFound) {
            throw YandexDiskError.LockLost()
        }
        if (remoteLock.lockId != ownedLock.lockId) throw YandexDiskError.LockLost()
    }

    private suspend fun awaitLock(rootPath: String, ownedLock: SyncLock): SyncLock {
        repeat(completionAttempts) { attempt ->
            val remote = try {
                readLock(rootPath)
            } catch (_: YandexDiskError.NotFound) {
                null
            }
            if (remote != null) {
                if (remote.lockId != ownedLock.lockId) throw YandexDiskError.LockLost()
                return remote
            }
            if (attempt + 1 < completionAttempts) completionDelay()
        }
        throw YandexDiskError.UploadIncomplete()
    }

    private suspend fun captureBaselineRevision(path: String): String? = try {
        api.metadata(path).revision?.content.requireField("revision")
    } catch (_: YandexDiskError.NotFound) {
        null
    }

    private suspend fun uploadCreateOnly(path: String, bytes: ByteArray): String {
        val link = api.uploadLink(path, overwrite = false, lockAcquisition = false)
        api.upload(link, bytes, lockAcquisition = false, exclusiveWrite = true)
        return awaitUploadedFile(path, bytes, baselineRevision = null)
    }

    private suspend fun downloadOrNull(path: String): RemoteFile? = try {
        download(path)
    } catch (_: YandexDiskError.NotFound) {
        null
    }

    private suspend fun moveCreateOnlyAndVerify(from: String, path: String, expected: ByteArray): RemoteFile {
        val moved = moveCreateOnlyAndObserve(from, path)
        if (moved.bytes.contentEquals(expected)) return moved
        throw YandexDiskError.UploadIncomplete()
    }

    private suspend fun moveCreateOnlyAndFence(from: String, path: String, expected: ByteArray): RemoteFile {
        when (val move = api.moveCreateOnly(from, path)) {
            MoveResult.Completed -> Unit
            is MoveResult.Accepted -> awaitOperation(move.operation)
        }
        repeat(completionAttempts) { attempt ->
            val target = downloadOrNull(path)
            val source = downloadOrNull(from)
            if (target != null && target.bytes.contentEquals(expected) && source == null) return target
            if (target != null && !target.bytes.contentEquals(expected)) throw YandexDiskError.UploadIncomplete()
            if (attempt + 1 < completionAttempts) completionDelay()
        }
        throw YandexDiskError.UploadIncomplete()
    }

    private suspend fun moveCreateOnlyAndObserve(from: String, path: String): RemoteFile {
        when (val move = api.moveCreateOnly(from, path)) {
            MoveResult.Completed -> Unit
            is MoveResult.Accepted -> awaitOperation(move.operation)
        }
        repeat(completionAttempts) { attempt ->
            downloadOrNull(path)?.let { return it }
            if (attempt + 1 < completionAttempts) completionDelay()
        }
        throw YandexDiskError.UploadIncomplete()
    }

    private suspend fun awaitOperation(operation: LinkDto) {
        repeat(completionAttempts) { attempt ->
            when (api.operationStatus(operation).status) {
                "success" -> return
                "in-progress" -> Unit
                "failed" -> throw YandexDiskError.InvalidRemote("Yandex Disk move operation failed")
                else -> throw YandexDiskError.InvalidRemote("Unknown Yandex Disk operation status")
            }
            if (attempt + 1 < completionAttempts) completionDelay()
        }
        throw YandexDiskError.UploadIncomplete()
    }

    private suspend fun deleteStrict(path: String) {
        try {
            api.delete(path)
        } catch (_: YandexDiskError.NotFound) {
            Unit
        }
    }

    private fun authenticatedArtifactName(rootPath: String, entry: RemoteEntry): Pair<String, TransactionArtifact>? {
        if (entry.path != childPath(rootPath, entry.name)) return null
        val name = parseManifestRecoveryArtifactName(entry.name) ?: return null
        val artifact = when (name) {
            is ManifestRecoveryArtifactName.Previous -> TransactionArtifact.Previous(entry.path)
            is ManifestRecoveryArtifactName.Next -> TransactionArtifact.Next(entry.path)
            is ManifestRecoveryArtifactName.Retired -> TransactionArtifact.Retired(entry.path, name.contentDigest)
            is ManifestRecoveryArtifactName.Transition ->
                TransactionArtifact.Transition(entry.path, name.contentDigest, name.index)
        }
        return name.transactionId to artifact
    }

    private suspend fun nextTransitionPath(rootPath: String, transactionId: String, contentDigest: String): String {
        val indexes = listFolder(rootPath)
            .mapNotNull { authenticatedArtifactName(rootPath, it)?.takeIf { pair -> pair.first == transactionId }?.second }
            .filterIsInstance<TransactionArtifact.Transition>()
            .map(TransactionArtifact.Transition::index)
        val nextIndex = nextManifestTransitionIndex(indexes)
        return childPath(rootPath, ".pocket-editor.manifest.transition.$transactionId.$contentDigest.$nextIndex")
    }

    private suspend fun revalidateCanonical(path: String, expected: RemoteFile) {
        val current = downloadOrNull(path) ?: throw YandexDiskError.UploadIncomplete()
        if (current != expected) throw YandexDiskError.ConcurrentRemoteChange(current)
    }

    private suspend fun revalidateCanonicalBytes(path: String, expected: ByteArray) {
        val current = downloadOrNull(path) ?: throw YandexDiskError.UploadIncomplete()
        if (!current.bytes.contentEquals(expected)) throw YandexDiskError.ConcurrentRemoteChange(current)
    }

    private suspend fun awaitUploadedFile(
        path: String,
        expected: ByteArray,
        baselineRevision: String?,
    ): String {
        repeat(completionAttempts) { attempt ->
            val remote = try {
                download(path)
            } catch (_: YandexDiskError.NotFound) {
                null
            }
            if (remote != null) {
                val confirmedRevision = try {
                    api.metadata(path).revision?.content.requireField("revision")
                } catch (_: YandexDiskError.NotFound) {
                    null
                }
                val isNewObservation = baselineRevision == null || remote.revision != baselineRevision
                if (
                    remote.revision == confirmedRevision &&
                    isNewObservation &&
                    remote.bytes.contentEquals(expected)
                ) {
                    return remote.revision
                }
            }
            if (attempt + 1 < completionAttempts) completionDelay()
        }
        throw YandexDiskError.UploadIncomplete()
    }

    private fun requireCanonicalWritePath(relativePath: String) {
        require(
            relativePath == MANIFEST_NAME ||
                (relativePath.length > REVIEW_SUFFIX.length &&
                    relativePath.endsWith(REVIEW_SUFFIX) &&
                    '/' !in relativePath &&
                    '\\' !in relativePath),
        ) { "Remote writes are limited to Pocket Editor metadata" }
    }

    private fun lockPath(rootPath: String): String = childPath(rootPath, LOCK_NAME)

    private fun childPath(rootPath: String, name: String): String = "${rootPath.trimEnd('/')}/$name"

    private fun manifestTransactionId(previous: ByteArray, next: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(previous + byteArrayOf(0) + next)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(24)

    private fun manifestContentDigest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(24)

    private sealed interface TransactionArtifact {
        data class Previous(val path: String) : TransactionArtifact
        data class Next(val path: String) : TransactionArtifact
        data class Retired(val path: String, val contentDigest: String) : TransactionArtifact
        data class Transition(val path: String, val contentDigest: String, val index: Int) : TransactionArtifact
    }

    private data class ManifestRecoveryResult(
        val resumable: Boolean,
        val retiredCandidate: RemoteFile?,
    ) {
        companion object {
            val NotResumable = ManifestRecoveryResult(resumable = false, retiredCandidate = null)
        }
    }

    private fun String?.requireField(name: String): String =
        this?.takeIf(String::isNotBlank) ?: throw YandexDiskError.InvalidRemote("Missing $name")

    private companion object {
        const val LOCK_NAME = ".pocket-editor.sync.lock"
        const val MANIFEST_NAME = ".pocket-editor.json"
        const val REVIEW_SUFFIX = ".review.json"
        const val DEFAULT_COMPLETION_ATTEMPTS = 5
        const val DEFAULT_COMPLETION_DELAY_MILLIS = 250L
    }
}
