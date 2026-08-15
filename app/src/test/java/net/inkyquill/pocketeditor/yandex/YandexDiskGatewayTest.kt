package net.inkyquill.pocketeditor.yandex

import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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
    fun `listFolder accepts numeric Yandex revision`() = runBlocking {
        enqueueJson(
            """{"_embedded":{"offset":0,"limit":20,"total":1,"items":[{"name":"chapter.md","path":"disk:/Book/chapter.md","type":"file","size":12,"revision":1481547696947}]}}""",
        )

        val entries = gateway.listFolder("disk:/Book")

        assertEquals("1481547696947", entries.single().revision)
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
    fun `download follows a same-origin transfer redirect`() = runBlocking {
        enqueueJson("""{"path":"disk:/Книга/глава.md","revision":"remote-r7"}""")
        enqueueJson("""{"href":"${server.url("/download-redirect")}","method":"GET","templated":false}""")
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", server.url("/download-target"))
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("chapter text").build())

        val file = gateway.download("disk:/Книга/глава.md")

        assertEquals("chapter text", file.bytes.toString(Charsets.UTF_8))
        assertEquals("/download-target", server.takeRequestAfter(3).url.encodedPath)
    }

    @Test
    fun `transfer 429 preserves Retry-After`() {
        enqueueJson("""{"path":"disk:/Book/chapter.md","revision":"r1"}""")
        enqueueJson("""{"href":"${server.url("/transfer")}","method":"GET","templated":false}""")
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "18").build())

        val failure = assertThrows(YandexDiskError.RateLimited::class.java) {
            runBlocking { gateway.download("disk:/Book/chapter.md") }
        }

        assertEquals(18L, failure.retryAfterSeconds)
    }

    @Test
    fun `transfer 503 rounds future HTTP-date Retry-After up to the next second`() {
        gateway = OkHttpYandexDiskGateway(
            client = OkHttpClient(),
            apiBaseUrl = server.url("/v1/disk/"),
            completionAttempts = 3,
            completionDelay = {},
            now = { Instant.parse("2026-08-15T10:00:00.250Z") },
            accessToken = { SecretToken("test-token") },
        )
        enqueueJson("""{"path":"disk:/Book/chapter.md","revision":"r1"}""")
        enqueueJson("""{"href":"${server.url("/transfer")}","method":"GET","templated":false}""")
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Retry-After", "Sat, 15 Aug 2026 10:00:01 GMT")
                .build(),
        )

        val failure = assertThrows(YandexDiskError.ServerFailure::class.java) {
            runBlocking { gateway.download("disk:/Book/chapter.md") }
        }

        assertEquals(1L, failure.retryAfterSeconds)
    }

    @Test
    fun `transfer 503 preserves Retry-After while malformed value is ignored`() {
        enqueueJson("""{"path":"disk:/Book/chapter.md","revision":"r1"}""")
        enqueueJson("""{"href":"${server.url("/transfer")}","method":"GET","templated":false}""")
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "not-a-date").build())

        val failure = assertThrows(YandexDiskError.ServerFailure::class.java) {
            runBlocking { gateway.download("disk:/Book/chapter.md") }
        }

        assertEquals(503, failure.statusCode)
        assertEquals(null, failure.retryAfterSeconds)
    }

    @Test
    fun `transfer responses map through Yandex domain errors`() {
        val cases = listOf(
            401 to YandexDiskError.Unauthorized::class.java,
            404 to YandexDiskError.NotFound::class.java,
            500 to YandexDiskError.ServerFailure::class.java,
        )

        cases.forEach { (code, type) ->
            enqueueJson("""{"path":"disk:/Book/chapter.md","revision":"r1"}""")
            enqueueJson("""{"href":"${server.url("/transfer")}","method":"GET","templated":false}""")
            server.enqueue(MockResponse.Builder().code(code).build())

            assertThrows(type) { runBlocking { gateway.download("disk:/Book/chapter.md") } }
        }
    }

    @Test
    fun `download body truncated mid-stream is classified as offline`() {
        gatewayWithoutTransportRetry()
        enqueueJson("""{"path":"disk:/Книга/глава.md","revision":"remote-r7"}""")
        enqueueJson("""{"href":"${server.url("/download-target")}","method":"GET","templated":false}""")
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("some chapter text that is long enough to be worth truncating mid-stream")
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )

        assertThrows(YandexDiskError.Offline::class.java) {
            runBlocking { gateway.download("disk:/Книга/глава.md") }
        }
    }

    @Test
    fun `listFolder body truncated mid-stream is classified as offline`() {
        gatewayWithoutTransportRetry()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(folderPage(offset = 0, total = 5, itemPath = "disk:/Книга/глава 1.md", revision = "r1"))
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )

        assertThrows(YandexDiskError.Offline::class.java) {
            runBlocking { gateway.listFolder("disk:/Книга") }
        }
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
    fun `competing lock from a different device maps conflict to LockHeld`() {
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(409).body("already exists").build())
        enqueueLockDownload(lock(holderId = "device-b"))

        assertThrows(YandexDiskError.LockHeld::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", lock()) }
        }
    }

    @Test
    fun `competing lock from this device is reclaimed and acquisition retried`() = runBlocking {
        val requested = lock(holderId = "device-a")
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(409).body("already exists").build())
        enqueueLockDownload(lock(holderId = "device-a"))
        server.enqueue(MockResponse.Builder().code(204).build())
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).build())
        enqueueLockDownload(requested)

        assertEquals(requested, gateway.tryAcquireLock("disk:/Книга", requested))
        assertEquals(11, server.requestCount)
    }

    @Test
    fun `lock conflict from upload-link request for a different device maps LockHeld`() {
        server.enqueue(MockResponse.Builder().code(409).build())
        enqueueLockDownload(lock(holderId = "device-b"))

        assertThrows(YandexDiskError.LockHeld::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", lock()) }
        }
    }

    @Test
    fun `lock conflict from upload-link request for this device is reclaimed and retried`() = runBlocking {
        val requested = lock(holderId = "device-a")
        server.enqueue(MockResponse.Builder().code(409).build())
        enqueueLockDownload(lock(holderId = "device-a"))
        server.enqueue(MockResponse.Builder().code(204).build())
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).build())
        enqueueLockDownload(requested)

        assertEquals(requested, gateway.tryAcquireLock("disk:/Книга", requested))
    }

    @Test
    fun `ambiguous candidate PUT recovers when the pre-existing lock belongs to this device`() = runBlocking {
        val requested = lock(holderId = "device-a")
        gatewayWithoutTransportRetry()
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).onResponseStart(SocketEffect.CloseSocket()).build())
        enqueueLockDownload(lock(holderId = "device-a"))
        server.enqueue(MockResponse.Builder().code(204).build())
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).build())
        enqueueLockDownload(requested)

        assertEquals(requested, gateway.tryAcquireLock("disk:/Книга", requested))
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
    fun `cancellation after lock PUT cleans owned candidate without replacing cancellation`() = runBlocking {
        val lock = lock()
        val polling = CompletableDeferred<Unit>()
        gateway = OkHttpYandexDiskGateway(
            client = OkHttpClient(),
            apiBaseUrl = server.url("/v1/disk/"),
            accessToken = { SecretToken("test-token") },
            completionAttempts = 3,
            completionDelay = {
                polling.complete(Unit)
                awaitCancellation()
            },
        )
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        enqueueLockDownload(lock)
        enqueueLockDownload(lock)
        server.enqueue(MockResponse.Builder().code(204).build())
        val original = CancellationException("caller cancelled")
        val acquiring = async { gateway.tryAcquireLock("disk:/Книга", lock) }
        withTimeout(2_000) { polling.await() }

        acquiring.cancel(original)
        val thrown = assertThrows(CancellationException::class.java) { runBlocking { acquiring.await() } }

        assertEquals("caller cancelled", thrown.message)
        assertEquals(10, server.requestCount)
        repeat(9) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `verification failure plus cleanup failure reports unconfirmed candidate identity and both causes`() {
        val requested = lock()
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).build())
        enqueueLockDownload(lock())
        server.enqueue(MockResponse.Builder().code(503).build())

        val thrown = assertThrows(YandexDiskError.CandidateCleanupUnconfirmed::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", requested) }
        }

        assertEquals(requested, thrown.candidateLock)
        assertTrue(thrown.verificationFailure is YandexDiskError.LockLost)
        assertTrue(thrown.cleanupFailure is YandexDiskError.ServerFailure)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `accepted candidate verification offline plus cleanup offline is actionable`() {
        val requested = lock()
        gateway = OkHttpYandexDiskGateway(
            OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            server.url("/v1/disk/"),
        ) { SecretToken("test-token") }
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).build())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

        val thrown = assertThrows(YandexDiskError.CandidateCleanupUnconfirmed::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", requested) }
        }

        assertEquals(requested.lockId, thrown.candidateLock.lockId)
        assertTrue(
            thrown.verificationFailure is YandexDiskError.Offline,
            "verification=${thrown.verificationFailure::class.qualifiedName}: ${thrown.verificationFailure.message}",
        )
        assertTrue(
            thrown.cleanupFailure is YandexDiskError.Offline,
            "cleanup=${thrown.cleanupFailure::class.qualifiedName}: ${thrown.cleanupFailure.message}",
        )
        assertTrue(thrown.message!!.contains(requested.lockId))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `candidate cancellation stays primary when cleanup is offline`() {
        val requested = lock()
        val original = CancellationException("cancel acquisition")
        gateway = OkHttpYandexDiskGateway(
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            apiBaseUrl = server.url("/v1/disk/"),
            completionAttempts = 3,
            completionDelay = { throw original },
        ) { SecretToken("test-token") }
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", requested) }
        }

        assertEquals("cancel acquisition", thrown.message)
        assertTrue(
            thrown.suppressed.any {
                it is YandexDiskError.CandidateCleanupUnconfirmed &&
                    it.cleanupFailure is YandexDiskError.Offline
            },
        )
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `create PUT committed before offline response is verified and guarded deleted`() {
        val requested = lock()
        gatewayWithoutTransportRetry()
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).onResponseStart(SocketEffect.CloseSocket()).build())
        enqueueLockDownload(requested)
        enqueueLockDownload(requested)
        server.enqueue(MockResponse.Builder().code(204).build())

        assertThrows(YandexDiskError.Offline::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", requested) }
        }

        repeat(8) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals(9, server.requestCount)
    }

    @Test
    fun `create PUT cancellation still verifies and guarded deletes committed candidate`() = runBlocking {
        val requested = lock()
        gatewayWithoutTransportRetry()
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        enqueueLockDownload(requested)
        enqueueLockDownload(requested)
        server.enqueue(MockResponse.Builder().code(204).build())
        val original = CancellationException("cancel create PUT")
        val acquiring = async { gateway.tryAcquireLock("disk:/Книга", requested) }
        withTimeout(2_000) { while (server.requestCount < 2) delay(10) }

        acquiring.cancel(original)
        val thrown = assertThrows(CancellationException::class.java) { runBlocking { acquiring.await() } }

        assertEquals("cancel create PUT", thrown.message)
        withTimeout(2_000) { while (server.requestCount < 9) delay(10) }
        repeat(8) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `ambiguous create PUT with absent candidate does not delete`() {
        val requested = lock()
        gatewayWithoutTransportRetry()
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse.Builder().code(404).build())

        assertThrows(YandexDiskError.Offline::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", requested) }
        }

        assertEquals(3, server.requestCount)
        assertTrue((0 until 3).map { server.takeRequest().method }.none { it == "DELETE" })
    }

    @Test
    fun `ambiguous create PUT preserves foreign nonce without delete`() {
        val requested = lock()
        gatewayWithoutTransportRetry()
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).onResponseStart(SocketEffect.CloseSocket()).build())
        enqueueLockDownload(lock(holderId = "device-b"))

        assertThrows(YandexDiskError.LockHeld::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", requested) }
        }

        assertEquals(5, server.requestCount)
        assertTrue((0 until 5).map { server.takeRequest().method }.none { it == "DELETE" })
    }

    @Test
    fun `ambiguous create cleanup delete failure reports candidate evidence`() {
        val requested = lock()
        gatewayWithoutTransportRetry()
        enqueueJson(uploadLink("/lock-upload"))
        server.enqueue(MockResponse.Builder().code(201).onResponseStart(SocketEffect.CloseSocket()).build())
        enqueueLockDownload(requested)
        enqueueLockDownload(requested)
        server.enqueue(MockResponse.Builder().code(503).build())

        val thrown = assertThrows(YandexDiskError.CandidateCleanupUnconfirmed::class.java) {
            runBlocking { gateway.tryAcquireLock("disk:/Книга", requested) }
        }

        assertEquals(requested, thrown.candidateLock)
        assertTrue(thrown.verificationFailure is YandexDiskError.Offline)
        assertTrue(thrown.cleanupFailure is YandexDiskError.ServerFailure)
        repeat(8) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
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
        server.enqueue(MockResponse.Builder().code(404).build())
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
        repeat(4) { server.takeRequest() }
        val linkRequest = server.takeRequest()
        assertEquals("true", linkRequest.url.queryParameter("overwrite"))
        assertEquals("disk:/Книга/chapter.review.json", linkRequest.url.queryParameter("path"))
    }

    @Test
    fun `accepted guarded upload polls content until moved then returns observed revision`() = runBlocking {
        val lock = lock()
        server.enqueue(MockResponse.Builder().code(404).build())
        enqueueLockDownload(lock)
        enqueueJson(uploadLink("/guarded-upload"))
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        enqueueStableFileObservation("disk:/Книга/chapter.review.json", "new-r", "{}")

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
    fun `accepted overwrite does not complete on identical baseline bytes before revision advances`() = runBlocking {
        val lock = lock()
        enqueueJson("""{"path":"disk:/Книга/chapter.review.json","revision":"R1"}""")
        enqueueLockDownload(lock)
        enqueueJson(uploadLink("/guarded-upload"))
        server.enqueue(MockResponse.Builder().code(202).build())
        enqueueStableFileObservation("disk:/Книга/chapter.review.json", "R1", "{}")
        enqueueStableFileObservation("disk:/Книга/chapter.review.json", "R2", "{}")

        val revision = gateway.uploadGuarded(
            "disk:/Книга",
            "chapter.review.json",
            "{}".toByteArray(),
            lock,
        )

        assertEquals("R2", revision)
        assertEquals(14, server.requestCount)
        assertEquals("disk:/Книга/chapter.review.json", server.takeRequest().url.queryParameter("path"))
        assertEquals("disk:/Книга/.pocket-editor.sync.lock", server.takeRequest().url.queryParameter("path"))
    }

    @Test
    fun `accepted overwrite retries when revision changes during byte observation`() = runBlocking {
        val lock = lock()
        enqueueJson("""{"path":"disk:/Книга/chapter.review.json","revision":"R1"}""")
        enqueueLockDownload(lock)
        enqueueJson(uploadLink("/guarded-upload"))
        server.enqueue(MockResponse.Builder().code(202).build())
        enqueueFileDownload("disk:/Книга/chapter.review.json", "R2", "{}")
        enqueueJson("""{"path":"disk:/Книга/chapter.review.json","revision":"R3"}""")
        enqueueStableFileObservation("disk:/Книга/chapter.review.json", "R3", "{}")

        val revision = gateway.uploadGuarded(
            "disk:/Книга",
            "chapter.review.json",
            "{}".toByteArray(),
            lock,
        )

        assertEquals("R3", revision)
        assertEquals(14, server.requestCount)
    }

    @Test
    fun `accepted guarded upload times out without claiming success`() {
        val lock = lock()
        enqueueJson("""{"path":"disk:/Книга/chapter.review.json","revision":"old-r"}""")
        enqueueLockDownload(lock)
        enqueueJson(uploadLink("/guarded-upload"))
        server.enqueue(MockResponse.Builder().code(202).build())
        repeat(3) { enqueueStableFileObservation("disk:/Книга/chapter.review.json", "old-r", "{}") }

        assertThrows(YandexDiskError.UploadIncomplete::class.java) {
            runBlocking {
                gateway.uploadGuarded("disk:/Книга", "chapter.review.json", "{}".toByteArray(), lock)
            }
        }
    }

    @Test
    fun `uploadGuarded aborts without upload request when nonce changed`() {
        val ours = lock()
        server.enqueue(MockResponse.Builder().code(404).build())
        enqueueLockDownload(lock())

        assertThrows(YandexDiskError.LockLost::class.java) {
            runBlocking {
                gateway.uploadGuarded("disk:/Книга", ".pocket-editor.json", byteArrayOf(1), ours)
            }
        }
        assertEquals(4, server.requestCount)
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
        server.enqueue(MockResponse.Builder().code(404).build())

        assertThrows(YandexDiskError.LockLost::class.java) {
            runBlocking {
                gateway.uploadGuarded("disk:/Книга", ".pocket-editor.json", byteArrayOf(1), lock())
            }
        }
        assertEquals(2, server.requestCount)
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
    fun `breakObservedLock re-reads exact observed nonce immediately before delete`() = runBlocking {
        val observed = lock()
        enqueueLockDownload(observed)
        server.enqueue(MockResponse.Builder().code(204).build())

        gateway.breakObservedLock("disk:/Книга", observed)

        repeat(3) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `breakObservedLock preserves lock that changed since confirmation`() {
        val observed = lock()
        enqueueLockDownload(lock())

        assertThrows(YandexDiskError.LockLost::class.java) {
            runBlocking { gateway.breakObservedLock("disk:/Книга", observed) }
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
    fun `API responses preserve Retry-After seconds and parse HTTP dates`() {
        val cases = listOf(
            "18" to 18L,
            "Wed, 21 Oct 2015 07:28:00 GMT" to 0L,
        )

        cases.forEach { (header, expectedSeconds) ->
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", header).build())

            val failure = assertThrows(YandexDiskError.RateLimited::class.java) {
                runBlocking { gateway.listFolder("disk:/Book") }
            }

            assertEquals(expectedSeconds, failure.retryAfterSeconds)
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
    fun `logger redacts request and exception details on transport failure`() {
        val messages = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(RedactingHttpLogger(messages::add))
            .addInterceptor { throw IOException("token-and-manuscript-must-not-escape") }
            .build()

        assertThrows(IOException::class.java) {
            client.newCall(
                okhttp3.Request.Builder()
                    .url("https://cloud-api.yandex.net/v1/disk/resources?path=disk%3A%2FPrivate%20Book&query=secret")
                    .header("Authorization", "OAuth secret-token")
                    .build(),
            ).execute()
        }

        val output = messages.single()
        assertEquals("GET https://cloud-api.yandex.net/<redacted> -> IOException", output)
        assertFalse(output.contains("secret-token"))
        assertFalse(output.contains("Private"))
        assertFalse(output.contains("query"))
        assertFalse(output.contains("manuscript"))
    }

    @Test
    fun `transfer redirect is rejected without sending bytes to redirect target`() {
        val attacker = MockWebServer()
        attacker.start()
        try {
            val lock = lock()
            server.enqueue(MockResponse.Builder().code(404).build())
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

    @Test
    fun `arbitrary yandex host is rejected before transfer`() {
        enqueueJson("""{"path":"disk:/Книга/глава.md","revision":"remote-r7"}""")
        enqueueJson("""{"href":"https://attacker.yandex.ru/private","method":"GET","templated":false}""")

        assertThrows(YandexDiskError.InvalidRemote::class.java) {
            runBlocking { gateway.download("disk:/Книга/глава.md") }
        }
    }

    @Test
    fun `yandex transfer host on nonstandard tls port is rejected before transfer`() {
        val guardedClient = OkHttpClient.Builder()
            .dns { hostname ->
                if (hostname == server.hostName) okhttp3.Dns.SYSTEM.lookup(hostname)
                else throw AssertionError("Transfer network must not start")
            }
            .build()
        gateway = OkHttpYandexDiskGateway(
            client = guardedClient,
            apiBaseUrl = server.url("/v1/disk/"),
            accessToken = { SecretToken("test-token") },
        )
        enqueueJson("""{"path":"disk:/Книга/глава.md","revision":"remote-r7"}""")
        enqueueJson("""{"href":"https://downloader.disk.yandex.ru:444/private","method":"GET","templated":false}""")

        assertThrows(YandexDiskError.InvalidRemote::class.java) {
            runBlocking { gateway.download("disk:/Книга/глава.md") }
        }
        assertEquals(2, server.requestCount)
    }

    private fun enqueueLockDownload(lock: SyncLock) {
        enqueueJson("""{"path":"disk:/Книга/.pocket-editor.sync.lock","revision":"lock-r"}""")
        enqueueJson("""{"href":"${server.url("/lock-download")}","method":"GET","templated":false}""")
        server.enqueue(MockResponse.Builder().code(200).body(lock.json()).build())
    }

    private fun gatewayWithoutTransportRetry() {
        gateway = OkHttpYandexDiskGateway(
            OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            server.url("/v1/disk/"),
        ) { SecretToken("test-token") }
    }

    private fun enqueueFileDownload(path: String, revision: String, body: String) {
        enqueueJson("""{"path":"$path","revision":"$revision"}""")
        enqueueJson("""{"href":"${server.url("/file-download")}","method":"GET","templated":false}""")
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
    }

    private fun enqueueStableFileObservation(path: String, revision: String, body: String) {
        enqueueFileDownload(path, revision, body)
        enqueueJson("""{"path":"$path","revision":"$revision"}""")
    }

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build())
    }

    private fun uploadLink(path: String): String =
        """{"href":"${server.url(path)}","method":"PUT","templated":false}"""

    private fun folderPage(offset: Int, total: Int, itemPath: String, revision: String): String =
        """{"_embedded":{"offset":$offset,"limit":1,"total":$total,"items":[{"name":"${itemPath.substringAfterLast('/')}","path":"$itemPath","type":"file","size":12,"revision":"$revision"}]}}"""

    private fun lock(holderId: String = "device-a"): SyncLock = SyncLock(
        schemaVersion = 1,
        lockId = UUID.randomUUID().toString(),
        holderId = holderId,
        createdAt = Instant.parse("2026-07-19T10:00:00Z"),
    )

    private fun MockWebServer.takeRequestAfter(skip: Int): mockwebserver3.RecordedRequest {
        repeat(skip) { takeRequest() }
        return takeRequest()
    }
}
