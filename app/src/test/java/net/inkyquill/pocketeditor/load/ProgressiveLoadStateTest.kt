package net.inkyquill.pocketeditor.load

import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProgressiveLoadStateTest {
    @Test
    fun `only first three spine rows receive initial priority`() {
        assertEquals(listOf(1, 1, 1, 0, 0), (0 until 5).map(::initialPriority))
    }

    @Test
    fun `readiness requires exactly first three or the whole shorter book`() {
        assertFalse(snapshot(states = listOf(CACHED, CACHED, PENDING, CACHED)).initialReady)
        assertTrue(snapshot(states = listOf(CACHED, CACHED, CACHED, PENDING)).initialReady)
        assertTrue(snapshot(states = listOf(CACHED, CACHED)).initialReady)
    }

    @Test
    fun `readiness requires every initial row in the authoritative total`() {
        assertFalse(snapshot(states = emptyList(), totalFiles = 0).initialReady)
        assertFalse(snapshot(states = emptyList(), totalFiles = 2).initialReady)
        assertFalse(snapshot(states = listOf(CACHED, CACHED), totalFiles = 3).initialReady)
        assertTrue(snapshot(states = listOf(CACHED, CACHED), totalFiles = 2).initialReady)
    }

    private fun snapshot(
        states: List<ProgressiveLoadFileState>,
        totalFiles: Int = states.size,
    ) = ProgressiveLoadSnapshot(
        bookId = BOOK_ID,
        remoteRootPath = "disk:/Book",
        phase = ProgressiveLoadPhase.INITIAL,
        totalFiles = totalFiles,
        completedFiles = states.count { it == CACHED },
        activePath = null,
        retryAttempt = 0,
        retryAt = null,
        generation = 1,
        paused = false,
        cancelled = false,
        lastErrorCategory = null,
        files = states.mapIndexed { index, state ->
            ProgressiveLoadFileEntity(
                BOOK_ID, "chapter-$index.md", "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
                index, "r$index", 10, null, state, initialPriority(index),
            )
        },
    )

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        val CACHED = ProgressiveLoadFileState.CACHED
        val PENDING = ProgressiveLoadFileState.PENDING
    }
}
