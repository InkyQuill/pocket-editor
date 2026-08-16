package net.inkyquill.pocketeditor.storage

import io.mockk.mockk
import java.io.File
import net.inkyquill.pocketeditor.database.BookDao
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
    }
}
