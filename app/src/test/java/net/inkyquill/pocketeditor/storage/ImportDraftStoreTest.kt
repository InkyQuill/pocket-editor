package net.inkyquill.pocketeditor.storage

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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

        store.writeSource(BOOK_ID, "01-пролог.md", bytes, "rev-1")

        val reopened = ImportDraftStore(draftsRoot)
        assertTrue(reopened.hasMatchingSource(BOOK_ID, "01-пролог.md", "rev-1", bytes.sha256()))
        assertArrayEquals(bytes, reopened.readSource(BOOK_ID, "01-пролог.md"))
        assertFalse(reopened.hasMatchingSource(BOOK_ID, "01-пролог.md", "rev-2", bytes.sha256()))

        reopened.delete(BOOK_ID)

        assertFalse(reopened.directory(BOOK_ID).exists())
    }

    @Test
    fun `draft source paths reject traversal`() {
        val store = ImportDraftStore(File(root, "import-drafts"))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.writeSource(BOOK_ID, "../chapter.md", "unsafe".encodeToByteArray(), "rev-1")
            }
        }
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
    }
}
