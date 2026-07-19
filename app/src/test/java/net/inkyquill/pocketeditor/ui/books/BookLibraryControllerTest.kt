package net.inkyquill.pocketeditor.ui.books

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookLibraryControllerTest {
    @Test
    fun `folder selection always opens editable confirmation before import`() = runBlocking {
        val data = FakeBookLibraryData()
        val controller = controller(data)

        controller.openFolder("disk:/stories/alchemist")

        val confirmation = assertInstanceOf(BookDestination.ImportConfirmation::class.java, controller.state.value.destination)
        assertEquals("alchemist", confirmation.draft.title)
        assertEquals(listOf("Chapter 2", "Chapter 10"), confirmation.draft.chapters.map { it.title })
        assertTrue(data.imports.isEmpty())
    }

    @Test
    fun `book becomes usable only after full cache import completes`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val data = FakeBookLibraryData(importGate = gate)
        val controller = controller(data)
        controller.openFolder("disk:/stories/alchemist")
        val importing = async(start = CoroutineStart.UNDISPATCHED) { controller.confirmImport() }

        assertInstanceOf(BookDestination.Importing::class.java, controller.state.value.destination)
        assertTrue(controller.state.value.books.isEmpty())

        gate.complete(Unit)
        importing.await()

        val reader = assertInstanceOf(BookDestination.Reader::class.java, controller.state.value.destination)
        assertEquals(data.imported.bookId, reader.bookId)
        assertEquals(data.imported.chapters.first().id, reader.chapterId)
        assertEquals(listOf(data.imported), controller.state.value.books)
    }

    @Test
    fun `launch resumes last usable chapter and offline roots remain selectable`() = runBlocking {
        val data = FakeBookLibraryData(
            roots = listOf(BOOK, SECOND_BOOK),
            resume = ResumeLocation(SECOND_BOOK.bookId, SECOND_BOOK.chapters.last().id, 3, 81),
        )
        val controller = controller(data)

        controller.start()

        assertEquals(
            BookDestination.Reader(SECOND_BOOK.bookId, SECOND_BOOK.chapters.last().id, 3, 81),
            controller.state.value.destination,
        )
        assertEquals(listOf(SECOND_BOOK.bookId), data.opened)
        controller.switchBook(BOOK.bookId)
        assertEquals(BookDestination.Reader(BOOK.bookId, BOOK.chapters.first().id), controller.state.value.destination)
        assertEquals(listOf(SECOND_BOOK.bookId, BOOK.bookId), data.opened)
    }

    @Test
    fun `discovered chapter decision persists and forget requires confirmation`() = runBlocking {
        val data = FakeBookLibraryData(roots = listOf(BOOK))
        val controller = controller(data)
        controller.start()

        controller.ignoreDiscovered(BOOK.bookId, "bonus.md")
        assertEquals(listOf(BOOK.bookId to "bonus.md"), data.ignored)

        controller.requestForget(BOOK.bookId)
        assertEquals(BOOK.bookId, controller.state.value.forgetBookId)
        assertTrue(data.forgotten.isEmpty())
        controller.confirmForget()
        assertEquals(listOf(BOOK.bookId), data.forgotten)
        assertInstanceOf(BookDestination.Books::class.java, controller.state.value.destination)
        Unit
    }

    @Test
    fun `appearance is device local and text size is bounded and resettable`() = runBlocking {
        val data = FakeBookLibraryData(appearance = AppearancePreference(dark = false, textScale = 1f))
        val controller = controller(data)
        controller.start()

        controller.setDark(true)
        repeat(12) { controller.increaseTextSize() }
        assertEquals(AppearancePreference(true, 1.3f), controller.state.value.appearance)
        controller.resetTextSize()
        assertEquals(AppearancePreference(true, 1f), controller.state.value.appearance)
        repeat(12) { controller.decreaseTextSize() }
        assertEquals(.8f, controller.state.value.appearance.textScale)
        assertFalse(data.appearanceWrites.isEmpty())
    }

    @Test
    fun `existing manifest root installs its full cache and opens without first import confirmation`() = runBlocking {
        val data = FakeBookLibraryData(existingRoot = SECOND_BOOK)
        val controller = controller(data)

        controller.openFolder(SECOND_BOOK.remoteRootPath)

        assertEquals(listOf(SECOND_BOOK.remoteRootPath), data.existingInstalls)
        assertEquals(BookDestination.Reader(SECOND_BOOK.bookId, SECOND_BOOK.chapters.first().id), controller.state.value.destination)
        assertTrue(data.imports.isEmpty())
    }

    @Test
    fun `later discovery add ignore update locate and remove refresh quiet notices`() = runBlocking {
        val newFile = DiscoveryNotice.NewFile(BOOK.bookId, "bonus.md", "Bonus", 2)
        val renamed = DiscoveryNotice.MissingFile(BOOK.bookId, "chapter-a", "Salt Road", "old.md", "renamed.md")
        val missing = DiscoveryNotice.MissingFile(BOOK.bookId, "chapter-b", "Copper Gate", "gone.md", null)
        val data = FakeBookLibraryData(roots = listOf(BOOK), notices = mutableListOf(newFile, renamed, missing))
        val controller = controller(data)
        controller.start()

        assertEquals(listOf(newFile, renamed, missing), controller.state.value.discoveryNotices)
        controller.addDiscovered(BOOK.bookId, "bonus.md", "Afterword", 1)
        controller.ignoreDiscovered(BOOK.bookId, "appendix.md")
        controller.updateRenamed(BOOK.bookId, "chapter-a", "renamed.md")
        controller.locateMissing(BOOK.bookId, "chapter-b", "found.md")
        controller.removeMissing(BOOK.bookId, "chapter-b")

        assertEquals(listOf(Triple(BOOK.bookId, "bonus.md", "Afterword")), data.added)
        assertEquals(listOf(BOOK.bookId to "appendix.md"), data.ignored)
        assertEquals(listOf(Triple(BOOK.bookId, "chapter-a", "renamed.md")), data.updated)
        assertEquals(listOf(Triple(BOOK.bookId, "chapter-b", "found.md")), data.located)
        assertEquals(listOf(BOOK.bookId to "chapter-b"), data.removed)
    }

    private fun controller(data: BookLibraryData) = BookLibraryController(
        data = data,
        scope = CoroutineScope(Dispatchers.Unconfined),
        dispatcher = Dispatchers.Unconfined,
    )

    private class FakeBookLibraryData(
        var roots: List<BookSummary> = emptyList(),
        private val resume: ResumeLocation? = null,
        private var appearance: AppearancePreference = AppearancePreference(),
        private val importGate: CompletableDeferred<Unit>? = null,
        private val existingRoot: BookSummary? = null,
        val notices: MutableList<DiscoveryNotice> = mutableListOf(),
    ) : BookLibraryData {
        val imports = mutableListOf<ImportDraft>()
        val ignored = mutableListOf<Pair<String, String>>()
        val forgotten = mutableListOf<String>()
        val appearanceWrites = mutableListOf<AppearancePreference>()
        val opened = mutableListOf<String>()
        val existingInstalls = mutableListOf<String>()
        val added = mutableListOf<Triple<String, String, String>>()
        val updated = mutableListOf<Triple<String, String, String>>()
        val located = mutableListOf<Triple<String, String, String>>()
        val removed = mutableListOf<Pair<String, String>>()
        val imported = BOOK

        override suspend fun books() = roots
        override suspend fun resumeLocation() = resume
        override suspend fun appearance() = appearance
        override suspend fun browse(path: String) = FolderListing(
            path,
            folders = listOf(RemoteFolder("disk:/stories/alchemist/archive", "archive")),
            markdown = listOf("chapter-10.md", "chapter-2.md"),
        )
        override suspend fun propose(path: String) = ImportDraft(
            remoteRootPath = path,
            title = "alchemist",
            chapters = listOf(
                ImportChapterDraft("chapter-2.md", "Chapter 2", true),
                ImportChapterDraft("chapter-10.md", "Chapter 10", true),
            ),
        )
        override suspend fun existingRoot(path: String) = existingRoot
        override suspend fun installExisting(path: String): BookSummary {
            existingInstalls += path
            return requireNotNull(existingRoot).also { roots = roots + it }
        }
        override suspend fun import(draft: ImportDraft): BookSummary {
            imports += draft
            importGate?.await()
            roots = roots + imported
            return imported
        }
        override suspend fun persistResume(location: ResumeLocation) = Unit
        override suspend fun opened(bookId: String) { opened += bookId }
        override suspend fun discover(bookId: String) = notices.toList()
        override suspend fun add(bookId: String, path: String, title: String, position: Int) {
            added += Triple(bookId, path, title)
            notices.removeAll { it is DiscoveryNotice.NewFile && it.path == path }
        }
        override suspend fun updatePath(bookId: String, chapterId: String, path: String, requireSameHash: Boolean) {
            (if (requireSameHash) updated else located) += Triple(bookId, chapterId, path)
        }
        override suspend fun removeChapter(bookId: String, chapterId: String) { removed += bookId to chapterId }
        override suspend fun ignore(bookId: String, path: String) { ignored += bookId to path }
        override suspend fun forget(bookId: String) {
            forgotten += bookId
            roots = roots.filterNot { it.bookId == bookId }
        }
        override suspend fun saveAppearance(value: AppearancePreference) {
            appearance = value
            appearanceWrites += value
        }
    }

    private companion object {
        val BOOK = BookSummary(
            UUID.fromString("00000000-0000-0000-0000-000000000101").toString(),
            "Alchemist",
            "disk:/stories/alchemist",
            listOf(BookChapter("chapter-a", "Arrival"), BookChapter("chapter-b", "Refusal")),
        )
        val SECOND_BOOK = BookSummary(
            UUID.fromString("00000000-0000-0000-0000-000000000102").toString(),
            "Other story",
            "disk:/stories/other",
            listOf(BookChapter("chapter-c", "First"), BookChapter("chapter-d", "Second")),
        )
    }
}
