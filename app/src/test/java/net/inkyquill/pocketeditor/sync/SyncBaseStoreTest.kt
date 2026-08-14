package net.inkyquill.pocketeditor.sync

import java.nio.file.Files
import java.util.UUID
import net.inkyquill.pocketeditor.storage.DirectoryFsync
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SyncBaseStoreTest {
    @Test
    fun `stores exact confirmed bytes and metadata atomically`() {
        val root = Files.createTempDirectory("sync-bases").toFile()
        val store = AtomicSyncBaseStore(root)
        val bytes = "{\"schema_version\":1}".encodeToByteArray()

        store.write(BOOK_ID, "chapter.md.review.json", bytes, "remote-1")

        val base = store.read(BOOK_ID, "chapter.md.review.json")!!
        assertArrayEquals(bytes, base.bytes)
        assertEquals("remote-1", base.remoteRevision)
        assertEquals(sha256(bytes), base.sha256)
    }

    @Test
    fun `interrupted replacement preserves previous valid base`() {
        val root = Files.createTempDirectory("sync-bases-interrupt").toFile()
        AtomicSyncBaseStore(root).write(BOOK_ID, ".pocket-editor.json", "old".encodeToByteArray(), "r1")
        val failing = AtomicSyncBaseStore(root) { _, _ -> error("interrupted") }

        assertThrows(IllegalStateException::class.java) {
            failing.write(BOOK_ID, ".pocket-editor.json", "new".encodeToByteArray(), "r2")
        }

        val preserved = AtomicSyncBaseStore(root).read(BOOK_ID, ".pocket-editor.json")!!
        assertArrayEquals("old".encodeToByteArray(), preserved.bytes)
        assertEquals("r1", preserved.remoteRevision)
    }

    @Test
    fun `rejects sources and traversal and can remove obsolete metadata base`() {
        val store = AtomicSyncBaseStore(Files.createTempDirectory("sync-bases-paths").toFile())
        assertThrows(IllegalArgumentException::class.java) {
            store.write(BOOK_ID, "chapter.md", byteArrayOf(1), "r1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(BOOK_ID, "../chapter.md.review.json", byteArrayOf(1), "r1")
        }

        store.write(BOOK_ID, ".pocket-editor.json", byteArrayOf(1), "r1")
        store.delete(BOOK_ID, ".pocket-editor.json")
        assertNull(store.read(BOOK_ID, ".pocket-editor.json"))
    }

    @Test
    fun `directory fsync happens after rename and status is reported`() {
        val root = Files.createTempDirectory("sync-bases-order").toFile()
        val events = mutableListOf<String>()
        var targetPath: java.io.File? = null
        val store = AtomicSyncBaseStore(
            root = root,
            beforeReplace = { _, target ->
                targetPath = target
                events += "replace"
            },
            directoryFsync = DirectoryFsync { directory ->
                assertEquals(targetPath?.parentFile, directory)
                assertEquals(true, targetPath?.exists())
                events += "directory-fsync"
                DirectorySyncStatus.SYNCED
            },
        )

        val base = store.write(BOOK_ID, ".pocket-editor.json", byteArrayOf(1), "r1")

        assertEquals(listOf("replace", "directory-fsync"), events)
        assertEquals(DirectorySyncStatus.SYNCED, base.directorySyncStatus)
    }

    @Test
    fun `base deletion reports unsupported directory durability even when file is already absent`() {
        val store = AtomicSyncBaseStore(
            root = Files.createTempDirectory("sync-bases-delete").toFile(),
            beforeReplace = { _, _ -> },
            directoryFsync = DirectoryFsync { DirectorySyncStatus.UNSUPPORTED },
        )

        val status = store.delete(BOOK_ID, ".pocket-editor.json")

        assertEquals(DirectorySyncStatus.UNSUPPORTED, status)
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val BOOK_ID: String = UUID.randomUUID().toString()
    }
}
