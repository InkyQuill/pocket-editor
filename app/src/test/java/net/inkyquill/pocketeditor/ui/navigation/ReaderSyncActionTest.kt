package net.inkyquill.pocketeditor.ui.navigation

import net.inkyquill.pocketeditor.reader.ReaderSyncState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReaderSyncActionTest {
    @Test fun `authentication warning opens original source instead of retrying expired credentials`() {
        val root = "edda://${"a".repeat(64)}/project/book"
        var selected: String? = null
        handleReaderSyncAction(ReaderSyncState.SIGN_IN_REQUIRED, root, { selected = it }, { fail("Must sign in first") })
        assertEquals(root, selected)
    }
    @Test fun `network warning still retries sync`() {
        var retries = 0
        handleReaderSyncAction(ReaderSyncState.WAITING_TO_SYNC, "edda://source", { fail("Must not ask for login") }, { retries++ })
        assertEquals(1, retries)
    }
}
