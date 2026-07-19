package net.inkyquill.pocketeditor.sync

import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.OutboxState
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.storage.LocalRevision
import net.inkyquill.pocketeditor.storage.SourceCache
import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncEngineTest {
    @Test
    fun `offline sync retains outbox and cache and reports waiting`() = runBlocking {
        val fixture = fixture().apply {
            metadata.pending += outbox(REVIEW_PATH, localReview)
            remote.failure = YandexDiskError.Offline(IOException("offline"))
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertEquals(SyncStatus.WaitingToSync, status)
        assertEquals(1, fixture.metadata.pending.size)
        assertEquals(fixture.localReview, fixture.cache.reviews[REVIEW_PATH])
        assertTrue(fixture.remote.uploads.isEmpty())
    }

    @Test
    fun `full refresh downloads canonical sources only through SourceCache and valid sidecars`() = runBlocking {
        val fixture = fixture(withLocalReview = false).apply {
            remote.put(MANIFEST_PATH, BookManifest.encode(manifest).encodeToByteArray())
            remote.put(SOURCE_PATH, "remote source".encodeToByteArray())
            remote.put(REVIEW_PATH, ReviewJson.encode(remoteReview).encodeToByteArray())
        }

        fixture.engine.syncBook(BOOK_ID, ROOT)

        assertEquals("remote source", fixture.cache.sources[SOURCE_PATH]!!.decodeToString())
        assertEquals(fixture.remoteReview, fixture.cache.reviews[REVIEW_PATH])
        assertEquals(listOf(SOURCE_PATH), fixture.cache.sourceCacheWrites)
        assertTrue(fixture.remote.uploads.isEmpty())
        assertEquals(SyncStatus.Saved, fixture.engine.status(BOOK_ID).first())
    }

    @Test
    fun `pending review uploads under verified lock when remote still matches durable base`() = runBlocking {
        val fixture = fixture().apply {
            val baseBytes = ReviewJson.encode(baseReview).encodeToByteArray()
            remote.put(MANIFEST_PATH, BookManifest.encode(manifest).encodeToByteArray())
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            remote.put(REVIEW_PATH, baseBytes)
            bases.write(BOOK_ID, REVIEW_PATH, baseBytes, remote.revision(REVIEW_PATH))
            metadata.bases[REVIEW_PATH] = MergeBaseEntity(BOOK_ID, REVIEW_PATH, sha(baseBytes), remote.revision(REVIEW_PATH))
            metadata.pending += outbox(REVIEW_PATH, localReview, sha(baseBytes))
        }

        fixture.engine.syncBook(BOOK_ID, ROOT)

        assertEquals(listOf(REVIEW_PATH), fixture.remote.uploads)
        assertTrue(fixture.metadata.pending.isEmpty())
        assertEquals(fixture.localReview, ReviewJson.decode(fixture.remote.bytes(REVIEW_PATH).decodeToString(), CHAPTER_ID, SOURCE_PATH))
        assertTrue(fixture.remote.calls.last() == "release")
    }

    @Test
    fun `concurrent review edits conflict and MergeResult Conflicted blocks upload`() = runBlocking {
        val fixture = fixture().apply {
            val baseBytes = ReviewJson.encode(baseReview).encodeToByteArray()
            remote.put(MANIFEST_PATH, BookManifest.encode(manifest).encodeToByteArray())
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            remote.put(REVIEW_PATH, ReviewJson.encode(remoteReview.copy(chapterNote = "Yandex")).encodeToByteArray())
            bases.write(BOOK_ID, REVIEW_PATH, baseBytes, "old-review")
            metadata.bases[REVIEW_PATH] = MergeBaseEntity(BOOK_ID, REVIEW_PATH, sha(baseBytes), "old-review")
            metadata.pending += outbox(REVIEW_PATH, localReview.copy(chapterNote = "Mine"), sha(baseBytes))
            cache.reviews[REVIEW_PATH] = localReview.copy(chapterNote = "Mine")
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertTrue(status is SyncStatus.ActionRequired)
        assertTrue(fixture.remote.uploads.isEmpty())
        assertEquals(1, fixture.conflicts.conflicts(BOOK_ID).first().size)
        assertEquals(1, fixture.metadata.pending.size)
    }

    @Test
    fun `resolved review choices rebase on Yandex version before guarded upload`() = runBlocking {
        val fixture = fixture().apply {
            val baseBytes = ReviewJson.encode(baseReview).encodeToByteArray()
            val mine = localReview.copy(chapterNote = "Mine")
            val yandex = remoteReview.copy(chapterNote = "Yandex")
            cache.reviews[REVIEW_PATH] = mine
            remote.put(MANIFEST_PATH, BookManifest.encode(manifest).encodeToByteArray())
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            remote.put(REVIEW_PATH, ReviewJson.encode(yandex).encodeToByteArray())
            bases.write(BOOK_ID, REVIEW_PATH, baseBytes, "old-review")
            metadata.bases[REVIEW_PATH] = MergeBaseEntity(BOOK_ID, REVIEW_PATH, sha(baseBytes), "old-review")
            metadata.pending += outbox(REVIEW_PATH, mine, sha(baseBytes))
        }
        fixture.engine.syncBook(BOOK_ID, ROOT)

        fixture.engine.resolveReviewConflict(
            BOOK_ID,
            REVIEW_PATH,
            mapOf("chapter-note" to ConflictChoice.KEEP_MINE),
        )
        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertEquals(SyncStatus.Saved, status)
        assertEquals("Mine", ReviewJson.decode(fixture.remote.bytes(REVIEW_PATH).decodeToString(), CHAPTER_ID, SOURCE_PATH).chapterNote)
    }

    @Test
    fun `manifest Keep mine rebases whole file before guarded upload`() = runBlocking {
        val fixture = fixture().apply {
            val base = manifest.copy(title = "Base")
            val mine = manifest.copy(title = "Mine")
            val yandex = manifest.copy(title = "Yandex")
            val baseBytes = BookManifest.encode(base).encodeToByteArray()
            cache.manifest = mine
            remote.put(MANIFEST_PATH, BookManifest.encode(yandex).encodeToByteArray())
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            bases.write(BOOK_ID, MANIFEST_PATH, baseBytes, "old-manifest")
            metadata.bases[MANIFEST_PATH] = MergeBaseEntity(BOOK_ID, MANIFEST_PATH, sha(baseBytes), "old-manifest")
            metadata.pending += OutboxEntity(
                BOOK_ID,
                MANIFEST_PATH,
                sha(BookManifest.encode(mine).encodeToByteArray()),
                sha(baseBytes),
                OutboxState.PENDING,
            )
        }
        fixture.engine.syncBook(BOOK_ID, ROOT)

        fixture.engine.resolveManifestConflict(BOOK_ID, ConflictChoice.KEEP_MINE)
        fixture.engine.syncBook(BOOK_ID, ROOT)

        assertEquals("Mine", BookManifest.decode(fixture.remote.bytes(MANIFEST_PATH).decodeToString()).title)
    }

    @Test
    fun `one locked pass uploads local manifest then newly registered review and drains outbox`() = runBlocking {
        val fixture = fixture().apply {
            val newChapterId = UUID.randomUUID().toString()
            val newPath = "chapter2.md"
            val remoteManifest = manifest
            val localManifest = manifest.copy(
                chapters = manifest.chapters + ChapterEntry(newChapterId, newPath, "Chapter 2"),
            )
            val manifestBase = BookManifest.encode(remoteManifest).encodeToByteArray()
            val newReview = ReviewDocument(chapterId = newChapterId, sourcePath = newPath, chapterNote = "New")
            cache.manifest = localManifest
            cache.reviews["$newPath.review.json"] = newReview
            remote.put(MANIFEST_PATH, manifestBase)
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            remote.put(newPath, "source 2".encodeToByteArray())
            bases.write(BOOK_ID, MANIFEST_PATH, manifestBase, remote.revision(MANIFEST_PATH))
            metadata.bases[MANIFEST_PATH] = MergeBaseEntity(
                BOOK_ID, MANIFEST_PATH, sha(manifestBase), remote.revision(MANIFEST_PATH),
            )
            metadata.pending += OutboxEntity(
                BOOK_ID,
                MANIFEST_PATH,
                sha(BookManifest.encode(localManifest).encodeToByteArray()),
                sha(manifestBase),
                OutboxState.PENDING,
            )
            metadata.pending += outbox("$newPath.review.json", newReview)
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertEquals(SyncStatus.Saved, status)
        assertEquals(listOf(MANIFEST_PATH, "chapter2.md.review.json"), fixture.remote.uploads)
        assertTrue(fixture.metadata.pending.isEmpty())
    }

    @Test
    fun `concurrent renamed different ID manifest defers incompatible remote reviews`() = runBlocking {
        val fixture = fixture().apply {
            val baseBytes = BookManifest.encode(manifest).encodeToByteArray()
            val local = manifest.copy(title = "Local title")
            val remoteChapterId = UUID.randomUUID().toString()
            val remotePath = "renamed.md"
            val yandex = manifest.copy(
                title = "Yandex title",
                chapters = listOf(ChapterEntry(remoteChapterId, remotePath, "Renamed")),
            )
            cache.manifest = local
            remote.put(MANIFEST_PATH, BookManifest.encode(yandex).encodeToByteArray())
            remote.put(remotePath, "renamed source".encodeToByteArray())
            remote.put(
                "$remotePath.review.json",
                ReviewJson.encode(ReviewDocument(chapterId = remoteChapterId, sourcePath = remotePath)).encodeToByteArray(),
            )
            bases.write(BOOK_ID, MANIFEST_PATH, baseBytes, "old-manifest")
            metadata.bases[MANIFEST_PATH] = MergeBaseEntity(BOOK_ID, MANIFEST_PATH, sha(baseBytes), "old-manifest")
            metadata.pending += OutboxEntity(
                BOOK_ID,
                MANIFEST_PATH,
                sha(BookManifest.encode(local).encodeToByteArray()),
                sha(baseBytes),
                OutboxState.PENDING,
            )
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertTrue(status is SyncStatus.ActionRequired)
        assertEquals("Local title", fixture.cache.manifest.title)
        assertEquals(fixture.localReview, fixture.cache.reviews[REVIEW_PATH])
        assertTrue(fixture.cache.reviewWrites.isEmpty())
        assertTrue(fixture.remote.uploads.isEmpty())
    }

    @Test
    fun `different concurrent record IDs auto merge and upload combined review`() = runBlocking {
        val fixture = fixture().apply {
            val baseBytes = ReviewJson.encode(baseReview).encodeToByteArray()
            val mine = baseReview.copy(signals = listOf(signal(UUID.randomUUID().toString(), "Mine")))
            val yandex = baseReview.copy(signals = listOf(signal(UUID.randomUUID().toString(), "Yandex")))
            cache.reviews[REVIEW_PATH] = mine
            remote.put(MANIFEST_PATH, BookManifest.encode(manifest).encodeToByteArray())
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            remote.put(REVIEW_PATH, ReviewJson.encode(yandex).encodeToByteArray())
            bases.write(BOOK_ID, REVIEW_PATH, baseBytes, "old-review")
            metadata.bases[REVIEW_PATH] = MergeBaseEntity(BOOK_ID, REVIEW_PATH, sha(baseBytes), "old-review")
            metadata.pending += outbox(REVIEW_PATH, mine, sha(baseBytes))
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertEquals(SyncStatus.Saved, status)
        val merged = ReviewJson.decode(fixture.remote.bytes(REVIEW_PATH).decodeToString(), CHAPTER_ID, SOURCE_PATH)
        assertEquals(setOf("Mine", "Yandex"), merged.signals.map { it.comment }.toSet())
        assertTrue(fixture.metadata.pending.isEmpty())
    }

    @Test
    fun `missing or mismatched durable base blocks upload`() = runBlocking {
        val fixture = fixture().apply {
            val remoteBytes = ReviewJson.encode(baseReview).encodeToByteArray()
            remote.put(MANIFEST_PATH, BookManifest.encode(manifest).encodeToByteArray())
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            remote.put(REVIEW_PATH, remoteBytes)
            metadata.bases[REVIEW_PATH] = MergeBaseEntity(BOOK_ID, REVIEW_PATH, sha(remoteBytes), "old")
            metadata.pending += outbox(REVIEW_PATH, localReview, sha(remoteBytes))
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertTrue(status is SyncStatus.ActionRequired)
        assertTrue(fixture.remote.uploads.isEmpty())
        assertEquals(1, fixture.metadata.pending.size)
    }

    @Test
    fun `unsupported base directory durability keeps outbox and does not confirm Room base`() = runBlocking {
        val fixture = fixture().apply {
            val baseBytes = ReviewJson.encode(baseReview).encodeToByteArray()
            remote.put(MANIFEST_PATH, BookManifest.encode(manifest).encodeToByteArray())
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            remote.put(REVIEW_PATH, baseBytes)
            bases.write(BOOK_ID, REVIEW_PATH, baseBytes, remote.revision(REVIEW_PATH))
            metadata.bases[REVIEW_PATH] = MergeBaseEntity(BOOK_ID, REVIEW_PATH, sha(baseBytes), remote.revision(REVIEW_PATH))
            metadata.pending += outbox(REVIEW_PATH, localReview, sha(baseBytes))
            bases.directorySyncStatus = DirectorySyncStatus.UNSUPPORTED
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertTrue(status is SyncStatus.ActionRequired)
        assertTrue(fixture.metadata.pending.isNotEmpty())
        assertEquals(sha(ReviewJson.encode(fixture.baseReview).encodeToByteArray()), fixture.metadata.bases[REVIEW_PATH]?.sha256)
    }

    @Test
    fun `invalid remote metadata preserves last valid cache and never uploads`() = runBlocking {
        val fixture = fixture().apply {
            remote.put(MANIFEST_PATH, "{ invalid".encodeToByteArray())
            metadata.pending += outbox(REVIEW_PATH, localReview)
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertTrue(status is SyncStatus.ActionRequired)
        assertEquals(fixture.manifest, fixture.cache.manifest)
        assertEquals(fixture.localReview, fixture.cache.reviews[REVIEW_PATH])
        assertTrue(fixture.remote.uploads.isEmpty())
    }

    @Test
    fun `held and lost locks require action and retain outbox`() = runBlocking {
        val held = fixture().apply {
            metadata.pending += outbox(REVIEW_PATH, localReview)
            remote.heldLock = lock("other")
        }
        assertTrue(held.engine.syncBook(BOOK_ID, ROOT) is SyncStatus.ActionRequired)
        assertTrue(held.remote.uploads.isEmpty())

        val lost = fixture().apply {
            val baseBytes = ReviewJson.encode(baseReview).encodeToByteArray()
            remote.put(MANIFEST_PATH, BookManifest.encode(manifest).encodeToByteArray())
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            remote.put(REVIEW_PATH, baseBytes)
            bases.write(BOOK_ID, REVIEW_PATH, baseBytes, remote.revision(REVIEW_PATH))
            metadata.bases[REVIEW_PATH] = MergeBaseEntity(BOOK_ID, REVIEW_PATH, sha(baseBytes), remote.revision(REVIEW_PATH))
            metadata.pending += outbox(REVIEW_PATH, localReview, sha(baseBytes))
            remote.loseOnUpload = true
        }
        assertTrue(lost.engine.syncBook(BOOK_ID, ROOT) is SyncStatus.ActionRequired)
        assertEquals(1, lost.metadata.pending.size)
    }

    @Test
    fun `confirmed break rechecks observed lock then reacquires and fully refreshes before upload`() = runBlocking {
        val fixture = fixture().apply {
            val baseBytes = ReviewJson.encode(baseReview).encodeToByteArray()
            remote.put(MANIFEST_PATH, BookManifest.encode(manifest).encodeToByteArray())
            remote.put(SOURCE_PATH, "source".encodeToByteArray())
            remote.put(REVIEW_PATH, baseBytes)
            bases.write(BOOK_ID, REVIEW_PATH, baseBytes, remote.revision(REVIEW_PATH))
            metadata.bases[REVIEW_PATH] = MergeBaseEntity(BOOK_ID, REVIEW_PATH, sha(baseBytes), remote.revision(REVIEW_PATH))
            metadata.pending += outbox(REVIEW_PATH, localReview, sha(baseBytes))
            remote.heldLock = lock("stale")
        }
        val observed = fixture.remote.heldLock!!

        fixture.engine.syncBook(BOOK_ID, ROOT, breakObservedLock = observed)

        val calls = fixture.remote.calls
        assertTrue(calls.indexOf("break") < calls.indexOf("acquire"))
        assertTrue(calls.indexOf("list") < calls.indexOf("upload:$REVIEW_PATH"))
        assertTrue(calls.indexOf("download:$SOURCE_PATH") < calls.indexOf("upload:$REVIEW_PATH"))
    }

    @Test
    fun `revoked token keeps cache and asks for sign in`() = runBlocking {
        val fixture = fixture().apply { remote.failure = YandexDiskError.Unauthorized() }
        val status = fixture.engine.syncBook(BOOK_ID, ROOT)
        assertEquals(SyncStatus.SignInRequired, status)
        assertEquals(fixture.localReview, fixture.cache.reviews[REVIEW_PATH])
    }

    @Test
    fun `cancellation after acquisition releases owned lock non cancellably and preserves cause`() = runBlocking {
        val fixture = fixture().apply {
            remote.listEntered = CompletableDeferred()
            remote.suspendListing = true
        }
        val original = CancellationException("stop sync")
        val syncing = async { fixture.engine.syncBook(BOOK_ID, ROOT) }
        fixture.remote.listEntered!!.await()

        syncing.cancel(original)
        val thrown = org.junit.jupiter.api.Assertions.assertThrows(CancellationException::class.java) {
            runBlocking { syncing.await() }
        }

        assertEquals("stop sync", thrown.message)
        assertTrue(fixture.remote.releaseWasActive)
        assertTrue("release" in fixture.remote.calls)
    }

    private fun fixture(withLocalReview: Boolean = true): Fixture {
        val manifest = BookManifest(1, BOOK_ID, "Book", listOf(ChapterEntry(CHAPTER_ID, SOURCE_PATH, "Chapter")))
        val base = ReviewDocument(chapterId = CHAPTER_ID, sourcePath = SOURCE_PATH, chapterNote = "Base")
        val local = base.copy(chapterNote = "Local")
        val remoteReview = base.copy(chapterNote = "Remote")
        val cache = FakeCache(manifest).apply { if (withLocalReview) reviews[REVIEW_PATH] = local }
        val remote = FakeGateway(ROOT)
        val metadata = FakeMetadataStore()
        val bases = MemoryBaseStore()
        val conflicts = InMemoryConflictRepository()
        val engine = SyncEngine(remote, cache, cache, metadata, bases, conflicts, "device", { lock("device") })
        return Fixture(engine, cache, remote, metadata, bases, conflicts, manifest, base, local, remoteReview)
    }

    private data class Fixture(
        val engine: SyncEngine,
        val cache: FakeCache,
        val remote: FakeGateway,
        val metadata: FakeMetadataStore,
        val bases: MemoryBaseStore,
        val conflicts: InMemoryConflictRepository,
        val manifest: BookManifest,
        val baseReview: ReviewDocument,
        val localReview: ReviewDocument,
        val remoteReview: ReviewDocument,
    )

    private class FakeCache(var manifest: BookManifest) : BookStore, SourceCache {
        val sources = mutableMapOf<String, ByteArray>()
        val reviews = mutableMapOf<String, ReviewDocument>()
        val sourceCacheWrites = mutableListOf<String>()
        val reviewWrites = mutableListOf<String>()
        override suspend fun readSource(bookId: String, path: String) = sources.getValue(path)
        override suspend fun readManifest(bookId: String) = manifest
        override suspend fun writeManifest(bookId: String, value: BookManifest): LocalRevision {
            manifest = value
            return revision(MANIFEST_PATH, BookManifest.encode(value).encodeToByteArray())
        }
        override suspend fun readReview(bookId: String, path: String) = reviews[path]
        override suspend fun writeReview(bookId: String, path: String, value: ReviewDocument): LocalRevision {
            reviewWrites += path
            reviews[path] = value
            return revision(path, ReviewJson.encode(value).encodeToByteArray())
        }
        override suspend fun replaceDownloadedSource(bookId: String, path: String, bytes: ByteArray): LocalRevision {
            sourceCacheWrites += path
            sources[path] = bytes.copyOf()
            return revision(path, bytes)
        }
        private fun revision(path: String, bytes: ByteArray) =
            LocalRevision(path, sha(bytes), bytes.size.toLong(), DirectorySyncStatus.SYNCED)
    }

    private class FakeMetadataStore : SyncMetadataStore {
        val pending = mutableListOf<OutboxEntity>()
        val bases = mutableMapOf<String, MergeBaseEntity>()
        val revisions = mutableMapOf<String, RemoteRevisionEntity>()
        override suspend fun outbox(bookId: String) = pending.filter { it.bookId == bookId }
        override suspend fun mergeBase(bookId: String, path: String) = bases[path]
        override suspend fun recordRemote(value: RemoteRevisionEntity) { revisions[value.path] = value }
        override suspend fun recordBase(value: MergeBaseEntity) { bases[value.path] = value }
        override suspend fun recordOutbox(value: OutboxEntity) { pending.removeAll { it.path == value.path }; pending += value }
        override suspend fun removeOutbox(bookId: String, path: String) { pending.removeAll { it.path == path } }
    }

    private class MemoryBaseStore : SyncBaseStore {
        val values = mutableMapOf<String, SyncBase>()
        var directorySyncStatus = DirectorySyncStatus.SYNCED
        override fun read(bookId: String, path: String) = values[path]
        override fun write(bookId: String, path: String, bytes: ByteArray, remoteRevision: String): SyncBase =
            SyncBase(bytes.copyOf(), sha(bytes), remoteRevision, directorySyncStatus).also { values[path] = it }
        override fun delete(bookId: String, path: String) { values.remove(path) }
    }

    private class FakeGateway(private val root: String) : YandexDiskGateway {
        private val files = linkedMapOf<String, RemoteFile>()
        val calls = mutableListOf<String>()
        val uploads = mutableListOf<String>()
        var failure: YandexDiskError? = null
        var heldLock: SyncLock? = null
        var ownedLock: SyncLock? = null
        var loseOnUpload = false
        var listEntered: CompletableDeferred<Unit>? = null
        var suspendListing = false
        var releaseWasActive = false
        fun put(path: String, bytes: ByteArray) {
            val full = "$root/$path"
            files[full] = RemoteFile(full, bytes.copyOf(), "r-${files.size + 1}")
        }
        fun bytes(path: String) = files.getValue("$root/$path").bytes
        fun revision(path: String) = files.getValue("$root/$path").revision
        override suspend fun listFolder(path: String): List<RemoteEntry> {
            calls += "list"; failure?.let { throw it }
            listEntered?.complete(Unit)
            if (suspendListing) awaitCancellation()
            return files.values.map { RemoteEntry(it.path.substringAfterLast('/'), it.path, "file", it.bytes.size.toLong(), it.revision) }
        }
        override suspend fun download(path: String): RemoteFile {
            calls += "download:${path.substringAfterLast('/')}"; failure?.let { throw it }
            return files[path] ?: throw YandexDiskError.NotFound()
        }
        override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock {
            calls += "acquire"; failure?.let { throw it }
            if (heldLock != null) throw YandexDiskError.LockHeld()
            ownedLock = lock
            return lock
        }
        override suspend fun readLock(rootPath: String): SyncLock = heldLock ?: ownedLock ?: throw YandexDiskError.NotFound()
        override suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock): String {
            calls += "upload:$relativePath"
            if (loseOnUpload) { this.ownedLock = null; throw YandexDiskError.LockLost() }
            check(this.ownedLock?.lockId == ownedLock.lockId)
            uploads += relativePath
            put(relativePath, bytes)
            return revision(relativePath)
        }
        override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) {
            calls += "release"
            releaseWasActive = currentCoroutineContext().isActive
            if (this.ownedLock?.lockId != ownedLock.lockId) throw YandexDiskError.LockLost()
            this.ownedLock = null
        }
        override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) {
            calls += "break"
            if (heldLock?.lockId != observedLock.lockId) throw YandexDiskError.LockLost()
            heldLock = null
        }
    }

    private fun outbox(path: String, review: ReviewDocument, baseSha: String? = null) = OutboxEntity(
        BOOK_ID, path, sha(ReviewJson.encode(review).encodeToByteArray()), baseSha, OutboxState.PENDING,
    )

    private fun signal(id: String, comment: String) = Signal(
        id = id,
        type = SignalType.NOTE,
        selectedText = "x",
        anchor = Anchor(HASH, HASH, 0, 1, 1, 1, "", ""),
        comment = comment,
    )

    companion object {
        private const val ROOT = "disk:/Book"
        private const val MANIFEST_PATH = ".pocket-editor.json"
        private const val SOURCE_PATH = "chapter.md"
        private const val REVIEW_PATH = "chapter.md.review.json"
        private val BOOK_ID = UUID.randomUUID().toString()
        private val CHAPTER_ID = UUID.randomUUID().toString()
        private const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        private fun lock(holder: String) = SyncLock(1, UUID.randomUUID().toString(), holder, Instant.parse("2026-07-19T10:00:00Z"))
    }
}
