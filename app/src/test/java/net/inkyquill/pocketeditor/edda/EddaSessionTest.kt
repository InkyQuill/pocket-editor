package net.inkyquill.pocketeditor.edda

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EddaSessionTest {
    private val server = MockWebServer().apply { start() }
    private var stored = EddaCredentials(server.url("/").toString(), "expired", "refresh-old", "author")
    private val client = EddaClient(OkHttpClient()) { stored }.apply {
        saveRefreshedSession = { _, previous, session ->
            assertEquals(stored.token, previous)
            stored = EddaCredentials(stored.server, session.token, session.refreshToken, session.author.id)
        }
    }
    @AfterEach fun close() = server.close()
    private fun response(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private val renewed = """{"token":"renewed","refreshToken":"refresh-new","refreshExpiresAt":1900000000,"author":{"id":"author","email":"reader@example.invalid"}}"""

    @Test fun `expired access refreshes saves rotated session and retries original request`() = runBlocking {
        response(401); response(200, renewed); response(200, "[]")
        assertEquals(emptyList<EddaProject>(), client.projects("account"))
        assertEquals("Bearer expired", server.takeRequest().headers["Authorization"])
        val refresh = server.takeRequest()
        assertEquals("/api/auth/refresh", refresh.url.encodedPath)
        assertEquals("POST", refresh.method)
        assertNull(refresh.headers["Authorization"])
        assertEquals("{\"refreshToken\":\"refresh-old\"}", refresh.body!!.utf8())
        assertEquals("Bearer renewed", server.takeRequest().headers["Authorization"])
        assertEquals("refresh-new", stored.refreshToken)
    }

    @Test fun `legacy access token requires login without refresh loop`() {
        stored = EddaCredentials(stored.server, "legacy")
        response(401)
        assertThrows(YandexDiskError.Unauthorized::class.java) { runBlocking { client.projects("account") } }
        assertEquals(1, server.requestCount)
    }

    @Test fun `revoked refresh asks for sign in`() {
        response(401); response(401)
        assertThrows(YandexDiskError.Unauthorized::class.java) { runBlocking { client.projects("account") } }
        assertEquals(2, server.requestCount)
        assertEquals("refresh-old", stored.refreshToken)
    }

    @Test fun `temporary refresh failure preserves credentials for retry`() {
        response(401); response(503)
        assertThrows(YandexDiskError.ServerFailure::class.java) { runBlocking { client.projects("account") } }
        assertEquals("refresh-old", stored.refreshToken)
    }

    @Test fun `retry unauthorized stops after one refresh`() {
        response(401); response(200, renewed); response(401)
        assertThrows(YandexDiskError.Unauthorized::class.java) { runBlocking { client.projects("account") } }
        assertEquals(3, server.requestCount)
    }

    @Test fun `refresh cannot substitute another author`() {
        response(401); response(200, renewed.replace("\"id\":\"author\"", "\"id\":\"other\""))
        assertThrows(YandexDiskError.Unauthorized::class.java) { runBlocking { client.projects("account") } }
        assertEquals("expired", stored.token)
    }

    @Test fun `parallel expired requests share one refresh`() = runBlocking {
        val arrived = java.util.concurrent.CountDownLatch(2)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/api/auth/refresh") return MockResponse.Builder().body(renewed).build()
                if (request.headers["Authorization"] == "Bearer expired") {
                    arrived.countDown()
                    check(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse.Builder().code(401).build()
                }
                return MockResponse.Builder().body("[]").build()
            }
        }
        listOf(async { client.projects("account") }, async { client.projects("account") }).awaitAll()
        val requests = List(5) { server.takeRequest() }
        assertEquals(1, requests.count { it.url.encodedPath == "/api/auth/refresh" })
        assertEquals(2, requests.count { it.headers["Authorization"] == "Bearer renewed" })
    }
    @Test fun `cancelling refresh still persists rotated credentials`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        stored = EddaCredentials(stored.server, jwt(1), "refresh-old", "author")
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                entered.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return MockResponse.Builder().body(renewed).build()
            }
        }
        val operation = async { client.projects("account") }
        try {
            kotlinx.coroutines.withTimeout(5_000) { entered.await() }
            operation.cancel()
        } finally {
            release.countDown()
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals("refresh-new", stored.refreshToken)
        assertEquals("renewed", stored.token)
        assertEquals(1, server.requestCount)
    }

    private fun jwt(expires: Long): String = "header." + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"exp\":$expires}".encodeToByteArray()) + ".signature"

    @Test fun `normal use refreshes before expiry and uses latest rotated refresh on next renewal`() = runBlocking {
        val now = 1_800_000_000L
        client.clock = java.time.Clock.fixed(java.time.Instant.ofEpochSecond(now), java.time.ZoneOffset.UTC)
        stored = EddaCredentials(stored.server, jwt(now + 30), "refresh-old", "author")
        val nextToken = jwt(now + 86400)
        response(200, renewed.replace("\"renewed\"", "\"$nextToken\"")); response(200, "[]")
        client.projects("account")
        assertEquals("/api/auth/refresh", server.takeRequest().url.encodedPath)
        assertEquals("Bearer $nextToken", server.takeRequest().headers["Authorization"])
        assertEquals("refresh-new", stored.refreshToken)

        client.clock = java.time.Clock.fixed(java.time.Instant.ofEpochSecond(now + 86400), java.time.ZoneOffset.UTC)
        response(200, renewed.replace("refresh-new", "refresh-newer")); response(200, "[]")
        client.projects("account")
        val secondRefresh = server.takeRequest()
        assertEquals("/api/auth/refresh", secondRefresh.url.encodedPath)
        assertEquals("{\"refreshToken\":\"refresh-new\"}", secondRefresh.body!!.utf8())
        assertEquals("Bearer renewed", server.takeRequest().headers["Authorization"])
        assertEquals("refresh-newer", stored.refreshToken)
    }

    @Test fun `valid access token does not refresh on each sync`() = runBlocking {
        stored = EddaCredentials(stored.server, jwt(java.time.Instant.now().epochSecond + 3600), "refresh-old", "author")
        response(200, "[]")
        client.projects("account")
        assertEquals("/api/projects", server.takeRequest().url.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test fun `proactive refresh server failure preserves session without requesting login`() {
        stored = EddaCredentials(stored.server, jwt(1), "refresh-old", "author")
        response(503)
        assertThrows(YandexDiskError.ServerFailure::class.java) { runBlocking { client.projects("account") } }
        assertEquals("refresh-old", stored.refreshToken)
        assertEquals(1, server.requestCount)
    }

    @Test fun `forbidden project is not treated as expired login`() {
        response(403)
        assertThrows(YandexDiskError.InvalidRemote::class.java) { runBlocking { client.projects("account") } }
        assertEquals(1, server.requestCount)
    }

}
