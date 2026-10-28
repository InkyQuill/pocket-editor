package net.inkyquill.pocketeditor.sync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BookSyncMonitor(
    private val scope: CoroutineScope,
    private val probe: RevisionProbe,
    private val enqueue: (bookId: String, remoteRootPath: String, trigger: SyncTrigger) -> Unit,
    private val probeInterval: Duration = 60.seconds,
) {
    private data class ActiveBook(val bookId: String, val rootPath: String)
    private data class ProbeRequest(val target: ActiveBook, val trigger: SyncTrigger)

    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private var pendingRequest: ProbeRequest? = null
    private val probeMutex = Mutex()
    private val stateLock = Any()
    private var activeBook: ActiveBook? = null
    private var isForeground = false
    private var timerJob: Job? = null

    init {
        require(probeInterval.isPositive())
        scope.launch {
            while (currentCoroutineContext().isActive) {
                triggers.receive()
                val request = synchronized(stateLock) {
                    pendingRequest.also { pendingRequest = null }
                } ?: continue
                if (probeAndSchedule(request)) {
                    synchronized(stateLock) {
                        // A completed schedule covers passive probes, but not a later explicit retry.
                        if (pendingRequest?.target == request.target &&
                            pendingRequest?.trigger != SyncTrigger.SYNC_NOW
                        ) pendingRequest = null
                    }
                }
            }
        }
    }

    fun activate(bookId: String, rootPath: String) {
        require(bookId.isNotBlank())
        require(rootPath.isNotBlank())
        val changed = synchronized(stateLock) {
            val next = ActiveBook(bookId, rootPath)
            (activeBook != next).also { activeBook = next }
        }
        if (changed) {
            restartTimer()
            trigger(SyncTrigger.OPEN)
        }
    }

    fun deactivate() {
        synchronized(stateLock) { activeBook = null }
        restartTimer()
    }

    fun foreground(value: Boolean) {
        val changed = synchronized(stateLock) {
            (isForeground != value).also { isForeground = value }
        }
        if (!changed) return
        restartTimer()
        if (value) trigger(SyncTrigger.FOREGROUND)
    }

    fun trigger(trigger: SyncTrigger) {
        synchronized(stateLock) {
            val target = activeBook.takeIf { isForeground } ?: return
            // Coalesce passive work without letting it overwrite an explicit sync for this book.
            if (pendingRequest?.target != target || pendingRequest?.trigger != SyncTrigger.SYNC_NOW) {
                pendingRequest = ProbeRequest(target, trigger)
            }
            triggers.trySend(Unit)
        }
    }

    private fun restartTimer() {
        timerJob?.cancel()
        val active = synchronized(stateLock) { isForeground && activeBook != null }
        timerJob = if (active) {
            scope.launch {
                while (currentCoroutineContext().isActive) {
                    delay(probeInterval)
                    trigger(SyncTrigger.PERIODIC_PROBE)
                }
            }
        } else {
            null
        }
    }

    private suspend fun probeAndSchedule(request: ProbeRequest): Boolean = probeMutex.withLock {
        val target = request.target
        val active = synchronized(stateLock) { isForeground && activeBook == target }
        if (!active) return@withLock false
        val shouldSync = if (request.trigger == SyncTrigger.SYNC_NOW) true else try {
            probe.shouldSync(target.bookId, target.rootPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A full sync owns the user-facing error classification. Scheduling it keeps
            // authorization and invalid-root failures visible instead of silently dropping them.
            true
        }
        val stillActive = synchronized(stateLock) { isForeground && activeBook == target }
        if (shouldSync && stillActive) {
            try {
                enqueue(target.bookId, target.rootPath, request.trigger)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Queue implementations can fail transiently. Keep the long-lived consumer alive
                // so a later explicit, reconnect, or periodic trigger can retry scheduling.
                false
            }
        } else {
            false
        }
    }
}
