package net.inkyquill.pocketeditor.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StrictUtf8Test {
    @Test fun `decodes valid utf8 exactly`() {
        assertEquals("Привет", StrictUtf8.decode("Привет".encodeToByteArray(), "manifest"))
    }

    @Test fun `rejects malformed utf8 with document label`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            StrictUtf8.decode(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte()), "review")
        }
        assertEquals("review must be valid UTF-8", failure.message)
    }
}
