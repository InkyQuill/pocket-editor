package net.inkyquill.pocketeditor.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EditDiffTest {
    @Test
    fun `replacement emits deterministic unchanged deleted and added runs`() {
        assertEquals(
            listOf(
                DiffRun(DiffKind.UNCHANGED, "тикет "),
                DiffRun(DiffKind.DELETED, "снова"),
                DiffRun(DiffKind.ADDED, "опять"),
                DiffRun(DiffKind.UNCHANGED, " открыт"),
            ),
            EditDiff.compute("тикет снова открыт", "тикет опять открыт"),
        )
    }

    @Test
    fun `deletion emits only a deleted run`() {
        assertEquals(listOf(DiffRun(DiffKind.DELETED, "remove")), EditDiff.compute("remove", ""))
    }

    @Test
    fun `insertion emits only an added run`() {
        assertEquals(listOf(DiffRun(DiffKind.ADDED, "insert")), EditDiff.compute("", "insert"))
    }

    @Test
    fun `unchanged text is one unchanged run`() {
        assertEquals(listOf(DiffRun(DiffKind.UNCHANGED, "same")), EditDiff.compute("same", "same"))
    }

    @Test
    fun `diff never splits a supplementary Unicode code point`() {
        assertEquals(
            listOf(
                DiffRun(DiffKind.UNCHANGED, "a"),
                DiffRun(DiffKind.DELETED, "😀"),
                DiffRun(DiffKind.ADDED, "🦊"),
                DiffRun(DiffKind.UNCHANGED, "b"),
            ),
            EditDiff.compute("a😀b", "a🦊b"),
        )
    }
}
