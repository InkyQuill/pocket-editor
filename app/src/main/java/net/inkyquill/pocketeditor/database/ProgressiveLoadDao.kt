package net.inkyquill.pocketeditor.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.inkyquill.pocketeditor.load.BACKGROUND_PRIORITY
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.load.ProgressiveLoadJobWithFiles
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.load.toSnapshot
import net.inkyquill.pocketeditor.load.initialPriority

@Dao
interface ProgressiveLoadDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: ProgressiveLoadJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFiles(files: List<ProgressiveLoadFileEntity>)

    @Transaction
    @Query("SELECT * FROM progressive_load_jobs WHERE book_id = :bookId")
    fun observe(bookId: String): Flow<ProgressiveLoadJobWithFiles?>

    @Transaction
    @Query("SELECT * FROM progressive_load_jobs ORDER BY book_id")
    fun observeAll(): Flow<List<ProgressiveLoadJobWithFiles>>

    @Query("SELECT * FROM progressive_load_jobs WHERE book_id = :bookId")
    suspend fun getJob(bookId: String): ProgressiveLoadJobEntity?

    @Query("SELECT * FROM progressive_load_jobs WHERE remote_root_path = :remoteRootPath LIMIT 1")
    suspend fun getJobByRemoteRoot(remoteRootPath: String): ProgressiveLoadJobEntity?

    @Query("SELECT * FROM progressive_load_files WHERE book_id = :bookId ORDER BY spine_index")
    suspend fun getFiles(bookId: String): List<ProgressiveLoadFileEntity>

    @Query("SELECT * FROM progressive_load_files WHERE book_id = :bookId AND chapter_id = :chapterId")
    fun observeChapter(bookId: String, chapterId: String): Flow<ProgressiveLoadFileEntity?>

    @Query("SELECT * FROM progressive_load_files WHERE book_id = :bookId AND state = 'PENDING' ORDER BY priority DESC, spine_index ASC LIMIT 1")
    suspend fun nextPending(bookId: String): ProgressiveLoadFileEntity?

    @Update
    suspend fun updateJob(job: ProgressiveLoadJobEntity)

    @Update
    suspend fun updateFile(file: ProgressiveLoadFileEntity)

    @Query("UPDATE progressive_load_files SET priority = 2 WHERE book_id = :bookId AND path = :path AND state = 'PENDING' AND priority < 2")
    suspend fun prioritize(bookId: String, path: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM progressive_load_files f JOIN progressive_load_jobs j ON j.book_id = f.book_id " +
            "WHERE f.book_id = :bookId AND f.path = :path AND f.state = 'DOWNLOADING' " +
            "AND f.claim_generation = :generation AND j.generation = :generation)",
    )
    suspend fun ownsClaim(bookId: String, path: String, generation: Long): Boolean

    @Query("DELETE FROM progressive_load_jobs WHERE book_id = :bookId")
    suspend fun deleteJob(bookId: String)

    @Query("DELETE FROM progressive_load_files WHERE book_id = :bookId")
    suspend fun deleteFiles(bookId: String)

    @Transaction
    suspend fun snapshot(bookId: String) = getJob(bookId)?.let { job ->
        ProgressiveLoadJobWithFiles(job, getFiles(bookId)).toSnapshot()
    }

    @Transaction
    suspend fun resetCachedMismatch(bookId: String, path: String) {
        val job = getJob(bookId) ?: return
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        if (file.state != ProgressiveLoadFileState.CACHED) return
        updateFile(file.copy(
            state = ProgressiveLoadFileState.PENDING,
            sha256 = null,
            priority = initialPriority(file.spineIndex),
            claimGeneration = null,
        ))
        val files = getFiles(bookId)
        val completed = files.count { it.state == ProgressiveLoadFileState.CACHED }
        val initialReady = files.sortedBy { it.spineIndex }.take(minOf(3, files.size))
            .all { it.state == ProgressiveLoadFileState.CACHED }
        updateJob(job.copy(
            phase = if (initialReady) ProgressiveLoadPhase.BACKGROUND else ProgressiveLoadPhase.INITIAL,
            completedFiles = completed,
            activePath = null,
        ))
    }

    @Transaction
    suspend fun markActionRequired(bookId: String, path: String, generation: Long, category: ProgressiveLoadErrorCategory) {
        val job = getJob(bookId) ?: return
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        if (job.generation != generation || file.claimGeneration != generation) return
        updateFile(file.copy(state = ProgressiveLoadFileState.ACTION_REQUIRED, claimGeneration = null))
        updateJob(job.copy(
            phase = ProgressiveLoadPhase.ACTION_REQUIRED,
            activePath = null,
            retryAt = null,
            lastErrorCategory = category,
        ))
    }

    @Transaction
    suspend fun pauseUnauthorized(bookId: String, path: String, generation: Long) {
        val job = getJob(bookId) ?: return
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        if (job.generation != generation || file.claimGeneration != generation) return
        updateFile(file.copy(state = ProgressiveLoadFileState.PENDING, claimGeneration = null))
        updateJob(job.copy(
            phase = ProgressiveLoadPhase.PAUSED,
            activePath = null,
            retryAt = null,
            paused = true,
            lastErrorCategory = ProgressiveLoadErrorCategory.UNAUTHORIZED,
        ))
    }

    @Transaction
    suspend fun resetActionRequired(bookId: String) {
        val job = getJob(bookId) ?: return
        val reset = getFiles(bookId).map { file ->
            if (file.state == ProgressiveLoadFileState.ACTION_REQUIRED) {
                file.copy(
                    state = ProgressiveLoadFileState.PENDING,
                    priority = initialPriority(file.spineIndex),
                    claimGeneration = null,
                )
            } else {
                file
            }
        }
        reset.forEach { updateFile(it) }
        val initialReady = reset.sortedBy { it.spineIndex }.take(minOf(3, reset.size))
            .all { it.state == ProgressiveLoadFileState.CACHED }
        updateJob(job.copy(
            phase = if (initialReady) ProgressiveLoadPhase.BACKGROUND else ProgressiveLoadPhase.INITIAL,
            activePath = null,
            retryAttempt = 0,
            retryAt = null,
            paused = false,
            cancelled = false,
            lastErrorCategory = null,
        ))
    }

    @Transaction
    suspend fun claimNext(bookId: String, generation: Long): ProgressiveLoadFileEntity? {
        val job = getJob(bookId) ?: return null
        if (job.generation != generation || job.paused || job.cancelled || job.phase == ProgressiveLoadPhase.ACTION_REQUIRED) return null
        check(getFiles(bookId).none { it.state == ProgressiveLoadFileState.DOWNLOADING })
        val next = nextPending(bookId) ?: return null
        val claimed = next.copy(state = ProgressiveLoadFileState.DOWNLOADING, claimGeneration = generation)
        updateFile(claimed)
        updateJob(job.copy(activePath = next.path, retryAt = null, lastErrorCategory = null))
        return claimed
    }

    @Transaction
    suspend fun markCached(bookId: String, path: String, generation: Long, sha256: String) {
        val job = requireNotNull(getJob(bookId))
        if (job.generation != generation) return
        val file = getFiles(bookId).single { it.path == path }
        if (file.claimGeneration != generation) return
        updateFile(file.copy(
            state = ProgressiveLoadFileState.CACHED,
            sha256 = sha256,
            priority = BACKGROUND_PRIORITY,
            claimGeneration = null,
        ))
        val completed = getFiles(bookId).count { it.state == ProgressiveLoadFileState.CACHED }
        val phase = when {
            completed == job.totalFiles -> ProgressiveLoadPhase.COMPLETE
            getFiles(bookId).sortedBy { it.spineIndex }.take(minOf(3, job.totalFiles))
                .all { it.state == ProgressiveLoadFileState.CACHED } -> ProgressiveLoadPhase.BACKGROUND
            else -> ProgressiveLoadPhase.INITIAL
        }
        updateJob(job.copy(phase = phase, completedFiles = completed, activePath = null, retryAttempt = 0, retryAt = null))
    }

    @Transaction
    suspend fun restorePending(
        bookId: String,
        path: String,
        generation: Long,
        category: ProgressiveLoadErrorCategory?,
        retryAttempt: Int,
        retryAt: Long?,
    ) {
        val job = getJob(bookId) ?: return
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        val ownsFile =
            file.state == ProgressiveLoadFileState.DOWNLOADING &&
            file.claimGeneration == generation
        if (!ownsFile) return
        updateFile(file.copy(state = ProgressiveLoadFileState.PENDING, claimGeneration = null))
        if (job.generation == generation) {
            updateJob(job.copy(
                activePath = job.activePath.takeUnless { it == path },
                retryAttempt = retryAttempt,
                retryAt = retryAt,
                lastErrorCategory = category,
            ))
        } else if (job.activePath == path) {
            updateJob(job.copy(activePath = null))
        }
    }
}
