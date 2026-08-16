package net.inkyquill.pocketeditor.ui.contents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentsReorderStateTest {
    @Test
    fun `move and cancel preserve the complete immutable id set`() {
        val state = ContentsReorderState.create(listOf("one", "two", "three"))

        state.move(2, 1)

        assertEquals(listOf("one", "three", "two"), state.orderedChapterIds)
        assertTrue(state.changed)
        state.cancel()
        assertEquals(listOf("one", "two", "three"), state.orderedChapterIds)
        assertFalse(state.changed)
    }

    @Test
    fun `invalid drafts and moves are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ContentsReorderState.create(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ContentsReorderState.create(listOf("one", "one")) }
        val state = ContentsReorderState.create(listOf("one", "two"))
        assertThrows(IllegalArgumentException::class.java) { state.move(0, 2) }
    }

    @Test
    fun `restore discards a draft when the canonical spine changed`() {
        val saver = ContentsReorderState.saver(listOf("one", "two", "three"))

        assertNull(saver.restore(arrayListOf("one", "two")))
        assertNull(saver.restore(arrayListOf("one", "two", "four")))
    }

    @Test
    fun `stale draft cannot be saved after the canonical spine changed`() {
        val state = ContentsReorderState.create(listOf("one", "two", "three"))
        state.move(2, 1)

        assertNull(state.orderForSave(listOf("one", "two", "four")))
        assertNull(state.orderForSave(listOf("three", "two", "one")))
        assertEquals(listOf("one", "three", "two"), state.orderForSave(listOf("one", "two", "three")))
    }
}
