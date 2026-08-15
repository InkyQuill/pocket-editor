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

        assertEquals("chapter-3.md", claimed?.path)
        assertEquals(1, dao.getFiles(BOOK_ID).count { it.state == ProgressiveLoadFileState.DOWNLOADING })
        assertEquals(ON_DEMAND_PRIORITY, dao.getFiles(BOOK_ID).single { it.path == "chapter-3.md" }.priority)
    }

    private fun job() = ProgressiveLoadJobEntity(
        bookId = BOOK_ID,
        remoteRootPath = "disk:/Book",
        phase = ProgressiveLoadPhase.INITIAL,
        totalFiles = 4,
        completedFiles = 0,
        activePath = null,
        retryAttempt = 0,
        retryAt = null,
        generation = 1,
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
