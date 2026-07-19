package net.inkyquill.pocketeditor.book

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookDiscoveryTest {
    private val discovery = BookDiscovery()

    @Test
    fun `proposes direct markdown children with title priority and deterministic natural order`() {
        val result = discovery.propose(
            listOf(
                DiscoveryFile("chapter10.md", "# Tenth heading\n".encodeToByteArray()),
                DiscoveryFile("chapter2.md", "---\nnumber: 7\ntitle: Front matter title\n---\n# Ignored heading\n".encodeToByteArray()),
                DiscoveryFile("chapter1.md", "plain text".encodeToByteArray()),
                DiscoveryFile("nested/chapter3.md", "# Nested".encodeToByteArray()),
                DiscoveryFile("UPPER.MD", "# Upper".encodeToByteArray()),
                DiscoveryFile(".hidden.md", "# Hidden".encodeToByteArray()),
                DiscoveryFile("notes.txt", "# Text".encodeToByteArray()),
            ),
        )

        assertEquals(listOf("chapter2.md", "chapter1.md", "chapter10.md"), result.proposals.map { it.path })
        assertEquals(listOf("Front matter title", "chapter1", "Tenth heading"), result.proposals.map { it.suggestedTitle })
        assertEquals(listOf(0, 1, 2), result.proposals.map { it.suggestedOrder })
        assertTrue(result.proposals.none { it.path == "UPPER.MD" })
    }

    @Test
    fun `listed and ignored files are not proposed and proposals never mutate manifest`() {
        val manifest = manifest(
            chapters = listOf(ChapterEntry(CHAPTER_ID, "kept.md", "Kept")),
            ignored = listOf("ignored.md"),
        )

        val result = discovery.propose(
            listOf(
                file("kept.md", "Kept"),
                file("ignored.md", "Ignored"),
                file("new.md", "New"),
            ),
            manifest,
        )

        assertEquals(listOf("new.md"), result.proposals.map { it.path })
        assertEquals(listOf("kept.md"), manifest.chapters.map { it.path })
    }

    @Test
    fun `confirmed add and ignore are explicit manifest mutations`() {
        val proposal = ChapterProposal("new.md", "New", 0)
        val initial = manifest()

        val added = discovery.add(initial, proposal, CHAPTER_ID, title = "Renamed", order = 0)
        val ignored = discovery.ignore(initial, "new.md")

        assertEquals(listOf(ChapterEntry(CHAPTER_ID, "new.md", "Renamed")), added.chapters)
        assertEquals(listOf("new.md"), ignored.ignoredFiles)
        assertTrue(initial.chapters.isEmpty())
    }

    @Test
    fun `missing chapter offers unique same-hash rename otherwise locate and remove`() {
        val oldBytes = "same content".encodeToByteArray()
        val manifest = manifest(chapters = listOf(ChapterEntry(CHAPTER_ID, "old.md", "Old")))

        val unique = discovery.propose(
            listOf(DiscoveryFile("renamed.md", oldBytes, oldBytes.sha256Hex())),
            manifest,
            cachedSourceHashes = mapOf("old.md" to oldBytes.sha256Hex()),
        )
        val ambiguous = discovery.propose(
            listOf(
                DiscoveryFile("a.md", oldBytes, oldBytes.sha256Hex()),
                DiscoveryFile("b.md", oldBytes, oldBytes.sha256Hex()),
            ),
            manifest,
            cachedSourceHashes = mapOf("old.md" to oldBytes.sha256Hex()),
        )

        assertEquals("renamed.md", unique.missing.single().sameHashRenamePath)
        assertEquals(null, ambiguous.missing.single().sameHashRenamePath)
        assertEquals("renamed.md", discovery.locate(manifest, CHAPTER_ID, "renamed.md").chapters.single().path)
        assertTrue(discovery.remove(manifest, CHAPTER_ID).chapters.isEmpty())
    }

    private fun file(path: String, title: String) = DiscoveryFile(path, "# $title\n".encodeToByteArray())

    private fun manifest(
        chapters: List<ChapterEntry> = emptyList(),
        ignored: List<String> = emptyList(),
    ) = BookManifest(bookId = BOOK_ID, title = "Book", chapters = chapters, ignoredFiles = ignored)

    private fun ByteArray.sha256Hex() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val BOOK_ID: String = UUID.randomUUID().toString()
        val CHAPTER_ID: String = UUID.randomUUID().toString()
    }
}
