package net.inkyquill.pocketeditor.ui.books

import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.load.ProgressiveLoadSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProgressiveLoadPresentationTest {
    @Test
    fun `active load wins over selected completed load`() {
        val selectedComplete = snapshot("selected", "disk:/selected", ProgressiveLoadPhase.COMPLETE)
        val active = snapshot("active", "disk:/active", ProgressiveLoadPhase.BACKGROUND)

        assertEquals(active, selectVisibleLoad(listOf(selectedComplete, active), "selected", emptyList()))
    }

    @Test
    fun `most recently controlled active root wins deterministically`() {
        val older = snapshot("z-book", "disk:/older", ProgressiveLoadPhase.BACKGROUND)
        val newer = snapshot("a-book", "disk:/newer", ProgressiveLoadPhase.INITIAL)

        assertEquals(
            newer,
            selectVisibleLoad(listOf(newer, older), null, listOf("disk:/older", "disk:/newer")),
        )
    }

    @Test
    fun `selected completion remains visible when there is no active load`() {
        val selected = snapshot("selected", "disk:/selected", ProgressiveLoadPhase.COMPLETE)
        val other = snapshot("other", "disk:/other", ProgressiveLoadPhase.COMPLETE)

        assertEquals(selected, selectVisibleLoad(listOf(other, selected), "selected", emptyList()))
    }

    private fun snapshot(bookId: String, root: String, phase: ProgressiveLoadPhase) = ProgressiveLoadSnapshot(
        bookId = bookId,
        remoteRootPath = root,
        phase = phase,
        totalFiles = 4,
        completedFiles = if (phase == ProgressiveLoadPhase.COMPLETE) 4 else 1,
        activePath = null,
        retryAttempt = 0,
        retryAt = null,
        generation = 1,
        paused = false,
        cancelled = false,
        lastErrorCategory = null,
        files = emptyList(),
    )
}
