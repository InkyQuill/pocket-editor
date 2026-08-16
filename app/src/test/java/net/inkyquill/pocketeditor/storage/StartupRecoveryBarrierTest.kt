package net.inkyquill.pocketeditor.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupRecoveryBarrierTest {
    @Test
    fun `restored work waits until filesystem recovery is complete`() = runTest {
        val barrier = StartupRecoveryBarrier()
        var touchedFilesystem = false
        val worker = async {
            barrier.await()
            touchedFilesystem = true
        }

        testScheduler.runCurrent()
        assertFalse(touchedFilesystem)
        barrier.complete()
        worker.await()
        assertTrue(touchedFilesystem)
    }

    @Test
    fun `recovery failure fails waiting work instead of touching filesystem`() = runTest {
        val barrier = StartupRecoveryBarrier()
        barrier.fail(IllegalStateException("recovery failed"))

        var failed = false
        try {
            barrier.await()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }
}
