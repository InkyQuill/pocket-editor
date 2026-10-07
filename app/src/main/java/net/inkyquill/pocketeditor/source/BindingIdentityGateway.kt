package net.inkyquill.pocketeditor.source

import android.content.SharedPreferences
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.StrictUtf8
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskError

@Serializable
data class BindingIdentity(val sourceBookId: String, val cacheBookId: String)
interface BindingIdentityStore {
    fun get(root: String): BindingIdentity?
    fun put(root: String, identity: BindingIdentity)
}
class PreferencesBindingIdentityStore(private val preferences: SharedPreferences) : BindingIdentityStore {
    override fun get(root: String): BindingIdentity? = preferences.getString(root, null)?.let { Json.decodeFromString(it) }
    override fun put(root: String, identity: BindingIdentity) {
        check(preferences.edit().putString(root, Json.encodeToString(identity)).commit()) { "Cannot persist book binding" }
    }
}

/** Cache IDs are binding-scoped; wire book/chapter IDs are never changed on the server. */
class BindingIdentityGateway(private val remote: BookGateway, private val identities: BindingIdentityStore) : BookGateway by remote {
    override suspend fun download(path: String): RemoteFile {
        val file = remote.download(path)
        if (!path.endsWith("/" + BookPaths.MANIFEST_NAME)) return file
        return localize(file)
    }
    private fun localize(file: RemoteFile): RemoteFile {
        val root = file.path.substringBeforeLast('/')
        val document = Json.parseToJsonElement(StrictUtf8.decode(file.bytes, "Book manifest")).jsonObject
        val sourceId = document.getValue("book_id").jsonPrimitive.content
        val identity = synchronized(identities) {
            val existing = identities.get(root)
            if (existing != null && existing.sourceBookId != sourceId) {
                throw YandexDiskError.InvalidRemote("Remote manifest book_id does not match the stored binding")
            }
            existing ?: BindingIdentity(
                sourceId,
                UUID.nameUUIDFromBytes("pocket-binding\n$root\n$sourceId".encodeToByteArray()).toString(),
            ).also { identities.put(root, it) }
        }
        return file.withBookId(identity.cacheBookId)
    }
    override suspend fun uploadManifestConditionally(rootPath: String, bytes: ByteArray, expected: RemoteFile?, ownedLock: SyncLock, beforeTransaction: suspend () -> Boolean): String {
        val root = rootPath.trimEnd('/')
        val local = RemoteFile("$root/${BookPaths.MANIFEST_NAME}", bytes, "")
        val cacheId = Json.parseToJsonElement(StrictUtf8.decode(bytes, "Book manifest")).jsonObject.getValue("book_id").jsonPrimitive.content
        val identity = synchronized(identities) {
            identities.get(root) ?: BindingIdentity(cacheId, cacheId).also { identities.put(root, it) }
        }
        require(identity.cacheBookId == cacheId) { "Book binding identity changed" }
        return try {
            remote.uploadManifestConditionally(root, local.withBookId(identity.sourceBookId).bytes,
                expected?.withBookId(identity.sourceBookId), ownedLock, beforeTransaction)
        } catch (changed: YandexDiskError.ConcurrentRemoteChange) {
            throw YandexDiskError.ConcurrentRemoteChange(changed.observed?.let(::localize))
        }
    }
    private fun RemoteFile.withBookId(id: String): RemoteFile {
        val document = Json.parseToJsonElement(StrictUtf8.decode(bytes, "Book manifest")).jsonObject
        if (document.getValue("book_id").jsonPrimitive.content == id) return this
        val mapped = JsonObject(document + ("book_id" to JsonPrimitive(id)))
        return RemoteFile(path, mapped.toString().encodeToByteArray(), revision)
    }
}
