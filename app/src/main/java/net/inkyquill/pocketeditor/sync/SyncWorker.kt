package net.inkyquill.pocketeditor.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

fun interface SyncBookRunner {
    suspend fun syncBook(bookId: String, remoteRootPath: String): SyncStatus
}

enum class SyncWorkerOutcome { SUCCESS, RETRY }

class SyncWorkerLogic(private val runner: SyncBookRunner) {
    suspend fun run(bookId: String, remoteRootPath: String): SyncWorkerOutcome =
        if (runner.syncBook(bookId, remoteRootPath) == SyncStatus.WaitingToSync) {
            SyncWorkerOutcome.RETRY
        } else {
            SyncWorkerOutcome.SUCCESS
        }
}

class SyncWorker internal constructor(
    appContext: Context,
    parameters: WorkerParameters,
    private val logic: SyncWorkerLogic,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(BOOK_ID_KEY) ?: return Result.failure()
        val remoteRootPath = inputData.getString(REMOTE_ROOT_PATH_KEY) ?: return Result.failure()
        return when (logic.run(bookId, remoteRootPath)) {
            SyncWorkerOutcome.SUCCESS -> Result.success()
            SyncWorkerOutcome.RETRY -> Result.retry()
        }
    }

    companion object {
        const val BOOK_ID_KEY = "book_id"
        const val REMOTE_ROOT_PATH_KEY = "remote_root_path"
    }
}

class SyncWorkerFactory(runner: SyncBookRunner) : WorkerFactory() {
    private val logic = SyncWorkerLogic(runner)

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == SyncWorker::class.java.name) {
        SyncWorker(appContext, workerParameters, logic)
    } else {
        null
    }
}
