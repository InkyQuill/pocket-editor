package net.inkyquill.pocketeditor.reader

import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadingPositionClampTest {
    @Test
    fun `clamp retains chapter and requested visible block while bounding its byte offset`() {
        val position = ReadingPositionEntity("book", "chapter", blockIndex = 0, byteOffset = 999, updatedAt = 42)

        val clamped = ReadingPositionClamp.clamp(position, MarkdownParser.parse("# Heading\n\nBody"))

        assertEquals(ReadingPositionEntity("book", "chapter", blockIndex = 0, byteOffset = 9, updatedAt = 42), clamped)
    }

    @Test
    fun `clamp falls back to last visible block when requested block no longer exists`() {
        val position = ReadingPositionEntity("book", "chapter", blockIndex = 90, byteOffset = 999, updatedAt = 42)

        val clamped = ReadingPositionClamp.clamp(position, MarkdownParser.parse("# Heading\n\nBody"))

        assertEquals(ReadingPositionEntity("book", "chapter", blockIndex = 1, byteOffset = 15, updatedAt = 42), clamped)
    }

    @Test
    fun `clamp resets an empty replacement to its origin`() {
        val position = ReadingPositionEntity("book", "chapter", blockIndex = 3, byteOffset = 99, updatedAt = 42)

        val clamped = ReadingPositionClamp.clamp(position, MarkdownParser.parse(""))

        assertEquals(ReadingPositionEntity("book", "chapter", blockIndex = 0, byteOffset = 0, updatedAt = 42), clamped)
    }

    @Test
    fun `clamp uses UTF-8 byte boundaries instead of character offsets`() {
        val position = ReadingPositionEntity("book", "chapter", blockIndex = 1, byteOffset = 5, updatedAt = 42)

        val clamped = ReadingPositionClamp.clamp(position, MarkdownParser.parse("😀\n\nёж"))

        assertEquals(1, clamped.blockIndex)
        assertEquals(6, clamped.byteOffset)
    }
}
