package net.inkyquill.pocketeditor.source

import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock

interface BookGateway {
    suspend fun listFolder(path: String): List<RemoteEntry>
    suspend fun download(path: String): RemoteFile
    suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock
    suspend fun readLock(rootPath: String): SyncLock
    suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock): String
    /**
     * Publishes the binder without overwriting a canonical remote resource. [beforeTransaction]
     * revalidates sources before publication. Providers must preserve their concurrency contract:
     * Edda uses whole-project version CAS; Yandex uses its cooperative publication protocol.
     */
    suspend fun uploadManifestConditionally(
        rootPath: String,
        bytes: ByteArray,
        expected: RemoteFile?,
        ownedLock: SyncLock,
        beforeTransaction: suspend () -> Boolean = { true },
    ): String
    suspend fun recoverManifestPublication(rootPath: String, ownedLock: SyncLock)
    suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock)
    suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock)
}
