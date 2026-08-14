package net.inkyquill.pocketeditor.reader

import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.PendingDeletionEntity
import net.inkyquill.pocketeditor.database.DraftEntity
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.anchor.AnchorFactory
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.storage.LocalRevision
import net.inkyquill.pocketeditor.sync.SyncMetadataStore
import net.inkyquill.pocketeditor.sync.PendingDeletionStore
import net.inkyquill.pocketeditor.sync.SyncStatus
import net.inkyquill.pocketeditor.sync.SyncTrigger
import net.inkyquill.pocketeditor.sync.ConflictChoice
import net.inkyquill.pocketeditor.ui.review.EditorialReviewActions
import net.inkyquill.pocketeditor.ui.review.EditorialReviewController
import net.inkyquill.pocketeditor.ui.review.ReviewDraftPersistence
import net.inkyquill.pocketeditor.ui.review.ReviewDraftStore
import net.inkyquill.pocketeditor.ui.review.readerCallbacks
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderRepositoryTest {
    @Test
    fun `open reader derives title from synchronized source`() = runBlocking {
        val fixture = fixture()
        fixture.store.source = "---\ntitle: Frontmatter\n---\n# Heading\nBody".encodeToByteArray()

        assertEquals(
            "Frontmatter",
            fixture.repository.observeChapter(BOOK_ID, CHAPTER_ID, false).first().title,
        )
    }

    @Test
    fun `review off exposes canonical source with no review-derived state`() = runBlocking {
        val fixture = fixture()

        val state = fixture.repository.observeChapter(BOOK_ID, CHAPTER_ID, reviewEnabled = false).first()

        assertEquals("Канонический текст.", state.document.blocks.single().canonicalText)
        assertEquals(0, state.document.reviewObjectCount)
        assertNull(state.chapterNote)
        assertEquals(emptyList<UnresolvedReview>(), state.document.unresolved)
        assertFalse(ReaderState::class.java.declaredFields.any { it.type == ReviewDocument::class.java })
        assertFalse(
            ReaderState::class.java.declaredFields.any {
                it.type.packageName in setOf(
                    "net.inkyquill.pocketeditor.database",
                    "net.inkyquill.pocketeditor.sync",
                    "net.inkyquill.pocketeditor.yandex",
                    "net.inkyquill.pocketeditor.storage",
                )
            },
        )
    }

    @Test
    fun `review on exposes the complete overlay and ordered navigation`() = runBlocking {
        val fixture = fixture()

        val state = fixture.repository.observeChapter(BOOK_ID, CHAPTER_ID, reviewEnabled = true).first()

        assertEquals("Remember", state.chapterNote)
        assertEquals(2, state.document.reviewObjectCount)
        assertEquals(SIGNAL_ID, state.reviewItems?.signals?.single()?.id)
        assertEquals("Comment", state.reviewItems?.signals?.single()?.comment)
        assertEquals(NEXT_CHAPTER_ID, state.nextChapter?.id)
        assertNull(state.previousChapter)
    }

    @Test
    fun `chapter note is atomically written and outboxed before local sync is scheduled`() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)

        fixture.repository.saveChapterNote(BOOK_ID, CHAPTER_ID, "Changed")

        assertEquals(listOf("write", "outbox", "schedule:LOCAL_CHANGE"), events)
        assertEquals("Changed", fixture.store.review?.chapterNote)
    }

    @Test
    fun `production callback controller draft and repository retry chain is idempotent`() = runBlocking {
        val fixture = fixture()
        val persistence = FailingClearDraftPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rendered = MarkdownParser.parse(fixture.store.source.decodeToString())
        val controller = EditorialReviewController(
            BOOK_ID,
            CHAPTER_ID,
            { rendered },
            { emptyList() },
            RepositoryActions(fixture.repository),
            ReviewDraftStore(persistence),
            scope,
            uuid = { java.util.UUID.fromString("77777777-7777-4777-8777-777777777777") },
        )
        val callbacks = controller.readerCallbacks(scope)
        val end = "Канонический".encodeToByteArray().size

        callbacks.onTextSelected(ReaderSourceSelection(net.inkyquill.pocketeditor.markdown.RawRange(0, end), "Канонический"))
        callbacks.onSignalChosen(SignalType.WARNING)
        callbacks.onDraftTextChanged("Production chain")
        callbacks.onSaveDraft()
        repeat(100) {
            if (controller.state.value.error != null) return@repeat
            delay(5)
        }
        assertTrue(controller.state.value.error?.retryable == true)

        callbacks.onRetryReviewError()
        repeat(100) {
            if (controller.state.value.draftSession.draft == null) return@repeat
            delay(5)
        }

        val ids = fixture.store.review!!.signals.map(Signal::id)
        assertEquals(ids.distinct(), ids)
        assertEquals(1, ids.count { it == "77777777-7777-4777-8777-777777777777" })
        assertNull(controller.state.value.error)
    }

    @Test
    fun `failed local write neither outboxes nor schedules`() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.store.failWrites = true

        runCatching { fixture.repository.saveChapterNote(BOOK_ID, CHAPTER_ID, "Lost") }

        assertEquals(emptyList<String>(), events)
        assertEquals("Remember", fixture.store.review?.chapterNote)
    }

    @Test
    fun `delete is durable but deferred and survives repository recreation for undo`() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)

        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)
        assertTrue(fixture.store.review?.signals.orEmpty().isEmpty())
        assertEquals(listOf("pending", "write"), events)
        assertTrue(fixture.metadata.outbox(BOOK_ID).isEmpty())
        assertTrue(fixture.scheduler.triggers.isEmpty())

        val recovered = fixture.recreateRepository().pendingDeletions(BOOK_ID).single()
        assertEquals(deletion.tokenId, recovered.tokenId)
        fixture.recreateRepository().undoDeletion(recovered)
        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        assertEquals(listOf("pending", "write", "write", "pending-remove"), events)
        assertTrue(fixture.scheduler.triggers.isEmpty())
    }

    @Test
    fun `undo restores only the deleted record and preserves intervening changes`() = runBlocking {
        val fixture = fixture()
        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)
        fixture.repository.saveChapterNote(BOOK_ID, CHAPTER_ID, "Intervening")

        fixture.repository.undoDeletion(deletion)

        assertEquals("Intervening", fixture.store.review?.chapterNote)
        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        assertEquals(1, fixture.metadata.pending.size)
        assertEquals(2, fixture.scheduler.triggers.size)
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.repository.finalizeDeletion(deletion) } }
    }

    @Test
    fun `undo refuses a reused record id without clobbering its replacement`() = runBlocking {
        val fixture = fixture()
        val original = fixture.store.review!!.signals.single()
        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)
        fixture.repository.saveSignal(BOOK_ID, CHAPTER_ID, original.copy(comment = "Replacement"))

        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.repository.undoDeletion(deletion) } }

        assertEquals("Replacement", fixture.store.review?.signals?.single()?.comment)
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.repository.undoDeletion(deletion) } }
    }

    @Test
    fun `pending marker failure leaves review unchanged and returns no undo token`() = runBlocking {
        val fixture = fixture()
        fixture.deletions.failPut = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID) }
        }

        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        assertTrue(fixture.scheduler.triggers.isEmpty())
    }

    @Test
    fun `delete write failure removes prepared marker and preserves review`() = runBlocking {
        val fixture = fixture()
        fixture.store.failWrites = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID) }
        }

        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        assertTrue(fixture.deletions.values.isEmpty())
        assertTrue(fixture.metadata.pending.isEmpty())
    }

    @Test
    fun `failed undo outbox update keeps durable marker and remains retryable`() = runBlocking {
        val fixture = fixture()
        fixture.repository.saveChapterNote(BOOK_ID, CHAPTER_ID, "Preexisting")
        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)
        fixture.metadata.failOutbox = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.repository.undoDeletion(deletion) }
        }
        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        assertEquals(1, fixture.deletions.values.size)

        fixture.metadata.failOutbox = false
        fixture.repository.undoDeletion(deletion)

        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        assertTrue(fixture.deletions.values.isEmpty())
    }

    @Test
    fun `finalize promotes current review then removes marker and schedules`() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)

        fixture.repository.finalizeDeletion(deletion)

        assertTrue(fixture.store.review?.signals.orEmpty().isEmpty())
        assertEquals(listOf("pending", "write", "outbox", "pending-remove", "schedule:LOCAL_CHANGE"), events)
        assertEquals(1, fixture.metadata.pending.size)
        assertTrue(fixture.deletions.values.isEmpty())
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.repository.finalizeDeletion(deletion) } }
    }

    @Test
    fun `failed finalize keeps marker and is safely retryable`() = runBlocking {
        val fixture = fixture()
        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)
        fixture.metadata.failOutbox = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.repository.finalizeDeletion(deletion) }
        }
        assertEquals(1, fixture.deletions.values.size)
        assertTrue(fixture.scheduler.triggers.isEmpty())

        fixture.metadata.failOutbox = false
        fixture.repository.finalizeDeletion(deletion)

        assertTrue(fixture.deletions.values.isEmpty())
        assertEquals(1, fixture.metadata.pending.size)
    }

    @Test
    fun `finalize and undo race leaves exactly one consistent durable outcome`() = runBlocking {
        val fixture = fixture()
        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)
        val lockEntered = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val holder = async {
            fixture.mutations.withReview(BOOK_ID, "$SOURCE_PATH.review.json") {
                lockEntered.complete(Unit)
                releaseLock.await()
            }
        }
        lockEntered.await()
        val finalize = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.repository.finalizeDeletion(deletion) }
        }
        val undo = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.repository.undoDeletion(deletion) }
        }
        releaseLock.complete(Unit)
        holder.await()
        val results = listOf(finalize.await(), undo.await())

        assertEquals(1, results.count { it.isSuccess })
        assertTrue(fixture.deletions.values.isEmpty())
        if (fixture.metadata.pending.isNotEmpty()) {
            assertTrue(fixture.store.review?.signals.orEmpty().isEmpty())
        } else {
            assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        }
    }

    @Test
    fun `signal save rejects a rebasable but non-current source hash`() = runBlocking {
        val fixture = fixture()
        val signal = fixture.store.review!!.signals.single().copy(anchor = anchor())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.repository.saveSignal(BOOK_ID, CHAPTER_ID, signal) }
        }

        assertTrue(fixture.scheduler.triggers.isEmpty())
    }

    @Test
    fun `position and sync emissions reuse content and file work stays on injected IO dispatcher`() = runBlocking {
        val sync = MutableStateFlow<SyncStatus>(SyncStatus.Saved)
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "reader-test-io") }
            .asCoroutineDispatcher().use { dispatcher ->
                val fixture = fixture(sync, dispatcher)
                val initialSeen = CompletableDeferred<Unit>()
                val states = async {
                    fixture.repository.observeChapter(BOOK_ID, CHAPTER_ID, true)
                        .onEach { initialSeen.complete(Unit) }
                        .take(2)
                        .toList()
                }
                initialSeen.await()
                fixture.notifier.changed(BOOK_ID, "unrelated.md")
                sync.value = SyncStatus.Syncing
                val result = states.await()

                assertEquals(listOf(ReaderSyncState.SAVED, ReaderSyncState.SYNCING), result.map(ReaderState::syncState))
                assertEquals(2, fixture.store.sourceReads)
                assertEquals(1, fixture.store.manifestReads)
                assertEquals(1, fixture.store.reviewReads)
                assertTrue(
                    fixture.store.readThreads.all { it.contains("reader-test-io") },
                    fixture.store.readThreads.toString(),
                )
            }
    }

    @Test
    fun `durable outbox survives repository recreation as waiting to sync`() = runBlocking {
        val fixture = fixture()
        fixture.metadata.pending += OutboxEntity(
            BOOK_ID, "$SOURCE_PATH.review.json", "a".repeat(64), null, OutboxState.PENDING,
        )

        val state = fixture.recreateRepository().observeChapter(BOOK_ID, CHAPTER_ID, true).first()

        assertEquals(ReaderSyncState.WAITING_TO_SYNC, state.syncState)
    }

    @Test
    fun `external review change reloads matching open chapter`() = runBlocking {
        val fixture = fixture()
        val initialSeen = CompletableDeferred<Unit>()
        val states = async {
            fixture.repository.observeChapter(BOOK_ID, CHAPTER_ID, true)
                .onEach { initialSeen.complete(Unit) }
                .take(2)
                .toList()
        }
        initialSeen.await()

        fixture.store.review = fixture.store.review!!.copy(chapterNote = "From sync")
        fixture.notifier.changed(BOOK_ID, "$SOURCE_PATH.review.json")

        assertEquals(listOf("Remember", "From sync"), states.await().map(ReaderState::chapterNote))
    }

    @Test
    fun `open reader stays alive while a published binder removes its chapter`() = runBlocking {
        val fixture = fixture()
        val initialSeen = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Throwable?>()
        val states = mutableListOf<ReaderState>()
        val collection = launch {
            try {
                fixture.repository.observeChapter(BOOK_ID, CHAPTER_ID, false).collect { state ->
                    states += state
                    initialSeen.complete(Unit)
                }
                finished.complete(null)
            } catch (failure: Throwable) {
                finished.complete(failure)
            }
        }
        initialSeen.await()
        fixture.store.manifest = fixture.store.manifest.copy(
            chapters = listOf(ChapterEntry(NEXT_CHAPTER_ID, "next.md")),
        )

        fixture.notifier.changed(BOOK_ID, BookPaths.MANIFEST_NAME)
        delay(25)

        assertFalse(finished.isCompleted)
        assertEquals(listOf(CHAPTER_ID), states.map(ReaderState::chapterId))
        collection.cancel()
    }

    @Test
    fun `change during initial load is not lost`() = runBlocking {
        val fixture = fixture()
        fixture.store.reviewReadEntered = CompletableDeferred()
        fixture.store.releaseReviewRead = CompletableDeferred()
        val states = async { fixture.repository.observeChapter(BOOK_ID, CHAPTER_ID, true).take(2).toList() }
        fixture.store.reviewReadEntered!!.await()

        fixture.store.review = fixture.store.review!!.copy(chapterNote = "Raced")
        fixture.notifier.changed(BOOK_ID, "$SOURCE_PATH.review.json")
        fixture.store.releaseReviewRead!!.complete(Unit)

        assertEquals(listOf("Remember", "Raced"), states.await().map(ReaderState::chapterNote))
    }

    @Test
    fun `concurrent completed note and signal mutations preserve both`() = runBlocking {
        val fixture = fixture()
        val source = fixture.store.source
        val second = fixture.store.review!!.signals.single().copy(
            id = "66666666-6666-6666-6666-666666666666",
            comment = "Second",
            anchor = AnchorFactory.create(source, 0, "Канонический".encodeToByteArray().size),
        )

        val note = async { fixture.repository.saveChapterNote(BOOK_ID, CHAPTER_ID, "Concurrent") }
        val signal = async { fixture.repository.saveSignal(BOOK_ID, CHAPTER_ID, second) }
        note.await()
        signal.await()

        assertEquals("Concurrent", fixture.store.review?.chapterNote)
        assertEquals(setOf(SIGNAL_ID, second.id), fixture.store.review?.signals?.map(Signal::id)?.toSet())
    }

    @Test
    fun `saving signals edits reanchors positions and sync now stay local first`() = runBlocking {
        val fixture = fixture()
        val signal = fixture.store.review!!.signals.single().copy(comment = "Updated")
        val editAnchor = AnchorFactory.create(fixture.store.source, 0, "Канонический".encodeToByteArray().size)
        val edit = Edit(EDIT_ID, "Канонический", "Исправленный", editAnchor)

        fixture.repository.saveSignal(BOOK_ID, CHAPTER_ID, signal)
        fixture.repository.saveEdit(BOOK_ID, CHAPTER_ID, edit)
        fixture.repository.reanchorEdit(BOOK_ID, CHAPTER_ID, EDIT_ID, editAnchor.copy(startLine = 2))
        fixture.repository.saveReadingPosition(BOOK_ID, CHAPTER_ID, blockIndex = 1, byteOffset = 7)
        fixture.repository.syncNow(BOOK_ID)

        assertEquals("Updated", fixture.store.review!!.signals.single().comment)
        assertEquals(2, fixture.store.review!!.edits.single().anchor.startLine)
        assertEquals(7, fixture.books.position.value?.byteOffset)
        assertEquals(SyncTrigger.SYNC_NOW, fixture.scheduler.triggers.last())
    }

    @Test
    fun `reanchor rejects an anchor that does not resolve against canonical source`() = runBlocking {
        val fixture = fixture()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                fixture.repository.reanchorSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID, anchor().copy(startByte = 1))
            }
        }

        assertEquals(0, fixture.scheduler.triggers.size)
    }

    private fun fixture(
        events: MutableList<String> = mutableListOf(),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    ): Fixture = fixture(flowOf(SyncStatus.Saved), dispatcher, events)

    private class FailingClearDraftPersistence : ReviewDraftPersistence {
        private var value: DraftEntity? = null
        private var failClear = true
        override suspend fun put(draft: DraftEntity) { value = draft }
        override suspend fun get(bookId: String, chapterId: String, draftType: String, recordKey: String) = value
        override suspend fun delete(bookId: String, chapterId: String, draftType: String, recordKey: String) {
            if (failClear) { failClear = false; error("Room clear failed") }
            value = null
        }
    }

    private class RepositoryActions(private val repository: ReaderRepository) : EditorialReviewActions {
        override suspend fun saveSignal(signal: Signal) = repository.saveSignal(BOOK_ID, CHAPTER_ID, signal)
        override suspend fun saveEdit(edit: Edit) = repository.saveEdit(BOOK_ID, CHAPTER_ID, edit)
        override suspend fun saveChapterNote(text: String) = repository.saveChapterNote(BOOK_ID, CHAPTER_ID, text)
        override suspend fun deleteSignal(id: String) = repository.deleteSignal(BOOK_ID, CHAPTER_ID, id)
        override suspend fun deleteEdit(id: String) = repository.deleteEdit(BOOK_ID, CHAPTER_ID, id)
        override suspend fun pendingDeletions() = repository.pendingDeletions(BOOK_ID)
        override suspend fun undoDeletion(token: PendingDeletion) = repository.undoDeletion(token)
        override suspend fun finalizeDeletion(token: PendingDeletion) { repository.finalizeDeletion(token) }
        override suspend fun reanchor(recordId: String, anchor: Anchor) = Unit
        override suspend fun resolveReview(path: String, expectedIdentity: String, choices: Map<String, ConflictChoice>) = Unit
        override suspend fun resolveManifest(expectedIdentity: String, choice: ConflictChoice) = Unit
    }

    private fun fixture(
        sync: Flow<SyncStatus>,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        events: MutableList<String> = mutableListOf(),
    ): Fixture {
        val manifest = BookManifest(
            bookId = BOOK_ID,
            title = "Book",
            chapters = listOf(
                ChapterEntry(CHAPTER_ID, SOURCE_PATH),
                ChapterEntry(NEXT_CHAPTER_ID, "next.md"),
            ),
        )
        val source = "Канонический текст.".encodeToByteArray()
        val review = ReviewDocument(
            chapterId = CHAPTER_ID,
            sourcePath = SOURCE_PATH,
            chapterNote = "Remember",
            signals = listOf(
                Signal(
                    SIGNAL_ID,
                    SignalType.NOTE,
                    "Канонический",
                    AnchorFactory.create(source, 0, "Канонический".encodeToByteArray().size),
                    "Comment",
                ),
            ),
        )
        val store = FakeBookStore(manifest, source, review, events)
        val books = FakeReaderBookStore()
        val metadata = FakeMetadata(events)
        val deletions = FakePendingDeletionStore(events, metadata)
        val scheduler = FakeReaderSyncScheduler(events)
        val mutations = ReviewMutationCoordinator()
        val notifier = ContentChangeNotifier()
        return Fixture(
            ReaderRepository(
                store,
                books,
                metadata,
                scheduler,
                { sync },
                mutations,
                deletions,
                notifier,
                ioDispatcher = dispatcher,
                currentTimeMillis = { Instant.EPOCH.toEpochMilli() },
            ),
            store,
            books,
            scheduler,
            metadata,
            deletions,
            sync,
            dispatcher,
            mutations,
            notifier,
        )
    }

    private data class Fixture(
        val repository: ReaderRepository,
        val store: FakeBookStore,
        val books: FakeReaderBookStore,
        val scheduler: FakeReaderSyncScheduler,
        val metadata: FakeMetadata,
        val deletions: FakePendingDeletionStore,
        val sync: Flow<SyncStatus>,
        val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        val mutations: ReviewMutationCoordinator,
        val notifier: ContentChangeNotifier,
    ) {
        fun recreateRepository() = ReaderRepository(
            store,
            books,
            metadata,
            scheduler,
            { sync },
            mutations,
            deletions,
            notifier,
            ioDispatcher = dispatcher,
            currentTimeMillis = { Instant.EPOCH.toEpochMilli() },
        )
    }

    private class FakeBookStore(
        var manifest: BookManifest,
        var source: ByteArray,
        var review: ReviewDocument?,
        private val events: MutableList<String>,
    ) : BookStore {
        var failWrites = false
        var sourceReads = 0
        var manifestReads = 0
        var reviewReads = 0
        val readThreads = mutableListOf<String>()
        var reviewReadEntered: CompletableDeferred<Unit>? = null
        var releaseReviewRead: CompletableDeferred<Unit>? = null
        override suspend fun readSource(bookId: String, path: String): ByteArray {
            sourceReads++
            readThreads += Thread.currentThread().name
            return source
        }
        override suspend fun readManifest(bookId: String): BookManifest {
            manifestReads++
            readThreads += Thread.currentThread().name
            return manifest
        }
        override suspend fun writeManifest(bookId: String, value: BookManifest) = error("not used")
        override suspend fun replaceDownloadedManifest(bookId: String, bytes: ByteArray) = error("not used")
        override suspend fun readReview(bookId: String, path: String): ReviewDocument? {
            reviewReads++
            readThreads += Thread.currentThread().name
            val captured = review
            reviewReadEntered?.complete(Unit)
            releaseReviewRead?.await()
            return captured
        }
        override suspend fun writeReview(bookId: String, path: String, value: ReviewDocument): LocalRevision {
            if (failWrites) error("disk full")
            review = value
            events += "write"
            return LocalRevision(path, value.hashCode().toString(), 1, DirectorySyncStatus.SYNCED)
        }
        override suspend fun deleteReview(bookId: String, path: String): DirectorySyncStatus {
            review = null
            return DirectorySyncStatus.SYNCED
        }
    }

    private class FakeReaderBookStore : ReaderBookStore {
        val position = MutableStateFlow<ReadingPositionEntity?>(null)
        override fun observeReadingPosition(bookId: String): Flow<ReadingPositionEntity?> = position
        override suspend fun saveReadingPosition(position: ReadingPositionEntity) { this.position.value = position }
        override suspend fun root(bookId: String) = BookRootEntity(BOOK_ID, "/remote", "/local", 0)
    }

    private class FakeMetadata(private val events: MutableList<String>) : SyncMetadataStore {
        var failOutbox = false
        val pending = mutableListOf<OutboxEntity>()
        override suspend fun outbox(bookId: String) = pending.filter { it.bookId == bookId }
        override suspend fun confirmedRevisions(bookId: String) = emptyList<RemoteRevisionEntity>()
        override suspend fun mergeBase(bookId: String, path: String): MergeBaseEntity? = null
        override suspend fun recordRemote(value: RemoteRevisionEntity) = Unit
        override suspend fun recordBase(value: MergeBaseEntity) = Unit
        override suspend fun recordOutbox(value: OutboxEntity) {
            if (failOutbox) throw IllegalStateException("outbox failed")
            events += "outbox"
            pending.removeAll { it.bookId == value.bookId && it.path == value.path }
            pending += value
        }
        override suspend fun removeOutbox(bookId: String, path: String) {
            pending.removeAll { it.bookId == bookId && it.path == path }
        }
        override suspend fun removeRemote(bookId: String, path: String) = Unit
        override suspend fun removeBase(bookId: String, path: String) = Unit
    }

    private class FakePendingDeletionStore(
        private val events: MutableList<String>,
        private val metadata: FakeMetadata,
    ) : PendingDeletionStore {
        val values = mutableMapOf<String, PendingDeletionEntity>()
        var failPut = false
        override suspend fun put(value: PendingDeletionEntity) {
            if (failPut) error("pending failed")
            events += "pending"
            values[value.tokenId] = value
        }
        override suspend fun get(tokenId: String): PendingDeletionEntity? = values[tokenId]
        override suspend fun pendingForBook(bookId: String): List<PendingDeletionEntity> =
            values.values.filter { it.bookId == bookId }
        override suspend fun remove(tokenId: String): Boolean {
            events += "pending-remove"
            return values.remove(tokenId) != null
        }
        override suspend fun complete(tokenId: String, outbox: OutboxEntity?): Boolean {
            if (outbox != null) metadata.recordOutbox(outbox)
            return remove(tokenId)
        }
    }

    private class FakeReaderSyncScheduler(private val events: MutableList<String>) : ReaderSyncScheduler {
        val triggers = mutableListOf<SyncTrigger>()
        override fun enqueue(bookId: String, remoteRootPath: String, trigger: SyncTrigger) {
            triggers += trigger
            events += "schedule:$trigger"
        }
    }

    private fun anchor() = Anchor(
        sourceSha256 = "a".repeat(64), selectionSha256 = "b".repeat(64), startByte = 0, endByte = 24,
        startLine = 1, endLine = 1, prefix = "", suffix = " текст.",
    )

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ID = "22222222-2222-2222-2222-222222222222"
        const val NEXT_CHAPTER_ID = "33333333-3333-3333-3333-333333333333"
        const val SIGNAL_ID = "44444444-4444-4444-4444-444444444444"
        const val EDIT_ID = "55555555-5555-5555-5555-555555555555"
        const val SOURCE_PATH = "chapter.md"
    }
}
