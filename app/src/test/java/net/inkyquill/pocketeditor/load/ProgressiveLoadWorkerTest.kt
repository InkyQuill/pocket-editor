package net.inkyquill.pocketeditor.load

import android.content.Context
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.inkyquill.pocketeditor.sync.NetworkAvailability
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProgressiveLoadWorkerTest {
    @Test
    fun `adjacent worker self-publishes after enqueue publication gap`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), claimedFile(generation = 7))
        var calls = 0
        val logic = ProgressiveLoadWorkerLogic(
            runner = ProgressiveLoadRunner { _, generation ->
                calls++
                assertEquals(8, generation)
                ProgressiveLoadRunResult.FileCached
            },
            scheduleStore = store,
            network = NetworkAvailability { true },
        )

        assertEquals(ProgressiveLoadRunResult.FileCached, logic.run(BOOK_ID, 8))
        assertEquals(8, store.job.generation)
        assertEquals(ProgressiveLoadFileState.PENDING, store.file.state)
        assertEquals(1, calls)
    }

    @Test
    fun `worker rejects older and further-ahead generations`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), pendingFile())
        var calls = 0
        val logic = ProgressiveLoadWorkerLogic(
            runner = ProgressiveLoadRunner { _, _ ->
                calls++
                ProgressiveLoadRunResult.Complete
            },
            scheduleStore = store,
            network = NetworkAvailability { true },
        )

        assertEquals(ProgressiveLoadRunResult.Stale, logic.run(BOOK_ID, 6))
        assertEquals(ProgressiveLoadRunResult.Stale, logic.run(BOOK_ID, 9))
        assertEquals(0, calls)
    }

    @Test
    fun `worker admits exact current generation once`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), pendingFile())
        var calls = 0
        val logic = ProgressiveLoadWorkerLogic(
            runner = ProgressiveLoadRunner { _, generation ->
                calls++
                assertEquals(7, generation)
                ProgressiveLoadRunResult.Complete
            },
            scheduleStore = store,
            network = NetworkAvailability { true },
        )

        assertEquals(ProgressiveLoadRunResult.Complete, logic.run(BOOK_ID, 7))
        assertEquals(1, calls)
        assertEquals(7, store.job.generation)
    }

    @Test
    fun `worker never invokes runner without validated internet`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), pendingFile())
        var calls = 0
        val logic = ProgressiveLoadWorkerLogic(
            runner = ProgressiveLoadRunner { _, _ ->
                calls++
                ProgressiveLoadRunResult.Complete
            },
            scheduleStore = store,
            network = NetworkAvailability { false },
        )

        assertEquals(ProgressiveLoadRunResult.NoValidatedNetwork, logic.run(BOOK_ID, 7))
        assertEquals(0, calls)
    }

    @Test
    fun `worker rejects stopped and action-required current jobs`() = runTest {
        val blockedJobs = listOf(
            job(generation = 7).copy(paused = true, phase = ProgressiveLoadPhase.PAUSED),
            job(generation = 7).copy(cancelled = true, phase = ProgressiveLoadPhase.CANCELLED),
            job(generation = 7).copy(phase = ProgressiveLoadPhase.ACTION_REQUIRED),
        )

        blockedJobs.forEach { blockedJob ->
            var calls = 0
            val logic = ProgressiveLoadWorkerLogic(
                runner = ProgressiveLoadRunner { _, _ ->
                    calls++
                    ProgressiveLoadRunResult.Complete
                },
                scheduleStore = InMemoryProgressiveLoadScheduleStore(blockedJob, pendingFile()),
                network = NetworkAvailability { true },
            )

            assertEquals(ProgressiveLoadRunResult.Stale, logic.run(BOOK_ID, 7))
            assertEquals(0, calls)
        }
    }

    @Test
    fun `FileCached continues the same generation with one new file step`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), pendingFile())
        val queue = RecordingProgressiveLoadWorkQueue()
        val completion = ProgressiveLoadWorkerCompletion(
            scheduler = ProgressiveLoadScheduler(queue, store),
            now = { NOW },
        )

        completion.complete(BOOK_ID, 7, ProgressiveLoadRunResult.FileCached)

        assertEquals(
            listOf(ProgressiveLoadWorkRequest("progressive-load-$BOOK_ID", BOOK_ID, 7, Duration.ZERO)),
            queue.requests,
        )
        assertEquals(7, store.job.generation)
    }

    @Test
    fun `attempt fifty retry stays capped and schedules same generation`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), pendingFile())
        val queue = RecordingProgressiveLoadWorkQueue()
        val completion = ProgressiveLoadWorkerCompletion(
            scheduler = ProgressiveLoadScheduler(queue, store),
            now = { NOW },
        )
        val policy = ProgressiveLoadRetryPolicy(now = { NOW }, jitterMillis = { 0L })
        val retry = policy.classify(
            YandexDiskError.ServerFailure(503),
            attempt = 50,
        ) as LoadFailureDisposition.Retry

        completion.complete(BOOK_ID, 7, ProgressiveLoadRunResult.Retry(retry.retryAt))

        assertEquals(Duration.ofHours(6), queue.requests.single().delay)
        assertEquals(7, queue.requests.single().generation)
        assertEquals(7, store.job.generation)
    }

    @Test
    fun `unvalidated network schedules a thirty second same-generation check`() = runTest {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), pendingFile())
        val queue = RecordingProgressiveLoadWorkQueue()
        val completion = ProgressiveLoadWorkerCompletion(
            scheduler = ProgressiveLoadScheduler(queue, store),
            now = { NOW },
        )

        completion.complete(BOOK_ID, 7, ProgressiveLoadRunResult.NoValidatedNetwork)

        assertEquals(Duration.ofSeconds(30), queue.requests.single().delay)
        assertEquals(7, queue.requests.single().generation)
    }

    @Test
    fun `terminal and stale outcomes never enqueue more work`() = runTest {
        val terminalResults = listOf(
            ProgressiveLoadRunResult.Complete,
            ProgressiveLoadRunResult.SignInRequired,
            ProgressiveLoadRunResult.ActionRequired,
            ProgressiveLoadRunResult.Stale,
        )

        terminalResults.forEach { result ->
            val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), pendingFile())
            val queue = RecordingProgressiveLoadWorkQueue()
            val completion = ProgressiveLoadWorkerCompletion(
                scheduler = ProgressiveLoadScheduler(queue, store),
                now = { NOW },
            )

            completion.complete(BOOK_ID, 7, result)

            assertEquals(emptyList<ProgressiveLoadWorkRequest>(), queue.requests, "result=$result")
        }
    }

    @Test
    fun `progressive factory creates only the progressive worker`() {
        val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), pendingFile())
        val queue = RecordingProgressiveLoadWorkQueue()
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val parameters = mockk<WorkerParameters>(relaxed = true)
        val factory = ProgressiveLoadWorkerFactory(
            runner = ProgressiveLoadRunner { _, _ -> ProgressiveLoadRunResult.Complete },
            scheduler = ProgressiveLoadScheduler(queue, store),
            scheduleStore = store,
            network = NetworkAvailability { true },
        )

        val worker = factory.createWorker(context, ProgressiveLoadWorker::class.java.name, parameters)

        assertTrue(worker is ProgressiveLoadWorker)
        assertNull(factory.createWorker(context, "unknown.Worker", parameters))
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        val NOW: Instant = Instant.parse("2026-08-15T10:00:00Z")
    }
}
