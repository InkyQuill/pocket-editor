package net.inkyquill.pocketeditor.yandex

import java.io.IOException
import java.time.Instant
import java.util.UUID
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
        require(runCatching { UUID.fromString(lockId) }.isSuccess)
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
            SyncLock(
                schemaVersion = objectValue.getValue("schema_version").jsonPrimitive.int,
                lockId = objectValue.getValue("lock_id").jsonPrimitive.content,
                holderId = objectValue.getValue("holder_id").jsonPrimitive.content,
                createdAt = Instant.parse(objectValue.getValue("created_at").jsonPrimitive.content),
            )
        } catch (error: YandexDiskError.InvalidRemote) {
            throw error
        } catch (error: Exception) {
            throw YandexDiskError.InvalidRemote("Invalid cooperative lock", error)
        }
    }
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
}

interface YandexDiskGateway {
    suspend fun listFolder(path: String): List<RemoteEntry>
    suspend fun download(path: String): RemoteFile
    suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock
    suspend fun readLock(rootPath: String): SyncLock
    suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock): String
    suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock)
}

class OkHttpYandexDiskGateway(
    client: OkHttpClient,
    apiBaseUrl: HttpUrl,
    accessToken: suspend () -> SecretToken,
) : YandexDiskGateway {
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
        val link = api.uploadLink(lockPath, overwrite = false)
        api.upload(link, lock.json().toByteArray(), lockAcquisition = true)
        val remote = readLock(rootPath)
        if (remote.lockId != lock.lockId) throw YandexDiskError.LockLost()
        return remote
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
        verifyOwnership(rootPath, ownedLock)
        val remotePath = childPath(rootPath, relativePath)
        val link = api.uploadLink(remotePath, overwrite = true)
        api.upload(link, bytes, lockAcquisition = false)
        return api.metadata(remotePath).revision.requireField("revision")
    }

    override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) {
        verifyOwnership(rootPath, ownedLock)
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
    }
}
