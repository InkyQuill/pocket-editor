package net.inkyquill.pocketeditor.storage

import io.mockk.mockk
import java.io.File
import net.inkyquill.pocketeditor.database.BookDao
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallRecoveryJournalTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `discard removes malformed marker and temporary files without aborting forget cleanup`() {
        val marker = File(root, ".install-journal-$BOOK_ID.state").also { it.writeText("malformed") }
        val temporary = File(root, ".${marker.name}.pending.tmp").also { it.writeText("partial") }
        var syncCalls = 0
        val journal = InstallRecoveryJournal(
            BookPaths(root),
            mockk<BookDao>(),
            DirectoryFsync { syncCalls++; DirectorySyncStatus.SYNCED },
        )

        journal.discard(BOOK_ID)

        assertFalse(marker.exists())
        assertFalse(temporary.exists())
        assertEquals(1, syncCalls)
    }

    @Test
    fun `discard never removes a stage owned by a different payload book`() {
        val stage = File(root, ".install-22222222-2222-2222-2222-222222222222").also { it.mkdirs() }
        val marker = File(root, ".install-journal-$BOOK_ID.state").also {
            it.writeText(
                "version=1\n" +
                    "book_id=$OTHER_BOOK_ID\n" +
                    "stage_root=${stage.name}\n" +
                    "phase=PREPARED\n",
            )
        }
        val journal = InstallRecoveryJournal(BookPaths(root), mockk<BookDao>())

        journal.discard(BOOK_ID)

        assertTrue(stage.exists())
        assertFalse(marker.exists())
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val OTHER_BOOK_ID = "33333333-3333-3333-3333-333333333333"
    }
}
