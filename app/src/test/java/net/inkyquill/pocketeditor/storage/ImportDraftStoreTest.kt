package net.inkyquill.pocketeditor.storage

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImportDraftStoreTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `matching cached source survives reopen and explicit delete removes only draft tree`() = runBlocking {
        val draftsRoot = File(root, "import-drafts")
        val store = ImportDraftStore(draftsRoot)
        val bytes = "# Пролог\n".encodeToByteArray()

        writeLegacySource(draftsRoot, "01-пролог.md", bytes, "rev-1")

        val reopened = ImportDraftStore(draftsRoot)
        assertArrayEquals(bytes, reopened.readMatchingSource(BOOK_ID, "01-пролог.md", "rev-1", bytes.sha256()))
        assertNull(reopened.readMatchingSource(BOOK_ID, "01-пролог.md", "rev-2", bytes.sha256()))

        reopened.delete(BOOK_ID)

        assertFalse(BookPaths(draftsRoot).bookDirectory(BOOK_ID).exists())
    }

    @Test
    fun `mismatched cached source cannot be returned`() = runBlocking {
        val store = ImportDraftStore(File(root, "import-drafts"))
        val bytes = "# Original\n".encodeToByteArray()
        writeLegacySource(File(root, "import-drafts"), "chapter.md", bytes, "rev-1")
        BookPaths(File(root, "import-drafts")).source(BOOK_ID, "chapter.md").writeText("# Tampered\n")

        assertNull(store.readMatchingSource(BOOK_ID, "chapter.md", "rev-1", bytes.sha256()))
    }

    private fun writeLegacySource(draftsRoot: File, path: String, bytes: ByteArray, revision: String) {
        val source = BookPaths(draftsRoot).source(BOOK_ID, path)
        source.parentFile!!.mkdirs()
        source.writeBytes(bytes)
        File(source.parentFile, ".${source.name}.import-cache.json").writeText(
            """{"remoteRevision":"$revision","sha256":"${bytes.sha256()}","byteSize":${bytes.size}}""",
        )
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
    }
}
