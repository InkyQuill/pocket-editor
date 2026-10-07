package net.inkyquill.pocketeditor.edda

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class EddaProject(val id: String, val title: String, val storageMode: String)
@Serializable
data class EddaEntry(val id: String, val path: String, val kind: String, val sha256: String = "", val bytes: Long = 0)
@Serializable
data class EddaVersion(val id: String, val entries: List<EddaEntry> = emptyList())
@Serializable
internal data class EddaAuthor(val id: String, val email: String)
@Serializable
internal class EddaLogin(val token: String, val author: EddaAuthor)
class EddaCredentials(val server: String, val token: String)

class EddaClient(client: OkHttpClient, private val credentials: (String) -> EddaCredentials) {
    // Never forward bearer credentials through a server-controlled redirect.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    internal suspend fun login(server: String, email: String, password: String): EddaLogin {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString()
        return json.decodeFromString(request(serverUrl(server).newBuilder().addPathSegments("auth/login").build(), "POST", body.jsonBody(), null).decodeToString())
    }
    suspend fun projects(account: String): List<EddaProject> = json.decodeFromString<List<EddaProject>>(
        call(account, listOf("projects")).decodeToString(),
    ).filter { it.storageMode == "files" }
    suspend fun version(location: EddaLocation): EddaVersion = json.decodeFromString(
        call(location.account, fileRoute(location) + listOf("versions", "current")).decodeToString(),
    )
    suspend fun download(location: EddaLocation, version: EddaVersion, entry: EddaEntry): ByteArray {
        val bytes = call(location.account, fileRoute(location) + listOf("versions", version.id, "entries", entry.id))
        if (bytes.size.toLong() != entry.bytes || bytes.sha256() != entry.sha256) {
            throw YandexDiskError.InvalidRemote("Edda file checksum mismatch")
        }
        return bytes
    }
    suspend fun upload(location: EddaLocation, bytes: ByteArray) {
        call(location.account, fileRoute(location) + listOf("objects", bytes.sha256()), "PUT", bytes.toRequestBody(OCTETS))
    }
    suspend fun publish(location: EddaLocation, expected: EddaVersion, entries: List<EddaEntry>, operationId: String): EddaVersion {
        val body = buildJsonObject {
            put("expectedVersion", expected.id)
            put("operationId", operationId)
            put("message", "Pocket Editor review sync")
            put("entries", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(EddaEntry.serializer()), entries))
        }.toString().jsonBody()
        val route = fileRoute(location)
        return try {
            json.decodeFromString(call(location.account, route + "versions", "POST", body).decodeToString())
        } catch (failure: YandexDiskError) {
            if (failure !is YandexDiskError.Offline &&
                !(failure is YandexDiskError.ServerFailure && failure.statusCode in 500..599)
            ) throw failure
            // A lost response may hide a committed transaction. Recover this exact operation.
            try {
                json.decodeFromString(call(location.account, route + listOf("operations", operationId)).decodeToString())
            } catch (_: IOException) { throw failure }
        }
    }
    private fun fileRoute(location: EddaLocation) = listOf("projects", location.project, "files")
    private suspend fun call(account: String, segments: List<String>, method: String = "GET", body: RequestBody? = null): ByteArray {
        val auth = credentials(account)
        val url = serverUrl(auth.server).newBuilder().apply { segments.forEach(::addPathSegment) }.build()
        return request(url, method, body, auth.token)
    }
    private suspend fun request(url: HttpUrl, method: String, body: RequestBody?, token: String?): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).method(method, body).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> response.body.bytes()
                    401, 403 -> throw YandexDiskError.Unauthorized()
                    404 -> throw YandexDiskError.NotFound()
                    409 -> throw YandexDiskError.PublicationPreconditionFailed()
                    429 -> throw YandexDiskError.RateLimited(response.header("Retry-After")?.toLongOrNull())
                    else -> throw YandexDiskError.ServerFailure(response.code)
                }
            }
        } catch (failure: YandexDiskError) { throw failure }
        catch (failure: IOException) { throw YandexDiskError.Offline(failure) }
    }
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()
        private fun String.jsonBody() = toRequestBody(JSON)
        fun normalizeServer(value: String): String {
            val url = value.trim().toHttpUrl()
            require(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")) { "Для сервера Edda требуется HTTPS" }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            return url.toString().trimEnd('/')
        }
        private fun serverUrl(value: String): HttpUrl = (normalizeServer(value) + "/api/").toHttpUrl()
    }
}
