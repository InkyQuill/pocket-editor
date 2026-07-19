package net.inkyquill.pocketeditor.sync

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
        val scheduler = SyncScheduler(queue, changeDebounce = Duration.ofSeconds(2))

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
        val scheduler = SyncScheduler(queue)
        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.SYNC_NOW)
        queue.activeRunning = true

        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.RECONNECT)
        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.LOCAL_CHANGE)
        SyncDebounceLauncher(queue).launch(BOOK_ID, ROOT)

        assertFalse(queue.runningCancelled)
        assertTrue(queue.activeRunning)
        assertEquals(3, queue.active.size)
        assertTrue(queue.active.all { it.existingPolicy == ExistingSyncPolicy.APPEND_OR_REPLACE_ACTIVE })
    }

    @Test
    fun `local save enqueue is synchronous and never executes remote sync`() {
        val queue = RecordingWorkQueue()
        SyncScheduler(queue).enqueue(BOOK_ID, ROOT, SyncTrigger.LOCAL_CHANGE)
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

        override fun enqueue(request: SyncWorkRequest) {
            when (request.stage) {
                SyncWorkStage.DEBOUNCE_LAUNCHER -> {
                    delayedLaunchersSeen++
                    delayed.removeAll { it.uniqueName == request.uniqueName }
                    delayed += request
                }
                SyncWorkStage.ACTIVE_SYNC -> {
                    if (request.existingPolicy != ExistingSyncPolicy.APPEND_OR_REPLACE_ACTIVE && activeRunning) {
                        runningCancelled = true
                    }
                    active += request
                }
            }
        }
    }

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
        const val ROOT = "disk:/Book"
    }
}
