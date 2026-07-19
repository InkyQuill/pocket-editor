package net.inkyquill.pocketeditor.sync

import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SyncSchedulerTest {
    @Test
    fun `all triggers enqueue one unique connected exponential work item per book`() {
        val queue = RecordingWorkQueue()
        val scheduler = SyncScheduler(queue, changeDebounce = Duration.ofSeconds(2))

        SyncTrigger.entries.forEach { trigger -> scheduler.enqueue(BOOK_ID, ROOT, trigger) }

        assertEquals(4, queue.requests.size)
        queue.requests.forEach { request ->
            assertEquals("sync-book-$BOOK_ID", request.uniqueName)
            assertEquals(BOOK_ID, request.bookId)
            assertEquals(ROOT, request.remoteRootPath)
            assertEquals(NetworkRequirement.CONNECTED, request.networkRequirement)
            assertEquals(BackoffPolicy.EXPONENTIAL, request.backoffPolicy)
        }
        assertEquals(Duration.ofSeconds(2), queue.requests.single { it.trigger == SyncTrigger.LOCAL_CHANGE }.initialDelay)
        assertEquals(Duration.ZERO, queue.requests.single { it.trigger == SyncTrigger.SYNC_NOW }.initialDelay)
    }

    @Test
    fun `local save enqueue is synchronous and never runs or awaits background sync`() {
        val queue = RecordingWorkQueue()
        val scheduler = SyncScheduler(queue)

        scheduler.enqueue(BOOK_ID, ROOT, SyncTrigger.LOCAL_CHANGE)

        assertEquals(1, queue.requests.size)
        assertFalse(queue.executedBackgroundWork)
    }

    private class RecordingWorkQueue : SyncWorkQueue {
        val requests = mutableListOf<SyncWorkRequest>()
        var executedBackgroundWork = false
        override fun enqueue(request: SyncWorkRequest) { requests += request }
    }

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
        const val ROOT = "disk:/Book"
    }
}
