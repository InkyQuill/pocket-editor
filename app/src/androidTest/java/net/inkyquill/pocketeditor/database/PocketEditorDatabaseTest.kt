package net.inkyquill.pocketeditor.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.storage.RecoveryScanner
import net.inkyquill.pocketeditor.sync.RoomPendingDeletionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PocketEditorDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: PocketEditorDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PocketEditorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun schemaContainsDisposableMetadataAndDurableUndoRecords() {
        val tables = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'room_%' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertTrue(
            tables.containsAll(
                setOf("book_roots", "remote_revisions", "merge_bases", "outbox", "reading_positions", "drafts", "source_search"),
            ),
        )
        tables.forEach { table ->
            val columns = database.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildSet {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            if (!table.startsWith("source_search") && table != "pending_deletions") {
                assertFalse("$table must not store manuscript or review documents", "document" in columns || "content" in columns)
            }
        }
    }

    @Test
    fun androidCacheFilesystemSupportsParentDirectoryFsync() = runBlocking {
        val cacheRoot = File(context.cacheDir, "directory-fsync-$BOOK_ID").also { it.deleteRecursively() }
        try {
            val revision = AtomicBookStore(BookPaths(cacheRoot)).writeManifest(
                BOOK_ID,
                BookManifest(bookId = BOOK_ID, title = "Book"),
            )

            assertEquals(DirectorySyncStatus.SYNCED, revision.directorySyncStatus)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun daoFlowsExposeRegistrationsRevisionsBasesOutboxPositionsAndDrafts() = runBlocking {
        database.bookDao().upsertRoot(BookRootEntity(BOOK_ID, "/remote/book", "/local/book", 1L))
        database.syncDao().upsertRemoteRevision(RemoteRevisionEntity(BOOK_ID, REVIEW_PATH, "remote-1", HASH_A))
        database.syncDao().upsertMergeBase(MergeBaseEntity(BOOK_ID, REVIEW_PATH, HASH_A, "remote-1"))
        database.syncDao().upsertOutbox(OutboxEntity(BOOK_ID, REVIEW_PATH, HASH_B, HASH_A, OutboxState.PENDING))
        database.syncDao().upsertPendingDeletion(
            PendingDeletionEntity(
                TOKEN_ID,
                BOOK_ID,
                CHAPTER_ID,
                REVIEW_PATH,
                RECORD_ID,
                "signal",
                "payload",
                4L,
            ),
        )
        database.bookDao().upsertReadingPosition(ReadingPositionEntity(BOOK_ID, CHAPTER_ID, 4, 12, 2L))
        database.draftDao().upsert(DraftEntity(BOOK_ID, CHAPTER_ID, "chapter_note", null, "draft", 0, 5, 3L))

        assertEquals(1, database.bookDao().observeRoots().first().size)
        assertEquals(1, database.syncDao().observeRemoteRevisions(BOOK_ID).first().size)
        assertEquals(1, database.syncDao().observeMergeBases(BOOK_ID).first().size)
        assertEquals(1, database.syncDao().observeOutbox().first().size)
        assertEquals(TOKEN_ID, database.syncDao().pendingDeletions(BOOK_ID).single().tokenId)
        assertTrue(
            RoomPendingDeletionStore(database.syncDao()).complete(
                TOKEN_ID,
                OutboxEntity(BOOK_ID, REVIEW_PATH, HASH_A, HASH_B, OutboxState.RETRY),
            ),
        )
        assertEquals(null, database.syncDao().getPendingDeletion(TOKEN_ID))
        assertEquals(OutboxState.RETRY, database.syncDao().getOutbox(BOOK_ID, REVIEW_PATH)?.state)
        assertEquals(CHAPTER_ID, database.bookDao().observeReadingPosition(BOOK_ID).first()?.chapterId)
        assertEquals("draft", database.draftDao().observeBookDrafts(BOOK_ID).first().single().text)
    }

    @Test
    fun rebuildingRoomPreservesReviewAndRequiresRemoteCompareWithoutBase() = runBlocking {
        val cacheRoot = File(context.cacheDir, "recovery-$BOOK_ID").also { it.deleteRecursively() }
        try {
            val paths = BookPaths(cacheRoot)
            val store = AtomicBookStore(paths)
            store.writeManifest(
                BOOK_ID,
                BookManifest(
                    bookId = BOOK_ID,
                    title = "Book",
                    chapters = listOf(ChapterEntry(CHAPTER_ID, SOURCE_PATH, "Chapter")),
                ),
            )
            val review = ReviewDocument(chapterId = CHAPTER_ID, sourcePath = SOURCE_PATH, chapterNote = "Saved")
            store.writeReview(BOOK_ID, REVIEW_PATH, review)

            val report = RecoveryScanner(paths, database.bookDao(), database.syncDao()).reconcile()

            assertEquals(review, store.readReview(BOOK_ID, REVIEW_PATH))
            assertEquals(1, report.recoveredRegistrations)
            assertEquals(2, report.recreatedPendingWork)
            val recovered = database.syncDao().getOutbox(BOOK_ID, REVIEW_PATH)
            assertEquals(OutboxState.NEEDS_REMOTE_COMPARE, recovered?.state)
            assertFalse(recovered!!.isUploadable)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun recoveryRegistersUuidDirectoryWithInvalidManifestAsVisibleArtifact() = runBlocking {
        val cacheRoot = File(context.cacheDir, "invalid-recovery-$BOOK_ID").also { it.deleteRecursively() }
        try {
            val paths = BookPaths(cacheRoot)
            val invalidManifest = paths.manifest(BOOK_ID).also {
                it.parentFile?.mkdirs()
                it.writeText("{")
            }

            val report = RecoveryScanner(paths, database.bookDao(), database.syncDao()).reconcile()

            assertEquals(BOOK_ID, database.bookDao().getRoot(BOOK_ID)?.bookId)
            assertEquals(null, database.bookDao().getRoot(BOOK_ID)?.remoteRootPath)
            assertTrue(report.invalidFiles.contains(invalidManifest))
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun recoveryDeletesStaleOutboxWhenLocalFileMatchesTrustedBase() = runBlocking {
        withCachedReview { paths, store ->
            val revision = store.writeReview(BOOK_ID, REVIEW_PATH, validReview())
            database.syncDao().upsertMergeBase(MergeBaseEntity(BOOK_ID, REVIEW_PATH, revision.sha256, "remote-1"))
            database.syncDao().upsertOutbox(
                OutboxEntity(BOOK_ID, REVIEW_PATH, HASH_B, revision.sha256, OutboxState.PENDING),
            )

            RecoveryScanner(paths, database.bookDao(), database.syncDao()).reconcile()

            assertEquals(null, database.syncDao().getOutbox(BOOK_ID, REVIEW_PATH))
        }
    }

    @Test
    fun recoveryMakesExistingUploadableOutboxSafeWhenMergeBaseIsMissing() = runBlocking {
        withCachedReview { paths, store ->
            val revision = store.writeReview(BOOK_ID, REVIEW_PATH, validReview())
            database.syncDao().upsertOutbox(
                OutboxEntity(BOOK_ID, REVIEW_PATH, revision.sha256, null, OutboxState.PENDING),
            )

            RecoveryScanner(paths, database.bookDao(), database.syncDao()).reconcile()

            val recovered = database.syncDao().getOutbox(BOOK_ID, REVIEW_PATH)
            assertEquals(OutboxState.NEEDS_REMOTE_COMPARE, recovered?.state)
            assertFalse(recovered!!.isUploadable)
        }
    }

    @Test
    fun recoveryPreservesSafeRetryMetadataWithTrustedBase() = runBlocking {
        withCachedReview { paths, store ->
            val revision = store.writeReview(BOOK_ID, REVIEW_PATH, validReview())
            database.syncDao().upsertMergeBase(MergeBaseEntity(BOOK_ID, REVIEW_PATH, HASH_A, "remote-1"))
            val retry = OutboxEntity(
                bookId = BOOK_ID,
                path = REVIEW_PATH,
                localSha256 = revision.sha256,
                baseSha256 = HASH_A,
                state = OutboxState.RETRY,
                attempts = 2,
                nextAttemptAt = 500L,
                lastError = "offline",
            )
            database.syncDao().upsertOutbox(retry)

            RecoveryScanner(paths, database.bookDao(), database.syncDao()).reconcile()

            assertEquals(retry, database.syncDao().getOutbox(BOOK_ID, REVIEW_PATH))
        }
    }

    @Test
    fun recoveryDoesNotCreateReviewOutboxWhileDurableUndoIsPending() = runBlocking {
        withCachedReview { paths, _ ->
            database.syncDao().upsertPendingDeletion(
                PendingDeletionEntity(
                    TOKEN_ID,
                    BOOK_ID,
                    CHAPTER_ID,
                    REVIEW_PATH,
                    RECORD_ID,
                    "signal",
                    "payload",
                    4L,
                ),
            )

            RecoveryScanner(paths, database.bookDao(), database.syncDao()).reconcile()

            assertEquals(null, database.syncDao().getOutbox(BOOK_ID, REVIEW_PATH))
        }
    }

    @Test
    fun recoveryRejectsCorruptReviewAndClearsUnsafeOutbox() = runBlocking {
        assertInvalidReviewIsQuarantined("{".encodeToByteArray())
    }

    @Test
    fun recoveryRejectsMismatchedReviewSourcePathAndClearsUnsafeOutbox() = runBlocking {
        val bytes = ReviewJson.encode(validReview().copy(sourcePath = "other.md")).encodeToByteArray()
        assertInvalidReviewIsQuarantined(bytes)
    }

    @Test
    fun recoveryRejectsMismatchedReviewChapterIdAndClearsUnsafeOutbox() = runBlocking {
        val bytes = ReviewJson.encode(
            validReview().copy(chapterId = "33333333-3333-3333-3333-333333333333"),
        ).encodeToByteArray()
        assertInvalidReviewIsQuarantined(bytes)
    }

    private suspend fun assertInvalidReviewIsQuarantined(bytes: ByteArray) {
        withCachedReview { paths, _ ->
            val reviewFile = paths.review(BOOK_ID, REVIEW_PATH)
            reviewFile.writeBytes(bytes)
            database.syncDao().upsertOutbox(
                OutboxEntity(BOOK_ID, REVIEW_PATH, bytes.sha256(), HASH_A, OutboxState.PENDING),
            )

            val report = RecoveryScanner(paths, database.bookDao(), database.syncDao()).reconcile()

            assertTrue(report.invalidFiles.contains(reviewFile))
            assertEquals(null, database.syncDao().getOutbox(BOOK_ID, REVIEW_PATH))
        }
    }

    private suspend fun withCachedReview(block: suspend (BookPaths, AtomicBookStore) -> Unit) {
        val cacheRoot = File(context.cacheDir, "recovery-review-$BOOK_ID").also { it.deleteRecursively() }
        try {
            val paths = BookPaths(cacheRoot)
            val store = AtomicBookStore(paths)
            store.writeManifest(
                BOOK_ID,
                BookManifest(
                    bookId = BOOK_ID,
                    title = "Book",
                    chapters = listOf(ChapterEntry(CHAPTER_ID, SOURCE_PATH, "Chapter")),
                ),
            )
            store.writeReview(BOOK_ID, REVIEW_PATH, validReview())
            block(paths, store)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    private fun validReview() = ReviewDocument(
        chapterId = CHAPTER_ID,
        sourcePath = SOURCE_PATH,
        chapterNote = "Saved",
    )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ID = "22222222-2222-2222-2222-222222222222"
        const val SOURCE_PATH = "chapter.md"
        const val REVIEW_PATH = "chapter.md.review.json"
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TOKEN_ID = "44444444-4444-4444-4444-444444444444"
        const val RECORD_ID = "55555555-5555-5555-5555-555555555555"
    }
}
