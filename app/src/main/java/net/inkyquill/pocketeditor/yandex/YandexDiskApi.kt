package net.inkyquill.pocketeditor.yandex

import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
    private val now: () -> Instant = Instant::now,
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
        val body = execute(request).use { response -> response.readBodyString() }
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
        return authenticatedJson(url, lockAcquisition, exclusiveWrite = !overwrite && !lockAcquisition)
    }

    suspend fun download(link: LinkDto): ByteArray {
        val request = Request.Builder().url(validatedLink(link, "GET")).get().build()
        return executeDownload(request).use { response -> response.readBodyBytes() }
    }

    suspend fun upload(
        link: LinkDto,
        bytes: ByteArray,
        lockAcquisition: Boolean,
        exclusiveWrite: Boolean = false,
        onRequestStarted: () -> Unit = {},
    ): TransferResult {
        val request = Request.Builder()
            .url(validatedLink(link, "PUT"))
            .put(bytes.toRequestBody(OCTET_STREAM))
            .build()
        return execute(request, lockAcquisition, exclusiveWrite, onRequestStarted).use { response ->
            if (response.code == 202) TransferResult.ACCEPTED else TransferResult.COMPLETED
        }
    }

    suspend fun moveCreateOnly(from: String, path: String): MoveResult {
        val url = endpoint("resources", "move")
            .addQueryParameter("from", from)
            .addQueryParameter("path", path)
            .addQueryParameter("overwrite", "false")
            .build()
        val request = authenticatedRequest(url).post(ByteArray(0).toRequestBody()).build()
        return execute(request, exclusiveWrite = true).use { response ->
            if (response.code == 202) {
                val link = try {
                    json.decodeFromString<LinkDto>(response.readBodyString())
                } catch (error: SerializationException) {
                    throw YandexDiskError.InvalidRemote("Invalid asynchronous move response", error)
                }
                MoveResult.Accepted(link)
            } else {
                MoveResult.Completed
            }
        }
    }

    suspend fun operationStatus(link: LinkDto): OperationDto {
        val url = validatedLink(link, "GET")
        return authenticatedJson(url)
    }

    suspend fun delete(path: String) {
        val url = endpoint("resources")
            .addQueryParameter("path", path)
            .addQueryParameter("permanently", "true")
            .build()
        val request = authenticatedRequest(url).delete().build()
        execute(request).close()
    }

    private suspend inline fun <reified T> authenticatedJson(
        url: HttpUrl,
        lockAcquisition: Boolean = false,
        exclusiveWrite: Boolean = false,
    ): T {
        val request = authenticatedRequest(url).get().build()
        val body = execute(request, lockAcquisition, exclusiveWrite).use { response -> response.readBodyString() }
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
        exclusiveWrite: Boolean = false,
        onRequestStarted: () -> Unit = {},
    ): Response {
        val response = try {
            transferClient.newCall(request).await(onRequestStarted)
        } catch (error: IOException) {
            throw YandexDiskError.Offline(error)
        }
        if (response.isSuccessful) return response
        response.closeAndClassify(lockAcquisition, exclusiveWrite)
    }

    private suspend fun executeDownload(initialRequest: Request): Response {
        var request = initialRequest
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) { redirectCount ->
            val response = try {
                transferClient.newCall(request).await {}
            } catch (error: IOException) {
                throw YandexDiskError.Offline(error)
            }
            if (response.isSuccessful) return response
            if (response.code !in DOWNLOAD_REDIRECT_CODES) {
                response.closeAndClassify()
            }
            if (redirectCount == MAX_DOWNLOAD_REDIRECTS) {
                response.close()
                throw YandexDiskError.InvalidRemote("Too many Yandex Disk download redirects")
            }
            val redirect = response.header("Location")
                ?.let(request.url::resolve)
                ?: run {
                    response.close()
                    throw YandexDiskError.InvalidRemote("Invalid Yandex Disk download redirect")
                }
            response.close()
            request = Request.Builder().url(validatedDownloadRedirect(redirect)).get().build()
        }
        error("Download redirect loop must return or throw")
    }

    private fun classify(response: Response, lockAcquisition: Boolean, exclusiveWrite: Boolean): YandexDiskError {
        val status = response.code
        val retryAfter = parseRetryAfterSeconds(response.header("Retry-After"), now())
        return when {
            status == 401 -> YandexDiskError.Unauthorized()
            status == 404 -> YandexDiskError.NotFound()
            status == 409 && lockAcquisition -> YandexDiskError.LockHeld()
            status == 409 && exclusiveWrite -> YandexDiskError.AlreadyExists()
            status == 429 -> YandexDiskError.RateLimited(retryAfter)
            status >= 500 -> YandexDiskError.ServerFailure(status, retryAfter)
            else -> YandexDiskError.InvalidRemote("Unexpected Yandex Disk response ($status)")
        }
    }

    private fun Response.closeAndClassify(
        lockAcquisition: Boolean = false,
        exclusiveWrite: Boolean = false,
    ): Nothing {
        val failure = classify(this, lockAcquisition, exclusiveWrite)
        close()
        throw failure
    }

    private fun parseRetryAfterSeconds(value: String?, now: Instant): Long? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        text.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
        val target = runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull() ?: return null
        val delay = Duration.between(now, target)
        if (delay.isNegative || delay.isZero) return 0
        return delay.seconds + if (delay.nano == 0) 0 else 1
    }

    private fun Response.readBodyString(): String = try {
        body.string()
    } catch (error: IOException) {
        throw YandexDiskError.Offline(error)
    }

    private fun Response.readBodyBytes(): ByteArray = try {
        body.bytes()
    } catch (error: IOException) {
        throw YandexDiskError.Offline(error)
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
        val trustedHost = url.host == baseUrl.host || TRANSFER_HOST.matches(url.host)
        val secureYandex = url.scheme == "https" && url.port == HTTPS_PORT && trustedHost
        val configuredHttpTestOrigin = url.scheme == "http" && baseUrl.scheme == "http" && sameOrigin
        if (!secureYandex && !configuredHttpTestOrigin) {
            throw YandexDiskError.InvalidRemote("Untrusted Yandex Disk link")
        }
        return url
    }

    private fun validatedDownloadRedirect(url: HttpUrl): HttpUrl {
        val sameOrigin = url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port
        val configuredHttpTestOrigin = url.scheme == "http" && baseUrl.scheme == "http" && sameOrigin
        val trustedDownloadHost = TRANSFER_HOST.matches(url.host) || STORAGE_HOST.matches(url.host)
        val secureYandex = url.scheme == "https" && url.port == HTTPS_PORT && trustedDownloadHost
        if (!secureYandex && !configuredHttpTestOrigin) {
            throw YandexDiskError.InvalidRemote("Untrusted Yandex Disk download redirect")
        }
        return url
    }

    private companion object {
        const val PAGE_LIMIT = 100
        const val HTTPS_PORT = 443
        const val MAX_DOWNLOAD_REDIRECTS = 5
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val DOWNLOAD_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val TRANSFER_HOST = Regex("(?:uploader|downloader)[a-z0-9-]*\\.disk\\.yandex\\.(?:net|ru)")
        val STORAGE_HOST = Regex("[a-z0-9-]+\\.storage\\.yandex\\.net")
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
    val revision: JsonPrimitive? = null,
)

@Serializable
internal data class LinkDto(val href: String, val method: String, val templated: Boolean)

@Serializable
internal data class OperationDto(val status: String)

internal enum class TransferResult { COMPLETED, ACCEPTED }

internal sealed interface MoveResult {
    data object Completed : MoveResult
    data class Accepted(val operation: LinkDto) : MoveResult
}
