package net.inkyquill.pocketeditor.ui.review

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DraftTextFieldStateTest {
    @Test
    fun `selection and composition changes stay local without persistence writes`() {
        val current = TextFieldValue("quiet", selection = TextRange(5))
        val next = TextFieldValue(
            text = "quiet",
            selection = TextRange(1, 4),
            composition = TextRange(0, 5),
        )
        val writes = mutableListOf<String>()

        val result = applyDraftTextFieldChange(current, next, writes::add)

        assertEquals(next, result)
        assertEquals(TextRange(1, 4), result.selection)
        assertEquals(TextRange(0, 5), result.composition)
        assertEquals(emptyList<String>(), writes)
    }

    @Test
    fun `text changes publish the new string once and retain the ime state`() {
        val current = TextFieldValue("quiet", selection = TextRange(5))
        val next = TextFieldValue(
            text = "quiXet",
            selection = TextRange(4),
            composition = TextRange(0, 6),
        )
        val writes = mutableListOf<String>()

        val result = applyDraftTextFieldChange(current, next, writes::add)

        assertEquals(next, result)
        assertEquals(listOf("quiXet"), writes)
    }
}
