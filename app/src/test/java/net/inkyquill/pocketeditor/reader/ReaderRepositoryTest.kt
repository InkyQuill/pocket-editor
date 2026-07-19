package net.inkyquill.pocketeditor.reader

import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.anchor.AnchorFactory
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.storage.LocalRevision
import net.inkyquill.pocketeditor.sync.SyncMetadataStore
import net.inkyquill.pocketeditor.sync.SyncStatus
import net.inkyquill.pocketeditor.sync.SyncTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderRepositoryTest {
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
    fun `failed local write neither outboxes nor schedules`() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.store.failWrites = true

        runCatching { fixture.repository.saveChapterNote(BOOK_ID, CHAPTER_ID, "Lost") }

        assertEquals(emptyList<String>(), events)
        assertEquals("Remember", fixture.store.review?.chapterNote)
    }

    @Test
    fun `delete and undo are both authoritative outboxed mutations`() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)

        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)
        assertTrue(fixture.store.review?.signals.orEmpty().isEmpty())
        assertEquals(listOf("write", "outbox", "schedule:LOCAL_CHANGE"), events)

        fixture.repository.undoDeletion(deletion)
        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        assertEquals(
            listOf("write", "outbox", "schedule:LOCAL_CHANGE", "write", "outbox", "schedule:LOCAL_CHANGE"),
            events,
        )
    }

    @Test
    fun `undo restores only the deleted record and preserves intervening changes`() = runBlocking {
        val fixture = fixture()
        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)
        fixture.repository.saveChapterNote(BOOK_ID, CHAPTER_ID, "Intervening")

        fixture.repository.undoDeletion(deletion)

        assertEquals("Intervening", fixture.store.review?.chapterNote)
        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        assertThrows(IllegalStateException::class.java) { fixture.repository.finalizeDeletion(deletion) }
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
    fun `outbox failure rolls deletion back and returns no undo token`() = runBlocking {
        val fixture = fixture()
        fixture.metadata.failOutbox = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID) }
        }

        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
        assertTrue(fixture.scheduler.triggers.isEmpty())
    }

    @Test
    fun `failed undo remains retryable after its mutation is rolled back`() = runBlocking {
        val fixture = fixture()
        val deletion = fixture.repository.deleteSignal(BOOK_ID, CHAPTER_ID, SIGNAL_ID)
        fixture.metadata.failOutbox = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.repository.undoDeletion(deletion) }
        }
        assertTrue(fixture.store.review?.signals.orEmpty().isEmpty())

        fixture.metadata.failOutbox = false
        fixture.repository.undoDeletion(deletion)

        assertEquals(SIGNAL_ID, fixture.store.review?.signals?.single()?.id)
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
                sync.value = SyncStatus.Syncing
                val result = states.await()

                assertEquals(listOf(ReaderSyncState.SAVED, ReaderSyncState.SYNCING), result.map(ReaderState::syncState))
                assertEquals(1, fixture.store.sourceReads)
                assertEquals(1, fixture.store.manifestReads)
                assertEquals(1, fixture.store.reviewReads)
                assertTrue(
                    fixture.store.readThreads.all { it.contains("reader-test-io") },
                    fixture.store.readThreads.toString(),
                )
            }
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

    private fun fixture(
        sync: Flow<SyncStatus>,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        events: MutableList<String> = mutableListOf(),
    ): Fixture {
        val manifest = BookManifest(
            bookId = BOOK_ID,
            title = "Book",
            chapters = listOf(
                ChapterEntry(CHAPTER_ID, SOURCE_PATH, "First"),
                ChapterEntry(NEXT_CHAPTER_ID, "next.md", "Next"),
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
        val scheduler = FakeReaderSyncScheduler(events)
        return Fixture(
            ReaderRepository(
                store,
                books,
                metadata,
                scheduler,
                { sync },
                ReviewMutationCoordinator(),
                ioDispatcher = dispatcher,
                currentTimeMillis = { Instant.EPOCH.toEpochMilli() },
            ),
            store,
            books,
            scheduler,
            metadata,
        )
    }

    private data class Fixture(
        val repository: ReaderRepository,
        val store: FakeBookStore,
        val books: FakeReaderBookStore,
        val scheduler: FakeReaderSyncScheduler,
        val metadata: FakeMetadata,
    )

    private class FakeBookStore(
        private val manifest: BookManifest,
        val source: ByteArray,
        var review: ReviewDocument?,
        private val events: MutableList<String>,
    ) : BookStore {
        var failWrites = false
        var sourceReads = 0
        var manifestReads = 0
        var reviewReads = 0
        val readThreads = mutableListOf<String>()
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
        override suspend fun readReview(bookId: String, path: String): ReviewDocument? {
            reviewReads++
            readThreads += Thread.currentThread().name
            return review
        }
        override suspend fun writeReview(bookId: String, path: String, value: ReviewDocument): LocalRevision {
            if (failWrites) error("disk full")
            review = value
            events += "write"
            return LocalRevision(path, value.hashCode().toString(), 1, DirectorySyncStatus.SYNCED)
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
        override suspend fun outbox(bookId: String) = emptyList<OutboxEntity>()
        override suspend fun mergeBase(bookId: String, path: String): MergeBaseEntity? = null
        override suspend fun recordRemote(value: RemoteRevisionEntity) = Unit
        override suspend fun recordBase(value: MergeBaseEntity) = Unit
        override suspend fun recordOutbox(value: OutboxEntity) {
            if (failOutbox) throw IllegalStateException("outbox failed")
            events += "outbox"
        }
        override suspend fun removeOutbox(bookId: String, path: String) = Unit
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
