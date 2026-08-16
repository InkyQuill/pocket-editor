package net.inkyquill.pocketeditor.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import java.time.Duration
import net.inkyquill.pocketeditor.storage.StartupRecoveryBarrier

fun interface SyncBookRunner {
    suspend fun syncBook(bookId: String, remoteRootPath: String): SyncStatus
}

sealed interface SyncWorkerOutcome {
    data object SUCCESS : SyncWorkerOutcome
    data object TERMINAL : SyncWorkerOutcome
    data class RETRY(val minimumDelay: Duration? = null) : SyncWorkerOutcome
    data object STALE : SyncWorkerOutcome
    data object NO_VALIDATED_NETWORK : SyncWorkerOutcome
}

class SyncWorkerLogic(
    private val runner: SyncBookRunner,
    private val generations: RetryGenerationStore? = null,
    private val network: NetworkAvailability = NetworkAvailability { true },
    private val startupRecovery: StartupRecoveryBarrier? = null,
) {
    suspend fun run(
        bookId: String,
        remoteRootPath: String,
        isRetry: Boolean = false,
        retryGeneration: Long = 0L,
    ): SyncWorkerOutcome {
        startupRecovery?.await()
        if (isRetry && generations?.isCurrent(bookId, retryGeneration) != true) return SyncWorkerOutcome.STALE
        if (!network.hasValidatedInternet()) return SyncWorkerOutcome.NO_VALIDATED_NETWORK
        return when (val status = runner.syncBook(bookId, remoteRootPath)) {
            SyncStatus.Saved -> SyncWorkerOutcome.SUCCESS
            is SyncStatus.WaitingToSync -> SyncWorkerOutcome.RETRY(status.retryAfter)
            else -> SyncWorkerOutcome.TERMINAL
        }
    }
}

class SyncWorkerCompletion(
    private val queue: SyncWorkQueue,
    private val generations: RetryGenerationStore,
) {
    fun complete(
        bookId: String,
        remoteRootPath: String,
        outcome: SyncWorkerOutcome,
        retryAttempt: Int,
        retryGeneration: Long = generations.current(bookId),
    ) {
        require(retryAttempt >= 0)
        when (outcome) {
            SyncWorkerOutcome.SUCCESS,
            SyncWorkerOutcome.TERMINAL,
            -> if (generations.invalidateIfCurrent(bookId, retryGeneration)) {
                queue.cancel("sync-retry-$bookId")
            }
            SyncWorkerOutcome.STALE -> Unit
            is SyncWorkerOutcome.RETRY -> {
                SyncRetryLauncher(queue, generations).launch(
                    bookId,
                    remoteRootPath,
                    retryAttempt.coerceAtMost(Int.MAX_VALUE - 1) + 1,
                    retryGeneration,
                    outcome.minimumDelay,
                )
            }
            SyncWorkerOutcome.NO_VALIDATED_NETWORK -> {
                SyncRetryLauncher(queue, generations).launch(
                    bookId,
                    remoteRootPath,
                    retryAttempt.coerceAtMost(Int.MAX_VALUE - 1) + 1,
                    retryGeneration,
                )
            }
        }
    }
}

class SyncWorker internal constructor(
    appContext: Context,
    parameters: WorkerParameters,
    private val logic: SyncWorkerLogic,
    private val queue: SyncWorkQueue,
    private val generations: RetryGenerationStore,
    private val startupRecovery: StartupRecoveryBarrier? = null,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        startupRecovery?.await()
        val bookId = inputData.getString(BOOK_ID_KEY) ?: return Result.failure()
        val remoteRootPath = inputData.getString(REMOTE_ROOT_PATH_KEY) ?: return Result.failure()
        val retryAttempt = inputData.getInt(RETRY_ATTEMPT_KEY, 0)
        val retryGeneration = inputData.getLong(RETRY_GENERATION_KEY, 0L)
        val isRetry = inputData.getBoolean(IS_RETRY_KEY, false)
        val outcome = logic.run(bookId, remoteRootPath, isRetry, retryGeneration)
        SyncWorkerCompletion(queue, generations).complete(
            bookId,
            remoteRootPath,
            outcome,
            retryAttempt,
            retryGeneration,
        )
        return Result.success()
    }

    companion object {
        const val BOOK_ID_KEY = "book_id"
        const val REMOTE_ROOT_PATH_KEY = "remote_root_path"
        const val TRIGGER_KEY = "trigger"
        const val RETRY_ATTEMPT_KEY = "retry_attempt"
        const val RETRY_GENERATION_KEY = "retry_generation"
        const val IS_RETRY_KEY = "is_retry"
    }
}

class SyncDebounceWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val queue: SyncWorkQueue,
    private val generations: RetryGenerationStore,
    private val startupRecovery: StartupRecoveryBarrier? = null,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        startupRecovery?.await()
        val bookId = inputData.getString(SyncWorker.BOOK_ID_KEY) ?: return Result.failure()
        val remoteRootPath = inputData.getString(SyncWorker.REMOTE_ROOT_PATH_KEY) ?: return Result.failure()
        val trigger = inputData.getString(SyncWorker.TRIGGER_KEY)
            ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val retryAttempt = inputData.getInt(SyncWorker.RETRY_ATTEMPT_KEY, 0)
        val retryGeneration = inputData.getLong(SyncWorker.RETRY_GENERATION_KEY, 0L)
        val isRetry = inputData.getBoolean(SyncWorker.IS_RETRY_KEY, false)
        if (isRetry) {
            SyncRetryLauncher(queue, generations).appendIfCurrent(
                bookId,
                remoteRootPath,
                retryAttempt,
                retryGeneration,
            )
        } else if (acceptsDebounceGeneration(generations.current(bookId), retryGeneration)) {
            queue.enqueue(
                SyncScheduler.activeRequest(
                    bookId,
                    remoteRootPath,
                    trigger,
                    retryAttempt,
                    retryGeneration,
                ),
            )
        }
        return Result.success()
    }
}

internal fun acceptsDebounceGeneration(current: Long, requested: Long): Boolean =
    requested == current || requested == nextRetryGeneration(current)

class SyncWorkerFactory(
    private val runner: SyncBookRunner,
    internal val syncWorkQueue: SyncWorkQueue,
    internal val retryGenerationStore: RetryGenerationStore,
    private val network: NetworkAvailability = NetworkAvailability { true },
    private val startupRecovery: StartupRecoveryBarrier? = null,
) : WorkerFactory() {

    internal fun supports(workerClassName: String): Boolean =
        workerClassName == SyncWorker::class.java.name || workerClassName == SyncDebounceWorker::class.java.name

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(
            appContext,
            workerParameters,
            SyncWorkerLogic(runner, retryGenerationStore, network, startupRecovery),
            syncWorkQueue,
            retryGenerationStore,
            startupRecovery,
        )
        SyncDebounceWorker::class.java.name -> SyncDebounceWorker(
            appContext,
            workerParameters,
            syncWorkQueue,
            retryGenerationStore,
            startupRecovery,
        )
        else -> null
    }
}

class PocketEditorWorkerFactory(
    private vararg val delegates: WorkerFactory,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = delegates.firstNotNullOfOrNull { delegate ->
        delegate.createWorker(appContext, workerClassName, workerParameters)
    }
}
