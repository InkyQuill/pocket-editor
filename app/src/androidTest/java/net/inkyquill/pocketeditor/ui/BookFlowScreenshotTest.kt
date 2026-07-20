package net.inkyquill.pocketeditor.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import net.inkyquill.pocketeditor.search.SearchHit
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderChapter
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderRun
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.ui.books.AppearancePreference
import net.inkyquill.pocketeditor.ui.books.BookChapter
import net.inkyquill.pocketeditor.ui.books.BookSummary
import net.inkyquill.pocketeditor.ui.books.BooksScreen
import net.inkyquill.pocketeditor.ui.books.FolderBrowserScreen
import net.inkyquill.pocketeditor.ui.books.FolderListing
import net.inkyquill.pocketeditor.ui.books.ImportChapterDraft
import net.inkyquill.pocketeditor.ui.books.ImportConfirmationScreen
import net.inkyquill.pocketeditor.ui.books.ImportDraft
import net.inkyquill.pocketeditor.ui.books.RemoteFolder
import net.inkyquill.pocketeditor.ui.books.DiscoveryNotice
import net.inkyquill.pocketeditor.ui.contents.ContentsPanel
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.settings.AppearanceScreen
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class BookFlowScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureBookFlowScene() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("captureScreenshots", "false").toBoolean())
        val scene = args.getString("scene", "books")
        val name = args.getString("screenshotName", "task11-$scene")
        val dark = args.getString("dark", "true").toBoolean()
        val fontScale = args.getString("fontScale", "1").toFloat()

        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                PocketEditorTheme(darkTheme = dark, textScale = fontScale) {
                    when (scene) {
                        "first-import" -> FolderBrowserScreen(
                            FolderListing(
                                "disk:/writing",
                                listOf(RemoteFolder("disk:/writing/alchemist", "alchemist"), RemoteFolder("disk:/writing/winter", "winter-notes")),
                                listOf("chapter-01.md", "chapter-02.md", "chapter-03.md"),
                                otherFiles = 3,
                            ),
                            false, null, {}, {}, {}, {},
                        )
                        "confirmation" -> ImportConfirmationScreen(DRAFT, false, {}, {}, {})
                        "contents" -> ContentsPanel(
                            BOOKS, "book-a", "chapter-b", "дождём",
                            listOf(
                                SearchHit("chapter-b", "The Copper Gate", "…воздух пах дождём и старой медью…", 12, 18, 48, 73),
                                SearchHit("chapter-c", "A Name in Smoke", "…дождём размыло имя на письме…", 1, 7, 812, 826),
                            ),
                            false, "Close contents", {}, {}, {}, {}, {}, {}, {},
                        )
                        "discovery" -> ContentsPanel(
                            books = BOOKS,
                            currentBookId = "book-a",
                            currentChapterId = "chapter-b",
                            query = "",
                            searchResults = emptyList(),
                            searching = false,
                            closeLabel = "Close contents",
                            onClose = {}, onSwitchBook = {}, onChapterSelected = {}, onQueryChanged = {},
                            onSearchResult = {}, onOpenBooks = {}, onAppearance = {},
                            discoveryNotices = listOf(
                                DiscoveryNotice.NewFile("book-a", "chapter-04.md", "The Glass Orchard", 3, 3),
                                DiscoveryNotice.MissingFile(
                                    "book-a",
                                    "chapter-a",
                                    "The Salt Road",
                                    "chapter-01.md",
                                    "salt-road.md",
                                ),
                            ),
                            initialDiscoveryExpanded = true,
                        )
                        "appearance" -> AppearanceScreen(AppearancePreference(dark, 1.2f), {}, {}, {}, {}, {})
                        "reader" -> ReaderScreen(readerState(), ReaderCallbacks())
                        "recoverable" -> BooksScreen(
                            listOf(
                                BookSummary(
                                    "broken", "Winter Letters", "disk:/winter", emptyList(), false,
                                    "Local manifest is incomplete",
                                ),
                            ),
                            true, false, null, {}, {}, {}, {}, {}, {}, {},
                        )
                        "signout-error" -> BooksScreen(
                            BOOKS, true, false, null, {}, {}, {}, {}, {}, {}, {},
                            signOutError = "Could not delete protected credentials",
                        )
                        else -> BooksScreen(BOOKS, true, false, null, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        if (scene == "appearance") {
            compose.onNodeWithContentDescription("Reset text size").performScrollTo()
            compose.waitForIdle()
        }
        if (scene == "signout" || scene == "signout-error") {
            compose.onNodeWithContentDescription("Sign out of Yandex Disk").performClick()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }

        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf("$name%", "Pictures/PocketEditorTask11/"),
        )
        val output = requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketEditorTask11")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        resolver.openOutputStream(output).use { stream ->
            val screenshot = if (scene == "signout" || scene == "signout-error") {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            } else {
                compose.onRoot().captureToImage().asAndroidBitmap()
            }
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(stream)))
        }
        resolver.update(output, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private companion object {
        fun readerState() = ReaderState(
            bookId = "book-a",
            chapterId = "chapter-b",
            title = "The Copper Gate",
            document = ReaderDocument(
                listOf(
                    ReaderBlock(
                        sourceIndex = 0,
                        kind = BlockKind.PARAGRAPH,
                        canonicalText = "The gate held the last light of day.",
                        rawRange = RawRange(0, 38),
                        runs = listOf(ReaderRun("The gate held the last light of day.", ReaderRunKind.CANONICAL)),
                    ),
                ),
            ),
            reviewEnabled = false,
            chapterNote = null,
            reviewItems = null,
            previousChapter = ReaderChapter("chapter-a", "The Salt Road"),
            nextChapter = ReaderChapter("chapter-c", "A Name in Smoke"),
            readingPosition = null,
            syncState = ReaderSyncState.SAVED,
        )

        val BOOKS = listOf(
            BookSummary("book-a", "Alchemy of Rain", "disk:/alchemy", listOf(BookChapter("chapter-a", "The Salt Road"), BookChapter("chapter-b", "The Copper Gate"), BookChapter("chapter-c", "A Name in Smoke"))),
            BookSummary("book-b", "Winter Letters", "disk:/winter", listOf(BookChapter("chapter-d", "First Snow"), BookChapter("chapter-e", "The Empty Station"))),
        )
        val DRAFT = ImportDraft(
            "disk:/writing/alchemist",
            "Alchemy of Rain",
            listOf(
                ImportChapterDraft("chapter-01.md", "The Salt Road", true),
                ImportChapterDraft("chapter-02.md", "The Copper Gate", true),
                ImportChapterDraft("chapter-03.md", "A Name in Smoke", true),
                ImportChapterDraft("notes.md", "Author’s Notes", false),
            ),
        )
    }
}
