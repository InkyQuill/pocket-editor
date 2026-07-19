package net.inkyquill.pocketeditor.yandex

import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class YandexDiskGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var gateway: YandexDiskGateway

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = OkHttpYandexDiskGateway(
            client = OkHttpClient(),
            apiBaseUrl = server.url("/v1/disk/"),
            accessToken = { SecretToken("test-token") },
            completionAttempts = 3,
            completionDelay = {},
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `listFolder paginates and preserves remote metadata`() = runBlocking {
        enqueueJson(folderPage(offset = 0, total = 2, itemPath = "disk:/Книга/глава 1.md", revision = "r1"))
        enqueueJson(folderPage(offset = 1, total = 2, itemPath = "disk:/Книга/глава 2.md", revision = "r2"))

        val entries = gateway.listFolder("disk:/Книга")

        assertEquals(listOf("r1", "r2"), entries.map(RemoteEntry::revision))
        assertEquals("глава 1.md", entries.first().name)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("disk:/Книга", first.url.queryParameter("path"))
        assertEquals("0", first.url.queryParameter("offset"))
        assertEquals("1", second.url.queryParameter("offset"))
        assertEquals("OAuth test-token", first.headers["Authorization"])
    }

    @Test
    fun `download follows Yandex URL indirection and returns revision`() = runBlocking {
        enqueueJson("""{"path":"disk:/Книга/глава.md","revision":"remote-r7"}""")
        enqueueJson("""{"href":"${server.url("/download-target")}","method":"GET","templated":false}""")
        server.enqueue(MockResponse.Builder().code(200).body("текст").build())

        val file = gateway.download("disk:/Книга/глава.md")

        assertEquals("disk:/Книга/глава.md", file.path)
        assertEquals("remote-r7", file.revision)
        assertArrayEquals("текст".toByteArray(), file.bytes)
        assertEquals("/download-target", server.takeRequestAfter(2).url.encodedPath)
    }

    @Test
    fun `download rejects non-Yandex URL indirection before following it`() {
        enqueueJson("""{"path":"disk:/Книга/глава.md","revision":"remote-r7"}""")
        enqueueJson("""{"href":"https://example.invalid/private","method":"GET","templated":false}""")

        assertThrows(YandexDiskError.InvalidRemote::class.java) {
            runBlocking { gateway.download("disk:/Книга/глава.md") }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `tryAcquireLock creates strict JSON with overwrite false and verifies nonce`() = runBlocking {
        val lock = lock()
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).build())
        enqueueJson("""{"path":"disk:/Книга/.pocket-editor.sync.lock","revision":"lr1"}""")
        enqueueJson("""{"href":"${server.url("/lock-download")}","method":"GET","templated":false}""")
        server.enqueue(MockResponse.Builder().code(200).body(lock.json()).build())

        assertEquals(lock, gateway.tryAcquireLock("disk:/Книга", lock))

        val linkRequest = server.takeRequest()
        assertEquals("false", linkRequest.url.queryParameter("overwrite"))
        assertEquals("disk:/Книга/.pocket-editor.sync.lock", linkRequest.url.queryParameter("path"))
        val uploadRequest = server.takeRequest()
        assertEquals("PUT", uploadRequest.method)
        assertEquals(lock.json(), uploadRequest.body?.utf8())
    }

    @Test
    fun `competing lock maps conflict to LockHeld`() {
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(409).body("already exists").build())

        assertThrows(YandexDiskError.LockHeld::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", lock()) }
        }
    }

    @Test
    fun `lock conflict from upload-link request maps LockHeld without PUT`() {
        server.enqueue(MockResponse.Builder().code(409).build())

        assertThrows(YandexDiskError.LockHeld::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", lock()) }
        }
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `accepted lock upload polls until nonce ownership is observable`() = runBlocking {
        val lock = lock()
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        enqueueLockDownload(lock)

        assertEquals(lock, gateway.tryAcquireLock("disk:/Книга", lock))
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `strict lock JSON rejects unknown fields and invalid values`() {
        val invalid = listOf(
            """{"schema_version":2,"lock_id":"${UUID.randomUUID()}","holder_id":"phone","created_at":"2026-07-19T10:00:00Z"}""",
            """{"schema_version":1,"lock_id":"not-uuid","holder_id":"phone","created_at":"2026-07-19T10:00:00Z"}""",
            """{"schema_version":1,"lock_id":"${UUID.randomUUID()}","holder_id":" ","created_at":"2026-07-19T10:00:00Z"}""",
            """{"schema_version":1,"lock_id":"${UUID.randomUUID()}","holder_id":"phone","created_at":"not-utc"}""",
            """{"schema_version":1,"lock_id":"${UUID.randomUUID()}","holder_id":"phone","created_at":"2026-07-19T10:00:00Z","extra":true}""",
            """{"schema_version":"1","lock_id":"${UUID.randomUUID()}","holder_id":"phone","created_at":"2026-07-19T10:00:00Z"}""",
            """{"schema_version":1,"lock_id":123,"holder_id":"phone","created_at":"2026-07-19T10:00:00Z"}""",
            """{"schema_version":1,"lock_id":"${UUID.randomUUID()}","holder_id":123,"created_at":"2026-07-19T10:00:00Z"}""",
            """{"schema_version":1,"lock_id":"${UUID.randomUUID()}","holder_id":"phone","created_at":123}""",
            """{"schema_version":1,"lock_id":"1-1-1-1-1","holder_id":"phone","created_at":"2026-07-19T10:00:00Z"}""",
            """{"schema_version":1,"lock_id":"${UUID.randomUUID()}","holder_id":"phone","created_at":"2026-07-19T13:00:00+03:00"}""",
        )

        invalid.forEach { json ->
            assertThrows(YandexDiskError.InvalidRemote::class.java) { SyncLock.fromJson(json) }
        }
    }

    @Test
    fun `uploadGuarded re-reads nonce immediately before overwrite request`() = runBlocking {
        val lock = lock()
        enqueueLockDownload(lock)
        enqueueJson(uploadLink("/guarded-upload"))
        server.enqueue(MockResponse.Builder().code(201).build())
        enqueueJson("""{"path":"disk:/Книга/chapter.review.json","revision":"new-r"}""")

        val revision = gateway.uploadGuarded(
            rootPath = "disk:/Книга",
            relativePath = "chapter.review.json",
            bytes = "{}".toByteArray(),
            ownedLock = lock,
        )

        assertEquals("new-r", revision)
        repeat(3) { server.takeRequest() }
        val linkRequest = server.takeRequest()
        assertEquals("true", linkRequest.url.queryParameter("overwrite"))
        assertEquals("disk:/Книга/chapter.review.json", linkRequest.url.queryParameter("path"))
    }

    @Test
    fun `accepted guarded upload polls content until moved then returns observed revision`() = runBlocking {
        val lock = lock()
        enqueueLockDownload(lock)
        enqueueJson(uploadLink("/guarded-upload"))
        server.enqueue(MockResponse.Builder().code(202).build())
        enqueueFileDownload("disk:/Книга/chapter.review.json", "old-r", "old")
        enqueueFileDownload("disk:/Книга/chapter.review.json", "new-r", "{}")

        val revision = gateway.uploadGuarded(
            "disk:/Книга",
            "chapter.review.json",
            "{}".toByteArray(),
            lock,
        )

        assertEquals("new-r", revision)
        assertEquals(11, server.requestCount)
    }

    @Test
    fun `accepted guarded upload times out without claiming success`() {
        val lock = lock()
        enqueueLockDownload(lock)
        enqueueJson(uploadLink("/guarded-upload"))
        server.enqueue(MockResponse.Builder().code(202).build())
        repeat(3) { enqueueFileDownload("disk:/Книга/chapter.review.json", "old-r", "old") }

        assertThrows(YandexDiskError.UploadIncomplete::class.java) {
            runBlocking {
                gateway.uploadGuarded("disk:/Книга", "chapter.review.json", "{}".toByteArray(), lock)
            }
        }
    }

    @Test
    fun `uploadGuarded aborts without upload request when nonce changed`() {
        val ours = lock()
        enqueueLockDownload(lock())

        assertThrows(YandexDiskError.LockLost::class.java) {
            runBlocking {
                gateway.uploadGuarded("disk:/Книга", ".pocket-editor.json", byteArrayOf(1), ours)
            }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `uploadGuarded rejects every noncanonical write before a request`() {
        val rejected = listOf(
            "chapter.md",
            "nested/chapter.review.json",
            "../chapter.review.json",
            ".pocket-editor.sync.lock",
            ".pocket-editor.json.bak",
            ".review.json",
            "chapter.review.json/extra",
        )

        rejected.forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { gateway.uploadGuarded("disk:/Книга", path, byteArrayOf(), lock()) }
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `uploadGuarded maps a missing owned lock to LockLost`() {
        server.enqueue(MockResponse.Builder().code(404).build())

        assertThrows(YandexDiskError.LockLost::class.java) {
            runBlocking {
                gateway.uploadGuarded("disk:/Книга", ".pocket-editor.json", byteArrayOf(1), lock())
            }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `releaseOwnedLock verifies nonce immediately before delete`() = runBlocking {
        val lock = lock()
        enqueueLockDownload(lock)
        server.enqueue(MockResponse.Builder().code(204).build())

        gateway.releaseOwnedLock("disk:/Книга", lock)

        repeat(3) { server.takeRequest() }
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("disk:/Книга/.pocket-editor.sync.lock", delete.url.queryParameter("path"))
    }

    @Test
    fun `releaseOwnedLock preserves foreign lock`() {
        enqueueLockDownload(lock())

        assertThrows(YandexDiskError.LockLost::class.java) {
            runBlocking { gateway.releaseOwnedLock("disk:/Книга", lock()) }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `status and invalid responses map to domain errors`() {
        val cases = listOf(
            401 to YandexDiskError.Unauthorized::class.java,
            404 to YandexDiskError.NotFound::class.java,
            429 to YandexDiskError.RateLimited::class.java,
            503 to YandexDiskError.ServerFailure::class.java,
        )
        cases.forEach { (code, type) ->
            server.enqueue(MockResponse.Builder().code(code).build())
            assertThrows(type) { runBlocking { gateway.listFolder("disk:/Книга") } }
        }
        enqueueJson("not-json")
        assertThrows(YandexDiskError.InvalidRemote::class.java) {
            runBlocking { gateway.listFolder("disk:/Книга") }
        }
    }

    @Test
    fun `IO failures map to Offline without leaking OkHttp types`() {
        server.close()

        val error = assertThrows(YandexDiskError.Offline::class.java) {
            runBlocking { gateway.listFolder("disk:/Книга") }
        }
        assertTrue(error.cause is IOException)
    }

    @Test
    fun `caller cancellation cancels the HTTP operation without mapping Offline`() = runBlocking {
        val cancellations = AtomicInteger()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) {
                cancellations.incrementAndGet()
            }
        }).build()
        gateway = OkHttpYandexDiskGateway(client, server.url("/v1/disk/")) { SecretToken("test-token") }
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val request = async { gateway.listFolder("disk:/Книга") }
        withTimeout(2_000) {
            while (server.requestCount == 0) delay(10)
        }

        request.cancel()

        assertThrows(CancellationException::class.java) { runBlocking { request.await() } }
        withTimeout(2_000) {
            while (cancellations.get() == 0) delay(10)
        }
        assertEquals(1, cancellations.get())
    }

    @Test
    fun `logger excludes authorization query excerpts and full remote paths`() = runBlocking {
        val messages = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(RedactingHttpLogger(messages::add))
            .build()
        gateway = OkHttpYandexDiskGateway(client, server.url("/v1/disk/")) {
            SecretToken("super-secret-token")
        }
        enqueueJson("""{"_embedded":{"offset":0,"limit":20,"total":0,"items":[]}}""")

        gateway.listFolder("disk:/Private Book/Secret Chapter")

        val output = messages.joinToString("\n")
        assertFalse(output.contains("super-secret-token"))
        assertFalse(output.contains("Private Book"))
        assertFalse(output.contains("Secret Chapter"))
        assertFalse(output.contains("path="))
        assertFalse(output.contains("excerpt"))
        assertTrue(output.contains("<redacted>"))
    }

    @Test
    fun `transfer redirect is rejected without sending bytes to redirect target`() {
        val attacker = MockWebServer()
        attacker.start()
        try {
            val lock = lock()
            enqueueLockDownload(lock)
            enqueueJson(uploadLink("/guarded-upload"))
            server.enqueue(
                MockResponse.Builder()
                    .code(307)
                    .addHeader("Location", attacker.url("/stolen"))
                    .build(),
            )

            assertThrows(YandexDiskError.InvalidRemote::class.java) {
                runBlocking {
                    gateway.uploadGuarded("disk:/Книга", "chapter.review.json", "secret bytes".toByteArray(), lock)
                }
            }
            assertEquals(0, attacker.requestCount)
        } finally {
            attacker.close()
        }
    }

    @Test
    fun `plaintext transfer on a different configured-test origin is rejected`() {
        val otherOrigin = MockWebServer()
        otherOrigin.start()
        try {
            enqueueJson("""{"path":"disk:/Книга/глава.md","revision":"remote-r7"}""")
            enqueueJson("""{"href":"${otherOrigin.url("/plaintext")}","method":"GET","templated":false}""")

            assertThrows(YandexDiskError.InvalidRemote::class.java) {
                runBlocking { gateway.download("disk:/Книга/глава.md") }
            }
            assertEquals(0, otherOrigin.requestCount)
        } finally {
            otherOrigin.close()
        }
    }

    private fun enqueueLockDownload(lock: SyncLock) {
        enqueueJson("""{"path":"disk:/Книга/.pocket-editor.sync.lock","revision":"lock-r"}""")
        enqueueJson("""{"href":"${server.url("/lock-download")}","method":"GET","templated":false}""")
        server.enqueue(MockResponse.Builder().code(200).body(lock.json()).build())
    }

    private fun enqueueFileDownload(path: String, revision: String, body: String) {
        enqueueJson("""{"path":"$path","revision":"$revision"}""")
        enqueueJson("""{"href":"${server.url("/file-download")}","method":"GET","templated":false}""")
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
    }

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build())
    }

    private fun uploadLink(path: String): String =
        """{"href":"${server.url(path)}","method":"PUT","templated":false}"""

    private fun folderPage(offset: Int, total: Int, itemPath: String, revision: String): String =
        """{"_embedded":{"offset":$offset,"limit":1,"total":$total,"items":[{"name":"${itemPath.substringAfterLast('/')}","path":"$itemPath","type":"file","size":12,"revision":"$revision"}]}}"""

    private fun lock(): SyncLock = SyncLock(
        schemaVersion = 1,
        lockId = UUID.randomUUID().toString(),
        holderId = "device-a",
        createdAt = Instant.parse("2026-07-19T10:00:00Z"),
    )

    private fun MockWebServer.takeRequestAfter(skip: Int): mockwebserver3.RecordedRequest {
        repeat(skip) { takeRequest() }
        return takeRequest()
    }
}
