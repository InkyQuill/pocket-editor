package net.inkyquill.pocketeditor.load

import java.util.UUID
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.book.ImportDraftChapter
import net.inkyquill.pocketeditor.book.ImportDraftDocument
import net.inkyquill.pocketeditor.book.ImportDraftPhase
import net.inkyquill.pocketeditor.database.ImportDraftDao
import net.inkyquill.pocketeditor.database.ImportDraftEntity
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadDao
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadJobEntity
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.PendingDeletionEntity
import net.inkyquill.pocketeditor.database.PendingPublicationEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.SyncDao
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.search.SearchDao
import net.inkyquill.pocketeditor.search.SearchEntity
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.storage.ImportDraftStore
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.ui.books.LibraryTransaction
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
    fun `installer seed validation rejects row identity and spine corruption`() {
        val manifest = BookManifest(
            bookId = BOOK_ID,
            title = "Seed",
            chapters = listOf(ChapterEntry(CHAPTER_1, "a.md"), ChapterEntry(CHAPTER_2, "b.md")),
        )
        val rows = manifest.chapters.mapIndexed { index, chapter ->
            ProgressiveLoadFileEntity(BOOK_ID, chapter.path, chapter.id, index, "r$index", 1, null, ProgressiveLoadFileState.PENDING, 1)
        }
        fun seed(files: List<ProgressiveLoadFileEntity>) = ProgressiveBookSeed(manifest, "disk:/Book", files, true, null)

        assertThrows<IllegalArgumentException> { validateProgressiveSeed(seed(rows + rows.first())) }
        assertThrows<IllegalArgumentException> { validateProgressiveSeed(seed(rows.map { it.copy(spineIndex = 0) })) }
        assertThrows<IllegalArgumentException> { validateProgressiveSeed(seed(rows.mapIndexed { index, row -> if (index == 0) row.copy(bookId = CHAPTER_2) else row })) }
        assertThrows<IllegalArgumentException> { validateProgressiveSeed(seed(rows.mapIndexed { index, row -> if (index == 0) row.copy(chapterId = CHAPTER_2) else row })) }
        assertThrows<IllegalArgumentException> { validateProgressiveSeed(seed(rows.mapIndexed { index, row -> if (index == 0) row.copy(path = "b.md") else row })) }
        listOf("", ".", "..", "a/b.md", "a\\b.md", "bad\u0000.md").forEach { invalidRemoteName ->
            assertThrows<IllegalArgumentException>(invalidRemoteName) {
                validateProgressiveSeed(seed(rows.mapIndexed { index, row ->
                    if (index == 0) row.copy(remoteName = invalidRemoteName) else row
                }))
            }
        }
    }

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

    @Test
    fun `duplicate manifest identity path non markdown source and duplicate binder listing are rejected`() = runTest {
        val duplicateId = manifestJson(
            """{"id":"$CHAPTER_1","path":"a.md"},{"id":"$CHAPTER_1","path":"b.md"}""",
        )
        val duplicatePath = manifestJson(
            """{"id":"$CHAPTER_1","path":"a.md"},{"id":"$CHAPTER_2","path":"a.md"}""",
        )
        val nonMarkdown = manifestJson("""{"id":"$CHAPTER_1","path":"a.txt"}""")
        listOf(
            duplicateId to listOf(entry("a.md", "ra"), entry("b.md", "rb")),
            duplicatePath to listOf(entry("a.md", "ra")),
            nonMarkdown to listOf(entry("a.txt", "ra")),
        ).forEach { (json, sources) ->
            val gateway = manifestGateway(json.encodeToByteArray(), sources)
            assertThrows<net.inkyquill.pocketeditor.yandex.YandexDiskError.InvalidRemote> {
                ProgressiveBookLoader.builderOnly(gateway, EmptyLoads, RecordingInstaller()).start("disk:/Book")
            }
            assertEquals(listOf("disk:/Book/.pocket-editor.json"), gateway.downloadedPaths)
        }

        val duplicateListing = CountingGateway(
            listOf(entry(".pocket-editor.json", "r1"), entry(".pocket-editor.json", "r2"), entry("a.md", "ra")),
        )
        assertThrows<net.inkyquill.pocketeditor.yandex.YandexDiskError.InvalidRemote> {
            ProgressiveBookLoader.builderOnly(duplicateListing, EmptyLoads, RecordingInstaller()).start("disk:/Book")
        }
        assertTrue(duplicateListing.downloadedPaths.isEmpty())
    }

    @Test
    fun `manifest preserves binder spelling and persists canonical listing spelling for download`() = runTest {
        val binderPath = "e\u0301.md"
        val listingPath = "é.md"
        val manifest = BookManifest(bookId = BOOK_ID, title = "Unicode", chapters = listOf(ChapterEntry(CHAPTER_1, binderPath)))
        val gateway = manifestGateway(BookManifest.encode(manifest).encodeToByteArray(), listOf(entry(listingPath, "r1")))
        val installer = RecordingInstaller()

        ProgressiveBookLoader.builderOnly(gateway, EmptyLoads, installer).start("disk:/Book")

        assertEquals(binderPath, installer.seed.files.single().path)
        assertEquals(listingPath, installer.seed.files.single().remoteName)
        assertEquals(binderPath, installer.seed.manifest.chapters.single().path)
    }

    @Test
    fun `runner downloads using listing spelling while publishing binder path`() = runTest {
        val binderPath = "e\u0301.md"
        val listingPath = "é.md"
        val manifest = BookManifest(bookId = BOOK_ID, title = "Unicode", chapters = listOf(ChapterEntry(CHAPTER_1, binderPath)))
        val builderGateway = manifestGateway(BookManifest.encode(manifest).encodeToByteArray(), listOf(entry(listingPath, "r1")))
        val installer = RecordingInstaller()
        ProgressiveBookLoader.builderOnly(builderGateway, EmptyLoads, installer).start("disk:/Book")
        val paths = BookPaths(Files.createTempDirectory("normalized-download").toFile())
        val store = AtomicBookStore(paths).also { it.writeManifest(BOOK_ID, manifest) }
        val loads = MutableLoads(
            ProgressiveLoadJobEntity(BOOK_ID, "disk:/Book", ProgressiveLoadPhase.INITIAL, 1, 0, null, 0, null, 1, false, false, null),
            installer.seed.files,
        )
        val remotePath = "disk:/Book/$listingPath"
        val runnerGateway = CountingGateway(
            listOf(entry(listingPath, "r1")),
            mapOf(remotePath to RemoteFile(remotePath, "# Unicode\n".encodeToByteArray(), "r1")),
        )
        val loader = ProgressiveBookLoader.create(
            runnerGateway, loads, RecordingInstaller(), store, FakeSync(), SourceSearch(FakeSearchDao()),
            ReviewMutationCoordinator(), ContentChangeNotifier(), LibraryTransaction { it() }, noOpScheduler(loads),
            ProgressiveLoadRetryPolicy(now = { Instant.EPOCH }, jitterMillis = { 0 }),
        )

        assertEquals(ProgressiveLoadRunResult.FileCached, loader.runOne(BOOK_ID, 1))
        assertEquals(listOf(remotePath), runnerGateway.downloadedPaths)
        assertTrue(store.readSource(BOOK_ID, binderPath).isNotEmpty())
    }

    @Test
    fun `manifest download cancellation is never converted to invalid remote`() = runTest {
        val gateway = CountingGateway(listOf(entry(".pocket-editor.json", "rm")))
        val cancellation = SimulatedProcessDeath()
        gateway.downloadFailures["disk:/Book/.pocket-editor.json"] = cancellation

        val thrown = assertThrows<SimulatedProcessDeath> {
            ProgressiveBookLoader.builderOnly(gateway, EmptyLoads, RecordingInstaller()).start("disk:/Book")
        }

        assertTrue(thrown === cancellation)
    }

    @Test
    fun `runner downloads one file and returns to earliest spine after on-demand file`() = runTest {
        val fixture = runnerFixture(6)
        assertEquals(1, fixture.loads.prioritize(BOOK_ID, "chapter-5.md"))
        assertEquals(0, fixture.loads.prioritize(BOOK_ID, "chapter-5.md"))

        assertEquals(ProgressiveLoadRunResult.FileCached, fixture.loader.runOne(BOOK_ID, 1))
        assertEquals(listOf("disk:/Book/chapter-5.md"), fixture.gateway.downloadedPaths)
        assertEquals(1, fixture.gateway.maxConcurrentDownloads)

        assertEquals(ProgressiveLoadRunResult.FileCached, fixture.loader.runOne(BOOK_ID, 1))
        assertEquals("disk:/Book/chapter-0.md", fixture.gateway.downloadedPaths.last())
    }

    @Test
    fun `initial readiness flips only after three`() = runTest {
        val fixture = runnerFixture(5)
        repeat(2) { fixture.loader.runOne(BOOK_ID, 1) }
        assertFalse(requireNotNull(fixture.loads.snapshot(BOOK_ID)).initialReady)
        fixture.loader.runOne(BOOK_ID, 1)
        assertTrue(requireNotNull(fixture.loads.snapshot(BOOK_ID)).initialReady)
        assertEquals(1, fixture.gateway.maxConcurrentDownloads)
    }

    @Test
    fun `matching cache skips restart download while sha or revision mismatch resets and refetches`() = runTest {
        val fixture = runnerFixture(1)
        assertEquals(ProgressiveLoadRunResult.FileCached, fixture.loader.runOne(BOOK_ID, 1))
        val initialDownloads = fixture.gateway.downloadedPaths.size

        assertEquals(ProgressiveLoadRunResult.Complete, fixture.recreate().runOne(BOOK_ID, 1))
        assertEquals(initialDownloads, fixture.gateway.downloadedPaths.size)

        fixture.store.replaceDownloadedSource(BOOK_ID, "chapter-0.md", "tampered".encodeToByteArray())
        assertEquals(ProgressiveLoadRunResult.FileCached, fixture.recreate().runOne(BOOK_ID, 1))
        assertEquals(initialDownloads + 1, fixture.gateway.downloadedPaths.size)
        assertEquals(ProgressiveLoadFileState.CACHED, fixture.loads.getFiles(BOOK_ID).single().state)

        fixture.sync.revisions["chapter-0.md"] = requireNotNull(fixture.sync.revisions["chapter-0.md"]).copy(
            remoteRevision = "wrong-revision",
        )
        assertEquals(ProgressiveLoadRunResult.FileCached, fixture.recreate().runOne(BOOK_ID, 1))
        assertEquals(initialDownloads + 2, fixture.gateway.downloadedPaths.size)
    }

    @Test
    fun `not found confirms present as retry and absent as action required`() = runTest {
        val present = runnerFixture(1)
        present.gateway.downloadFailures["disk:/Book/chapter-0.md"] = net.inkyquill.pocketeditor.yandex.YandexDiskError.NotFound()
        assertTrue(present.loader.runOne(BOOK_ID, 1) is ProgressiveLoadRunResult.Retry)
        assertEquals(2, present.gateway.listCalls + present.gateway.downloadedPaths.size)
        assertEquals(ProgressiveLoadFileState.PENDING, present.loads.getFiles(BOOK_ID).single().state)

        val absent = runnerFixture(1)
        absent.gateway.downloadFailures["disk:/Book/chapter-0.md"] = net.inkyquill.pocketeditor.yandex.YandexDiskError.NotFound()
        absent.gateway.entries.clear()
        assertEquals(ProgressiveLoadRunResult.ActionRequired, absent.loader.runOne(BOOK_ID, 1))
        assertEquals(ProgressiveLoadFileState.ACTION_REQUIRED, absent.loads.getFiles(BOOK_ID).single().state)
    }

    @Test
    fun `cancellation during missing file relist restores claim and rethrows cancellation`() = runTest {
        val fixture = runnerFixture(1)
        fixture.gateway.downloadFailures["disk:/Book/chapter-0.md"] = net.inkyquill.pocketeditor.yandex.YandexDiskError.NotFound()
        fixture.gateway.listFailure = SimulatedProcessDeath()

        assertThrows<SimulatedProcessDeath> { fixture.loader.runOne(BOOK_ID, 1) }

        assertEquals(ProgressiveLoadFileState.PENDING, fixture.loads.getFiles(BOOK_ID).single().state)
        assertEquals(null, fixture.loads.getJob(BOOK_ID)?.activePath)
    }

    @Test
    fun `cancellation during missing file Room confirmation restores claim and active path`() = runTest {
        val fixture = runnerFixture(1)
        fixture.gateway.downloadFailures["disk:/Book/chapter-0.md"] = net.inkyquill.pocketeditor.yandex.YandexDiskError.NotFound()
        fixture.loads.failGetJobAtCall = 4

        assertThrows<SimulatedProcessDeath> { fixture.loader.runOne(BOOK_ID, 1) }

        assertEquals(ProgressiveLoadFileState.PENDING, fixture.loads.getFiles(BOOK_ID).single().state)
        assertEquals(null, fixture.loads.getJob(BOOK_ID)?.activePath)
    }

    @Test
    fun `real suspended download cancellation restores claim and releases book lease`() = runTest {
        val fixture = runnerFixture(1)
        fixture.gateway.downloadStarted = CompletableDeferred()
        fixture.gateway.suspendDownloads = true
        val running = backgroundScope.launch { fixture.loader.runOne(BOOK_ID, 1) }
        withTimeout(1_000) { fixture.gateway.downloadStarted?.await() }

        running.cancelAndJoin()

        assertEquals(ProgressiveLoadFileState.PENDING, fixture.loads.getFiles(BOOK_ID).single().state)
        assertEquals(null, fixture.loads.getJob(BOOK_ID)?.activePath)
        withTimeout(1_000) { fixture.mutations.withBookExclusive(BOOK_ID) { } }
    }

    @Test
    fun `crash after durable cache commit replays notifications without redownload`() = runTest {
        val fixture = runnerFixture(1, CachePublicationCheckpoint.DURABLE_CACHE_COMMITTED)
        assertThrows<SimulatedProcessDeath> { fixture.loader.runOne(BOOK_ID, 1) }
        assertEquals(ProgressiveLoadFileState.CACHED, fixture.loads.getFiles(BOOK_ID).single().state)
        assertEquals(listOf("chapter-0.md"), fixture.sync.getPendingPublicationPaths(BOOK_ID))
        val downloads = fixture.gateway.downloadedPaths.size

        assertEquals(ProgressiveLoadRunResult.Complete, fixture.recreate().runOne(BOOK_ID, 1))

        assertEquals(downloads, fixture.gateway.downloadedPaths.size)
        assertTrue(fixture.sync.getPendingPublicationPaths(BOOK_ID).isEmpty())
        assertEquals(1L, fixture.content.versions.value.values.single())
        assertEquals(1L, fixture.content.bookVersions.value.getValue(BOOK_ID))
    }

    @Test
    fun `cancellation after publication journal restores claim and leaves replay row`() = runTest {
        val fixture = runnerFixture(1, CachePublicationCheckpoint.JOURNAL_STAGED)
        assertThrows<SimulatedProcessDeath> { fixture.loader.runOne(BOOK_ID, 1) }
        assertEquals(ProgressiveLoadFileState.PENDING, fixture.loads.getFiles(BOOK_ID).single().state)
        assertEquals(null, fixture.loads.getJob(BOOK_ID)?.activePath)
        assertEquals(listOf("chapter-0.md"), fixture.sync.getPendingPublicationPaths(BOOK_ID))
        fixture.mutations.withBookExclusive(BOOK_ID) { }
    }

    @Test
    fun `crash after path notification replays path then book before acknowledgement`() = runTest {
        val fixture = runnerFixture(1, CachePublicationCheckpoint.PATH_NOTIFIED)
        assertThrows<SimulatedProcessDeath> { fixture.loader.runOne(BOOK_ID, 1) }
        assertEquals(1L, fixture.content.versions.value.values.single())
        assertTrue(fixture.content.bookVersions.value.isEmpty())
        val downloads = fixture.gateway.downloadedPaths.size

        assertEquals(ProgressiveLoadRunResult.Complete, fixture.recreate().runOne(BOOK_ID, 1))

        assertEquals(downloads, fixture.gateway.downloadedPaths.size)
        assertEquals(2L, fixture.content.versions.value.values.single())
        assertEquals(1L, fixture.content.bookVersions.value.getValue(BOOK_ID))
        assertTrue(fixture.sync.pending.isEmpty())
    }

    @Test
    fun `fully cached ready draft promotes without gateway access or scheduling`() = runTest {
        val bytes = "# Cached\n".encodeToByteArray()
        val sha = bytes.sha256()
        val document = ImportDraftDocument(
            bookId = BOOK_ID,
            remoteRootPath = "disk:/Book",
            title = "Legacy",
            phase = ImportDraftPhase.READY,
            chapters = listOf(ImportDraftChapter(CHAPTER_1, "a.md", "Cached", true, "r1", sha, bytes.size.toLong())),
        )
        val dao = FakeImportDraftDao(ImportDraftEntity(BOOK_ID, "disk:/Book", "/legacy", ImportDraftDocument.encode(document), 1))
        val adapter = LegacyImportDraftAdapter({ dao.getAll() }) { _, _, _, _ -> bytes }
        val installer = RecordingInstaller()
        val gateway = CountingGateway(emptyList())
        val paths = BookPaths(Files.createTempDirectory("legacy-progressive").toFile())
        val store = AtomicBookStore(paths)
        val sync = FakeSync()
        val loads = MutableLoads(
            ProgressiveLoadJobEntity("99999999-9999-9999-9999-999999999999", "disk:/Other", ProgressiveLoadPhase.COMPLETE, 0, 0, null, 0, null, 1, false, false, null),
            emptyList(),
        )
        val enqueued = mutableListOf<ProgressiveLoadWorkRequest>()
        val scheduler = ProgressiveLoadScheduler(
            object : ProgressiveLoadWorkQueue {
                override suspend fun enqueue(request: ProgressiveLoadWorkRequest) { enqueued += request }
                override fun cancel(uniqueName: String) = Unit
            },
            object : ProgressiveLoadScheduleStore {
                override suspend fun current(bookId: String) = 0L
                override suspend fun publishIfCurrent(bookId: String, expectedCurrent: Long, next: Long, paused: Boolean, cancelled: Boolean) = true
                override suspend fun admit(bookId: String, requested: Long) = GenerationAdmission.CURRENT
                override suspend fun stop(bookId: String, paused: Boolean, cancelled: Boolean) = 1L
            },
        )
        val importStore = ImportDraftStore(Files.createTempDirectory("legacy-import-store").toFile())
        val loader = ProgressiveBookLoader.create(
            gateway, loads, installer, store, sync, SourceSearch(FakeSearchDao()), ReviewMutationCoordinator(),
            ContentChangeNotifier(), LibraryTransaction { it() }, scheduler,
            ProgressiveLoadRetryPolicy(now = { Instant.EPOCH }, jitterMillis = { 0 }), adapter, dao, importStore,
        )

        loader.migrateLegacyDrafts()

        assertEquals(0, gateway.listCalls + gateway.downloadedPaths.size)
        assertEquals(listOf("a.md"), installer.seed.files.map { it.path })
        assertEquals(listOf("a.md"), installer.cachedSources.keys.toList())
        assertEquals(null, dao.row)
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `partial legacy promotion preserves ids and paths and schedules only after install`() = runTest {
        val cached = "# Cached\n".encodeToByteArray()
        val missing = "# Missing\n".encodeToByteArray()
        val chapters = listOf(
            ImportDraftChapter(CHAPTER_1, "a.md", "A", true, "r1", cached.sha256(), cached.size.toLong()),
            ImportDraftChapter(CHAPTER_2, "b.md", "B", true, "r2", missing.sha256(), missing.size.toLong()),
        )
        val document = ImportDraftDocument(
            bookId = BOOK_ID, remoteRootPath = "disk:/Book", title = "Legacy",
            phase = ImportDraftPhase.READY, chapters = chapters,
        )
        val dao = FakeImportDraftDao(ImportDraftEntity(BOOK_ID, "disk:/Book", "/legacy", ImportDraftDocument.encode(document), 1))
        val adapter = LegacyImportDraftAdapter({ dao.getAll() }) { _, path, _, _ -> cached.takeIf { path == "a.md" } }
        val installer = RecordingInstaller()
        val loads = MutableLoads(
            ProgressiveLoadJobEntity("99999999-9999-9999-9999-999999999999", "disk:/Other", ProgressiveLoadPhase.COMPLETE, 0, 0, null, 0, null, 1, false, false, null),
            emptyList(),
        )
        val enqueued = mutableListOf<ProgressiveLoadWorkRequest>()
        var installFinished = false
        val recordingInstaller = ProgressiveSeedInstaller { seed, cachedSources ->
            val snapshot = installer.install(seed, cachedSources)
            installFinished = true
            snapshot
        }
        val scheduler = ProgressiveLoadScheduler(
            object : ProgressiveLoadWorkQueue {
                override suspend fun enqueue(request: ProgressiveLoadWorkRequest) {
                    assertTrue(installFinished)
                    enqueued += request
                }
                override fun cancel(uniqueName: String) = Unit
            },
            object : ProgressiveLoadScheduleStore {
                override suspend fun current(bookId: String) = 0L
                override suspend fun publishIfCurrent(bookId: String, expectedCurrent: Long, next: Long, paused: Boolean, cancelled: Boolean) = true
                override suspend fun admit(bookId: String, requested: Long) = GenerationAdmission.CURRENT
                override suspend fun stop(bookId: String, paused: Boolean, cancelled: Boolean) = 1L
            },
        )
        val root = Files.createTempDirectory("partial-legacy").toFile()
        val loader = ProgressiveBookLoader.create(
            CountingGateway(emptyList()), loads, recordingInstaller, AtomicBookStore(BookPaths(root)), FakeSync(),
            SourceSearch(FakeSearchDao()), ReviewMutationCoordinator(), ContentChangeNotifier(), LibraryTransaction { it() },
            scheduler, ProgressiveLoadRetryPolicy(now = { Instant.EPOCH }, jitterMillis = { 0 }), adapter, dao,
            ImportDraftStore(Files.createTempDirectory("partial-legacy-store").toFile()),
        )

        loader.migrateLegacyDrafts()

        assertEquals(chapters.map { it.id }, installer.seed.manifest.chapters.map { it.id })
        assertEquals(chapters.map { it.path }, installer.seed.files.map { it.path })
        assertEquals(listOf(ProgressiveLoadFileState.CACHED, ProgressiveLoadFileState.PENDING), installer.seed.files.map { it.state })
        assertEquals(listOf(1L), enqueued.map { it.generation })
        assertEquals(null, dao.row)
    }

    @Test
    fun `registered v4 root is adopted from local persisted state without gateway access`() = runTest {
        val paths = BookPaths(Files.createTempDirectory("registered-root").toFile())
        val store = AtomicBookStore(paths)
        val manifest = BookManifest(bookId = BOOK_ID, title = "Installed", chapters = listOf(ChapterEntry(CHAPTER_1, "a.md")))
        val source = "# Local\n".encodeToByteArray()
        store.writeManifest(BOOK_ID, manifest)
        store.replaceDownloadedSource(BOOK_ID, "a.md", source)
        val loads = MutableLoads(
            ProgressiveLoadJobEntity("99999999-9999-9999-9999-999999999999", "disk:/Other", ProgressiveLoadPhase.COMPLETE, 0, 0, null, 0, null, 1, false, false, null),
            emptyList(),
        )
        val gateway = CountingGateway(emptyList()).also { it.listFailure = AssertionError("gateway must not be used") }
        val books = FakeBookDao(BookRootEntity(BOOK_ID, "disk:/Book", paths.bookDirectory(BOOK_ID).absolutePath, 1))
        val scheduler = noOpScheduler(loads)
        val loader = ProgressiveBookLoader.create(
            gateway, loads, RecordingInstaller(), store, FakeSync(), SourceSearch(FakeSearchDao()),
            ReviewMutationCoordinator(), ContentChangeNotifier(), LibraryTransaction { it() }, scheduler,
            ProgressiveLoadRetryPolicy(now = { Instant.EPOCH }, jitterMillis = { 0 }), books = books,
        )

        val snapshot = loader.start("disk:/Book")

        assertEquals(ProgressiveLoadPhase.COMPLETE, snapshot.phase)
        assertEquals(ProgressiveLoadFileState.CACHED, snapshot.files.single().state)
        assertEquals(CHAPTER_1, snapshot.files.single().chapterId)
        assertEquals(0, gateway.listCalls)
        assertTrue(gateway.downloadedPaths.isEmpty())
    }

    private suspend fun runnerFixture(chapterCount: Int, failAt: CachePublicationCheckpoint? = null): RunnerFixture {
        val chapters = (0 until chapterCount).map { index ->
            ChapterEntry("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", "chapter-$index.md")
        }
        val manifest = BookManifest(bookId = BOOK_ID, title = "Runner", chapters = chapters)
        val paths = BookPaths(Files.createTempDirectory("progressive-runner").toFile())
        val store = AtomicBookStore(paths)
        store.writeManifest(BOOK_ID, manifest)
        val loads = MutableLoads(
            ProgressiveLoadJobEntity(BOOK_ID, "disk:/Book", ProgressiveLoadPhase.INITIAL, chapterCount, 0, null, 0, null, 1, false, false, null),
            chapters.mapIndexed { index, chapter ->
                ProgressiveLoadFileEntity(BOOK_ID, chapter.path, chapter.id, index, "r$index", null, null, ProgressiveLoadFileState.PENDING, initialPriority(index))
            },
        )
        val entries = chapters.mapIndexed { index, chapter -> RemoteEntry(chapter.path, "disk:/Book/${chapter.path}", "file", null, "r$index") }
        val downloads = chapters.mapIndexed { index, chapter ->
            val path = "disk:/Book/${chapter.path}"
            path to RemoteFile(path, "# ${chapter.path}\n\nbody".encodeToByteArray(), "r$index")
        }.toMap()
        val gateway = CountingGateway(entries, downloads)
        val sync = FakeSync()
        val content = ContentChangeNotifier()
        val mutations = ReviewMutationCoordinator()
        val scheduler = ProgressiveLoadScheduler(
            object : ProgressiveLoadWorkQueue {
                override suspend fun enqueue(request: ProgressiveLoadWorkRequest) = Unit
                override fun cancel(uniqueName: String) = Unit
            },
            object : ProgressiveLoadScheduleStore {
                override suspend fun current(bookId: String) = loads.getJob(bookId)?.generation
                override suspend fun publishIfCurrent(bookId: String, expectedCurrent: Long, next: Long, paused: Boolean, cancelled: Boolean) = true
                override suspend fun admit(bookId: String, requested: Long) = GenerationAdmission.CURRENT
                override suspend fun stop(bookId: String, paused: Boolean, cancelled: Boolean) = requestedGeneration(loads)
            },
        )
        fun createLoader(checkpoint: CachePublicationCheckpoint? = null) = ProgressiveBookLoader.create(
            gateway, loads, RecordingInstaller(), store, sync, SourceSearch(FakeSearchDao()),
            mutations, content, LibraryTransaction { it() }, scheduler,
            ProgressiveLoadRetryPolicy(now = { Instant.EPOCH }, jitterMillis = { 0 }),
            publicationCheckpoint = { if (it == checkpoint) throw SimulatedProcessDeath() },
        )
        return RunnerFixture(createLoader(failAt), loads, gateway, sync, content, mutations, store) { createLoader() }
    }

    private fun requestedGeneration(loads: MutableLoads) = (loads.job.generation + 1)

    private fun noOpScheduler(loads: MutableLoads) = ProgressiveLoadScheduler(
        object : ProgressiveLoadWorkQueue {
            override suspend fun enqueue(request: ProgressiveLoadWorkRequest) = Unit
            override fun cancel(uniqueName: String) = Unit
        },
        object : ProgressiveLoadScheduleStore {
            override suspend fun current(bookId: String) = loads.getJob(bookId)?.generation
            override suspend fun publishIfCurrent(bookId: String, expectedCurrent: Long, next: Long, paused: Boolean, cancelled: Boolean) = true
            override suspend fun admit(bookId: String, requested: Long) = GenerationAdmission.CURRENT
            override suspend fun stop(bookId: String, paused: Boolean, cancelled: Boolean) = requestedGeneration(loads)
        },
    )

    private data class RunnerFixture(
        val loader: ProgressiveBookLoader,
        val loads: MutableLoads,
        val gateway: CountingGateway,
        val sync: FakeSync,
        val content: ContentChangeNotifier,
        val mutations: ReviewMutationCoordinator,
        val store: AtomicBookStore,
        val recreate: () -> ProgressiveBookLoader,
    )

    private class SimulatedProcessDeath : CancellationException("simulated process death")

    private class RecordingInstaller : ProgressiveSeedInstaller {
        lateinit var seed: ProgressiveBookSeed
        var cachedSources: Map<String, ByteArray> = emptyMap()
        private var snapshot: ProgressiveLoadSnapshot? = null
        override suspend fun install(seed: ProgressiveBookSeed, cachedSources: Map<String, ByteArray>): ProgressiveLoadSnapshot {
            this.seed = seed
            this.cachedSources = cachedSources
            return snapshot ?: ProgressiveLoadSnapshot(seed.manifest.bookId, seed.remoteRootPath, ProgressiveLoadPhase.INITIAL, seed.files.size, 0, null, 0, null, 0, false, false, null, seed.files).also { snapshot = it }
        }
    }

    private class FakeImportDraftDao(var row: ImportDraftEntity?) : ImportDraftDao {
        override fun observeAll(): Flow<List<ImportDraftEntity>> = flowOf(listOfNotNull(row))
        override suspend fun getAll(): List<ImportDraftEntity> = listOfNotNull(row)
        override suspend fun getByBookId(bookId: String) = row?.takeIf { it.bookId == bookId }
        override suspend fun getByRemoteRoot(remoteRootPath: String) = row?.takeIf { it.remoteRootPath == remoteRootPath }
        override suspend fun upsert(draft: ImportDraftEntity) { row = draft }
        override suspend fun delete(bookId: String) { if (row?.bookId == bookId) row = null }
    }

    private class FakeBookDao(var root: BookRootEntity?) : BookDao {
        private var position: ReadingPositionEntity? = null
        override suspend fun upsertRoot(root: BookRootEntity) { this.root = root }
        override fun observeRoots(): Flow<List<BookRootEntity>> = flowOf(listOfNotNull(root))
        override suspend fun getRoots(): List<BookRootEntity> = listOfNotNull(root)
        override suspend fun getRoot(bookId: String): BookRootEntity? = root?.takeIf { it.bookId == bookId }
        override suspend fun deleteRoot(bookId: String) { if (root?.bookId == bookId) root = null }
        override suspend fun upsertReadingPosition(position: ReadingPositionEntity) { this.position = position }
        override fun observeReadingPosition(bookId: String): Flow<ReadingPositionEntity?> = flowOf(position)
        override suspend fun getReadingPosition(bookId: String): ReadingPositionEntity? = position
        override suspend fun deleteReadingPosition(bookId: String) { position = null }
    }

    private class CountingGateway(entries: List<RemoteEntry>, private val downloads: Map<String, RemoteFile> = emptyMap()) : YandexDiskGateway {
        val entries = entries.toMutableList()
        val downloadFailures = mutableMapOf<String, Throwable>()
        var listFailure: Throwable? = null
        var downloadStarted: CompletableDeferred<Unit>? = null
        var suspendDownloads = false
        var listCalls = 0
        val downloadedPaths = mutableListOf<String>()
        var maxConcurrentDownloads = 0
        private var activeDownloads = 0
        override suspend fun listFolder(path: String): List<RemoteEntry> {
            listCalls++
            listFailure?.let { throw it }
            return entries.toList()
        }
        override suspend fun download(path: String): RemoteFile {
            activeDownloads++
            maxConcurrentDownloads = maxOf(maxConcurrentDownloads, activeDownloads)
            downloadedPaths += path
            downloadStarted?.complete(Unit)
            return try {
                downloadFailures[path]?.let { throw it }
                if (suspendDownloads) awaitCancellation()
                downloads.getValue(path)
            } finally { activeDownloads-- }
        }
        override suspend fun tryAcquireLock(rootPath: String, lock: SyncLock) = error("unused")
        override suspend fun readLock(rootPath: String) = error("unused")
        override suspend fun uploadGuarded(rootPath: String, relativePath: String, bytes: ByteArray, ownedLock: SyncLock) = error("unused")
        override suspend fun releaseOwnedLock(rootPath: String, ownedLock: SyncLock) = error("unused")
        override suspend fun breakObservedLock(rootPath: String, observedLock: SyncLock) = error("unused")
    }

    private class MutableLoads(var job: ProgressiveLoadJobEntity, files: List<ProgressiveLoadFileEntity>) : ProgressiveLoadDao {
        private val rows = files.associateByTo(linkedMapOf()) { it.path }
        var failGetJobAtCall: Int? = null
        private var getJobCalls = 0
        override suspend fun insertJob(job: ProgressiveLoadJobEntity) { this.job = job }
        override suspend fun insertFiles(files: List<ProgressiveLoadFileEntity>) { files.forEach { rows[it.path] = it } }
        override fun observe(bookId: String): Flow<ProgressiveLoadJobWithFiles?> = flowOf(ProgressiveLoadJobWithFiles(job, rows.values.toList()))
        override fun observeAll(): Flow<List<ProgressiveLoadJobWithFiles>> = flowOf(listOf(ProgressiveLoadJobWithFiles(job, rows.values.toList())))
        override suspend fun getJob(bookId: String): ProgressiveLoadJobEntity? {
            getJobCalls++
            if (failGetJobAtCall == getJobCalls) {
                failGetJobAtCall = null
                throw SimulatedProcessDeath()
            }
            return job.takeIf { it.bookId == bookId }
        }
        override suspend fun getJobByRemoteRoot(remoteRootPath: String): ProgressiveLoadJobEntity? = job.takeIf { it.remoteRootPath == remoteRootPath }
        override suspend fun getFiles(bookId: String) = rows.values.sortedBy { it.spineIndex }
        override fun observeChapter(bookId: String, chapterId: String): Flow<ProgressiveLoadFileEntity?> = flowOf(rows.values.singleOrNull { it.chapterId == chapterId })
        override suspend fun nextPending(bookId: String) = rows.values.filter { it.state == ProgressiveLoadFileState.PENDING }.sortedWith(compareByDescending<ProgressiveLoadFileEntity> { it.priority }.thenBy { it.spineIndex }).firstOrNull()
        override suspend fun updateJob(job: ProgressiveLoadJobEntity) { this.job = job }
        override suspend fun updateFile(file: ProgressiveLoadFileEntity) { rows[file.path] = file }
        override suspend fun prioritize(bookId: String, path: String): Int {
            val row = rows[path]?.takeIf { it.state == ProgressiveLoadFileState.PENDING && it.priority < ON_DEMAND_PRIORITY } ?: return 0
            rows[path] = row.copy(priority = ON_DEMAND_PRIORITY)
            return 1
        }
        override suspend fun ownsClaim(bookId: String, path: String, generation: Long): Boolean =
            job.generation == generation && rows[path]?.let {
                it.state == ProgressiveLoadFileState.DOWNLOADING && it.claimGeneration == generation
            } == true
        override suspend fun deleteJob(bookId: String) = Unit
        override suspend fun deleteFiles(bookId: String) { rows.clear() }
    }

    private class FakeSync : SyncDao {
        val revisions = linkedMapOf<String, RemoteRevisionEntity>()
        val pending = linkedSetOf<String>()
        override suspend fun deleteRemoteRevisions(bookId: String) { revisions.clear() }
        override suspend fun deleteRemoteRevision(bookId: String, path: String) { revisions.remove(path) }
        override suspend fun deleteMergeBases(bookId: String) = Unit
        override suspend fun deleteMergeBase(bookId: String, path: String) = Unit
        override suspend fun deleteOutbox(bookId: String) = Unit
        override suspend fun deletePendingDeletions(bookId: String) = Unit
        override suspend fun deletePendingPublications(bookId: String) { pending.clear() }
        override suspend fun upsertRemoteRevision(revision: RemoteRevisionEntity) { revisions[revision.path] = revision }
        override fun observeRemoteRevisions(bookId: String): Flow<List<RemoteRevisionEntity>> = flowOf(revisions.values.toList())
        override suspend fun getRemoteRevisions(bookId: String) = revisions.values.toList()
        override suspend fun upsertPendingPublication(value: PendingPublicationEntity) { pending += value.path }
        override suspend fun getPendingPublicationPaths(bookId: String) = pending.toList()
        override suspend fun deletePendingPublication(bookId: String, path: String) { pending -= path }
        override suspend fun upsertMergeBase(base: MergeBaseEntity) = Unit
        override suspend fun getMergeBase(bookId: String, path: String): MergeBaseEntity? = null
        override fun observeMergeBases(bookId: String): Flow<List<MergeBaseEntity>> = flowOf(emptyList())
        override suspend fun upsertOutbox(item: OutboxEntity) = Unit
        override suspend fun getOutbox(bookId: String, path: String): OutboxEntity? = null
        override suspend fun getOutbox(bookId: String): List<OutboxEntity> = emptyList()
        override suspend fun deleteOutbox(bookId: String, path: String) = Unit
        override fun observeOutbox(): Flow<List<OutboxEntity>> = flowOf(emptyList())
        override suspend fun upsertPendingDeletion(value: PendingDeletionEntity) = Unit
        override suspend fun getPendingDeletion(tokenId: String): PendingDeletionEntity? = null
        override suspend fun pendingDeletions(bookId: String): List<PendingDeletionEntity> = emptyList()
        override suspend fun deletePendingDeletion(tokenId: String) = 0
    }

    private class FakeSearchDao : SearchDao {
        override suspend fun deleteChapter(bookId: String, chapterId: String) = Unit
        override suspend fun insert(rows: List<SearchEntity>) = Unit
        override suspend fun deleteBook(bookId: String) = Unit
        override fun query(bookId: String, matchQuery: String): Flow<List<SearchEntity>> = flowOf(emptyList())
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
        override suspend fun ownsClaim(bookId: String, path: String, generation: Long) = false
    }

    private fun entry(name: String, revision: String) = RemoteEntry(name, "disk:/Book/$name", "file", 1, revision)

    private fun manifestGateway(bytes: ByteArray, sources: List<RemoteEntry>) = CountingGateway(
        sources + entry(BookPaths.MANIFEST_NAME, "rm"),
        mapOf(
            "disk:/Book/${BookPaths.MANIFEST_NAME}" to
                RemoteFile("disk:/Book/${BookPaths.MANIFEST_NAME}", bytes, "rm"),
        ),
    )

    private fun manifestJson(chapters: String) =
        """{"schema_version":2,"book_id":"$BOOK_ID","title":"Invalid","chapters":[$chapters]}"""

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_1 = "22222222-2222-2222-2222-222222222222"
        const val CHAPTER_2 = "33333333-3333-3333-3333-333333333333"
        const val CHAPTER_B = "44444444-4444-4444-4444-444444444444"
        const val CHAPTER_A_UPPER = "55555555-5555-5555-5555-555555555555"
        const val CHAPTER_A_LOWER = "66666666-6666-6666-6666-666666666666"
    }
}
