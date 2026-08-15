package net.inkyquill.pocketeditor.ui.books

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes install/recovery/forget filesystem protocols that share the library roots. */
class LibraryInstallCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
