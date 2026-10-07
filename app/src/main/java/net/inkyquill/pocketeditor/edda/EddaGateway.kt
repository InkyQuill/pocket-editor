package net.inkyquill.pocketeditor.edda

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.inkyquill.pocketeditor.source.BookGateway
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.StrictUtf8
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskError

/** Uses project-version CAS in addition to the cross-client cooperative book lock. */
class EddaGateway(private val client: EddaClient) : BookGateway {
    private data class Session(val lock: SyncLock, val version: EddaVersion)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val acquisition = Mutex()
    override suspend fun listFolder(path: String): List<RemoteEntry> {
        val location = EddaLocation.parse(path)
        val version = snapshot(location)
        if (location.path.isNotEmpty() && version.entries.none { it.path == location.path && it.kind == "directory" }) {
            throw YandexDiskError.NotFound()
        }
        val prefix = location.path.takeIf(String::isNotEmpty)?.plus("/") ?: ""
        return version.entries.filter { it.path.startsWith(prefix) && '/' !in it.path.removePrefix(prefix) && it.path != location.path }
            .map { entry -> RemoteEntry(entry.path.removePrefix(prefix), location.copy(path = entry.path).root,
                if (entry.kind == "directory") "dir" else "file", entry.bytes, entry.sha256) }
    }
    override suspend fun download(path: String): RemoteFile {
        val location = EddaLocation.parse(path)
        val version = snapshot(location)
        val entry = version.entries.singleOrNull { it.path == location.path && it.kind == "file" } ?: throw YandexDiskError.NotFound()
        return RemoteFile(path, client.download(location, version, entry), entry.sha256)
    }
    private suspend fun snapshot(location: EddaLocation): EddaVersion = sessions.entries
        .filter { location.root == it.key || location.root.startsWith(it.key + "/") }
        .maxByOrNull { it.key.length }?.value?.version ?: client.version(location)

    override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock = acquisition.withLock {
        val root = EddaLocation.parse(rootPath)
        if (sessions.containsKey(root.root)) throw YandexDiskError.LockHeld()
        val current = client.version(root)
        val previous = lockIn(root, current)
        if (previous != null && previous.holderId != lock.holderId) throw YandexDiskError.LockHeld()
        // A same-device stale lock can be replaced atomically; another holder's lock cannot.
        val next = replace(root, current, LOCK, lock.json().encodeToByteArray())
        sessions[root.root] = Session(lock, next)
        lock
    }
    override suspend fun readLock(rootPath: String): SyncLock {
        val root = EddaLocation.parse(rootPath)
        return lockIn(root, client.version(root)) ?: throw YandexDiskError.NotFound()
    }
    private suspend fun lockIn(root: EddaLocation, version: EddaVersion): SyncLock? {
        val entry = version.entries.singleOrNull { it.path == root.child(LOCK).path } ?: return null
        return SyncLock.fromJson(StrictUtf8.decode(client.download(root, version, entry), "Sync lock"))
    }
    private fun owned(root: EddaLocation, lock: SyncLock): Session = sessions[root.root]
        ?.takeIf { it.lock == lock } ?: throw YandexDiskError.LockLost()

    override suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock): String {
        require(relativePath != BookPaths.MANIFEST_NAME && relativePath.endsWith(BookPaths.REVIEW_SUFFIX)) { "Only review sidecars can be published" }
        val root = EddaLocation.parse(rootPath)
        val session = owned(root, ownedLock)
        val next = replace(root, session.version, relativePath, bytes)
        sessions[root.root] = session.copy(version = next)
        return bytes.sha256()
    }
    override suspend fun uploadManifestConditionally(rootPath: String, bytes: ByteArray, expected: RemoteFile?, ownedLock: SyncLock, beforeTransaction: suspend () -> Boolean): String {
        val root = EddaLocation.parse(rootPath)
        val session = owned(root, ownedLock)
        val entry = session.version.entries.singleOrNull { it.path == root.child(BookPaths.MANIFEST_NAME).path }
        if ((expected == null) != (entry == null) || (expected != null && entry?.sha256 != expected.revision)) {
            throw YandexDiskError.ConcurrentRemoteChange(entry?.let { RemoteFile(root.child(BookPaths.MANIFEST_NAME).root, client.download(root, session.version, it), it.sha256) })
        }
        if (!beforeTransaction()) throw YandexDiskError.PublicationPreconditionFailed()
        val next = replace(root, session.version, BookPaths.MANIFEST_NAME, bytes)
        sessions[root.root] = session.copy(version = next)
        return bytes.sha256()
    }
    override suspend fun recoverManifestPublication(rootPath: String, ownedLock: SyncLock) {
        owned(EddaLocation.parse(rootPath), ownedLock) // Edda publication is one atomic version transaction.
    }
    override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) {
        val root = EddaLocation.parse(rootPath)
        try { removeLock(root, ownedLock) } finally { sessions.remove(root.root) }
    }
    override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) {
        removeLock(EddaLocation.parse(rootPath), observedLock)
    }
    private suspend fun removeLock(root: EddaLocation, expected: SyncLock) {
        val current = client.version(root)
        val observed = lockIn(root, current) ?: return
        if (observed != expected) throw YandexDiskError.LockLost()
        client.publish(root, current, current.entries.filterNot { it.path == root.child(LOCK).path }, UUID.randomUUID().toString())
    }
    private suspend fun replace(root: EddaLocation, current: EddaVersion, name: String, bytes: ByteArray): EddaVersion {
        val path = root.child(name).path
        val previous = current.entries.singleOrNull { it.path == path }
        require(previous == null || previous.kind == "file") { "Cannot replace a folder" }
        client.upload(root, bytes)
        val next = EddaEntry(previous?.id ?: UUID.randomUUID().toString(), path, "file", bytes.sha256(), bytes.size.toLong())
        return client.publish(root, current, current.entries.filterNot { it.path == path } + next, UUID.randomUUID().toString())
    }
    companion object { const val LOCK = ".pocket-editor.sync.lock" }
}
