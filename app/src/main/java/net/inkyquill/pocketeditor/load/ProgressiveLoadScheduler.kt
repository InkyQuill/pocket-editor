package net.inkyquill.pocketeditor.load

import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import net.inkyquill.pocketeditor.database.ProgressiveLoadDao
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity

data class ProgressiveLoadWorkRequest(
    val uniqueName: String,
    val bookId: String,
    val generation: Long,
    val delay: Duration,
)

interface ProgressiveLoadWorkQueue {
    suspend fun enqueue(request: ProgressiveLoadWorkRequest)
    fun cancel(uniqueName: String)
}

enum class GenerationAdmission { CURRENT, PUBLISHED_NEXT, STALE }

interface ProgressiveLoadScheduleStore {
    suspend fun current(bookId: String): Long?

    suspend fun publishIfCurrent(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
        paused: Boolean,
        cancelled: Boolean,
    ): Boolean

    suspend fun publishContinueIfCurrent(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
    ): Boolean = publishIfCurrent(bookId, expectedCurrent, next, paused = false, cancelled = false)

    suspend fun admit(bookId: String, requested: Long): GenerationAdmission

    suspend fun stop(bookId: String, paused: Boolean, cancelled: Boolean): Long

}

class RoomProgressiveLoadScheduleStore(
    private val database: PocketEditorDatabase,
    private val dao: ProgressiveLoadDao,
) : ProgressiveLoadScheduleStore {
    override suspend fun current(bookId: String): Long? = dao.getJob(bookId)?.generation

    override suspend fun publishIfCurrent(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
        paused: Boolean,
        cancelled: Boolean,
    ): Boolean = database.withTransaction {
        publishLocked(bookId, expectedCurrent, next, paused, cancelled, resetActionRequired = false)
    }

    override suspend fun publishContinueIfCurrent(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
    ): Boolean = database.withTransaction {
        publishLocked(bookId, expectedCurrent, next, paused = false, cancelled = false, resetActionRequired = true)
    }

    override suspend fun admit(bookId: String, requested: Long): GenerationAdmission =
        database.withTransaction {
            val job = dao.getJob(bookId) ?: return@withTransaction GenerationAdmission.STALE
            val current = job.generation
            when {
                requested == current && !job.paused && !job.cancelled &&
                    job.phase != ProgressiveLoadPhase.ACTION_REQUIRED -> GenerationAdmission.CURRENT
                current != Long.MAX_VALUE && requested == current + 1 -> {
                    check(
                        publishLocked(
                            bookId, current, requested, paused = false, cancelled = false,
                            resetActionRequired = job.phase == ProgressiveLoadPhase.ACTION_REQUIRED,
                        ),
                    )
                    GenerationAdmission.PUBLISHED_NEXT
                }
                else -> GenerationAdmission.STALE
            }
        }

    override suspend fun stop(bookId: String, paused: Boolean, cancelled: Boolean): Long =
        database.withTransaction {
            require(paused.xor(cancelled))
            val current = requireNotNull(dao.getJob(bookId)).generation
            val next = Math.addExact(current, 1L)
            check(publishLocked(bookId, current, next, paused, cancelled, resetActionRequired = false))
            next
        }

    private suspend fun publishLocked(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
        paused: Boolean,
        cancelled: Boolean,
        resetActionRequired: Boolean,
    ): Boolean {
        require(next == Math.addExact(expectedCurrent, 1L))
        val job = dao.getJob(bookId) ?: return false
        if (job.generation != expectedCurrent) return false
        if (resetActionRequired) {
            dao.getFiles(bookId)
                .filter { it.state == ProgressiveLoadFileState.ACTION_REQUIRED }
                .forEach { file ->
                    dao.updateFile(
                        file.copy(
                            state = ProgressiveLoadFileState.PENDING,
                            priority = initialPriority(file.spineIndex),
                            claimGeneration = null,
                        ),
                    )
                }
        }
        dao.getFiles(bookId)
            .filter {
                it.state == ProgressiveLoadFileState.DOWNLOADING &&
                    it.claimGeneration == expectedCurrent
            }
            .forEach { file ->
                dao.updateFile(
                    file.copy(
                        state = ProgressiveLoadFileState.PENDING,
                        claimGeneration = null,
                    ),
                )
            }
        val files = dao.getFiles(bookId)
        val initialReady = job.totalFiles > 0 && files
            .sortedBy(ProgressiveLoadFileEntity::spineIndex)
            .take(minOf(3, files.size))
            .all { it.state == ProgressiveLoadFileState.CACHED }
        dao.updateJob(
            job.copy(
                generation = next,
                activePath = null,
                retryAt = null,
                retryAttempt = if (resetActionRequired) 0 else job.retryAttempt,
                paused = paused,
                cancelled = cancelled,
                lastErrorCategory = if (resetActionRequired) null else job.lastErrorCategory,
                phase = when {
                    cancelled -> ProgressiveLoadPhase.CANCELLED
                    paused -> ProgressiveLoadPhase.PAUSED
                    job.totalFiles > 0 && job.completedFiles == job.totalFiles -> ProgressiveLoadPhase.COMPLETE
                    initialReady -> ProgressiveLoadPhase.BACKGROUND
                    job.totalFiles == 0 -> ProgressiveLoadPhase.PREPARING
                    else -> ProgressiveLoadPhase.INITIAL
                },
            ),
        )
        return true
    }
}

