package net.inkyquill.pocketeditor.yandex

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class YandexDiskApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val accessToken: suspend () -> SecretToken,
) {
    private val transferClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listFolder(path: String, offset: Int): FolderPageDto {
        val url = endpoint("resources")
            .addQueryParameter("path", path)
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("fields", "_embedded.offset,_embedded.limit,_embedded.total,_embedded.items.name,_embedded.items.path,_embedded.items.type,_embedded.items.size,_embedded.items.revision")
            .build()
        val request = authenticatedRequest(url).get().build()
        val body = execute(request).use { response -> response.body.string() }
        return try {
            json.decodeFromString<FolderResponseDto>(body).embedded
        } catch (error: SerializationException) {
            throw YandexDiskError.InvalidRemote("Invalid Yandex Disk JSON", error)
        }
    }

    suspend fun metadata(path: String): ResourceDto {
        val url = endpoint("resources")
            .addQueryParameter("path", path)
            .addQueryParameter("fields", "path,revision")
            .build()
        return authenticatedJson(url)
    }

    suspend fun downloadLink(path: String): LinkDto {
        val url = endpoint("resources", "download").addQueryParameter("path", path).build()
        return authenticatedJson(url)
    }

    suspend fun uploadLink(path: String, overwrite: Boolean, lockAcquisition: Boolean): LinkDto {
        val url = endpoint("resources", "upload")
            .addQueryParameter("path", path)
            .addQueryParameter("overwrite", overwrite.toString())
            .build()
        return authenticatedJson(url, lockAcquisition)
    }

    suspend fun download(link: LinkDto): ByteArray {
        val request = Request.Builder().url(validatedLink(link, "GET")).get().build()
        return execute(request).use { response -> response.body.bytes() }
    }

    suspend fun upload(
        link: LinkDto,
        bytes: ByteArray,
        lockAcquisition: Boolean,
        onRequestStarted: () -> Unit = {},
    ): TransferResult {
        val request = Request.Builder()
            .url(validatedLink(link, "PUT"))
            .put(bytes.toRequestBody(OCTET_STREAM))
            .build()
        return execute(request, lockAcquisition, onRequestStarted).use { response ->
            if (response.code == 202) TransferResult.ACCEPTED else TransferResult.COMPLETED
        }
    }

    suspend fun delete(path: String) {
        val url = endpoint("resources")
            .addQueryParameter("path", path)
            .addQueryParameter("permanently", "true")
            .build()
        val request = authenticatedRequest(url).delete().build()
        execute(request).close()
    }

    private suspend inline fun <reified T> authenticatedJson(url: HttpUrl, lockAcquisition: Boolean = false): T {
        val request = authenticatedRequest(url).get().build()
        val body = execute(request, lockAcquisition).use { response -> response.body.string() }
        return try {
            json.decodeFromString<T>(body)
        } catch (error: SerializationException) {
            throw YandexDiskError.InvalidRemote("Invalid Yandex Disk JSON", error)
        }
    }

    private suspend fun authenticatedRequest(url: HttpUrl): Request.Builder {
        val token = accessToken()
        return Request.Builder()
            .url(url)
            .header("Authorization", "OAuth ${token.revealForAuthorization()}")
    }

    private suspend fun execute(
        request: Request,
        lockAcquisition: Boolean = false,
        onRequestStarted: () -> Unit = {},
    ): Response {
        val response = try {
            transferClient.newCall(request).await(onRequestStarted)
        } catch (error: IOException) {
            throw YandexDiskError.Offline(error)
        }
        if (response.isSuccessful) return response
        val status = response.code
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
        response.close()
        throw when {
            status == 401 -> YandexDiskError.Unauthorized()
            status == 404 -> YandexDiskError.NotFound()
            status == 409 && lockAcquisition -> YandexDiskError.LockHeld()
            status == 429 -> YandexDiskError.RateLimited(retryAfter)
            status >= 500 -> YandexDiskError.ServerFailure(status)
            else -> YandexDiskError.InvalidRemote("Unexpected Yandex Disk response ($status)")
        }
    }

    private fun endpoint(vararg segments: String): HttpUrl.Builder = baseUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }

    private fun validatedLink(link: LinkDto, requiredMethod: String): HttpUrl {
        if (link.templated || link.method != requiredMethod) {
            throw YandexDiskError.InvalidRemote("Unsupported Yandex Disk link")
        }
        val url = runCatching { link.href.toHttpUrl() }
            .getOrElse { throw YandexDiskError.InvalidRemote("Invalid Yandex Disk link", it) }
        val sameOrigin = url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port
        val trustedHost = url.host == baseUrl.host ||
            url.host.endsWith(".yandex.net") ||
            url.host.endsWith(".yandex.ru")
        val secureYandex = url.scheme == "https" && trustedHost
        val configuredHttpTestOrigin = url.scheme == "http" && baseUrl.scheme == "http" && sameOrigin
        if (!secureYandex && !configuredHttpTestOrigin) {
            throw YandexDiskError.InvalidRemote("Untrusted Yandex Disk link")
        }
        return url
    }

    private companion object {
        const val PAGE_LIMIT = 100
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

private suspend fun Call.await(onRequestStarted: () -> Unit): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
    onRequestStarted()
}

@Serializable
internal data class FolderResponseDto(@SerialName("_embedded") val embedded: FolderPageDto)

@Serializable
internal data class FolderPageDto(
    val offset: Int,
    val limit: Int,
    val total: Int,
    val items: List<ResourceDto>,
)

@Serializable
internal data class ResourceDto(
    val name: String = "",
    val path: String? = null,
    val type: String = "",
    val size: Long? = null,
    val revision: String? = null,
)

@Serializable
internal data class LinkDto(val href: String, val method: String, val templated: Boolean)

internal enum class TransferResult { COMPLETED, ACCEPTED }
