package net.inkyquill.pocketeditor.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressiveLoadRequestDaoTest {
    private lateinit var database: PocketEditorDatabase
    private lateinit var dao: ProgressiveLoadRequestDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PocketEditorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.progressiveLoadRequestDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun requestIdentityCannotBeClaimedByTwoRoots() = runBlocking {
        val first = request(requestId = "shared", generation = 1)
        val collision = request(ROOT + "-other", requestId = "shared", generation = 1)

        val firstRowId = dao.insertIgnore(first)
        val collisionRowId = dao.insertIgnore(collision)

        assertEquals(true, firstRowId >= 0)
        assertEquals(-1L, collisionRowId)
        assertEquals(first, dao.getByRequestId("shared"))
        assertEquals(listOf(first), dao.getAll())
        assertEquals(listOf(first), dao.observeAll().first())
    }

    @Test
    fun compareAndSetAndDeleteRejectStaleGenerations() = runBlocking {
        val first = request(requestId = "request", generation = 4)
        dao.insertIgnore(first)
        val next = first.copy(
            generation = 5,
            phase = ProgressiveLoadPhase.PAUSED,
            retryAttempt = 2,
            retryAt = 400,
            lastErrorCategory = ProgressiveLoadErrorCategory.TIMEOUT,
            paused = true,
            cancelled = false,
            updatedAt = 500,
        )

        assertEquals(false, dao.compareAndSet(next, expectedGeneration = 3))
        assertEquals(first, dao.get(ROOT))
        assertEquals(true, dao.compareAndSet(next, expectedGeneration = 4))
        assertEquals(next, dao.get(ROOT))
        assertEquals(0, dao.deleteIfGeneration(ROOT, "request", expectedGeneration = 4))
        assertEquals(1, dao.deleteIfGeneration(ROOT, "request", expectedGeneration = 5))
        assertEquals(null, dao.get(ROOT))
    }

    @Test
    fun staleRequestCannotMutateReplacementWithSameRootAndGeneration() = runBlocking {
        val staleA = request(requestId = "request-a", generation = 0)
        dao.insertIgnore(staleA)
        assertEquals(1, dao.deleteIfGeneration(ROOT, "request-a", expectedGeneration = 0))
        val currentB = request(requestId = "request-b", generation = 0).copy(updatedAt = 200)
        dao.insertIgnore(currentB)

        val staleUpdate = staleA.copy(
            generation = 1,
            phase = ProgressiveLoadPhase.PAUSED,
            paused = true,
            updatedAt = 300,
        )

        assertEquals(false, dao.compareAndSet(staleUpdate, expectedGeneration = 0))
        assertEquals(0, dao.deleteIfGeneration(ROOT, "request-a", expectedGeneration = 0))
        assertEquals(currentB, dao.get(ROOT))
    }

    private fun request(
        remoteRootPath: String = ROOT,
        requestId: String,
        generation: Long,
    ) = ProgressiveLoadRequestEntity(
        remoteRootPath = remoteRootPath,
        requestId = requestId,
        generation = generation,
        phase = ProgressiveLoadPhase.PREPARING,
        retryAttempt = 0,
        retryAt = null,
        lastErrorCategory = null,
        paused = false,
        cancelled = false,
        updatedAt = 100,
    )

    private companion object {
        const val ROOT = "disk:/Book"
    }
}
