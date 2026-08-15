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

    @Query("UPDATE progressive_load_files SET priority = 2 WHERE book_id = :bookId AND path = :path AND state = 'PENDING'")
    suspend fun prioritize(bookId: String, path: String): Int

    @Query("DELETE FROM progressive_load_jobs WHERE book_id = :bookId")
    suspend fun deleteJob(bookId: String)

    @Query("DELETE FROM progressive_load_files WHERE book_id = :bookId")
    suspend fun deleteFiles(bookId: String)

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
