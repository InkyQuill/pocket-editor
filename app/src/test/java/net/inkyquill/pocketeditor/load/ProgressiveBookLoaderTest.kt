package net.inkyquill.pocketeditor.load

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.ProgressiveLoadDao
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadJobEntity
import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class ProgressiveBookLoaderTest {
    @Test
    fun `raw folder uses normalized case-folded path order and generates each id once`() = runTest {
        val gateway = CountingGateway(listOf(entry("b.md", "rb"), entry("a.md", "ra"), entry("A.md", "rA"), entry("notes.txt", "rn")))
        val installer = RecordingInstaller()
        val ids = ArrayDeque(listOf(CHAPTER_B, CHAPTER_A_UPPER, CHAPTER_A_LOWER))
        val loader = ProgressiveBookLoader.builderOnly(gateway, EmptyLoads, installer, chapterIdFactory = ids::removeFirst)

        loader.start("disk:/Book")
        loader.start("disk:/Book")

        assertEquals(1, gateway.listCalls)
        assertEquals(listOf("A.md", "a.md", "b.md"), installer.seed.manifest.chapters.map(ChapterEntry::path))
        assertEquals(listOf(CHAPTER_B, CHAPTER_A_UPPER, CHAPTER_A_LOWER), installer.seed.manifest.chapters.map(ChapterEntry::id))
        assertTrue(installer.seed.rawBinder)
        assertEquals(listOf(1, 1, 1), installer.seed.files.map(ProgressiveLoadFileEntity::priority))
    }

    @Test
    fun `manifest folder preserves full binder ids and order`() = runTest {
        val manifest = BookManifest(bookId = BOOK_ID, title = "Aria", chapters = listOf(ChapterEntry(CHAPTER_2, "z.md"), ChapterEntry(CHAPTER_1, "a.md")))
        val bytes = BookManifest.encode(manifest).encodeToByteArray()
        val gateway = CountingGateway(
            listOf(entry("a.md", "ra"), entry("z.md", "rz"), entry(".pocket-editor.json", "rm")),
            mapOf("disk:/Book/.pocket-editor.json" to RemoteFile("disk:/Book/.pocket-editor.json", bytes, "rm")),
        )
        val installer = RecordingInstaller()

        ProgressiveBookLoader.builderOnly(gateway, EmptyLoads, installer).start("disk:/Book")

        assertEquals(1, gateway.listCalls)
        assertEquals(listOf(CHAPTER_2, CHAPTER_1), installer.seed.manifest.chapters.map(ChapterEntry::id))
        assertEquals(listOf("z.md", "a.md"), installer.seed.files.map(ProgressiveLoadFileEntity::path))
        assertFalse(installer.seed.rawBinder)
        assertEquals(listOf("disk:/Book/.pocket-editor.json"), gateway.downloadedPaths)
    }

    @Test
    fun `raw normalization collisions are rejected before downloads`() = runTest {
        val gateway = CountingGateway(listOf(entry("é.md", "r1"), entry("e\u0301.md", "r2")))
        assertThrows<net.inkyquill.pocketeditor.yandex.YandexDiskError.InvalidRemote> {
            ProgressiveBookLoader.builderOnly(gateway, EmptyLoads, RecordingInstaller()).start("disk:/Book")
        }
        assertTrue(gateway.downloadedPaths.isEmpty())
    }

    @Test
    fun `invalid manifest structures are rejected without source downloads`() = runTest {
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        val invalidGateway = CountingGateway(
            listOf(entry(".pocket-editor.json", "rm")),
            mapOf("disk:/Book/.pocket-editor.json" to RemoteFile("disk:/Book/.pocket-editor.json", invalidUtf8, "rm")),
        )
        assertThrows<net.inkyquill.pocketeditor.yandex.YandexDiskError.InvalidRemote> {
            ProgressiveBookLoader.builderOnly(invalidGateway, EmptyLoads, RecordingInstaller()).start("disk:/Book")
        }
        assertEquals(listOf("disk:/Book/.pocket-editor.json"), invalidGateway.downloadedPaths)

        val missing = BookManifest(bookId = BOOK_ID, title = "Missing", chapters = listOf(ChapterEntry(CHAPTER_1, "missing.md")))
        val missingBytes = BookManifest.encode(missing).encodeToByteArray()
        val missingGateway = CountingGateway(
            listOf(entry(".pocket-editor.json", "rm")),
            mapOf("disk:/Book/.pocket-editor.json" to RemoteFile("disk:/Book/.pocket-editor.json", missingBytes, "rm")),
        )
        assertThrows<net.inkyquill.pocketeditor.yandex.YandexDiskError.InvalidRemote> {
            ProgressiveBookLoader.builderOnly(missingGateway, EmptyLoads, RecordingInstaller()).start("disk:/Book")
        }
        assertEquals(listOf("disk:/Book/.pocket-editor.json"), missingGateway.downloadedPaths)
    }

    private class RecordingInstaller : ProgressiveSeedInstaller {
        lateinit var seed: ProgressiveBookSeed
        private var snapshot: ProgressiveLoadSnapshot? = null
        override suspend fun install(seed: ProgressiveBookSeed, cachedSources: Map<String, ByteArray>): ProgressiveLoadSnapshot {
            this.seed = seed
            return snapshot ?: ProgressiveLoadSnapshot(seed.manifest.bookId, seed.remoteRootPath, ProgressiveLoadPhase.INITIAL, seed.files.size, 0, null, 0, null, 0, false, false, null, seed.files).also { snapshot = it }
        }
    }

    private class CountingGateway(private val entries: List<RemoteEntry>, private val downloads: Map<String, RemoteFile> = emptyMap()) : YandexDiskGateway {
        var listCalls = 0
        val downloadedPaths = mutableListOf<String>()
        override suspend fun listFolder(path: String): List<RemoteEntry> = entries.also { listCalls++ }
        override suspend fun download(path: String): RemoteFile = downloads.getValue(path).also { downloadedPaths += path }
        override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock) = error("unused")
        override suspend fun readLock(rootPath: String) = error("unused")
        override suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock) = error("unused")
        override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) = error("unused")
        override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) = error("unused")
    }

    private object EmptyLoads : ProgressiveLoadDao {
        override suspend fun insertJob(job: ProgressiveLoadJobEntity) = Unit
        override suspend fun insertFiles(files: List<ProgressiveLoadFileEntity>) = Unit
        override fun observe(bookId: String): Flow<ProgressiveLoadJobWithFiles?> = emptyFlow()
        override fun observeAll(): Flow<List<ProgressiveLoadJobWithFiles>> = emptyFlow()
        override suspend fun getJob(bookId: String): ProgressiveLoadJobEntity? = null
        override suspend fun getJobByRemoteRoot(remoteRootPath: String): ProgressiveLoadJobEntity? = null
        override suspend fun getFiles(bookId: String): List<ProgressiveLoadFileEntity> = emptyList()
        override fun observeChapter(bookId: String, chapterId: String): Flow<ProgressiveLoadFileEntity?> = emptyFlow()
        override suspend fun nextPending(bookId: String): ProgressiveLoadFileEntity? = null
        override suspend fun updateJob(job: ProgressiveLoadJobEntity) = Unit
        override suspend fun updateFile(file: ProgressiveLoadFileEntity) = Unit
        override suspend fun prioritize(bookId: String, path: String): Int = 0
        override suspend fun deleteJob(bookId: String) = Unit
        override suspend fun deleteFiles(bookId: String) = Unit
    }

    private fun entry(name: String, revision: String) = RemoteEntry(name, "disk:/Book/$name", "file", 1, revision)

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_1 = "22222222-2222-2222-2222-222222222222"
        const val CHAPTER_2 = "33333333-3333-3333-3333-333333333333"
        const val CHAPTER_B = "44444444-4444-4444-4444-444444444444"
        const val CHAPTER_A_UPPER = "55555555-5555-5555-5555-555555555555"
        const val CHAPTER_A_LOWER = "66666666-6666-6666-6666-666666666666"
    }
}
