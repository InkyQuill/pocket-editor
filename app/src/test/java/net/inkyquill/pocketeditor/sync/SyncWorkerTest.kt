package net.inkyquill.pocketeditor.sync

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncWorkerTest {
    @Test
    fun `worker retries only transient waiting status`() = runBlocking {
        val statuses = listOf(
            SyncStatus.Saved to SyncWorkerOutcome.SUCCESS,
            SyncStatus.ActionRequired("conflict") to SyncWorkerOutcome.TERMINAL,
            SyncStatus.SignInRequired to SyncWorkerOutcome.TERMINAL,
            SyncStatus.WaitingToSync to SyncWorkerOutcome.RETRY,
        )

        statuses.forEach { (status, expected) ->
            val runner = SyncBookRunner { _, _ -> status }
            assertEquals(expected, SyncWorkerLogic(runner).run(BOOK_ID, ROOT))
        }
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

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
        const val ROOT = "disk:/Book"
    }
}
