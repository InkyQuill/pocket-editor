package net.inkyquill.pocketeditor.ui.books

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.load.ProgressiveLoadSnapshot
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BookLibraryControllerTest {
    @Test
    fun `folder selection stays usable and first three publication opens Reader`() = runBlocking {
        val data = FakeBookLibraryData()
        val controller = controller(data)
        controller.openFolderBrowser("disk:/Aria")

        controller.openFolder("disk:/Aria")

        assertEquals(listOf("disk:/Aria"), data.startedPaths)
        assertInstanceOf(BookDestination.FolderBrowser::class.java, controller.state.value.destination)
        data.roots = listOf(partialBook(cached = 3, total = 52))
        data.loads.value = listOf(loadSnapshot(cached = 3, total = 52))

        val reader = assertInstanceOf(BookDestination.Reader::class.java, controller.state.value.destination)
        assertEquals("chapter-0", reader.chapterId)
    }

    @Test
    fun `uncached selection persists one priority before Reader navigation`() = runBlocking {
        val data = FakeBookLibraryData(roots = listOf(partialBook(cached = 3, total = 6)))
        val controller = controller(data)

        controller.openChapter("progressive-book", "chapter-5")
        controller.openChapter("progressive-book", "chapter-5")

        assertEquals(listOf("progressive-book" to "chapter-5.md"), data.prioritizedPaths)
        assertEquals("chapter-5", (controller.state.value.destination as BookDestination.Reader).chapterId)
    }

    @Test
    fun `load controls forward without replacing the current destination`() = runBlocking {
        val data = FakeBookLibraryData(roots = listOf(partialBook(cached = 3, total = 6)))
        val controller = controller(data)
        controller.openBooks()

        controller.pauseLoad("progressive-book")
        controller.continueLoad("progressive-book")
        controller.cancelLoad("progressive-book")

        assertEquals(listOf("progressive-book"), data.pausedLoads)
        assertEquals(listOf("progressive-book"), data.continuedLoads)
        assertEquals(listOf("progressive-book"), data.cancelledLoads)
        assertTrue(controller.state.value.destination is BookDestination.Books)
    }

    @Test
    fun `reorder refreshes books while preserving current reader destination`() = runBlocking {
        val data = FakeBookLibraryData(roots = listOf(partialBook(cached = 3, total = 3)))
        val controller = controller(data)
        controller.start()
        controller.openChapter("progressive-book", "chapter-1", blockIndex = 4, byteOffset = 12)

        controller.reorder("progressive-book", listOf("chapter-2", "chapter-0", "chapter-1"))

        assertEquals(listOf("chapter-2", "chapter-0", "chapter-1"), data.reordered.single().second)
        assertEquals(
            BookDestination.Reader("progressive-book", "chapter-1", blockIndex = 4, byteOffset = 12),
            controller.state.value.destination,
        )
    }

    @Test
    fun `late reorder completion does not restore stale reader navigation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val data = FakeBookLibraryData(
            roots = listOf(partialBook(cached = 3, total = 3)),
            reorderEntered = entered,
            reorderRelease = release,
        )
        val controller = controller(data)
        controller.start()
        val reordering = async(start = CoroutineStart.UNDISPATCHED) {
            controller.reorder("progressive-book", listOf("chapter-2", "chapter-0", "chapter-1"))
        }
        entered.await()

        controller.openBooks()
        release.complete(Unit)
        reordering.await()

        assertTrue(controller.state.value.destination is BookDestination.Books)
    }

    @Test
    fun `older ready job cannot auto open over the root selected by the current action`() = runBlocking {
        val selectedPending = loadSnapshot(0, 6, bookId = "selected", root = "disk:/Selected")
        val data = FakeBookLibraryData(
            roots = listOf(
                partialBook(3, 6, "older", "disk:/Older"),
                partialBook(0, 6, "selected", "disk:/Selected"),
            ),
            startSnapshot = selectedPending,
        )
        val controller = controller(data)
        controller.openFolderBrowser("disk:/Selected")
        controller.openFolder("disk:/Selected")

        data.loads.value = listOf(loadSnapshot(3, 6, "older", "disk:/Older"), selectedPending)
        assertInstanceOf(BookDestination.FolderBrowser::class.java, controller.state.value.destination)

        data.roots = listOf(
            partialBook(3, 6, "older", "disk:/Older"),
            partialBook(3, 6, "selected", "disk:/Selected"),
        )
        data.loads.value = listOf(
            loadSnapshot(3, 6, "older", "disk:/Older"),
            loadSnapshot(3, 6, "selected", "disk:/Selected"),
        )

        val reader = assertInstanceOf(BookDestination.Reader::class.java, controller.state.value.destination)
        assertEquals("selected", reader.bookId)
    }

    @Test
    fun `late startLoad completion cannot overwrite newer Books navigation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val data = FakeBookLibraryData(startEntered = entered, startRelease = release)
        val controller = controller(data)
        controller.openFolderBrowser("disk:/Aria")

        val opening = async(start = CoroutineStart.UNDISPATCHED) { controller.openFolder("disk:/Aria") }
        entered.await()
        controller.openBooks()
        release.complete(Unit)
        opening.await()

        assertTrue(controller.state.value.destination is BookDestination.Books)
    }

    @Test
    fun `collector auto open never invokes immediate durable resume before later user confirmation`() = runBlocking {
        val data = FakeBookLibraryData(
            roots = listOf(partialBook(0, 6, "selected", "disk:/Selected")),
            startSnapshot = loadSnapshot(0, 6, "selected", "disk:/Selected"),
            persistWritesImmediately = true,
        )
        val controller = controller(data)
        controller.openFolderBrowser("disk:/Selected")
        controller.openFolder("disk:/Selected")
        data.roots = listOf(partialBook(3, 6, "selected", "disk:/Selected"))

        data.loads.value = listOf(loadSnapshot(3, 6, "selected", "disk:/Selected"))
        assertInstanceOf(BookDestination.Reader::class.java, controller.state.value.destination)
        controller.openBooks()

        assertTrue(data.persisted.none { it.bookId == "selected" })
        assertEquals(null, data.durableResume)
        assertTrue(controller.state.value.destination is BookDestination.Books)
    }

    @Test
    fun `direct ready auto open never invokes immediate durable resume before later user confirmation`() = runBlocking {
        val data = FakeBookLibraryData(
            roots = listOf(partialBook(3, 6, "selected", "disk:/Selected")),
            startSnapshot = loadSnapshot(3, 6, "selected", "disk:/Selected"),
            persistWritesImmediately = true,
        )
        val controller = controller(data)
        controller.openFolderBrowser("disk:/Selected")

        controller.openFolder("disk:/Selected")
        assertInstanceOf(BookDestination.Reader::class.java, controller.state.value.destination)
        controller.openBooks()

        assertTrue(data.persisted.none { it.bookId == "selected" })
        assertEquals(null, data.durableResume)
        assertTrue(controller.state.value.destination is BookDestination.Books)
    }

    @Test
    fun `startup reads authoritative ready load before choosing destination`() = runBlocking {
        val ready = loadSnapshot(3, 6)
        val data = FakeBookLibraryData(roots = listOf(partialBook(3, 6))).apply {
            loads.value = listOf(ready)
        }

        val controller = controller(data)
        controller.start()

        val reader = assertInstanceOf(BookDestination.Reader::class.java, controller.state.value.destination)
        assertEquals("progressive-book", reader.bookId)
    }

    @Test
    fun `startup restores pending root and opens only after its readiness transition`() = runBlocking {
        val selectedPending = loadSnapshot(2, 6, "selected", "disk:/Selected")
        val data = FakeBookLibraryData(
            roots = listOf(partialBook(2, 6, "selected", "disk:/Selected")),
        ).apply { loads.value = listOf(selectedPending) }
        val controller = controller(data)

        controller.start()
        controller.openBooks()
        // Simulate a recreated controller while the selected job is still below the readiness threshold.
        val recreated = controller(data)
        recreated.start()
        assertEquals("disk:/Selected", recreated.state.value.pendingLoadRoot)

        data.roots = listOf(partialBook(3, 6, "selected", "disk:/Selected"))
        data.loads.value = listOf(loadSnapshot(3, 6, "selected", "disk:/Selected"))

        val reader = assertInstanceOf(BookDestination.Reader::class.java, recreated.state.value.destination)
        assertEquals("selected", reader.bookId)
    }
    @Test
    fun `import failures use safe actionable messages without remote details`() {
        assertEquals(
            "Нет подключения к Яндекс Диску. Загруженные главы сохранены.",
            YandexDiskError.Offline(IOException("secret path")).toImportUserMessage(),
        )
        assertEquals(
            "Яндекс Диск временно ограничил запросы. Повторите позже.",
            YandexDiskError.RateLimited(60).toImportUserMessage(),
        )
        assertEquals(
            "Не удалось продолжить импорт. Загруженные главы сохранены.",
            IllegalStateException("secret path").toImportUserMessage(),
        )
    }

    @Test
    fun `retrying registered broken book uses repair protocol instead of first install`() = runBlocking {
        val broken = BOOK.copy(availableOffline = false, recoveryError = "damaged")
        val data = FakeBookLibraryData(roots = listOf(broken), existingRoot = BOOK)
        val controller = controller(data)

        controller.retryBook(BOOK.bookId)

        assertEquals(listOf(BOOK.bookId), data.repairs)
        assertTrue(data.existingInstalls.isEmpty())
    }

    @Test
    fun `folder browser failure stops loading and exposes retry error`() = runBlocking {
        val controller = controller(FakeBookLibraryData(browseFailure = IllegalStateException("Disk request failed")))

        controller.openFolderBrowser()

        val browser = assertInstanceOf(BookDestination.FolderBrowser::class.java, controller.state.value.destination)
        assertFalse(browser.loading)
        assertEquals("Не удалось выполнить действие. Попробуйте ещё раз.", controller.state.value.error)
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
        assertEquals(BookDestination.Reader(BOOK.bookId, BOOK.chapters.last().id, 5, 144), controller.state.value.destination)
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
    fun `exact search navigation retains complete raw range`() = runBlocking {
        val data = FakeBookLibraryData(roots = listOf(BOOK))
        val controller = controller(data)
        controller.start()

        controller.openChapter(BOOK.bookId, BOOK.chapters.last().id, blockIndex = 7, byteOffset = 4096, rawEndByte = 4128)

        assertEquals(
            BookDestination.Reader(BOOK.bookId, BOOK.chapters.last().id, 7, 4096, 4128),
            controller.state.value.destination,
        )
    }

    @Test
    fun `older delayed chapter navigation cannot replace the latest selection`() = runBlocking {
        val delayed = CompletableDeferred<Unit>()
        val data = FakeBookLibraryData(
            roots = listOf(BOOK),
            persistGate = BOOK.chapters.last().id to delayed,
        )
        val controller = controller(data)
        controller.start()
        val openedBeforeNavigation = data.opened.size

        val olderNavigation = async(start = CoroutineStart.UNDISPATCHED) {
            controller.openChapter(BOOK.bookId, BOOK.chapters.last().id)
        }
        val latestNavigation = async(start = CoroutineStart.UNDISPATCHED) {
            controller.openChapter(BOOK.bookId, BOOK.chapters.first().id)
        }
        delayed.complete(Unit)
        latestNavigation.await()
        olderNavigation.await()

        assertEquals(
            BookDestination.Reader(BOOK.bookId, BOOK.chapters.first().id),
            controller.state.value.destination,
        )
        assertEquals(openedBeforeNavigation + 2, data.opened.size)
    }

    @Test
    fun `later discovery add ignore update locate and remove refresh quiet notices`() = runBlocking {
        val newFile = DiscoveryNotice.NewFile(BOOK.bookId, "bonus.md", "Bonus", 2)
        val renamed = DiscoveryNotice.MissingFile(BOOK.bookId, "chapter-a", "Salt Road", "old.md", "renamed.md")
        val missing = DiscoveryNotice.MissingFile(BOOK.bookId, "chapter-b", "Copper Gate", "gone.md", null)
        val data = FakeBookLibraryData(roots = listOf(BOOK), notices = mutableListOf(newFile, renamed, missing))
        val controller = controller(data)
        controller.start()

        assertTrue(controller.state.value.discoveryNotices.isEmpty())
        data.publishBookChange(BOOK.bookId)
        assertEquals(listOf(newFile, renamed, missing), controller.state.value.discoveryNotices)
        controller.addDiscovered(BOOK.bookId, "bonus.md", 1)
        controller.replaceDiscovered(BOOK.bookId, "chapter-a", "replacement.md")
        controller.ignoreDiscovered(BOOK.bookId, "appendix.md")
        controller.updateRenamed(BOOK.bookId, "chapter-a", "renamed.md")
        controller.locateMissing(BOOK.bookId, "chapter-b", "found.md")
        controller.removeMissing(BOOK.bookId, "chapter-b")

        assertEquals(listOf(Triple(BOOK.bookId, "bonus.md", 1)), data.added)
        assertEquals(listOf(Triple(BOOK.bookId, "chapter-a", "replacement.md")), data.replaced)
        assertEquals(listOf(BOOK.bookId to "appendix.md"), data.ignored)
        assertEquals(listOf(Triple(BOOK.bookId, "chapter-a", "renamed.md")), data.updated)
        assertEquals(listOf(Triple(BOOK.bookId, "chapter-b", "found.md")), data.located)
        assertEquals(listOf(BOOK.bookId to "chapter-b"), data.removed)
    }

    @Test
    fun `published cache change refreshes the active book summary before discovery`() = runBlocking {
        val publishedNotice = DiscoveryNotice.NewFile(BOOK.bookId, "remote.md", "Remote", 2)
        val data = FakeBookLibraryData(roots = listOf(BOOK), notices = mutableListOf(publishedNotice))
        val controller = controller(data)
        controller.start()
        val refreshed = BOOK.copy(title = "Remote title")
        data.roots = listOf(refreshed)

        data.publishBookChange(BOOK.bookId)

        assertEquals(listOf(refreshed), controller.state.value.books)
        assertEquals(listOf(publishedNotice), controller.state.value.discoveryNotices)
        assertEquals(listOf("books", "discover:${BOOK.bookId}"), data.refreshEvents.takeLast(2))
    }

    @Test
    fun `published remote spine replacement moves an open removed chapter to a persisted zero-offset fallback`() = runBlocking {
        val replacement = BOOK.copy(
            chapters = (1..28).map { index ->
                BookChapter("replacement-$index", "replacement-$index.md", "Replacement $index", cached = true)
            },
        )
        val data = FakeBookLibraryData(roots = listOf(BOOK))
        val controller = controller(data)
        controller.start()
        data.roots = listOf(replacement)

        data.publishBookChange(BOOK.bookId)

        assertEquals(
            BookDestination.Reader(BOOK.bookId, "replacement-1", blockIndex = 0, byteOffset = 0, rawEndByte = null),
            controller.state.value.destination,
        )
        assertEquals(ResumeLocation(BOOK.bookId, "replacement-1", 0, 0), data.persisted.last())
        assertEquals(listOf(replacement), controller.state.value.books)
    }

    @Test
    fun `book A reactive discovery cannot publish after navigation to book B`() = runBlocking {
        val discoverEntered = CompletableDeferred<Unit>()
        val releaseDiscover = CompletableDeferred<Unit>()
        val noticeA = DiscoveryNotice.NewFile(BOOK.bookId, "remote.md", "Remote", 2)
        val data = FakeBookLibraryData(
            roots = listOf(BOOK, SECOND_BOOK),
            notices = mutableListOf(noticeA),
            discoverGate = BOOK.bookId to releaseDiscover,
            discoverEntered = discoverEntered,
        )
        val controller = controller(data)
        controller.start()
        val publishing = launch { data.publishBookChange(BOOK.bookId) }
        discoverEntered.await()

        controller.switchBook(SECOND_BOOK.bookId)
        releaseDiscover.complete(Unit)
        publishing.join()

        assertEquals(BookDestination.Reader(SECOND_BOOK.bookId, SECOND_BOOK.chapters.first().id), controller.state.value.destination)
        assertTrue(controller.state.value.discoveryNotices.isEmpty())
    }

    @Test
    fun `switching books cannot be durably overwritten by an older fallback persistence`() = runBlocking {
        val persistEntered = CompletableDeferred<Unit>()
        val releaseFallbackPersist = CompletableDeferred<Unit>()
        val fallbackChapterId = "replacement-1"
        val replacement = BOOK.copy(
            chapters = listOf(BookChapter(fallbackChapterId, "replacement.md", "Replacement", cached = true)),
        )
        val data = FakeBookLibraryData(
            roots = listOf(BOOK, SECOND_BOOK),
            persistCompletionGate = fallbackChapterId to releaseFallbackPersist,
            persistCompletionEntered = persistEntered,
        )
        val controller = controller(data)
        controller.start()
        data.persisted.clear()
        data.roots = listOf(replacement, SECOND_BOOK)
        val publishing = async(start = CoroutineStart.UNDISPATCHED) { data.publishBookChange(BOOK.bookId) }
        persistEntered.await()

        val switching = async(start = CoroutineStart.UNDISPATCHED) { controller.switchBook(SECOND_BOOK.bookId) }
        releaseFallbackPersist.complete(Unit)
        switching.await()
        publishing.await()

        assertEquals(SECOND_BOOK.bookId, data.persisted.last().bookId)
        assertEquals(BookDestination.Reader(SECOND_BOOK.bookId, SECOND_BOOK.chapters.first().id), controller.state.value.destination)
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
        private val importFailure: Throwable? = null,
        var proposeFailure: Throwable? = null,
        private val existingFailure: Throwable? = null,
        private val browseFailure: Throwable? = null,
        private val persistGate: Pair<String, CompletableDeferred<Unit>>? = null,
        private val persistCompletionGate: Pair<String, CompletableDeferred<Unit>>? = null,
        private val persistCompletionEntered: CompletableDeferred<Unit>? = null,
        private val persistWritesImmediately: Boolean = false,
        val notices: MutableList<DiscoveryNotice> = mutableListOf(),
        private val discoverGate: Pair<String, CompletableDeferred<Unit>>? = null,
        private val discoverEntered: CompletableDeferred<Unit>? = null,
        var startSnapshot: ProgressiveLoadSnapshot = loadSnapshot(0, 52),
        private val startEntered: CompletableDeferred<Unit>? = null,
        private val startRelease: CompletableDeferred<Unit>? = null,
        private val reorderEntered: CompletableDeferred<Unit>? = null,
        private val reorderRelease: CompletableDeferred<Unit>? = null,
    ) : BookLibraryData {
        private val changes = MutableSharedFlow<String>()
        val loads = MutableStateFlow<List<ProgressiveLoadSnapshot>>(emptyList())
        val startedPaths = mutableListOf<String>()
        val prioritizedPaths = mutableListOf<Pair<String, String>>()
        val pausedLoads = mutableListOf<String>()
        val continuedLoads = mutableListOf<String>()
        val cancelledLoads = mutableListOf<String>()
        val reordered = mutableListOf<Pair<String, List<String>>>()
        val imports = mutableListOf<ImportDraft>()
        val proposedPaths = mutableListOf<String>()
        val ignored = mutableListOf<Pair<String, String>>()
        val forgotten = mutableListOf<String>()
        val appearanceWrites = mutableListOf<AppearancePreference>()
        val opened = mutableListOf<String>()
        val existingInstalls = mutableListOf<String>()
        val repairs = mutableListOf<String>()
        val relinks = mutableListOf<Pair<String, String>>()
        val added = mutableListOf<Triple<String, String, Int>>()
        val replaced = mutableListOf<Triple<String, String, String>>()
        val updated = mutableListOf<Triple<String, String, String>>()
        val located = mutableListOf<Triple<String, String, String>>()
        val removed = mutableListOf<Pair<String, String>>()
        val imported = BOOK
        var savedImportDraft: ImportDraft? = null
        val draftSummaries = mutableListOf<ImportDraftSummary>()
        val discardedImports = mutableListOf<String>()
        val refreshEvents = mutableListOf<String>()
        val persisted = mutableListOf<ResumeLocation>()
        var durableResume: ResumeLocation? = null

        override suspend fun books() = roots.also { refreshEvents += "books" }
        override fun bookChanges(): Flow<String> = changes
        override fun loadChanges(): Flow<List<ProgressiveLoadSnapshot>> = loads
        override suspend fun currentLoads(): List<ProgressiveLoadSnapshot> = loads.value
        override suspend fun startLoad(path: String): ProgressiveLoadSnapshot {
            startedPaths += path
            startEntered?.complete(Unit)
            startRelease?.await()
            return startSnapshot
        }
        override suspend fun prioritizeChapter(bookId: String, path: String) {
            if (bookId to path !in prioritizedPaths) prioritizedPaths += bookId to path
        }
        override suspend fun pauseLoad(bookId: String) { pausedLoads += bookId }
        override suspend fun continueLoad(bookId: String) { continuedLoads += bookId }
        override suspend fun cancelLoad(bookId: String) { cancelledLoads += bookId }
        override suspend fun reorder(bookId: String, orderedChapterIds: List<String>) {
            reorderEntered?.complete(Unit)
            reorderRelease?.await()
            reordered += bookId to orderedChapterIds
            roots = roots.map { book ->
                if (book.bookId != bookId) book else {
                    val byId = book.chapters.associateBy(BookChapter::id)
                    book.copy(chapters = orderedChapterIds.map(byId::getValue))
                }
            }
        }
        override suspend fun importDrafts() = draftSummaries.toList()
        override suspend fun resumeImport(bookId: String): ImportDraft =
            requireNotNull(savedImportDraft).also { require(it.bookId == bookId) }

        override suspend fun updateImport(draft: ImportDraft) {
            savedImportDraft = draft
            draftSummaries.removeAll { it.bookId == draft.bookId }
            draftSummaries += ImportDraftSummary(
                draft.bookId,
                draft.remoteRootPath,
                draft.title,
                draft.chapters.size,
                draft.phase,
            )
        }

        override suspend fun discardImport(bookId: String) {
            discardedImports += bookId
            draftSummaries.removeAll { it.bookId == bookId }
            if (savedImportDraft?.bookId == bookId) savedImportDraft = null
        }
        override suspend fun resumeLocation() = resume
        override suspend fun resumeLocation(bookId: String) = if (bookId == BOOK.bookId) {
            ResumeLocation(BOOK.bookId, BOOK.chapters.last().id, 5, 144)
        } else null
        override suspend fun appearance() = appearance
        override suspend fun browse(path: String): FolderListing {
            browseFailure?.let { throw it }
            return FolderListing(
                path,
                folders = listOf(RemoteFolder("disk:/stories/alchemist/archive", "archive")),
                markdown = listOf("chapter-10.md", "chapter-2.md"),
            )
        }
        override suspend fun propose(path: String): ImportDraft {
            proposedPaths += path
            proposeFailure?.let { throw it }
            return ImportDraft(
                remoteRootPath = path,
                title = "alchemist",
                chapters = listOf(
                    ImportChapterDraft("chapter-2.md", "Chapter 2", true),
                    ImportChapterDraft("chapter-10.md", "Chapter 10", true),
                ),
            ).also { updateImport(it) }
        }
        override suspend fun existingRoot(path: String) = existingRoot
        override suspend fun installExisting(path: String): BookSummary {
            existingInstalls += path
            existingFailure?.let { throw it }
            return requireNotNull(existingRoot).also { roots = roots + it }
        }

        override suspend fun repairRegistered(bookId: String): BookSummary {
            repairs += bookId
            return requireNotNull(existingRoot).also { roots = listOf(it) }
        }
        override suspend fun relinkRegistered(bookId: String, path: String): BookSummary {
            relinks += bookId to path
            return requireNotNull(existingRoot).also { roots = listOf(it) }
        }
        override suspend fun import(draft: ImportDraft): BookSummary {
            imports += draft
            importFailure?.let { throw it }
            importGate?.await()
            roots = roots + imported
            draftSummaries.removeAll { it.bookId == draft.bookId }
            return imported
        }
        override suspend fun persistResume(location: ResumeLocation) {
            if (persistWritesImmediately) durableResume = location
            if (persistCompletionGate?.first == location.chapterId) {
                persistCompletionEntered?.complete(Unit)
                persistCompletionGate.second.await()
            }
            persisted += location
            if (persistGate?.first == location.chapterId) persistGate.second.await()
        }
        override suspend fun opened(bookId: String) { opened += bookId }
        override suspend fun discover(bookId: String): List<DiscoveryNotice> {
            refreshEvents += "discover:$bookId"
            if (discoverGate?.first == bookId) {
                discoverEntered?.complete(Unit)
                discoverGate.second.await()
            }
            return notices.toList()
        }

        suspend fun publishBookChange(bookId: String) {
            changes.emit(bookId)
        }
        override suspend fun add(bookId: String, path: String, position: Int) {
            added += Triple(bookId, path, position)
            notices.removeAll { it is DiscoveryNotice.NewFile && it.path == path }
        }
        override suspend fun replace(bookId: String, chapterId: String, path: String) {
            replaced += Triple(bookId, chapterId, path)
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
        fun partialBook(
            cached: Int,
            total: Int,
            bookId: String = "progressive-book",
            root: String = "disk:/Aria",
        ) = BookSummary(
            bookId,
            root.substringAfterLast('/'),
            root,
            List(total) { index -> BookChapter("chapter-$index", "chapter-$index.md", "chapter-$index", index < cached) },
        )

        fun loadSnapshot(
            cached: Int,
            total: Int,
            bookId: String = "progressive-book",
            root: String = "disk:/Aria",
        ) = ProgressiveLoadSnapshot(
            bookId = bookId,
            remoteRootPath = root,
            phase = if (cached >= minOf(3, total)) ProgressiveLoadPhase.BACKGROUND else ProgressiveLoadPhase.INITIAL,
            totalFiles = total,
            completedFiles = cached,
            activePath = null,
            retryAttempt = 0,
            retryAt = null,
            generation = 1,
            paused = false,
            cancelled = false,
            lastErrorCategory = null,
            files = List(total) { index ->
                ProgressiveLoadFileEntity(
                    bookId = bookId,
                    path = "chapter-$index.md",
                    chapterId = "chapter-$index",
                    spineIndex = index,
                    expectedRevision = "r$index",
                    expectedSize = null,
                    sha256 = null,
                    state = if (index < cached) ProgressiveLoadFileState.CACHED else ProgressiveLoadFileState.PENDING,
                    priority = 0,
                )
            },
        )

        val BOOK = BookSummary(
            UUID.fromString("00000000-0000-0000-0000-000000000101").toString(),
            "Alchemist",
            "disk:/stories/alchemist",
            listOf(
                BookChapter("chapter-a", "chapter-a.md", "Arrival", cached = true),
                BookChapter("chapter-b", "chapter-b.md", "Refusal", cached = true),
            ),
        )
        val SECOND_BOOK = BookSummary(
            UUID.fromString("00000000-0000-0000-0000-000000000102").toString(),
            "Other story",
            "disk:/stories/other",
            listOf(
                BookChapter("chapter-c", "chapter-c.md", "First", cached = true),
                BookChapter("chapter-d", "chapter-d.md", "Second", cached = true),
            ),
        )
    }
}
