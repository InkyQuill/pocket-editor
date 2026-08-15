package net.inkyquill.pocketeditor.sync

import java.util.UUID
import kotlinx.coroutines.test.runTest
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.storage.LocalRevision
import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteRevisionProbeTest {
    private val manifest = BookManifest(
        bookId = BOOK_ID,
        title = "Book",
        chapters = listOf(ChapterEntry(CHAPTER_ID, SOURCE_PATH)),
    )
    private val gateway = ProbeGateway()
    private val metadata = ProbeMetadata()
    private val probe = RemoteRevisionProbe(gateway, ManifestStore(manifest), metadata)

    @Test
    fun `changed remote binder revision requests full sync`() = runTest {
        metadata.confirmed += revision(BookPaths.MANIFEST_NAME, "old")
        gateway.entries += remoteEntry(BookPaths.MANIFEST_NAME, "new")

        assertTrue(probe.shouldSync(BOOK_ID, ROOT))
    }

    @Test
    fun `unchanged tracked revisions with empty outbox skip full sync`() = runTest {
        confirmAndExpose(BookPaths.MANIFEST_NAME, "manifest")
        confirmAndExpose(SOURCE_PATH, "source")

        assertFalse(probe.shouldSync(BOOK_ID, ROOT))
        assertEquals(1, gateway.listCalls)
    }

    @Test
    fun `tracked source or review deletion requests full sync`() = runTest {
        listOf(SOURCE_PATH, REVIEW_PATH).forEach { missingPath ->
            gateway.entries.clear()
            metadata.confirmed.clear()
            confirmAndExpose(BookPaths.MANIFEST_NAME, "manifest")
            confirmAndExpose(SOURCE_PATH, "source")
            confirmAndExpose(REVIEW_PATH, "review")
            gateway.entries.removeAll { it.name == missingPath }

            assertTrue(probe.shouldSync(BOOK_ID, ROOT), missingPath)
        }
    }

    @Test
    fun `untracked markdown addition is discovery only`() = runTest {
        confirmAndExpose(BookPaths.MANIFEST_NAME, "manifest")
        confirmAndExpose(SOURCE_PATH, "source")
        gateway.entries += remoteEntry("bonus.md", "bonus")

        assertFalse(probe.shouldSync(BOOK_ID, ROOT))
    }

    @Test
    fun `non-empty outbox requests full sync without touching remote files`() = runTest {
        metadata.pending += OutboxEntity(
            BOOK_ID,
            REVIEW_PATH,
            "local",
            "base",
            OutboxState.PENDING,
        )

        assertTrue(probe.shouldSync(BOOK_ID, ROOT))
        assertEquals(0, gateway.listCalls)
    }

    @Test
    fun `pending deletion publication requests full sync without touching remote files`() = runTest {
        metadata.publications += REVIEW_PATH

        assertTrue(probe.shouldSync(BOOK_ID, ROOT))
        assertEquals(0, gateway.listCalls)
    }

    @Test
    fun `incomplete progressive book suppresses outbox and remote probe until complete`() = runTest {
        metadata.pending += OutboxEntity(BOOK_ID, BookPaths.MANIFEST_NAME, "local", null, OutboxState.PENDING)
        val blocked = RemoteRevisionProbe(gateway, ManifestStore(manifest), metadata, SyncEligibility { false })

        assertFalse(blocked.shouldSync(BOOK_ID, ROOT))
        assertEquals(0, gateway.listCalls)

        val complete = RemoteRevisionProbe(gateway, ManifestStore(manifest), metadata, SyncEligibility { true })
        assertTrue(complete.shouldSync(BOOK_ID, ROOT))
        assertEquals(0, gateway.listCalls)
    }

    private fun confirmAndExpose(path: String, revision: String) {
        metadata.confirmed += revision(path, revision)
        gateway.entries += remoteEntry(path, revision)
    }

    private fun revision(path: String, revision: String) =
        RemoteRevisionEntity(BOOK_ID, path, revision, null)

    private fun remoteEntry(path: String, revision: String) =
        RemoteEntry(path, "$ROOT/$path", "file", 1L, revision)

    private class ProbeMetadata : RemoteRevisionMetadata {
        val confirmed = mutableListOf<RemoteRevisionEntity>()
        val pending = mutableListOf<OutboxEntity>()
        val publications = mutableListOf<String>()

        override suspend fun confirmedRevisions(bookId: String) = confirmed.filter { it.bookId == bookId }
        override suspend fun outbox(bookId: String) = pending.filter { it.bookId == bookId }
        override suspend fun pendingPublicationPaths(bookId: String) = publications.toList()
    }

    private class ManifestStore(private val manifest: BookManifest) : BookStore {
        override suspend fun readManifest(bookId: String) = manifest
        override suspend fun readSource(bookId: String, path: String): ByteArray = error("probe downloaded source")
        override suspend fun writeManifest(bookId: String, value: BookManifest): LocalRevision = error("probe wrote manifest")
        override suspend fun replaceDownloadedManifest(bookId: String, bytes: ByteArray): LocalRevision =
            error("probe replaced manifest")
        override suspend fun readReview(bookId: String, path: String): ReviewDocument? = error("probe read review")
        override suspend fun writeReview(bookId: String, path: String, value: ReviewDocument): LocalRevision =
            error("probe wrote review")
        override suspend fun deleteReview(bookId: String, path: String) = error("probe deleted review")
    }

    private class ProbeGateway : YandexDiskGateway {
        val entries = mutableListOf<RemoteEntry>()
        var listCalls = 0

        override suspend fun listFolder(path: String): List<RemoteEntry> {
            listCalls++
            return entries.toList()
        }

        override suspend fun download(path: String): RemoteFile = error("probe downloaded remote bytes")
        override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock = error("probe acquired lock")
        override suspend fun readLock(rootPath: String): SyncLock = error("probe read lock")
        override suspend fun uploadGuarded(
            rootPath: String,
            relativePath: String,
            bytes: ByteArray,
            ownedLock: SyncLock,
        ): String = error("probe uploaded")
        override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) = error("probe released lock")
        override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) = error("probe broke lock")
    }

    private companion object {
        val BOOK_ID = UUID.fromString("00000000-0000-4000-8000-000000000401").toString()
        val CHAPTER_ID = UUID.fromString("00000000-0000-4000-8000-000000000402").toString()
        const val ROOT = "disk:/Book"
        const val SOURCE_PATH = "chapter.md"
        const val REVIEW_PATH = "$SOURCE_PATH.review.json"
    }
}
