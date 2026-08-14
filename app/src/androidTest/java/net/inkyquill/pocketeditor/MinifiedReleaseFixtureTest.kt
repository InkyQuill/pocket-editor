package net.inkyquill.pocketeditor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.search.SearchChapterSource
import net.inkyquill.pocketeditor.ui.books.ResumeLocation
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinifiedReleaseFixtureTest {
    @Test
    fun prepareSyntheticOfflineCacheForMinifiedApkSmoke() = runBlocking {
        assumeTrue(
            "Fixture preparation is an explicit local release-smoke operation",
            InstrumentationRegistry.getArguments().getString("prepareMinifiedSmoke") == "true",
        )
        val app = ApplicationProvider.getApplicationContext<PocketEditorApp>()
        val container = app.container
        val source = "# Offline smoke\n\nCached content is available without a network.\n".toByteArray()
        val manifest = BookManifest(
            bookId = BOOK_ID,
            title = "Offline smoke book",
            chapters = listOf(ChapterEntry(CHAPTER_ID, CHAPTER_PATH)),
        )

        container.database.clearAllTables()
        container.bookStore.writeManifest(BOOK_ID, manifest)
        container.bookStore.replaceDownloadedSource(BOOK_ID, CHAPTER_PATH, source)
        container.database.bookDao().upsertRoot(
            BookRootEntity(
                bookId = BOOK_ID,
                remoteRootPath = null,
                localDirectory = container.bookPaths.bookDirectory(BOOK_ID).absolutePath,
                registeredAt = 1L,
            ),
        )
        container.sourceSearch.rebuildBook(
            BOOK_ID,
            listOf(SearchChapterSource(CHAPTER_ID, "Cached chapter", source)),
        )
        container.libraryData.persistResume(ResumeLocation(BOOK_ID, CHAPTER_ID))

        assertArrayEquals(source, container.bookStore.readSource(BOOK_ID, CHAPTER_PATH))
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-4111-8111-111111111111"
        const val CHAPTER_ID = "22222222-2222-4222-8222-222222222222"
        const val CHAPTER_PATH = "offline-smoke.md"
    }
}
