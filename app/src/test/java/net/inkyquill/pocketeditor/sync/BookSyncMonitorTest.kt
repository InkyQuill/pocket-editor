package net.inkyquill.pocketeditor.sync

import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookSyncMonitorTest {
    @Test
    fun `overlapping foreground chapter and timer triggers enqueue once`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<SyncTrigger>()
        val monitor = BookSyncMonitor(
            scope = backgroundScope,
            probe = RevisionProbe { _, _ -> entered.complete(Unit); release.await(); true },
            enqueue = { _, _, trigger -> requests += trigger },
            probeInterval = 60.seconds,
        )

        monitor.activate(BOOK_ID, ROOT)
        monitor.foreground(true)
        entered.await()
        monitor.trigger(SyncTrigger.CHAPTER_CHANGE)
        advanceTimeBy(60_000)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        monitor.foreground(false)
        advanceUntilIdle()

        assertEquals(1, requests.size)
    }

    @Test
    fun `foreground timer probes after sixty seconds`() = runTest {
        val outcomes = ArrayDeque(listOf(false, true))
        val probes = mutableListOf<Pair<String, String>>()
        val requests = mutableListOf<SyncTrigger>()
        val monitor = BookSyncMonitor(
            scope = backgroundScope,
            probe = RevisionProbe { bookId, root -> probes += bookId to root; outcomes.removeFirst() },
            enqueue = { _, _, trigger -> requests += trigger },
            probeInterval = 60.seconds,
        )

        monitor.activate(BOOK_ID, ROOT)
        monitor.foreground(true)
        runCurrent()
        assertEquals(1, probes.size)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(1, probes.size)
        advanceTimeBy(1)
        runCurrent()
        monitor.foreground(false)
        advanceUntilIdle()

        assertEquals(2, probes.size)
        assertEquals(listOf(SyncTrigger.PERIODIC_PROBE), requests)
    }

    @Test
    fun `scheduling one book does not discard a newly activated book trigger`() = runTest {
        val probes = mutableListOf<String>()
        val requests = mutableListOf<String>()
        lateinit var monitor: BookSyncMonitor
        monitor = BookSyncMonitor(
            scope = backgroundScope,
            probe = RevisionProbe { bookId, _ -> probes += bookId; true },
            enqueue = { bookId, _, _ ->
                requests += bookId
                if (bookId == BOOK_ID) monitor.activate(OTHER_BOOK_ID, OTHER_ROOT)
            },
            probeInterval = 60.seconds,
        )

        monitor.activate(BOOK_ID, ROOT)
        monitor.foreground(true)
        runCurrent()
        monitor.foreground(false)

        assertEquals(listOf(BOOK_ID, OTHER_BOOK_ID), probes)
        assertEquals(listOf(BOOK_ID, OTHER_BOOK_ID), requests)
    }

    @Test
    fun `background and deactivated monitor do not probe`() = runTest {
        var probes = 0
        val monitor = BookSyncMonitor(
            scope = backgroundScope,
            probe = RevisionProbe { _, _ -> probes++; true },
            enqueue = { _, _, _ -> },
            probeInterval = 60.seconds,
        )

        monitor.activate(BOOK_ID, ROOT)
        monitor.trigger(SyncTrigger.SYNC_NOW)
        advanceTimeBy(60_000)
        runCurrent()
        monitor.deactivate()
        monitor.foreground(true)
        advanceTimeBy(60_000)
        runCurrent()
        monitor.foreground(false)

        assertEquals(0, probes)
    }

    @Test
    fun `authorization and invalid root probe failures enqueue full sync for classification`() = runTest {
        listOf(
            YandexDiskError.Unauthorized(),
            YandexDiskError.InvalidRemote("missing root"),
        ).forEach { failure ->
            val requests = mutableListOf<SyncTrigger>()
            val monitor = BookSyncMonitor(
                scope = backgroundScope,
                probe = RevisionProbe { _, _ -> throw failure },
                enqueue = { _, _, trigger -> requests += trigger },
                probeInterval = 60.seconds,
            )

            monitor.activate(BOOK_ID, ROOT)
            monitor.foreground(true)
            runCurrent()
            monitor.foreground(false)

            assertEquals(listOf(SyncTrigger.FOREGROUND), requests, failure::class.simpleName)
        }
    }

    @Test
    fun `enqueue failure does not terminate later monitor triggers`() = runTest {
        val requests = mutableListOf<SyncTrigger>()
        var attempts = 0
        val monitor = BookSyncMonitor(
            scope = backgroundScope,
            probe = RevisionProbe { _, _ -> true },
            enqueue = { _, _, trigger ->
                attempts++
                if (attempts == 1) error("queue unavailable")
                requests += trigger
            },
            probeInterval = 60.seconds,
        )

        monitor.activate(BOOK_ID, ROOT)
        monitor.foreground(true)
        runCurrent()
        monitor.trigger(SyncTrigger.SYNC_NOW)
        runCurrent()
        monitor.foreground(false)

        assertEquals(2, attempts)
        assertEquals(listOf(SyncTrigger.SYNC_NOW), requests)
    }

    private companion object {
        val BOOK_ID = UUID.fromString("00000000-0000-4000-8000-000000000411").toString()
        val OTHER_BOOK_ID = UUID.fromString("00000000-0000-4000-8000-000000000412").toString()
        const val ROOT = "disk:/Book"
        const val OTHER_ROOT = "disk:/Other"
    }
}
