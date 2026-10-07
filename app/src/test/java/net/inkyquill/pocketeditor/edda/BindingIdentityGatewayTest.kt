package net.inkyquill.pocketeditor.edda

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.inkyquill.pocketeditor.source.*
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BindingIdentityGatewayTest {
    private val remote = mockk<BookGateway>()
    private val records = mutableMapOf<String, BindingIdentity>()
    private val store = object : BindingIdentityStore {
        override fun get(root: String) = records[root]
        override fun put(root: String, identity: BindingIdentity) { records[root] = identity }
    }
    private val sourceId = "a7000000-0000-4000-8000-000000000001"
    private val source = """{"schema_version":2,"book_id":"$sourceId","title":"Book","chapters":[],"future_metadata":{"preserve":true}}""".encodeToByteArray()
    private fun id(file: RemoteFile) = Json.parseToJsonElement(file.bytes.decodeToString()).jsonObject.getValue("book_id").jsonPrimitive.content
    @Test fun `copies across roots projects and accounts have isolated cache identities and unchanged wire IDs`() = runBlocking {
        val roots = listOf(
            EddaLocation("a".repeat(64), "series", "book-01"),
            EddaLocation("a".repeat(64), "series", "book-02"),
            EddaLocation("a".repeat(64), "other", "book-01"),
            EddaLocation("b".repeat(64), "series", "book-01"),
        ).map { it.root }
        coEvery { remote.download(any()) } answers { RemoteFile(firstArg(), source, "wire-hash") }
        val gateway = BindingIdentityGateway(remote, store)
        val downloaded = roots.map { gateway.download("$it/.pocket-editor.json") }
        assertEquals(4, downloaded.map(::id).distinct().size)
        assertTrue(downloaded.none { id(it) == sourceId })
        assertTrue(downloaded.all { it.revision == "wire-hash" })
        assertEquals(id(downloaded.first()), id(BindingIdentityGateway(remote, store).download(downloaded.first().path)))
        var published: ByteArray? = null
        coEvery { remote.uploadManifestConditionally(any(), any(), any(), any(), any()) } answers { published = secondArg(); "new-wire-hash" }
        val lock = SyncLock(1, UUID.randomUUID().toString(), "device", Instant.now())
        gateway.uploadManifestConditionally(roots.first(), downloaded.first().bytes, downloaded.first(), lock)
        val result = Json.parseToJsonElement(requireNotNull(published).decodeToString()).jsonObject
        assertEquals(sourceId, result.getValue("book_id").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(source.decodeToString()), result)
    }
    @Test fun `new binder keeps its original ID over publication restart and reimport`() = runBlocking {
        val root = EddaLocation("a".repeat(64), "new", "book").root
        val gateway = BindingIdentityGateway(remote, store)
        coEvery { remote.uploadManifestConditionally(any(), any(), any(), any(), any()) } returns "hash"
        gateway.uploadManifestConditionally(root, source, null, SyncLock(1, UUID.randomUUID().toString(), "device", Instant.now()))
        coEvery { remote.download(any()) } answers { RemoteFile(firstArg(), source, "hash") }
        assertEquals(sourceId, id(BindingIdentityGateway(remote, store).download("$root/.pocket-editor.json")))
    }
    @Test fun `source bytes and review sidecars pass through exactly`() = runBlocking {
        val path = "edda://${"a".repeat(64)}/project/book/chapter.review.json"
        val file = RemoteFile(path, "{\"unknown\":1}".encodeToByteArray(), "revision")
        coEvery { remote.download(path) } returns file
        assertSame(file, BindingIdentityGateway(remote, store).download(path))
        assertTrue(records.isEmpty())
    }
}
