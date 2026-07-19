package net.inkyquill.pocketeditor.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager

fun interface SyncBookRunner {
    suspend fun syncBook(bookId: String, remoteRootPath: String): SyncStatus
}

internal const val MAX_RETRY_ATTEMPTS = 5

enum class SyncWorkerOutcome { SUCCESS, TERMINAL, RETRY }

class SyncWorkerLogic(private val runner: SyncBookRunner) {
    suspend fun run(bookId: String, remoteRootPath: String): SyncWorkerOutcome =
        when (runner.syncBook(bookId, remoteRootPath)) {
            SyncStatus.Saved -> SyncWorkerOutcome.SUCCESS
            SyncStatus.WaitingToSync -> SyncWorkerOutcome.RETRY
            else -> SyncWorkerOutcome.TERMINAL
        }
}

class SyncWorkerCompletion(private val queue: SyncWorkQueue) {
    fun complete(
        bookId: String,
        remoteRootPath: String,
        outcome: SyncWorkerOutcome,
        retryAttempt: Int,
    ) {
        require(retryAttempt >= 0)
        when (outcome) {
            SyncWorkerOutcome.SUCCESS -> queue.cancel("sync-retry-$bookId")
            SyncWorkerOutcome.TERMINAL -> Unit
            SyncWorkerOutcome.RETRY -> if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                SyncRetryLauncher(queue).launch(bookId, remoteRootPath, retryAttempt + 1)
            }
        }
    }
}

class SyncWorker internal constructor(
    appContext: Context,
    parameters: WorkerParameters,
    private val logic: SyncWorkerLogic,
    private val queue: SyncWorkQueue,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(BOOK_ID_KEY) ?: return Result.failure()
        val remoteRootPath = inputData.getString(REMOTE_ROOT_PATH_KEY) ?: return Result.failure()
        val retryAttempt = inputData.getInt(RETRY_ATTEMPT_KEY, 0)
        val outcome = logic.run(bookId, remoteRootPath)
        SyncWorkerCompletion(queue).complete(bookId, remoteRootPath, outcome, retryAttempt)
        return Result.success()
    }

    companion object {
        const val BOOK_ID_KEY = "book_id"
        const val REMOTE_ROOT_PATH_KEY = "remote_root_path"
        const val TRIGGER_KEY = "trigger"
        const val RETRY_ATTEMPT_KEY = "retry_attempt"
    }
}

class SyncDebounceWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(SyncWorker.BOOK_ID_KEY) ?: return Result.failure()
        val remoteRootPath = inputData.getString(SyncWorker.REMOTE_ROOT_PATH_KEY) ?: return Result.failure()
        val trigger = inputData.getString(SyncWorker.TRIGGER_KEY)
            ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val retryAttempt = inputData.getInt(SyncWorker.RETRY_ATTEMPT_KEY, 0)
        val queue = WorkManagerSyncWorkQueue(WorkManager.getInstance(applicationContext))
        queue.enqueue(SyncScheduler.activeRequest(bookId, remoteRootPath, trigger, retryAttempt))
        return Result.success()
    }
}

class SyncWorkerFactory(runner: SyncBookRunner) : WorkerFactory() {
    private val logic = SyncWorkerLogic(runner)

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == SyncWorker::class.java.name) {
        SyncWorker(
            appContext,
            workerParameters,
            logic,
            WorkManagerSyncWorkQueue(WorkManager.getInstance(appContext)),
        )
    } else {
        null
    }
}
