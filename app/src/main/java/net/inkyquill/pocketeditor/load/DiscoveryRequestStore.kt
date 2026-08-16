package net.inkyquill.pocketeditor.load

import java.util.concurrent.ConcurrentHashMap
import net.inkyquill.pocketeditor.database.ProgressiveLoadRequestDao
import net.inkyquill.pocketeditor.database.ProgressiveLoadRequestEntity

interface DiscoveryRequestStore {
    suspend fun insertIfAbsent(request: ProgressiveLoadRequestEntity): Boolean
    suspend fun get(remoteRootPath: String): ProgressiveLoadRequestEntity?
    suspend fun getByRequestId(requestId: String): ProgressiveLoadRequestEntity?
    suspend fun getAll(): List<ProgressiveLoadRequestEntity>
    suspend fun compareAndSet(request: ProgressiveLoadRequestEntity, expectedGeneration: Long): Boolean
    suspend fun deleteIfGeneration(remoteRootPath: String, requestId: String, expectedGeneration: Long): Boolean
}

class RoomDiscoveryRequestStore(private val dao: ProgressiveLoadRequestDao) : DiscoveryRequestStore {
    override suspend fun insertIfAbsent(request: ProgressiveLoadRequestEntity) = dao.insertIgnore(request) != -1L
    override suspend fun get(remoteRootPath: String) = dao.get(remoteRootPath)
    override suspend fun getByRequestId(requestId: String) = dao.getByRequestId(requestId)
    override suspend fun getAll() = dao.getAll()
    override suspend fun compareAndSet(request: ProgressiveLoadRequestEntity, expectedGeneration: Long) =
        dao.compareAndSet(request, expectedGeneration)
    override suspend fun deleteIfGeneration(remoteRootPath: String, requestId: String, expectedGeneration: Long) =
        dao.deleteIfGeneration(remoteRootPath, requestId, expectedGeneration) == 1
}

internal class InMemoryDiscoveryRequestStore : DiscoveryRequestStore {
    private val rows = ConcurrentHashMap<String, ProgressiveLoadRequestEntity>()

    override suspend fun insertIfAbsent(request: ProgressiveLoadRequestEntity): Boolean =
        rows.putIfAbsent(request.remoteRootPath, request) == null
    override suspend fun get(remoteRootPath: String) = rows[remoteRootPath]
    override suspend fun getByRequestId(requestId: String) = rows.values.singleOrNull { it.requestId == requestId }
    override suspend fun getAll() = rows.values.sortedWith(
        compareByDescending<ProgressiveLoadRequestEntity> { it.updatedAt }.thenBy { it.remoteRootPath },
    )
    override suspend fun compareAndSet(request: ProgressiveLoadRequestEntity, expectedGeneration: Long): Boolean {
        var changed = false
        rows.computeIfPresent(request.remoteRootPath) { _, current ->
            if (current.requestId == request.requestId && current.generation == expectedGeneration) {
                request.also { changed = true }
            } else current
        }
        return changed
    }
    override suspend fun deleteIfGeneration(remoteRootPath: String, requestId: String, expectedGeneration: Long): Boolean {
        val current = rows[remoteRootPath] ?: return false
        return current.requestId == requestId && current.generation == expectedGeneration && rows.remove(remoteRootPath, current)
    }
}

fun ProgressiveLoadRequestEntity.toSnapshot() = ProgressiveLoadSnapshot(
    bookId = requestId,
    remoteRootPath = remoteRootPath,
    phase = phase,
    totalFiles = 0,
    completedFiles = 0,
    activePath = null,
    retryAttempt = retryAttempt,
    retryAt = retryAt,
    generation = generation,
    paused = paused,
    cancelled = cancelled,
    lastErrorCategory = lastErrorCategory,
    files = emptyList(),
)
