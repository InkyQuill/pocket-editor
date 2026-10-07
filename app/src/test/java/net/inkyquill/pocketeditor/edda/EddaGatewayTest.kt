package net.inkyquill.pocketeditor.edda

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EddaGatewayTest {
    private val server = MockWebServer().apply { start() }
    private val root = EddaLocation("a".repeat(64), "project", "book-01")
    private val client = EddaClient(OkHttpClient().newBuilder().retryOnConnectionFailure(false).build()) {
        EddaCredentials(server.url("/").toString(), "fixture-token")
    }
    private val gateway = EddaGateway(client)
    private val lock = SyncLock(1, UUID.randomUUID().toString(), "fixture-device", Instant.parse("2026-10-07T00:00:00Z"))
    private val chapter = "# Chapter\nOriginal 🦊\n".encodeToByteArray()
    private fun entry(path: String, bytes: ByteArray) = EddaEntry(UUID.randomUUID().toString(), path, "file", bytes.sha256(), bytes.size.toLong())
    private val source = entry("book-01/chapter.md", chapter)
    private val directory = EddaEntry("dir-1", "book-01", "directory")
    private val other = entry("book-02/chapter.md", "other".encodeToByteArray())
    private val initial = EddaVersion("v1", listOf(directory, source, other))
    private val lockEntry = entry("book-01/${EddaGateway.LOCK}", lock.json().encodeToByteArray())
    private val acquired = initial.copy(id = "v2", entries = initial.entries + lockEntry)
    private fun respond(value: EddaVersion) = server.enqueue(MockResponse.Builder().body(Json.encodeToString(value)).build())
    private fun respond(bytes: ByteArray) = server.enqueue(MockResponse.Builder().body(okio.Buffer().write(bytes)).build())
    private fun ok() = server.enqueue(MockResponse.Builder().code(204).build())
    private suspend fun acquire() {
        respond(initial); ok(); respond(acquired)
        assertEquals(lock, gateway.tryAcquireLock(root.root, lock))
        repeat(3) { server.takeRequest() }
    }
    @AfterEach fun close() { server.close() }

    @Test fun `folder listing isolates book directories and account project identities`() = runBlocking {
        respond(initial)
        val files = gateway.listFolder(root.root)
        assertEquals(listOf("chapter.md"), files.map { it.name })
        assertEquals(root.child("chapter.md").root, files.single().path)
        val request = server.takeRequest()
        assertEquals("/api/projects/project/files/versions/current", request.url.encodedPath)
        assertEquals("Bearer fixture-token", request.headers["Authorization"])
        assertNotEquals(root.root, root.copy(project = "another").root)
        assertNotEquals(root.root, root.copy(account = "b".repeat(64)).root)
        assertNotEquals(root.root, root.copy(path = "book-02").root)
    }
    @Test fun `downloads validate immutable source hashes`() = runBlocking {
        respond(initial); respond(chapter)
        assertArrayEquals(chapter, gateway.download(root.child("chapter.md").root).bytes)
        respond(initial); respond("corrupt".encodeToByteArray())
        assertThrows(YandexDiskError.InvalidRemote::class.java) { runBlocking { gateway.download(root.child("chapter.md").root) } }
    }
    @Test fun `review writes preserve canonical sources other folders IDs and version preconditions`() = runBlocking {
        acquire()
        val review = "{\"chapter_note\":\"offline note\"}".encodeToByteArray()
        ok(); respond(acquired.copy(id = "v3", entries = acquired.entries + entry("book-01/chapter.review.json", review)))
        assertEquals(review.sha256(), gateway.uploadGuarded(root.root, "chapter.review.json", review, lock))
        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertArrayEquals(review, upload.body!!.toByteArray())
        val publication = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("v2", publication.getValue("expectedVersion").jsonPrimitive.content)
        val entries = Json.decodeFromJsonElement<List<EddaEntry>>(publication.getValue("entries"))
        assertEquals(source, entries.single { it.path == source.path })
        assertEquals(other, entries.single { it.path == other.path })
        assertEquals(directory, entries.single { it.path == directory.path })
    }
    @Test fun `canonical Markdown publication and path traversal are rejected without network`() = runBlocking {
        acquire()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { gateway.uploadGuarded(root.root, "chapter.md", chapter, lock) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { gateway.uploadGuarded(root.root, "../other.review.json", chapter, lock) } }
        assertThrows(IllegalArgumentException::class.java) { EddaLocation.parse(root.root + "/../book-02") }
        assertEquals(3, server.requestCount)
    }
    @Test fun `desktop changes reject stale publication rather than overwrite newer tree`() = runBlocking {
        acquire(); ok(); server.enqueue(MockResponse.Builder().code(409).build())
        assertThrows(YandexDiskError.PublicationPreconditionFailed::class.java) {
            runBlocking { gateway.uploadGuarded(root.root, "chapter.review.json", "note".encodeToByteArray(), lock) }
        }
    }
    @Test fun `foreign lock cannot be acquired or released`() = runBlocking {
        val foreign = lock.copy(lockId = UUID.randomUUID().toString(), holderId = "desktop")
        val bytes = foreign.json().encodeToByteArray()
        val version = initial.copy(entries = initial.entries + entry("book-01/${EddaGateway.LOCK}", bytes))
        respond(version); respond(bytes)
        assertThrows(YandexDiskError.LockHeld::class.java) { runBlocking { gateway.tryAcquireLock(root.root, lock) } }
        respond(version); respond(bytes)
        assertThrows(YandexDiskError.LockLost::class.java) { runBlocking { gateway.releaseOwnedLock(root.root, lock) } }
        assertEquals(4, server.requestCount)
    }
    @Test fun `release uses current tree so unrelated desktop changes survive`() = runBlocking {
        acquire()
        val desktop = entry("desktop.md", "new".encodeToByteArray())
        val current = acquired.copy(id = "v7", entries = acquired.entries + desktop)
        respond(current); respond(lock.json().encodeToByteArray()); respond(current.copy(id = "v8", entries = current.entries - lockEntry))
        gateway.releaseOwnedLock(root.root, lock)
        repeat(2) { server.takeRequest() }
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("v7", body.getValue("expectedVersion").jsonPrimitive.content)
        val entries = Json.decodeFromJsonElement<List<EddaEntry>>(body.getValue("entries"))
        assertTrue(desktop in entries)
        assertFalse(lockEntry in entries)
    }
    @Test fun `source validation failure prevents manifest publication`() = runBlocking {
        acquire()
        assertThrows(YandexDiskError.PublicationPreconditionFailed::class.java) {
            runBlocking { gateway.uploadManifestConditionally(root.root, "{}".encodeToByteArray(), null, lock) { false } }
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun `lost publication response recovers the same operation without a second write`() = runBlocking {
        acquire()
        val bytes = "note".encodeToByteArray()
        ok()
        server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.CloseSocket()).build())
        respond(acquired.copy(id = "v3", entries = acquired.entries + entry("book-01/chapter.review.json", bytes)))
        assertEquals(bytes.sha256(), gateway.uploadGuarded(root.root, "chapter.review.json", bytes, lock))
        server.takeRequest()
        val publication = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val operation = publication.getValue("operationId").jsonPrimitive.content
        assertEquals("/api/projects/project/files/operations/$operation", server.takeRequest().url.encodedPath)
    }
    @Test fun `redirect cannot exfiltrate authorization`() = runBlocking {
        MockWebServer().use { otherServer ->
            otherServer.start()
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", otherServer.url("/steal")).build())
            assertThrows(YandexDiskError.ServerFailure::class.java) { runBlocking { client.projects(root.account) } }
            assertEquals(0, otherServer.requestCount)
        }
    }
    @Test fun `server identity normalization rejects embedded credentials query and insecure remote`() {
        assertEquals("https://example.org", EddaClient.normalizeServer("https://example.org/"))
        listOf("http://example.org", "https://user:pass@example.org", "https://example.org?token=x", "https://example.org/#secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { EddaClient.normalizeServer(it) }
        }
    }
}