class ProgressiveLoadScheduler(
    private val queue: ProgressiveLoadWorkQueue,
    private val store: ProgressiveLoadScheduleStore,
) {
    private val schedulingMutex = Mutex()

    suspend fun start(bookId: String) = replace(bookId, Duration.ZERO)

    suspend fun replaceNow(bookId: String) = replace(bookId, Duration.ZERO)

    suspend fun continueLoad(bookId: String) = schedulingMutex.withLock {
        replaceLocked(bookId, Duration.ZERO, resetActionRequired = true)
    }

    suspend fun enqueueCurrent(bookId: String, generation: Long, delay: Duration) = schedulingMutex.withLock {
        if (store.current(bookId) == generation) {
            queue.enqueue(request(bookId, generation, delay))
        }
    }

    suspend fun pause(bookId: String) = schedulingMutex.withLock {
        store.stop(bookId, paused = true, cancelled = false)
        queue.cancel(uniqueName(bookId))
    }

    suspend fun cancel(bookId: String) = schedulingMutex.withLock {
        store.stop(bookId, paused = false, cancelled = true)
        queue.cancel(uniqueName(bookId))
    }

    private suspend fun replace(bookId: String, delay: Duration) = schedulingMutex.withLock {
        replaceLocked(bookId, delay, resetActionRequired = false)
    }

    private suspend fun replaceLocked(bookId: String, delay: Duration, resetActionRequired: Boolean) {
        val current = requireNotNull(store.current(bookId))
        val next = Math.addExact(current, 1L)
        queue.enqueue(request(bookId, next, delay))
        if (resetActionRequired) {
            store.publishContinueIfCurrent(bookId, current, next)
        } else {
            store.publishIfCurrent(bookId, current, next, paused = false, cancelled = false)
        }
    }

    private fun request(bookId: String, generation: Long, delay: Duration) =
        ProgressiveLoadWorkRequest(uniqueName(bookId), bookId, generation, delay)

    private fun uniqueName(bookId: String) = "progressive-load-$bookId"
}

class WorkManagerProgressiveLoadQueue(
    private val workManager: WorkManager,
) : ProgressiveLoadWorkQueue {
    override suspend fun enqueue(request: ProgressiveLoadWorkRequest) {
        require(!request.delay.isNegative)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val input = Data.Builder()
            .putString(ProgressiveLoadWorker.BOOK_ID_KEY, request.bookId)
            .putLong(ProgressiveLoadWorker.GENERATION_KEY, request.generation)
            .build()
        val work = OneTimeWorkRequest.Builder(ProgressiveLoadWorker::class.java)
            .setInputData(input)
            .setConstraints(constraints)
            .setInitialDelay(request.delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(request.uniqueName)
            .build()
        workManager
            .enqueueUniqueWork(request.uniqueName, ExistingWorkPolicy.REPLACE, work)
            .awaitSuccess()
    }

    override fun cancel(uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName)
    }
}

private suspend fun Operation.awaitSuccess() {
    suspendCancellableCoroutine { continuation ->
        val future = result
        future.addListener(
            {
                if (!continuation.isActive) return@addListener
                try {
                    future.get()
                    continuation.resume(Unit)
                } catch (failure: ExecutionException) {
                    continuation.resumeWithException(failure.cause ?: failure)
                } catch (failure: Throwable) {
                    continuation.resumeWithException(failure)
                }
            },
            { command -> command.run() },
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
}
