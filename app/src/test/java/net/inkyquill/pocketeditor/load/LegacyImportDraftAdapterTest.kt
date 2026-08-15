package net.inkyquill.pocketeditor.load

import kotlinx.coroutines.test.runTest
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.book.ImportDraftChapter
import net.inkyquill.pocketeditor.book.ImportDraftDocument
import net.inkyquill.pocketeditor.book.ImportDraftPhase
import net.inkyquill.pocketeditor.database.ImportDraftEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacyImportDraftAdapterTest {
    @Test
    fun `discard delegates legacy row and cache cleanup`() = runTest {
        val discarded = mutableListOf<String>()
        val adapter = LegacyImportDraftAdapter(
            rows = { emptyList() },
            matchingSource = { _, _, _, _ -> null },
            discard = { discarded += it },
        )

        adapter.discard("legacy-book")

        assertEquals(listOf("legacy-book"), discarded)
    }

    @Test
    fun `ready legacy draft becomes a complete seed without network`() = runTest {
        val adapter = LegacyImportDraftAdapter(
            rows = { listOf(entity(phase = ImportDraftPhase.READY)) },
            matchingSource = { _, path, _, _ -> if (path == "chapter-1.md") "# One".encodeToByteArray() else null },
        )

        val seed = adapter.seeds().single()

        assertEquals(listOf(CHAPTER_1_ID, CHAPTER_2_ID), seed.manifest.chapters.map(ChapterEntry::id))
        assertEquals(listOf("chapter-1.md", "chapter-2.md"), seed.manifest.chapters.map(ChapterEntry::path))
        assertEquals(setOf("chapter-1.md"), seed.cachedSources.keys)
        assertEquals(ProgressiveLoadFileState.CACHED, seed.files[0].state)
        assertEquals(ProgressiveLoadFileState.PENDING, seed.files[1].state)
        assertFalse(seed.readyWithoutNetwork)
    }

    @Test
    fun `legacy seed is ready without network when every source matches`() = runTest {
        val adapter = LegacyImportDraftAdapter(
            rows = { listOf(entity(phase = ImportDraftPhase.READY)) },
            matchingSource = { _, _, _, _ -> "# Cached".encodeToByteArray() },
        )

        assertTrue(adapter.seeds().single().readyWithoutNetwork)
    }

    @Test
    fun `incomplete legacy drafts do not produce seeds`() = runTest {
        val adapter = LegacyImportDraftAdapter(
            rows = {
                listOf(
                    entity(phase = ImportDraftPhase.DOWNLOADING),
                    entity(phase = ImportDraftPhase.FAILED),
                )
            },
            matchingSource = { _, _, _, _ -> "# Cached".encodeToByteArray() },
        )

        assertTrue(adapter.seeds().isEmpty())
    }

    @Test
    fun `empty ready legacy draft does not produce a seed`() = runTest {
        val adapter = LegacyImportDraftAdapter(
            rows = { listOf(entity(phase = ImportDraftPhase.READY, chapters = emptyList())) },
            matchingSource = { _, _, _, _ -> "# Cached".encodeToByteArray() },
        )

        assertTrue(adapter.seeds().isEmpty())
    }

    @Test
    fun `empty seed is not ready without network`() {
        val seed = LegacyProgressiveSeed(
            manifest = BookManifest(bookId = BOOK_ID, title = "Book"),
            remoteRootPath = "disk:/Book",
            files = emptyList(),
            cachedSources = emptyMap(),
        )

        assertFalse(seed.readyWithoutNetwork)
    }

    private fun entity(
        phase: ImportDraftPhase,
        chapters: List<ImportDraftChapter> = listOf(
            ImportDraftChapter(CHAPTER_1_ID, "chapter-1.md", "Old one", included = false, "r1", SHA_1, 7),
            ImportDraftChapter(CHAPTER_2_ID, "chapter-2.md", "Old two", included = true, "r2", SHA_2, 7),
        ),
    ) = ImportDraftEntity(
        bookId = BOOK_ID,
        remoteRootPath = "disk:/Book",
        localDirectory = "/cache/$BOOK_ID",
        documentJson = ImportDraftDocument.encode(
            ImportDraftDocument(
                bookId = BOOK_ID,
                remoteRootPath = "disk:/Book",
                title = "",
                phase = phase,
                chapters = chapters,
            ),
        ),
        updatedAt = 20,
    )

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_1_ID = "22222222-2222-2222-2222-222222222222"
        const val CHAPTER_2_ID = "33333333-3333-3333-3333-333333333333"
        const val SHA_1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
