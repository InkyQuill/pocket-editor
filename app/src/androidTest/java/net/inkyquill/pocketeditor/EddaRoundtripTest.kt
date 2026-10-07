package net.inkyquill.pocketeditor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.inkyquill.pocketeditor.edda.EddaLocation
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.reader.ReaderLoadState
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.sync.SyncTrigger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EddaRoundtripTest {
    @Test fun isolatedBooksOfflineReviewAndWireIdentity() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("eddaRoundtrip") == "true")
        val projects = requireNotNull(arguments.getString("eddaProjects")).split(',')
        val app = ApplicationProvider.getApplicationContext<PocketEditorApp>()
        val c = app.container
        val account = c.eddaAccounts.signIn(c.eddaClient, "http://127.0.0.1:4199", "pocket-fixture@example.invalid", "pocket-fixture-password")
        val roots = listOf(EddaLocation(account.key, projects[0], "book-01"), EddaLocation(account.key, projects[0], "book-02"), EddaLocation(account.key, projects[1], "book-03"))
        try {
            for (root in roots) {
                c.libraryData.startLoad(root.root)
                withTimeout(60_000) {
                    while (c.progressiveLoads.getJobByRemoteRoot(root.root)?.phase != ProgressiveLoadPhase.COMPLETE) delay(200)
                }
            }
            val books = c.libraryData.books().filter { it.remoteRootPath in roots.map(EddaLocation::root) }
            assertEquals(3, books.size)
            assertEquals(3, books.map { it.bookId }.distinct().size)
            val first = books.single { it.remoteRootPath == roots[0].root }
            val manifest = c.bookStore.readManifest(first.bookId)
            val chapter = manifest.chapters.single { it.path == "chapter-01.md" }
            // Wait for initial sidecar hydration by the normal sync worker.
            withTimeout(60_000) {
                while (runCatching { c.bookStore.readReview(first.bookId, "chapter-01.review.json")?.signals?.size ?: 0 }.getOrDefault(0) != 1) delay(200)
            }
            if (arguments.getString("eddaPhase") == "return") {
                val returned = requireNotNull(c.bookStore.readReview(first.bookId, "chapter-01.review.json"))
                assertEquals("Desktop review via Galley", returned.signals.single().comment)
                assertEquals("Offline Edda review", returned.chapterNote)
                assertEquals(2, returned.edits.size)
                assertEquals(listOf("chapter-02.md", "chapter-01.md"), manifest.chapters.map { it.path })
                return@runBlocking
            }
            c.eddaAccounts.signOut(account.key)
            val original = c.bookStore.readSource(first.bookId, chapter.path)
            val loaded = withTimeout(10_000) { c.readerRepository.observeChapter(first.bookId, chapter.id, true).first { it is ReaderLoadState.Ready } }
            assertTrue(loaded is ReaderLoadState.Ready)
            c.readerRepository.saveChapterNote(first.bookId, chapter.id, "Offline Edda review")
            assertArrayEquals(original, c.bookStore.readSource(first.bookId, chapter.path))
            val offline = requireNotNull(c.bookStore.readReview(first.bookId, "chapter-01.review.json"))
            assertEquals("Offline Edda review", offline.chapterNote)
            assertEquals(2, offline.edits.size)
            assertEquals(1, offline.signals.size)
            c.eddaAccounts.signIn(c.eddaClient, account.server, account.email, "pocket-fixture-password")
            c.syncScheduler.enqueue(first.bookId, first.remoteRootPath, SyncTrigger.SYNC_NOW)
            withTimeout(60_000) {
                while (true) {
                    val version = c.eddaClient.version(roots[0])
                    val entry = version.entries.single { it.path == "book-01/chapter-01.review.json" }
                    val remote = ReviewJson.decode(c.eddaClient.download(roots[0], version, entry).decodeToString(), chapter.id, chapter.path)
                    if (remote.chapterNote == "Offline Edda review") {
                        assertEquals(offline, remote)
                        break
                    }
                    delay(200)
                }
            }
            for (book in books) {
                val cached = c.bookStore.readManifest(book.bookId)
                assertEquals(manifest.chapters.map { it.id }, cached.chapters.map { it.id })
                assertArrayEquals(original, c.bookStore.readSource(book.bookId, chapter.path))
                c.readerRepository.observeChapter(book.bookId, chapter.id, true).first { it is ReaderLoadState.Ready }
            }
        } finally {
            c.libraryData.books().filter { it.remoteRootPath in roots.map(EddaLocation::root) }.forEach { c.libraryData.forget(it.bookId) }
            c.eddaAccounts.signOut(account.key)
        }
    }
}
