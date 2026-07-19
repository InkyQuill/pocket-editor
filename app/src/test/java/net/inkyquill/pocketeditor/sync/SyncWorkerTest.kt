package net.inkyquill.pocketeditor.sync

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
        const val ROOT = "disk:/Book"
    }
}
