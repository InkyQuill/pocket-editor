package net.inkyquill.pocketeditor.anchor

import net.inkyquill.pocketeditor.review.Anchor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AnchorTest {
    @Test
    fun `sha256 is exact bytes and canonically equivalent unicode stays distinct`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "abc".encodeToByteArray().sha256())
        assertNotEquals("é".encodeToByteArray().sha256(), "e\u0301".encodeToByteArray().sha256())
    }
    @Test
    fun `emoji uses UTF-8 byte offsets`() {
        val source = "До 😀 после".encodeToByteArray()
        val start = "До ".encodeToByteArray().size
        val end = start + "😀".encodeToByteArray().size

        val anchor = AnchorFactory.create(source, start, end)

        assertEquals(start.toLong(), anchor.startByte)
        assertEquals(end.toLong(), anchor.endByte)
        assertEquals(Resolved(start, end), AnchorResolver.resolve(source, anchor, "😀"))
    }

    @Test
    fun `creation hashes exact bytes and records one-based line hints`() {
        val source = "first\nsecond\nthird".encodeToByteArray()
        val start = "first\n".encodeToByteArray().size
        val end = start + "second\n".encodeToByteArray().size

        val anchor = AnchorFactory.create(source, start, end)

        assertEquals(2, anchor.startLine)
        assertEquals(2, anchor.endLine)
        assertNotEquals(anchor.sourceSha256, anchor.selectionSha256)
        assertEquals("first\n", anchor.prefix)
        assertEquals("third", anchor.suffix)
    }

    @Test
    fun `creation limits each context to 128 Unicode code points`() {
        val prefix = "😀".repeat(129)
        val suffix = "🦊".repeat(129)
        val selected = "middle"
        val source = (prefix + selected + suffix).encodeToByteArray()
        val start = prefix.encodeToByteArray().size

        val anchor = AnchorFactory.create(source, start, start + selected.encodeToByteArray().size)

        assertEquals(128, anchor.prefix.codePointCount(0, anchor.prefix.length))
        assertEquals(128, anchor.suffix.codePointCount(0, anchor.suffix.length))
        assertEquals("😀".repeat(128), anchor.prefix)
        assertEquals("🦊".repeat(128), anchor.suffix)
    }

    @Test
    fun `creation rejects offsets that split UTF-8 code points`() {
        val source = "a😀b".encodeToByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            AnchorFactory.create(source, 2, 5)
        }
    }

    @Test
    fun `changed source relocates a unique exact selection`() {
        val original = "alpha target omega".encodeToByteArray()
        val anchor = AnchorFactory.create(original, 6, 12)
        val changed = "intro alpha target omega".encodeToByteArray()
        val expectedStart = "intro alpha ".encodeToByteArray().size

        assertEquals(
            Resolved(expectedStart, expectedStart + "target".encodeToByteArray().size),
            AnchorResolver.resolve(changed, anchor, "target"),
        )
    }

    @Test
    fun `multiple occurrences relocate only by full exact context`() {
        val original = "left target right".encodeToByteArray()
        val anchor = AnchorFactory.create(original, 5, 11)
        val changed = "other target place | left target right".encodeToByteArray()
        val expectedStart = "other target place | left ".encodeToByteArray().size

        assertEquals(
            Resolved(expectedStart, expectedStart + "target".encodeToByteArray().size),
            AnchorResolver.resolve(changed, anchor, "target"),
        )
    }

    @Test
    fun `missing exact selection is stale without fuzzy fallback`() {
        val original = "left target right".encodeToByteArray()
        val anchor = AnchorFactory.create(original, 5, 11)

        assertEquals(Stale, AnchorResolver.resolve("left Target right".encodeToByteArray(), anchor, "target"))
    }

    @Test
    fun `unresolved repeated selection reports all exact candidates`() {
        val original = "left target right".encodeToByteArray()
        val anchor = AnchorFactory.create(original, 5, 11)
        val changed = "target and target".encodeToByteArray()

        assertEquals(
            Ambiguous(listOf(Resolved(0, 6), Resolved(11, 17))),
            AnchorResolver.resolve(changed, anchor, "target"),
        )
    }

    @Test
    fun `matching source hash never relocates a corrupted saved range`() {
        val source = "left target right".encodeToByteArray()
        val valid = AnchorFactory.create(source, 5, 11)
        val corrupted = valid.copy(startByte = 0, endByte = 4)

        assertEquals(Stale, AnchorResolver.resolve(source, corrupted, "target"))
    }

    @Test
    fun `matching source hash rejects a saved range wider than selected bytes`() {
        val source = "left target right".encodeToByteArray()
        val valid = AnchorFactory.create(source, 5, 11)
        val corrupted = valid.copy(endByte = 12)

        assertEquals(Stale, AnchorResolver.resolve(source, corrupted, "target"))
    }

    @Test
    fun `selection hash must match supplied selected text`() {
        val original = "left target right".encodeToByteArray()
        val valid = AnchorFactory.create(original, 5, 11)
        val corrupted = valid.copy(selectionSha256 = "0".repeat(64))

        assertEquals(Stale, AnchorResolver.resolve(original, corrupted, "target"))
    }

    private fun Anchor.copy(
        startByte: Long = this.startByte,
        endByte: Long = this.endByte,
        selectionSha256: String = this.selectionSha256,
    ): Anchor = Anchor(
        sourceSha256 = sourceSha256,
        selectionSha256 = selectionSha256,
        startByte = startByte,
        endByte = endByte,
        startLine = startLine,
        endLine = endLine,
        prefix = prefix,
        suffix = suffix,
    )
}
