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
    fun `unconfirmed accepted lock cleanup is action required even when both causes are offline`() = runBlocking {
        val candidate = lock("device")
        val verification = YandexDiskError.Offline(IOException("verification offline"))
        val cleanup = YandexDiskError.Offline(IOException("cleanup offline"))
        val fixture = fixture().apply {
            remote.failure = YandexDiskError.CandidateCleanupUnconfirmed(candidate, verification, cleanup)
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertTrue(status is SyncStatus.ActionRequired)
        status as SyncStatus.ActionRequired
        assertEquals(candidate, status.lock)
        assertTrue(status.reason.contains(candidate.lockId))
        assertTrue(status.reason.contains("verification offline"))
        assertTrue(status.reason.contains("cleanup offline"))
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
    fun `review resolution preserves conflict and Room state when base directory sync is unsupported`() = runBlocking {
        val fixture = reviewConflictFixture()
        val previousBase = fixture.metadata.bases[REVIEW_PATH]
        val previousRevision = fixture.metadata.revisions[REVIEW_PATH]
        val previousOutbox = fixture.metadata.pending.toList()
        val previousReview = fixture.cache.reviews[REVIEW_PATH]
        fixture.bases.directorySyncStatus = DirectorySyncStatus.UNSUPPORTED

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                fixture.engine.resolveReviewConflict(
                    BOOK_ID,
                    REVIEW_PATH,
                    mapOf("chapter-note" to ConflictChoice.KEEP_MINE),
                )
            }
        }

        assertEquals(1, fixture.conflicts.conflicts(BOOK_ID).first().size)
        assertEquals(previousBase, fixture.metadata.bases[REVIEW_PATH])
        assertEquals(previousRevision, fixture.metadata.revisions[REVIEW_PATH])
        assertEquals(previousOutbox, fixture.metadata.pending)
        assertEquals(previousReview, fixture.cache.reviews[REVIEW_PATH])
    }

    @Test
    fun `review resolution commits conflict last and every failed step is retryable`() = runBlocking {
        val cases = listOf(
            ResolutionFailure.RECORD_BASE to ConflictChoice.KEEP_MINE,
            ResolutionFailure.RECORD_REMOTE to ConflictChoice.KEEP_MINE,
            ResolutionFailure.LOCAL_REVIEW to ConflictChoice.KEEP_MINE,
            ResolutionFailure.RECORD_OUTBOX to ConflictChoice.KEEP_MINE,
            ResolutionFailure.REMOVE_OUTBOX to ConflictChoice.KEEP_YANDEX,
        )

        cases.forEach { (failure, choice) ->
            val fixture = reviewConflictFixture().apply {
                metadata.failure = failure
                cache.failure = failure
            }

            org.junit.jupiter.api.Assertions.assertThrows(IOException::class.java) {
                runBlocking {
                    fixture.engine.resolveReviewConflict(
                        BOOK_ID,
                        REVIEW_PATH,
                        mapOf("chapter-note" to choice),
                    )
                }
            }
            assertEquals(1, fixture.conflicts.conflicts(BOOK_ID).first().size, failure.name)

            fixture.metadata.failure = null
            fixture.cache.failure = null
            fixture.engine.resolveReviewConflict(
                BOOK_ID,
                REVIEW_PATH,
                mapOf("chapter-note" to choice),
            )

            assertTrue(fixture.conflicts.conflicts(BOOK_ID).first().isEmpty(), failure.name)
            assertEquals(fixture.bases.values[REVIEW_PATH]?.sha256, fixture.metadata.bases[REVIEW_PATH]?.sha256)
            assertEquals(SyncStatus.Saved, fixture.engine.syncBook(BOOK_ID, ROOT), failure.name)
            assertTrue(fixture.conflicts.conflicts(BOOK_ID).first().none { it is SyncConflict.MissingBase })
        }
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
    fun `manifest resolution preserves conflict and Room state when base write fails`() = runBlocking {
        val fixture = manifestConflictFixture()
        val previousBase = fixture.metadata.bases[MANIFEST_PATH]
        val previousRevision = fixture.metadata.revisions[MANIFEST_PATH]
        val previousOutbox = fixture.metadata.pending.toList()
        val previousManifest = fixture.cache.manifest
        fixture.bases.writeFailure = IOException("directory fsync failed")

        org.junit.jupiter.api.Assertions.assertThrows(IOException::class.java) {
            runBlocking { fixture.engine.resolveManifestConflict(BOOK_ID, ConflictChoice.KEEP_MINE) }
        }

        assertEquals(1, fixture.conflicts.conflicts(BOOK_ID).first().size)
        assertEquals(previousBase, fixture.metadata.bases[MANIFEST_PATH])
        assertEquals(previousRevision, fixture.metadata.revisions[MANIFEST_PATH])
        assertEquals(previousOutbox, fixture.metadata.pending)
        assertEquals(previousManifest, fixture.cache.manifest)
    }

    @Test
    fun `manifest resolution commits conflict last and every failed step is retryable`() = runBlocking {
        val cases = listOf(
            ResolutionFailure.RECORD_BASE to ConflictChoice.KEEP_MINE,
            ResolutionFailure.RECORD_REMOTE to ConflictChoice.KEEP_MINE,
            ResolutionFailure.LOCAL_MANIFEST to ConflictChoice.KEEP_MINE,
            ResolutionFailure.RECORD_OUTBOX to ConflictChoice.KEEP_MINE,
            ResolutionFailure.REMOVE_OUTBOX to ConflictChoice.KEEP_YANDEX,
        )

        cases.forEach { (failure, choice) ->
            val fixture = manifestConflictFixture().apply {
                metadata.failure = failure
                cache.failure = failure
            }

            org.junit.jupiter.api.Assertions.assertThrows(IOException::class.java) {
                runBlocking { fixture.engine.resolveManifestConflict(BOOK_ID, choice) }
            }
            assertEquals(1, fixture.conflicts.conflicts(BOOK_ID).first().size, failure.name)

            fixture.metadata.failure = null
            fixture.cache.failure = null
            fixture.engine.resolveManifestConflict(BOOK_ID, choice)

            assertTrue(fixture.conflicts.conflicts(BOOK_ID).first().isEmpty(), failure.name)
            assertEquals(fixture.bases.values[MANIFEST_PATH]?.sha256, fixture.metadata.bases[MANIFEST_PATH]?.sha256)
            assertEquals(SyncStatus.Saved, fixture.engine.syncBook(BOOK_ID, ROOT), failure.name)
            assertTrue(fixture.conflicts.conflicts(BOOK_ID).first().none { it is SyncConflict.MissingBase })
        }
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

    @Test
    fun `cancellation keeps its cause and suppresses lock release failure`() = runBlocking {
        val original = CancellationException("stop sync")
        val fixture = fixture().apply {
            remote.listCancellation = original
            remote.releaseFailure = YandexDiskError.ServerFailure(503)
        }

        val thrown = org.junit.jupiter.api.Assertions.assertThrows(CancellationException::class.java) {
            runBlocking { fixture.engine.syncBook(BOOK_ID, ROOT) }
        }

        assertEquals("stop sync", thrown.message)
        assertTrue(thrown.suppressed.any { it.message?.contains("503") == true })
        assertTrue(fixture.remote.releaseWasActive)
    }

    @Test
    fun `offline refresh with release failure escalates with owned lock and both errors`() = runBlocking {
        val fixture = fixture().apply {
            remote.listFailure = YandexDiskError.Offline(IOException("refresh offline"))
            remote.releaseFailure = YandexDiskError.ServerFailure(503)
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertTrue(status is SyncStatus.ActionRequired)
        status as SyncStatus.ActionRequired
        assertEquals(fixture.remote.lastAcquiredLock, status.lock)
        assertTrue(status.reason.contains("refresh offline"))
        assertTrue(status.reason.contains("503"))
    }

    @Test
    fun `existing action required result still exposes release failure`() = runBlocking {
        val fixture = fixture().apply {
            remote.put(MANIFEST_PATH, "{ invalid".encodeToByteArray())
            remote.releaseFailure = YandexDiskError.ServerFailure(503)
        }

        val status = fixture.engine.syncBook(BOOK_ID, ROOT)

        assertTrue(status is SyncStatus.ActionRequired)
        status as SyncStatus.ActionRequired
        assertEquals(fixture.remote.lastAcquiredLock, status.lock)
        assertTrue(status.reason.contains("503"))
        assertTrue(status.reason.contains("Expected") || status.reason.contains("invalid"))
    }

    private suspend fun reviewConflictFixture(): Fixture = fixture().apply {
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
        engine.syncBook(BOOK_ID, ROOT)
    }

    private suspend fun manifestConflictFixture(): Fixture = fixture().apply {
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
        engine.syncBook(BOOK_ID, ROOT)
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
        var failure: ResolutionFailure? = null
        override suspend fun readSource(bookId: String, path: String) = sources.getValue(path)
        override suspend fun readManifest(bookId: String) = manifest
        override suspend fun writeManifest(bookId: String, value: BookManifest): LocalRevision {
            if (failure == ResolutionFailure.LOCAL_MANIFEST) throw IOException("LOCAL_MANIFEST")
            manifest = value
            return revision(MANIFEST_PATH, BookManifest.encode(value).encodeToByteArray())
        }
        override suspend fun readReview(bookId: String, path: String) = reviews[path]
        override suspend fun writeReview(bookId: String, path: String, value: ReviewDocument): LocalRevision {
            if (failure == ResolutionFailure.LOCAL_REVIEW) throw IOException("LOCAL_REVIEW")
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
        var failure: ResolutionFailure? = null
        override suspend fun outbox(bookId: String) = pending.filter { it.bookId == bookId }
        override suspend fun mergeBase(bookId: String, path: String) = bases[path]
        override suspend fun recordRemote(value: RemoteRevisionEntity) {
            if (failure == ResolutionFailure.RECORD_REMOTE) throw IOException("RECORD_REMOTE")
            revisions[value.path] = value
        }
        override suspend fun recordBase(value: MergeBaseEntity) {
            if (failure == ResolutionFailure.RECORD_BASE) throw IOException("RECORD_BASE")
            bases[value.path] = value
        }
        override suspend fun recordOutbox(value: OutboxEntity) {
            if (failure == ResolutionFailure.RECORD_OUTBOX) throw IOException("RECORD_OUTBOX")
            pending.removeAll { it.path == value.path }; pending += value
        }
        override suspend fun removeOutbox(bookId: String, path: String) {
            if (failure == ResolutionFailure.REMOVE_OUTBOX) throw IOException("REMOVE_OUTBOX")
            pending.removeAll { it.path == path }
        }
    }

    private class MemoryBaseStore : SyncBaseStore {
        val values = mutableMapOf<String, SyncBase>()
        var directorySyncStatus = DirectorySyncStatus.SYNCED
        var writeFailure: Throwable? = null
        override fun read(bookId: String, path: String) = values[path]
        override fun write(bookId: String, path: String, bytes: ByteArray, remoteRevision: String): SyncBase {
            writeFailure?.let { throw it }
            return SyncBase(bytes.copyOf(), sha(bytes), remoteRevision, directorySyncStatus).also { values[path] = it }
        }
        override fun delete(bookId: String, path: String) { values.remove(path) }
    }

    private class FakeGateway(private val root: String) : YandexDiskGateway {
        private val files = linkedMapOf<String, RemoteFile>()
        val calls = mutableListOf<String>()
        val uploads = mutableListOf<String>()
        var failure: YandexDiskError? = null
        var listFailure: YandexDiskError? = null
        var releaseFailure: YandexDiskError? = null
        var listCancellation: CancellationException? = null
        var heldLock: SyncLock? = null
        var ownedLock: SyncLock? = null
        var lastAcquiredLock: SyncLock? = null
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
            calls += "list"; listCancellation?.let { throw it }; listFailure?.let { throw it }; failure?.let { throw it }
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
            lastAcquiredLock = lock
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
            releaseFailure?.let { throw it }
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
        private enum class ResolutionFailure {
            RECORD_BASE,
            RECORD_REMOTE,
            LOCAL_REVIEW,
            LOCAL_MANIFEST,
            RECORD_OUTBOX,
            REMOVE_OUTBOX,
        }
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
