package net.inkyquill.pocketeditor.sync

import androidx.work.WorkRequest
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncSchedulerTest {
    @Test
    fun `local changes replace only delayed launcher while immediate triggers append active work`() {
        val queue = RecordingWorkQueue()
        val scheduler = SyncScheduler(queue, InMemoryRetryGenerationStore(), changeDebounce = Duration.ofSeconds(2))

        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.LOCAL_CHANGE)
        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.LOCAL_CHANGE)
        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.SYNC_NOW)

        assertEquals(2, queue.delayedLaunchersSeen)
        assertEquals(1, queue.delayed.size)
        assertEquals("sync-debounce-$BOOK_ID", queue.delayed.single().uniqueName)
        assertEquals(ExistingSyncPolicy.REPLACE_DELAYED, queue.delayed.single().existingPolicy)
        assertEquals(Duration.ofSeconds(2), queue.delayed.single().initialDelay)
        assertEquals(1, queue.active.size)
        assertEquals("sync-book-$BOOK_ID", queue.active.single().uniqueName)
        assertEquals(ExistingSyncPolicy.APPEND_OR_REPLACE_ACTIVE, queue.active.single().existingPolicy)
        assertEquals(NetworkRequirement.CONNECTED, queue.active.single().networkRequirement)
        assertEquals(BackoffPolicy.EXPONENTIAL, queue.active.single().backoffPolicy)
    }

    @Test
    fun `trigger during running sync retains it and schedules a follow up`() {
        val queue = RecordingWorkQueue()
        val generations = InMemoryRetryGenerationStore()
        val scheduler = SyncScheduler(queue, generations)
        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.SYNC_NOW)
        queue.activeRunning = true

        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.RECONNECT)
        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.LOCAL_CHANGE)
        SyncDebounceLauncher(queue, generations).launch(BOOK_ID, ROOT)

        assertFalse(queue.runningCancelled)
        assertTrue(queue.activeRunning)
        assertEquals(3, queue.active.size)
        assertTrue(queue.active.all { it.existingPolicy == ExistingSyncPolicy.APPEND_OR_REPLACE_ACTIVE })
    }

    @Test
    fun `retry backoff is isolated from active chain so sync now starts immediately`() {
        val queue = RecordingWorkQueue()
        val generations = InMemoryRetryGenerationStore()
        val scheduler = SyncScheduler(queue, generations)
        SyncRetryLauncher(queue, generations).launch(BOOK_ID, ROOT, retryAttempt = 3)

        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.SYNC_NOW)

        assertEquals(1, queue.delayed.size)
        val retry = queue.delayed.single()
        assertEquals("sync-retry-$BOOK_ID", retry.uniqueName)
        assertEquals(SyncWorkStage.RETRY_LAUNCHER, retry.stage)
        assertEquals(Duration.ofSeconds(40), retry.initialDelay)
        assertEquals(1, queue.active.size)
        assertEquals(SyncTrigger.SYNC_NOW, queue.active.single().trigger)
        assertEquals(Duration.ZERO, queue.active.single().initialDelay)
    }

    @Test
    fun `sync now during running work keeps one active execution and queues follow up`() {
        val queue = RecordingWorkQueue()
        val scheduler = SyncScheduler(queue, InMemoryRetryGenerationStore())
        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.OPEN)
        queue.startNextActive()

        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.SYNC_NOW)
        queue.startNextActive()

        assertTrue(queue.activeRunning)
        assertFalse(queue.runningCancelled)
        assertEquals(1, queue.maxConcurrentActive)
        assertEquals(1, queue.pendingActiveCount)

        queue.finishActive()
        queue.startNextActive()
        assertEquals(1, queue.maxConcurrentActive)
    }

    @Test
    fun `retry after the former attempt limit remains scheduled`() {
        val queue = RecordingWorkQueue()
        val generations = InMemoryRetryGenerationStore()
        val generation = generations.advance(BOOK_ID)
        val completion = SyncWorkerCompletion(queue, generations)

        completion.complete(BOOK_ID, ROOT, SyncWorkerOutcome.RETRY, retryAttempt = 50, retryGeneration = generation)

        assertEquals(51, queue.delayed.single().retryAttempt)
        assertEquals(WorkRequest.MAX_BACKOFF_MILLIS, queue.delayed.single().initialDelay.toMillis())
    }

    @Test
    fun `retry remains scheduled at the integer attempt boundary`() {
        val queue = RecordingWorkQueue()
        val generations = InMemoryRetryGenerationStore()
        val generation = generations.advance(BOOK_ID)

        SyncWorkerCompletion(queue, generations).complete(
            BOOK_ID,
            ROOT,
            SyncWorkerOutcome.RETRY,
            retryAttempt = Int.MAX_VALUE,
            retryGeneration = generation,
        )

        assertEquals(Int.MAX_VALUE, queue.delayed.single().retryAttempt)
        assertEquals(WorkRequest.MAX_BACKOFF_MILLIS, queue.delayed.single().initialDelay.toMillis())
    }

    @Test
    fun `successful manual sync cancels stale retry without disturbing active chain`() {
        val queue = RecordingWorkQueue()
        val generations = InMemoryRetryGenerationStore()
        val scheduler = SyncScheduler(queue, generations)
        SyncRetryLauncher(queue, generations).launch(BOOK_ID, ROOT, retryAttempt = 2)
        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.SYNC_NOW)
        queue.startNextActive()

        SyncWorkerCompletion(queue, generations).complete(BOOK_ID, ROOT, SyncWorkerOutcome.SUCCESS, retryAttempt = 0)

        assertTrue(queue.delayed.isEmpty())
        assertEquals(listOf("sync-retry-$BOOK_ID"), queue.cancelled)
        assertTrue(queue.activeRunning)
        assertEquals(1, queue.maxConcurrentActive)
    }

    @Test
    fun `terminal outcome invalidates current retry generation and cancels launcher`() {
        val queue = RecordingWorkQueue()
        val generations = InMemoryRetryGenerationStore()
        val generation = generations.advance(BOOK_ID)
        SyncRetryLauncher(queue, generations).launch(BOOK_ID, ROOT, 1, generation)

        SyncWorkerCompletion(queue, generations).complete(
            BOOK_ID,
            ROOT,
            SyncWorkerOutcome.TERMINAL,
            retryAttempt = 1,
            retryGeneration = generation,
        )

        assertFalse(generations.isCurrent(BOOK_ID, generation))
        assertTrue(queue.delayed.isEmpty())
    }

    @Test
    fun `launcher that races after invalidation cannot append stale retry`() {
        val queue = RecordingWorkQueue()
        val generations = InMemoryRetryGenerationStore()
        val stale = generations.advance(BOOK_ID)
        val launcher = SyncRetryLauncher(queue, generations)
        generations.advance(BOOK_ID)

        launcher.appendIfCurrent(BOOK_ID, ROOT, retryAttempt = 1, retryGeneration = stale)

        assertTrue(queue.active.isEmpty())
    }

    @Test
    fun `explicit enqueue advances generation before active work is queued`() {
        val queue = RecordingWorkQueue()
        val generations = InMemoryRetryGenerationStore()
        val old = generations.advance(BOOK_ID)

        SyncScheduler(queue, generations = generations).enqueue(BOOK_ID, ROOT, SyncTrigger.SYNC_NOW)

        val request = queue.active.single()
        assertFalse(generations.isCurrent(BOOK_ID, old))
        assertTrue(generations.isCurrent(BOOK_ID, request.retryGeneration))
        assertFalse(request.isRetry)
    }

    @Test
    fun `local save enqueue is synchronous and never executes remote sync`() {
        val queue = RecordingWorkQueue()
        SyncScheduler(queue, InMemoryRetryGenerationStore()).enqueue(BOOK_ID, ROOT, SyncTrigger.LOCAL_CHANGE)
        assertEquals(1, queue.delayed.size)
        assertFalse(queue.executedRemoteSync)
    }

    private class RecordingWorkQueue : SyncWorkQueue {
        val delayed = mutableListOf<SyncWorkRequest>()
        val active = mutableListOf<SyncWorkRequest>()
        var delayedLaunchersSeen = 0
        var activeRunning = false
        var runningCancelled = false
        var executedRemoteSync = false
        var maxConcurrentActive = 0
        var pendingActiveCount = 0
        val cancelled = mutableListOf<String>()

        override fun enqueue(request: SyncWorkRequest) {
            when (request.stage) {
                SyncWorkStage.DEBOUNCE_LAUNCHER -> {
                    delayedLaunchersSeen++
                    delayed.removeAll { it.uniqueName == request.uniqueName }
                    delayed += request
                }
                SyncWorkStage.RETRY_LAUNCHER -> {
                    delayed.removeAll { it.uniqueName == request.uniqueName }
                    delayed += request
                }
                SyncWorkStage.ACTIVE_SYNC -> {
                    if (request.existingPolicy != ExistingSyncPolicy.APPEND_OR_REPLACE_ACTIVE && activeRunning) {
                        runningCancelled = true
                    }
                    active += request
                    pendingActiveCount++
                }
            }
        }

        override fun cancel(uniqueName: String) {
            cancelled += uniqueName
            delayed.removeAll { it.uniqueName == uniqueName }
        }

        fun startNextActive() {
            if (activeRunning || pendingActiveCount == 0) return
            pendingActiveCount--
            activeRunning = true
            maxConcurrentActive = maxOf(maxConcurrentActive, 1)
        }

        fun finishActive() {
            activeRunning = false
        }
    }

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
        const val ROOT = "disk:/Book"
    }
}
