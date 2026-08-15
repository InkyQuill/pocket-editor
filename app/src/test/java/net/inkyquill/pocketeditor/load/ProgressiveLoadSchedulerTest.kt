package net.inkyquill.pocketeditor.load

import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadJobEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProgressiveLoadSchedulerTest {
    @Test
    fun `queue failure keeps current generation and claim valid`() {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 4), claimedFile(generation = 4))
        val queue = RecordingProgressiveLoadWorkQueue(failure = IOException("queue unavailable"))

        assertThrows(IOException::class.java) {
            runBlocking { ProgressiveLoadScheduler(queue, store).replaceNow(BOOK_ID) }
        }

        assertEquals(4, store.job.generation)
        assertEquals(ProgressiveLoadFileState.DOWNLOADING, store.file.state)
        assertEquals(4, store.file.claimGeneration)
    }

    @Test
    fun `accepted priority request publishes only after enqueue and restores old claim`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(
            job(generation = 4),
            claimedFile(generation = 4).copy(priority = ON_DEMAND_PRIORITY),
        )
        val queue = RecordingProgressiveLoadWorkQueue(
            beforeEnqueue = {
                assertEquals(4, store.job.generation)
                assertEquals(ProgressiveLoadFileState.DOWNLOADING, store.file.state)
                assertEquals(ON_DEMAND_PRIORITY, store.file.priority)
            },
        )

        ProgressiveLoadScheduler(queue, store).replaceNow(BOOK_ID)

        assertEquals(listOf(5L), queue.requests.map(ProgressiveLoadWorkRequest::generation))
        assertEquals(5, store.job.generation)
        assertEquals(ProgressiveLoadFileState.PENDING, store.file.state)
        assertNull(store.file.claimGeneration)
        assertEquals(ON_DEMAND_PRIORITY, store.file.priority)
    }

    @Test
    fun `pause publishes stop state before cancellation and retains cached rows`() = runTest {
        val cached = pendingFile(path = "cached.md", spineIndex = 0).copy(
            state = ProgressiveLoadFileState.CACHED,
            sha256 = "abc123",
        )
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 4), cached, claimedFile(generation = 4))
        val queue = RecordingProgressiveLoadWorkQueue(
            beforeCancel = {
                assertEquals(5, store.job.generation)
                assertEquals(ProgressiveLoadPhase.PAUSED, store.job.phase)
                assertEquals(ProgressiveLoadFileState.PENDING, store.files[1].state)
                assertNull(store.files[1].claimGeneration)
            },
        )

        ProgressiveLoadScheduler(queue, store).pause(BOOK_ID)

        assertEquals(listOf("progressive-load-$BOOK_ID"), queue.cancellations)
        assertEquals(ProgressiveLoadFileState.CACHED, store.files[0].state)
        assertEquals("abc123", store.files[0].sha256)
        assertEquals(emptyList<ProgressiveLoadWorkRequest>(), queue.requests)
    }

    @Test
    fun `cancel publishes stop state before cancellation and retains cached rows`() = runTest {
        val cached = pendingFile(path = "cached.md", spineIndex = 0).copy(
            state = ProgressiveLoadFileState.CACHED,
            sha256 = "abc123",
        )
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 4), cached, claimedFile(generation = 4))
        val queue = RecordingProgressiveLoadWorkQueue(
            beforeCancel = {
                assertEquals(5, store.job.generation)
                assertEquals(ProgressiveLoadPhase.CANCELLED, store.job.phase)
                assertEquals(ProgressiveLoadFileState.PENDING, store.files[1].state)
                assertNull(store.files[1].claimGeneration)
            },
        )

        ProgressiveLoadScheduler(queue, store).cancel(BOOK_ID)

        assertEquals(listOf("progressive-load-$BOOK_ID"), queue.cancellations)
        assertEquals(ProgressiveLoadFileState.CACHED, store.files[0].state)
        assertEquals("abc123", store.files[0].sha256)
        assertEquals(emptyList<ProgressiveLoadWorkRequest>(), queue.requests)
    }

    @Test
    fun `continue uses enqueue-first replacement and progressive unique name`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(
            job(generation = 11).copy(paused = true, phase = ProgressiveLoadPhase.PAUSED),
            claimedFile(generation = 11),
        )
        val queue = RecordingProgressiveLoadWorkQueue(
            beforeEnqueue = { assertEquals(11, store.job.generation) },
        )

        ProgressiveLoadScheduler(queue, store).continueLoad(BOOK_ID)

        assertEquals(
            listOf(ProgressiveLoadWorkRequest("progressive-load-$BOOK_ID", BOOK_ID, 12, Duration.ZERO)),
            queue.requests,
        )
        assertEquals(12, store.job.generation)
        assertEquals(false, store.job.paused)
    }

    @Test
    fun `delayed older replacement cannot overwrite the published current request`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 4), pendingFile())
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val queue = GatedFirstProgressiveLoadWorkQueue(firstEntered, releaseFirst)
        val scheduler = ProgressiveLoadScheduler(queue, store)
        val first = async { scheduler.replaceNow(BOOK_ID) }
        firstEntered.await()
        val second = async { scheduler.replaceNow(BOOK_ID) }
        testScheduler.runCurrent()

        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf(5L, 6L), queue.requests.map(ProgressiveLoadWorkRequest::generation))
        assertEquals(6, store.job.generation)
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
    }
}

