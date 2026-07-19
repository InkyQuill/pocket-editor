package net.inkyquill.pocketeditor.yandex

import java.io.IOException
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
    class RateLimited(val retryAfterSeconds: Long?) : YandexDiskError("Yandex Disk rate limit reached")
    class InvalidRemote(message: String, cause: Throwable? = null) : YandexDiskError(message, cause)
    class ServerFailure(val statusCode: Int) : YandexDiskError("Yandex Disk server failure ($statusCode)")
    class UploadIncomplete : YandexDiskError("Accepted upload did not become observable in time")
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
    suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock)
    suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock)
}

class OkHttpYandexDiskGateway(
    client: OkHttpClient,
    apiBaseUrl: HttpUrl,
    private val completionAttempts: Int = DEFAULT_COMPLETION_ATTEMPTS,
    private val completionDelay: suspend () -> Unit = { delay(DEFAULT_COMPLETION_DELAY_MILLIS) },
    accessToken: suspend () -> SecretToken,
) : YandexDiskGateway {
    init {
        require(completionAttempts > 0)
    }

    private val api = YandexDiskApi(client, apiBaseUrl, accessToken)

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
                    item.revision.requireField("revision"),
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
            revision = metadata.revision.requireField("revision"),
        )
    }

    override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock {
        val lockPath = lockPath(rootPath)
        val link = api.uploadLink(lockPath, overwrite = false, lockAcquisition = true)
        var candidatePutAccepted = false
        try {
            val result = api.upload(link, lock.json().toByteArray(), lockAcquisition = true)
            candidatePutAccepted = true
            val remote = if (result == TransferResult.ACCEPTED) {
                awaitLock(rootPath, lock)
            } else {
                readLock(rootPath)
            }
            if (remote.lockId != lock.lockId) throw YandexDiskError.LockLost()
            return remote
        } catch (failure: Throwable) {
            if (!candidatePutAccepted) throw failure
            val cleanup = runCatching {
                withContext(NonCancellable) { releaseOwnedLock(rootPath, lock) }
            }.exceptionOrNull()
            if (cleanup != null && failure is CancellationException) {
                failure.addSuppressed(cleanup)
                throw failure
            }
            if (cleanup != null) {
                throw YandexDiskError.CandidateCleanupUnconfirmed(lock, failure, cleanup)
            }
            throw failure
        }
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
            api.metadata(remotePath).revision.requireField("revision")
        }
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
        api.metadata(path).revision.requireField("revision")
    } catch (_: YandexDiskError.NotFound) {
        null
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
                    api.metadata(path).revision.requireField("revision")
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
