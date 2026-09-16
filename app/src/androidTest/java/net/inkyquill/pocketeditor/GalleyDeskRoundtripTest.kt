package net.inkyquill.pocketeditor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.reader.ReaderLoadState
import net.inkyquill.pocketeditor.review.ReviewJson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit synthetic folder import into the supported offline cache, then the
 * same ReaderRepository and saveChapterNote action used by the production UI.
 * adb transport alone is not the test: Reader must discover all actual records.
 */
@RunWith(AndroidJUnit4::class)
class GalleyDeskRoundtripTest {
    @Test fun deskFolderThroughRealReaderAndBack() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("galleyRoundtrip") == "true")
        val app = ApplicationProvider.getApplicationContext<PocketEditorApp>()
        val c = app.container
        val input = File(app.filesDir, "galley-roundtrip-in")
        val manifest = BookManifest.decode(File(input, ".pocket-editor.json").readText())
        require(manifest.bookId == "a7000000-0000-4000-8000-000000000001")
        val chapter = manifest.chapters.single()
        val source = File(input, chapter.path).readBytes()
        val sidecar = chapter.path.removeSuffix(".md") + ".review.json"
        val original = ReviewJson.decode(File(input, sidecar).readText(), chapter.id, chapter.path)
        c.bookStore.writeManifest(manifest.bookId, manifest)
        c.bookStore.replaceDownloadedSource(manifest.bookId, chapter.path, source)
        // Preserve the transferred folder filename: do not conceal discovery bugs
        // by renaming it or calling writeReview with a derived alternative path.
        File(c.bookPaths.bookDirectory(manifest.bookId), sidecar).writeBytes(File(input, sidecar).readBytes())
        c.database.bookDao().upsertRoot(BookRootEntity(manifest.bookId, null, c.bookPaths.bookDirectory(manifest.bookId).absolutePath, 1L))
        c.database.progressiveLoadDao().deleteFiles(manifest.bookId)
        c.database.progressiveLoadDao().insertFiles(listOf(ProgressiveLoadFileEntity(
            bookId = manifest.bookId, path = chapter.path, chapterId = chapter.id,
            spineIndex = 0, expectedRevision = "synthetic", expectedSize = source.size.toLong(),
            sha256 = null, state = ProgressiveLoadFileState.CACHED, priority = 0,
        )))
        val loaded = withTimeout(15_000) { c.readerRepository.observeChapter(manifest.bookId, chapter.id, true).first { it is ReaderLoadState.Ready } } as ReaderLoadState.Ready
        assertEquals("Desk chapter_note discovered by production Reader", original.chapterNote, loaded.state.chapterNote)
        assertEquals(original.edits.map { it.id }, loaded.state.reviewItems!!.edits.map { it.id })
        assertEquals(original.signals.map { it.id }, loaded.state.reviewItems.signals.map { it.id })
        c.readerRepository.saveChapterNote(manifest.bookId, chapter.id, "Pocket roundtrip note 🦊")
        val returned = ReviewJson.decode(File(c.bookPaths.bookDirectory(manifest.bookId), sidecar).readText(), chapter.id, chapter.path)
        assertEquals(original.edits, returned.edits)
        assertEquals(original.signals, returned.signals)
        assertEquals("Pocket roundtrip note 🦊", returned.chapterNote)
        assertArrayEquals(source, c.bookStore.readSource(manifest.bookId, chapter.path))
        val output = File(app.filesDir, "galley-roundtrip-out").apply { mkdirs() }
        for (name in listOf(".pocket-editor.json", chapter.path, sidecar)) {
            File(c.bookPaths.bookDirectory(manifest.bookId), name).copyTo(File(output, name), overwrite = true)
        }
    }
}