private class GatedFirstProgressiveLoadWorkQueue(
    private val firstEntered: CompletableDeferred<Unit>,
    private val releaseFirst: CompletableDeferred<Unit>,
) : ProgressiveLoadWorkQueue {
    val requests = mutableListOf<ProgressiveLoadWorkRequest>()
    private var calls = 0

    override suspend fun enqueue(request: ProgressiveLoadWorkRequest) {
        calls++
        if (calls == 1) {
            firstEntered.complete(Unit)
            releaseFirst.await()
        }
        requests += request
    }

    override fun cancel(uniqueName: String) = Unit
}

internal class RecordingProgressiveLoadWorkQueue(
    private val failure: IOException? = null,
    private val beforeEnqueue: () -> Unit = {},
    private val beforeCancel: () -> Unit = {},
) : ProgressiveLoadWorkQueue {
    val requests = mutableListOf<ProgressiveLoadWorkRequest>()
    val cancellations = mutableListOf<String>()

    override suspend fun enqueue(request: ProgressiveLoadWorkRequest) {
        beforeEnqueue()
        failure?.let { throw it }
        requests += request
    }

    override fun cancel(uniqueName: String) {
        beforeCancel()
        cancellations += uniqueName
    }
}

internal class InMemoryProgressiveLoadScheduleStore(
    initialJob: ProgressiveLoadJobEntity,
    vararg initialFiles: ProgressiveLoadFileEntity,
) : ProgressiveLoadScheduleStore {
    var job = initialJob
        private set
    val files = initialFiles.toMutableList()
    val file: ProgressiveLoadFileEntity
        get() = files.single()

    override suspend fun current(bookId: String): Long? = job.takeIf { it.bookId == bookId }?.generation

    override suspend fun publishIfCurrent(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
        paused: Boolean,
        cancelled: Boolean,
    ): Boolean = publish(bookId, expectedCurrent, next, paused, cancelled)

    override suspend fun admit(bookId: String, requested: Long): GenerationAdmission {
        val current = current(bookId) ?: return GenerationAdmission.STALE
        return when {
            requested == current && !job.paused && !job.cancelled &&
                job.phase != ProgressiveLoadPhase.ACTION_REQUIRED -> GenerationAdmission.CURRENT
            current != Long.MAX_VALUE && requested == current + 1 -> {
                check(publish(bookId, current, requested, paused = false, cancelled = false))
                GenerationAdmission.PUBLISHED_NEXT
            }
            else -> GenerationAdmission.STALE
        }
    }

    override suspend fun stop(bookId: String, paused: Boolean, cancelled: Boolean): Long {
        require(paused.xor(cancelled))
        val next = Math.addExact(current(bookId) ?: error("missing job"), 1L)
        check(publish(bookId, job.generation, next, paused, cancelled))
        return next
    }

    private fun publish(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
        paused: Boolean,
        cancelled: Boolean,
    ): Boolean {
        require(next == Math.addExact(expectedCurrent, 1L))
        if (job.bookId != bookId || job.generation != expectedCurrent) return false
        files.indices.forEach { index ->
            val candidate = files[index]
            if (
                candidate.state == ProgressiveLoadFileState.DOWNLOADING &&
                candidate.claimGeneration == expectedCurrent
            ) {
                files[index] = candidate.copy(
                    state = ProgressiveLoadFileState.PENDING,
                    claimGeneration = null,
                )
            }
        }
        job = job.copy(
            generation = next,
            activePath = null,
            retryAt = null,
            paused = paused,
            cancelled = cancelled,
            phase = when {
                cancelled -> ProgressiveLoadPhase.CANCELLED
                paused -> ProgressiveLoadPhase.PAUSED
                job.completedFiles == job.totalFiles -> ProgressiveLoadPhase.COMPLETE
                else -> ProgressiveLoadPhase.INITIAL
            },
        )
        return true
    }
}

internal fun job(generation: Long): ProgressiveLoadJobEntity = ProgressiveLoadJobEntity(
    bookId = "11111111-1111-1111-1111-111111111111",
    remoteRootPath = "disk:/Book",
    phase = ProgressiveLoadPhase.INITIAL,
    totalFiles = 1,
    completedFiles = 0,
    activePath = "chapter.md",
    retryAttempt = 0,
    retryAt = null,
    generation = generation,
    paused = false,
    cancelled = false,
    lastErrorCategory = null,
)

internal fun claimedFile(generation: Long): ProgressiveLoadFileEntity = pendingFile().copy(
    state = ProgressiveLoadFileState.DOWNLOADING,
    claimGeneration = generation,
)

internal fun pendingFile(
    path: String = "chapter.md",
    spineIndex: Int = 0,
): ProgressiveLoadFileEntity = ProgressiveLoadFileEntity(
    bookId = "11111111-1111-1111-1111-111111111111",
    path = path,
    chapterId = "00000000-0000-0000-0000-${spineIndex.toString().padStart(12, '0')}",
    spineIndex = spineIndex,
    expectedRevision = "r1",
    expectedSize = 12,
    sha256 = null,
    state = ProgressiveLoadFileState.PENDING,
    priority = BACKGROUND_PRIORITY,
    claimGeneration = null,
)
