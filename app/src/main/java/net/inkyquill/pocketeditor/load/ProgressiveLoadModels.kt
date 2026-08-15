package net.inkyquill.pocketeditor.load

import androidx.room.Embedded
import androidx.room.Relation
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadJobEntity

enum class ProgressiveLoadPhase { PREPARING, INITIAL, BACKGROUND, PAUSED, CANCELLED, ACTION_REQUIRED, COMPLETE }

enum class ProgressiveLoadFileState { PENDING, DOWNLOADING, CACHED, ACTION_REQUIRED }

enum class ProgressiveLoadErrorCategory {
    OFFLINE, TIMEOUT, RATE_LIMITED, SERVER, TEMPORARY_AVAILABILITY, UNAUTHORIZED, INVALID_REMOTE,
}

data class ProgressiveLoadJobWithFiles(
    @Embedded val job: ProgressiveLoadJobEntity,
    @Relation(parentColumn = "book_id", entityColumn = "book_id")
    val files: List<ProgressiveLoadFileEntity>,
)

data class ProgressiveLoadSnapshot(
    val bookId: String,
    val remoteRootPath: String,
    val phase: ProgressiveLoadPhase,
    val totalFiles: Int,
    val completedFiles: Int,
    val activePath: String?,
    val retryAttempt: Int,
    val retryAt: Long?,
    val generation: Long,
    val paused: Boolean,
    val cancelled: Boolean,
    val lastErrorCategory: ProgressiveLoadErrorCategory?,
    val files: List<ProgressiveLoadFileEntity>,
) {
    val initialReady: Boolean
        get() {
            val requiredIndices = 0 until minOf(INITIAL_CHAPTER_COUNT, totalFiles)
            val initialRows = files.filter { it.spineIndex in requiredIndices }
            return initialRows.size == requiredIndices.count() &&
                requiredIndices.all { index ->
                    initialRows.singleOrNull { it.spineIndex == index }?.state == ProgressiveLoadFileState.CACHED
                }
        }
}

fun ProgressiveLoadJobWithFiles.toSnapshot() = ProgressiveLoadSnapshot(
    job.bookId, job.remoteRootPath, job.phase, job.totalFiles, job.completedFiles,
    job.activePath, job.retryAttempt, job.retryAt, job.generation, job.paused,
    job.cancelled, job.lastErrorCategory, files.sortedBy(ProgressiveLoadFileEntity::spineIndex),
)

fun initialPriority(spineIndex: Int): Int {
    require(spineIndex >= 0)
    return if (spineIndex < INITIAL_CHAPTER_COUNT) INITIAL_PRIORITY else BACKGROUND_PRIORITY
}

const val ON_DEMAND_PRIORITY = 2
const val INITIAL_PRIORITY = 1
const val BACKGROUND_PRIORITY = 0
private const val INITIAL_CHAPTER_COUNT = 3
