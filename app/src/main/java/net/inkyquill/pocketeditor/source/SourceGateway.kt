package net.inkyquill.pocketeditor.source

import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock

/** Routes every operation by its persisted book root, never by a global selected account. */
class SourceGateway(private val providers: Map<String, BookGateway>) : BookGateway {
    private fun source(path: String): BookGateway = providers[path.substringBefore(':')]
        ?: throw IllegalArgumentException("Unknown book source")
    override suspend fun listFolder(path: String) = source(path).listFolder(path)
    override suspend fun download(path: String) = source(path).download(path)
    override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock) = source(rootPath).tryAcquireLock(rootPath, lock)
    override suspend fun readLock(rootPath: String) = source(rootPath).readLock(rootPath)
    override suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock) =
        source(rootPath).uploadGuarded(rootPath, relativePath, bytes, ownedLock)
    override suspend fun uploadManifestConditionally(rootPath: String, bytes: ByteArray, expected: RemoteFile?, ownedLock: SyncLock, beforeTransaction: suspend () -> Boolean) =
        source(rootPath).uploadManifestConditionally(rootPath, bytes, expected, ownedLock, beforeTransaction)
    override suspend fun recoverManifestPublication(rootPath: String, ownedLock: SyncLock) = source(rootPath).recoverManifestPublication(rootPath, ownedLock)
    override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) = source(rootPath).releaseOwnedLock(rootPath, ownedLock)
    override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) = source(rootPath).breakObservedLock(rootPath, observedLock)
}

fun normalizeSourceRoot(value: String): String {
    val path = value.trim()
    require(path.startsWith("disk:/") || path.startsWith("edda://")) { "Unknown book source" }
    if (path.startsWith("edda://")) net.inkyquill.pocketeditor.edda.EddaLocation.parse(path)
    return if (path == "disk:/") path else path.trimEnd('/')
}
