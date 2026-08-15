package net.inkyquill.pocketeditor.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.load.INITIAL_PRIORITY
import net.inkyquill.pocketeditor.load.ON_DEMAND_PRIORITY
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressiveLoadDaoTest {
    private lateinit var database: PocketEditorDatabase
    private lateinit var dao: ProgressiveLoadDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PocketEditorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.progressiveLoadDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun claimIsSequentialAndPriorityRequestCoalesces() = runBlocking {
        dao.insertJob(job())
        dao.insertFiles((0..3).map(::file))
        dao.prioritize(BOOK_ID, "chapter-3.md")
        dao.prioritize(BOOK_ID, "chapter-3.md")

        val claimed = dao.claimNext(BOOK_ID, generation = 1)
        val persisted = dao.getFiles(BOOK_ID).single { it.path == "chapter-3.md" }

        assertEquals("chapter-3.md", claimed?.path)
        assertEquals(1L, claimed?.claimGeneration)
        assertEquals(persisted, claimed)
        assertEquals(1, dao.getFiles(BOOK_ID).count { it.state == ProgressiveLoadFileState.DOWNLOADING })
        assertEquals(ON_DEMAND_PRIORITY, dao.getFiles(BOOK_ID).single { it.path == "chapter-3.md" }.priority)
    }

    @Test
    fun staleRestoreDoesNotClearNewerClaim() = runBlocking {
        dao.insertJob(job(generation = 1))
        dao.insertFiles(listOf(file(0)))
        dao.claimNext(BOOK_ID, generation = 1)
        dao.updateJob(requireNotNull(dao.getJob(BOOK_ID)).copy(generation = 2))
        dao.updateFile(dao.getFiles(BOOK_ID).single().copy(
            state = ProgressiveLoadFileState.PENDING,
            claimGeneration = null,
        ))
        dao.claimNext(BOOK_ID, generation = 2)

        dao.restorePending(BOOK_ID, "chapter-0.md", generation = 1, category = null, retryAttempt = 1, retryAt = 20)

        assertEquals("chapter-0.md", dao.getJob(BOOK_ID)?.activePath)
        assertEquals(ProgressiveLoadFileState.DOWNLOADING, dao.getFiles(BOOK_ID).single().state)
        assertEquals(2L, dao.getFiles(BOOK_ID).single().claimGeneration)
    }

    private fun job(generation: Long = 1) = ProgressiveLoadJobEntity(
        bookId = BOOK_ID,
        remoteRootPath = "disk:/Book",
        phase = ProgressiveLoadPhase.INITIAL,
        totalFiles = 4,
        completedFiles = 0,
        activePath = null,
        retryAttempt = 0,
        retryAt = null,
        generation = generation,
        paused = false,
        cancelled = false,
        lastErrorCategory = null,
    )

    private fun file(index: Int) = ProgressiveLoadFileEntity(
        bookId = BOOK_ID,
        path = "chapter-$index.md",
        chapterId = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        spineIndex = index,
        expectedRevision = "r$index",
        expectedSize = 10,
        sha256 = null,
        state = ProgressiveLoadFileState.PENDING,
        priority = INITIAL_PRIORITY,
    )

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
    }
}
