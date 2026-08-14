package net.inkyquill.pocketeditor.ui.books

import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.book.ImportDraftPhase
import net.inkyquill.pocketeditor.database.ImportDraftDao
import net.inkyquill.pocketeditor.database.ImportDraftEntity
import net.inkyquill.pocketeditor.storage.ImportDraftStore
import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImportDraftRepositoryTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `proposal persists downloaded chapters and reopening ready draft makes no requests`() = runBlocking {
        val dao = FakeImportDraftDao()
        val gateway = CountingGateway()
        val repository = repository(gateway, dao)

        val first = repository.createOrResume(ROOT)
        val reopened = repository.createOrResume("$ROOT/")

        assertEquals(listOf("$ROOT/01.md", "$ROOT/02.md"), gateway.downloadedPaths)
        assertEquals(first, reopened)
        assertEquals(ImportDraftPhase.READY, reopened.phase)
        assertEquals(first.bookId, dao.getByRemoteRoot(ROOT)?.bookId)
    }

    @Test
    fun `retry downloads only chapter missing after offline interruption`() = runBlocking {
        val dao = FakeImportDraftDao()
        val firstGateway = CountingGateway(failPath = "$ROOT/02.md")

        assertThrows(YandexDiskError.Offline::class.java) {
            runBlocking { repository(firstGateway, dao).createOrResume(ROOT) }
        }
        assertEquals(listOf("$ROOT/01.md", "$ROOT/02.md"), firstGateway.downloadedPaths)

        val secondGateway = CountingGateway()
        val resumed = repository(secondGateway, dao).createOrResume(ROOT)

        assertEquals(listOf("$ROOT/02.md"), secondGateway.downloadedPaths)
        assertEquals(ImportDraftPhase.READY, resumed.phase)
        assertEquals(listOf("One", "Two"), resumed.chapters.map(ImportChapterDraft::title))
    }

    @Test
    fun `metadata edits persist and only explicit discard removes cached draft`() = runBlocking {
        val dao = FakeImportDraftDao()
        val repository = repository(CountingGateway(), dao)
        val draft = repository.createOrResume(ROOT)
        val edited = draft.copy(
            title = "My book",
            chapters = draft.chapters.reversed().mapIndexed { index, chapter ->
                chapter.copy(title = "Chapter ${index + 1}", included = index == 0)
            },
        )

        repository.update(edited)

        assertEquals(edited, repository.resume(draft.bookId))
        repository.discard(draft.bookId)
        assertEquals(null, dao.getByBookId(draft.bookId))
        assertEquals(false, File(root, "import-drafts/${draft.bookId}").exists())
    }

    @Test
    fun `cached chapters derive title from downloaded source`() = runBlocking {
        val repository = repository(CountingGateway(), FakeImportDraftDao())
        val draft = repository.createOrResume(ROOT)

        repository.update(
            draft.copy(chapters = draft.chapters.map { it.copy(title = "Stale edited title") }),
        )

        assertEquals("One", repository.cachedChapters(draft.bookId).first().title)
    }

    @Test
    fun `mismatched cached source cannot be returned for promotion`() = runBlocking {
        val repository = repository(CountingGateway(), FakeImportDraftDao())
        val draft = repository.createOrResume(ROOT)
        File(root, "import-drafts/${draft.bookId}/01.md").writeText("# Tampered\n")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.cachedChapters(draft.bookId) }
        }
    }

    private fun repository(gateway: CountingGateway, dao: FakeImportDraftDao) = ImportDraftRepository(
        gateway = gateway,
        drafts = dao,
        store = ImportDraftStore(File(root, "import-drafts")),
        bookIdFactory = { BOOK_ID },
        chapterIdFactory = sequenceOf(CHAPTER_ONE, CHAPTER_TWO).iterator()::next,
        currentTimeMillis = { 100L },
    )

    private class FakeImportDraftDao : ImportDraftDao {
        private val values = linkedMapOf<String, ImportDraftEntity>()

        override fun observeAll(): Flow<List<ImportDraftEntity>> = flowOf(values.values.toList())
        override suspend fun getAll(): List<ImportDraftEntity> = values.values.toList()
        override suspend fun getByBookId(bookId: String): ImportDraftEntity? = values[bookId]
        override suspend fun getByRemoteRoot(remoteRootPath: String): ImportDraftEntity? =
            values.values.singleOrNull { it.remoteRootPath == remoteRootPath }

        override suspend fun upsert(draft: ImportDraftEntity) {
            values.entries.removeAll { it.value.remoteRootPath == draft.remoteRootPath && it.key != draft.bookId }
            values[draft.bookId] = draft
        }

        override suspend fun delete(bookId: String) {
            values.remove(bookId)
        }
    }

    private class CountingGateway(
        private val failPath: String? = null,
    ) : YandexDiskGateway {
        val downloadedPaths = mutableListOf<String>()
        private val files = linkedMapOf(
            "$ROOT/01.md" to "# One\n".encodeToByteArray(),
            "$ROOT/02.md" to "# Two\n".encodeToByteArray(),
        )

        override suspend fun listFolder(path: String): List<RemoteEntry> = files.map { (remotePath, bytes) ->
            RemoteEntry(
                name = remotePath.substringAfterLast('/'),
                path = remotePath,
                type = "file",
                size = bytes.size.toLong(),
                revision = "rev-${remotePath.substringAfterLast('/')}",
            )
        }

        override suspend fun download(path: String): RemoteFile {
            downloadedPaths += path
            if (path == failPath) throw YandexDiskError.Offline(IOException("offline"))
            return RemoteFile(path, files.getValue(path), "rev-${path.substringAfterLast('/')}")
        }

        override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock): SyncLock = error("not used")
        override suspend fun readLock(rootPath: String): SyncLock = error("not used")
        override suspend fun uploadGuarded(
            rootPath: String,
            relativePath: String,
            bytes: ByteArray,
            ownedLock: SyncLock,
        ): String = error("not used")

        override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) = error("not used")
        override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) = error("not used")
    }

    private companion object {
        const val ROOT = "disk:/growth-cheat/result/book01"
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ONE = "22222222-2222-2222-2222-222222222222"
        const val CHAPTER_TWO = "33333333-3333-3333-3333-333333333333"
    }
}
