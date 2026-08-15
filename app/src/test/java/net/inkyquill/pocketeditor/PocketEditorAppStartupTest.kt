package net.inkyquill.pocketeditor

import java.time.Duration
import kotlinx.coroutines.test.runTest
import net.inkyquill.pocketeditor.load.ProgressiveLoadWorkQueue
import net.inkyquill.pocketeditor.load.ProgressiveLoadWorkRequest
import net.inkyquill.pocketeditor.sync.SyncWorkQueue
import net.inkyquill.pocketeditor.sync.SyncWorkRequest
import net.inkyquill.pocketeditor.sync.SyncTrigger
import net.inkyquill.pocketeditor.sync.SyncWorkStage
import net.inkyquill.pocketeditor.sync.ExistingSyncPolicy
import net.inkyquill.pocketeditor.sync.NetworkRequirement
import net.inkyquill.pocketeditor.sync.BackoffPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class PocketEditorAppStartupTest {
    @Test
    fun `install journal recovery precedes generic scan and legacy promotion`() = runTest {
        val calls = mutableListOf<String>()

        recoverAppState(
            recoverInstallJournal = { calls += "install-journal" },
            recoverLibrary = { calls += "library-scan" },
            promoteLegacy = { calls += "legacy" },
        )

        assertEquals(listOf("install-journal", "library-scan", "legacy"), calls)
    }

    @Test
    fun `work queues cannot recurse during container construction and bind exactly once`() = runTest {
        val sync = LateBoundSyncWorkQueue()
        val progressive = LateBoundProgressiveLoadQueue()
        assertThrows<IllegalArgumentException> { sync.cancel("before-init") }
        assertThrows<IllegalArgumentException> {
            progressive.enqueue(ProgressiveLoadWorkRequest("before-init", BOOK_ID, 1, Duration.ZERO))
        }
        val calls = mutableListOf<String>()
        sync.bind(object : SyncWorkQueue {
            override fun enqueue(request: SyncWorkRequest) { calls += "sync:${request.bookId}" }
            override fun cancel(uniqueName: String) { calls += "cancel:$uniqueName" }
        })
        progressive.bind(object : ProgressiveLoadWorkQueue {
            override suspend fun enqueue(request: ProgressiveLoadWorkRequest) { calls += "load:${request.bookId}" }
            override fun cancel(uniqueName: String) { calls += "load-cancel:$uniqueName" }
        })

        sync.enqueue(SyncWorkRequest(
            "sync-$BOOK_ID", BOOK_ID, "disk:/Book", SyncTrigger.OPEN,
            SyncWorkStage.ACTIVE_SYNC, ExistingSyncPolicy.APPEND_OR_REPLACE_ACTIVE,
            NetworkRequirement.CONNECTED, BackoffPolicy.EXPONENTIAL, Duration.ZERO,
        ))
        progressive.enqueue(ProgressiveLoadWorkRequest("load-$BOOK_ID", BOOK_ID, 1, Duration.ZERO))

        assertEquals(listOf("sync:$BOOK_ID", "load:$BOOK_ID"), calls)
        assertThrows<IllegalStateException> { sync.bind(object : SyncWorkQueue {
            override fun enqueue(request: SyncWorkRequest) = Unit
            override fun cancel(uniqueName: String) = Unit
        }) }
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
    }
}
