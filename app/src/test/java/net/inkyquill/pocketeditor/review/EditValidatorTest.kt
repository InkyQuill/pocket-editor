package net.inkyquill.pocketeditor.review

import net.inkyquill.pocketeditor.anchor.AnchorFactory
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EditValidatorTest {
    private val source = "one two three".encodeToByteArray()

    @Test
    fun `adjacent half-open edits are valid`() {
        val left = edit("00000000-0000-0000-0000-000000000001", 0, 4, "ONE ")
        val right = edit("00000000-0000-0000-0000-000000000002", 4, 8, "TWO ")

        assertDoesNotThrow { EditValidator.validate(right, listOf(left), source) }
    }

    @Test
    fun `intersecting half-open edits are rejected`() {
        val left = edit("00000000-0000-0000-0000-000000000001", 0, 4, "ONE ")
        val intersecting = edit("00000000-0000-0000-0000-000000000002", 3, 8, " TWO ")

        assertThrows(IllegalArgumentException::class.java) {
            EditValidator.validate(intersecting, listOf(left), source)
        }
    }

    @Test
    fun `empty before text is rejected`() {
        val anchor = AnchorFactory.create(source, 0, 3)
        val edit = Edit("00000000-0000-0000-0000-000000000001", "", "ONE", anchor)

        assertThrows(IllegalArgumentException::class.java) {
            EditValidator.validate(edit, emptyList(), source)
        }
    }

    @Test
    fun `unchanged after text is rejected`() {
        val anchor = AnchorFactory.create(source, 0, 3)
        val edit = Edit("00000000-0000-0000-0000-000000000001", "one", "one", anchor)

        assertThrows(IllegalArgumentException::class.java) {
            EditValidator.validate(edit, emptyList(), source)
        }
    }

    @Test
    fun `anchor from a different source revision is rejected`() {
        val oldSource = "old one two three".encodeToByteArray()
        val anchor = AnchorFactory.create(oldSource, 4, 7)
        val edit = Edit("00000000-0000-0000-0000-000000000001", "one", "ONE", anchor)

        assertThrows(IllegalArgumentException::class.java) {
            EditValidator.validate(edit, emptyList(), source)
        }
    }

    @Test
    fun `before text must equal exact source bytes at anchor`() {
        val anchor = AnchorFactory.create(source, 0, 3)
        val edit = Edit("00000000-0000-0000-0000-000000000001", "two", "TWO", anchor)

        assertThrows(IllegalArgumentException::class.java) {
            EditValidator.validate(edit, emptyList(), source)
        }
    }

    private fun edit(id: String, start: Int, end: Int, after: String): Edit = Edit(
        id = id,
        before = source.copyOfRange(start, end).decodeToString(),
        after = after,
        anchor = AnchorFactory.create(source, start, end),
    )
}
