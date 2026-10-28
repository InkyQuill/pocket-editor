package net.inkyquill.pocketeditor.edda

import java.io.IOException
import java.time.Clock
import java.util.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
internal class EddaLogin(
    val token: String,
    val author: EddaAuthor,
    val refreshToken: String? = null,
    val refreshExpiresAt: Long = 0,
)
class EddaCredentials(
    val server: String,
    val token: String,
    val refreshToken: String? = null,
    val authorId: String? = null,
)

class EddaClient(
    client: OkHttpClient,
    private val credentials: (String) -> EddaCredentials,
) {
    internal var saveRefreshedSession: ((String, String, EddaLogin) -> Unit)? = null
    private val refreshMutex = Mutex()
    internal var clock: Clock = Clock.systemUTC()
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
        val stored = credentials(account)
        // The JWT expiry is only a scheduling hint; the server still validates every token.
        val renewalDue = saveRefreshedSession != null && !stored.refreshToken.isNullOrBlank() &&
            accessExpiresAt(stored.token)?.let { it <= clock.instant().epochSecond + 60 } == true
        val auth = if (renewalDue) refreshSession(account, stored) else stored
        val url = serverUrl(auth.server).newBuilder().apply { segments.forEach(::addPathSegment) }.build()
        try {
            return request(url, method, body, auth.token)
        } catch (expired: YandexDiskError.Unauthorized) {
            // A proactively renewed token already failed: do not refresh endlessly.
            if (renewalDue) throw expired
            val refreshed = refreshSession(account, auth)
            // Retry the original operation once, retaining its publication operation ID.
            return request(url, method, body, refreshed.token)
        }
    }
    private suspend fun refreshSession(account: String, previous: EddaCredentials): EddaCredentials = refreshMutex.withLock {
        val current = credentials(account)
        if (current.token != previous.token || current.refreshToken != previous.refreshToken) return@withLock current
        val save = saveRefreshedSession ?: throw YandexDiskError.Unauthorized()
        val refresh = current.refreshToken?.takeIf { it.isNotBlank() } ?: throw YandexDiskError.Unauthorized()
        val payload = buildJsonObject { put("refreshToken", refresh) }.toString().jsonBody()
        // Persist a potentially rotated refresh token even if the caller stops waiting.
        withContext(NonCancellable) {
            val session = json.decodeFromString<EddaLogin>(request(
                serverUrl(current.server).newBuilder().addPathSegments("auth/refresh").build(),
                "POST", payload, null,
            ).decodeToString())
            if (session.author.id != current.authorId || session.token.isBlank() || session.refreshToken.isNullOrBlank()) {
                throw YandexDiskError.Unauthorized()
            }
            save(account, current.token, session)
            credentials(account)
        }
    }
    private fun accessExpiresAt(token: String): Long? = runCatching {
        val parts = token.split('.')
        if (parts.size != 3) return@runCatching null
        json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).decodeToString())
            .jsonObject["exp"]?.jsonPrimitive?.longOrNull
    }.getOrNull()
    private suspend fun request(url: HttpUrl, method: String, body: RequestBody?, token: String?): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).method(method, body).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> response.body.bytes()
                    401 -> throw YandexDiskError.Unauthorized()
                    403 -> throw YandexDiskError.InvalidRemote("Нет доступа к ресурсу Open Edda")
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
