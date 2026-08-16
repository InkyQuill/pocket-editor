package net.inkyquill.pocketeditor.storage

import kotlinx.coroutines.CompletableDeferred

/**
 * Prevents restored WorkManager jobs from observing the library before startup recovery has
 * reconciled install/repair journals and the filesystem. Waiting is suspending and never blocks
 * the application main thread.
 */
class StartupRecoveryBarrier {
    private val ready = CompletableDeferred<Unit>()

    suspend fun await() = ready.await()

    fun complete() {
        ready.complete(Unit)
    }

    fun fail(cause: Throwable) {
        ready.completeExceptionally(cause)
    }
}
