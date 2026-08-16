package net.inkyquill.pocketeditor.sync

import android.content.Context
import androidx.work.WorkerParameters
import java.util.UUID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncWorkerTest {
    @Test
    fun `worker never invokes sync runner without validated internet`() = runBlocking {
        var calls = 0
        val logic = SyncWorkerLogic(
            runner = SyncBookRunner { _, _ -> calls++; SyncStatus.Saved },
            network = NetworkAvailability { false },
        )

        assertEquals(SyncWorkerOutcome.NO_VALIDATED_NETWORK, logic.run(BOOK_ID, ROOT))
        assertEquals(0, calls)
    }

    @Test
    fun `worker invokes sync runner when Android reports validated internet`() = runBlocking {
        var calls = 0
        val logic = SyncWorkerLogic(
            runner = SyncBookRunner { _, _ -> calls++; SyncStatus.Saved },
            network = NetworkAvailability { true },
        )

        assertEquals(SyncWorkerOutcome.SUCCESS, logic.run(BOOK_ID, ROOT))
        assertEquals(1, calls)
    }

    @Test
    fun `worker retries only transient waiting status`() = runBlocking {
        val statuses = listOf(
            SyncStatus.Saved to SyncWorkerOutcome.SUCCESS,
            SyncStatus.ActionRequired("conflict") to SyncWorkerOutcome.TERMINAL,
            SyncStatus.SignInRequired to SyncWorkerOutcome.TERMINAL,
            SyncStatus.WaitingToSync() to SyncWorkerOutcome.RETRY(),
        )

        statuses.forEach { (status, expected) ->
            val runner = SyncBookRunner { _, _ -> status }
            assertEquals(expected, SyncWorkerLogic(runner).run(BOOK_ID, ROOT))
        }
    }

    @Test
    fun `worker preserves retry-after hint from sync status`() = runBlocking {
        val delay = java.time.Duration.ofSeconds(45)
        val logic = SyncWorkerLogic(SyncBookRunner { _, _ -> SyncStatus.WaitingToSync(delay) })

        assertEquals(SyncWorkerOutcome.RETRY(delay), logic.run(BOOK_ID, ROOT))
    }

    @Test
    fun `appended stale retry is a no-op while current generation runs`() = runBlocking {
        val generations = InMemoryRetryGenerationStore()
        val current = generations.advance(BOOK_ID)
        var calls = 0
        val runner = SyncBookRunner { _, _ -> calls++; SyncStatus.Saved }
        val logic = SyncWorkerLogic(runner, generations)

        assertEquals(
            SyncWorkerOutcome.STALE,
            logic.run(BOOK_ID, ROOT, isRetry = true, retryGeneration = current - 1),
        )
        assertEquals(0, calls)
        assertEquals(
            SyncWorkerOutcome.SUCCESS,
            logic.run(BOOK_ID, ROOT, isRetry = true, retryGeneration = current),
        )
        assertEquals(1, calls)
    }

    @Test
    fun `debounce accepts only its current or uncommitted publication generation`() {
        val current = 42L

        assertTrue(acceptsDebounceGeneration(current, current))
        assertTrue(acceptsDebounceGeneration(current, nextRetryGeneration(current)))
        assertFalse(acceptsDebounceGeneration(current, current - 1))
        assertFalse(acceptsDebounceGeneration(current, nextRetryGeneration(nextRetryGeneration(current))))
    }

    @Test
    fun `factory shares exact durable generation store and lazy queue with both worker types`() {
        val generations = InMemoryRetryGenerationStore()
        val queue = object : SyncWorkQueue {
            override fun enqueue(request: SyncWorkRequest) = Unit
            override fun cancel(uniqueName: String) = Unit
        }
        val factory = SyncWorkerFactory(SyncBookRunner { _, _ -> SyncStatus.Saved }, queue, generations)

        assertSame(generations, factory.retryGenerationStore)
        assertSame(queue, factory.syncWorkQueue)
        assertTrue(factory.supports(SyncWorker::class.java.name))
        assertTrue(factory.supports(SyncDebounceWorker::class.java.name))
    }

    @Test
    fun `application worker factory delegates sync workers to existing factory`() {
        val generations = InMemoryRetryGenerationStore()
        val queue = object : SyncWorkQueue {
            override fun enqueue(request: SyncWorkRequest) = Unit
            override fun cancel(uniqueName: String) = Unit
        }
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val parameters = mockk<WorkerParameters>(relaxed = true)
        val syncFactory = SyncWorkerFactory(SyncBookRunner { _, _ -> SyncStatus.Saved }, queue, generations)
        val factory = PocketEditorWorkerFactory(syncFactory)

        val worker = factory.createWorker(context, SyncWorker::class.java.name, parameters)

        assertTrue(worker is SyncWorker)
    }

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
        const val ROOT = "disk:/Book"
    }
}
