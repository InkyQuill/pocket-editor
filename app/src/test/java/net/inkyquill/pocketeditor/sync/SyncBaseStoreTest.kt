package net.inkyquill.pocketeditor.sync

import java.io.File
import java.nio.file.Files
import java.util.UUID
import net.inkyquill.pocketeditor.storage.DirectoryFsync
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `forgetting a book removes every base artifact without touching another book`() {
        val root = Files.createTempDirectory("sync-bases-forget").toFile()
        val store = AtomicSyncBaseStore(root)
        val otherBookId = UUID.randomUUID().toString()
        store.write(BOOK_ID, ".pocket-editor.json", byteArrayOf(1), "r1")
        store.write(BOOK_ID, "chapter.md.review.json", byteArrayOf(2), "r2")
        store.write(otherBookId, ".pocket-editor.json", byteArrayOf(3), "r3")
        File(root, "$BOOK_ID/.orphan.tmp").writeText("interrupted")

        store.deleteBook(BOOK_ID)

        assertFalse(File(root, BOOK_ID).exists())
        assertTrue(File(root, otherBookId).isDirectory)
        assertArrayEquals(byteArrayOf(3), store.read(otherBookId, ".pocket-editor.json")?.bytes)
    }

    @Test
    fun `startup pruning removes only orphaned canonical book directories`() {
        val root = Files.createTempDirectory("sync-bases-prune").toFile()
        val store = AtomicSyncBaseStore(root)
        val retainedBookId = UUID.randomUUID().toString()
        val orphanedBookId = UUID.randomUUID().toString()
        store.write(retainedBookId, ".pocket-editor.json", byteArrayOf(1), "r1")
        store.write(orphanedBookId, ".pocket-editor.json", byteArrayOf(2), "r2")
        val unrelated = File(root, "migration-note.txt").also { it.writeText("preserve") }

        val orphaned = store.bookIds() - retainedBookId
        orphaned.forEach(store::deleteBook)

        assertEquals(setOf(orphanedBookId), orphaned)
        assertTrue(File(root, retainedBookId).isDirectory)
        assertFalse(File(root, orphanedBookId).exists())
        assertTrue(unrelated.isFile)
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val BOOK_ID: String = UUID.randomUUID().toString()
    }
}
