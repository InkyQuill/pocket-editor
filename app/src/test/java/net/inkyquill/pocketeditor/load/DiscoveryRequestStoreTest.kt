package net.inkyquill.pocketeditor.load

import kotlinx.coroutines.test.runTest
import net.inkyquill.pocketeditor.database.ProgressiveLoadRequestEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscoveryRequestStoreTest {
    @Test
    fun `stale identity cannot update or delete replacement at same root and generation`() = runTest {
        val store = InMemoryDiscoveryRequestStore()
        val staleA = request("request-a")
        val currentB = request("request-b")
        assertTrue(store.insertIfAbsent(staleA))
        assertTrue(store.deleteIfGeneration(ROOT, staleA.requestId, 0))
        assertTrue(store.insertIfAbsent(currentB))

        assertFalse(store.compareAndSet(staleA.copy(generation = 1, paused = true), expectedGeneration = 0))
        assertFalse(store.deleteIfGeneration(ROOT, staleA.requestId, expectedGeneration = 0))
        assertEquals(currentB, store.get(ROOT))
    }

    private fun request(id: String) = ProgressiveLoadRequestEntity(
        remoteRootPath = ROOT,
        requestId = id,
        generation = 0,
        phase = ProgressiveLoadPhase.PREPARING,
        retryAttempt = 0,
        retryAt = null,
        lastErrorCategory = null,
        paused = false,
        cancelled = false,
        updatedAt = 1,
    )

    private companion object { const val ROOT = "disk:/Book" }
}
