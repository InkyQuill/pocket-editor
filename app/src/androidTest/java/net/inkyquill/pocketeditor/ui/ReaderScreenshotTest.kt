package net.inkyquill.pocketeditor.ui

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderChapter
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderRun
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.reader.ReaderObservedLock
import java.time.Instant
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.PocketEditorApp
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.ui.navigation.PocketEditorRoot

class ReaderScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureReader() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass captureScreenshots=true to write a MediaStore artifact",
            arguments.getString("captureScreenshots", "false").toBoolean(),
        )
        val dark = arguments.getString("dark", "true").toBoolean()
        val fontScale = arguments.getString("fontScale", "1").toFloat()
        val review = arguments.getString("review", "false").toBoolean()
        val openReview = arguments.getString("openReview", "false").toBoolean()
        val name = arguments.getString("screenshotName", "reader")
        val actionRequired = arguments.getString("actionRequired", "false").toBoolean()
        val showBreak = arguments.getString("showBreak", "false").toBoolean()

        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                PocketEditorTheme(darkTheme = dark) {
                    ReaderScreen(
                        if (actionRequired) sampleState(review).copy(
                            syncState = ReaderSyncState.ACTION_REQUIRED,
                            syncReason = "Книга заблокирована другим сеансом Pocket Editor",
                            observedSyncLock = ReaderObservedLock(1, "lock-a", "tablet", Instant.parse("2026-07-19T12:00:00Z")),
                        ) else sampleState(review),
                        ReaderCallbacks(),
                    )
                }
            }
        }
        if (openReview) {
            compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        }
        if (showBreak) {
            compose.onNodeWithContentDescription("Снять обнаруженную устаревшую блокировку синхронизации").performClick()
            compose.mainClock.advanceTimeBy(1_000)
        }
        compose.waitForIdle()

        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$name.png", "Pictures/PocketEditorTask9%"),
        )
        val output = requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketEditorTask9")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        resolver.openOutputStream(output).use { stream ->
            requireNotNull(stream)
            val screenshot = if (showBreak) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            } else {
                compose.onRoot().captureToImage().asAndroidBitmap()
            }
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        resolver.update(output, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    @Test
    fun captureProductionRootReader() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captureProductionRoot", "false").toBoolean())
        val name = arguments.getString("screenshotName", "task11-production-root-reader")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketEditorApp
        val container = app.container
        val bookId = "00000000-0000-0000-0000-000000000811"
        val chapterId = "00000000-0000-0000-0000-000000000812"
        runBlocking {
            container.database.clearAllTables()
            container.bookPaths.bookDirectory(bookId).deleteRecursively()
            container.bookStore.replaceDownloadedSource(
                bookId,
                "chapter.md",
                ("# The City of Brass\n\n" + List(10) { "At dusk, the sandstone walls kept the last warmth of the sun.\n\n" }.joinToString("")).encodeToByteArray(),
            )
            container.bookStore.writeManifest(
                bookId,
                BookManifest(bookId = bookId, title = "Alchemy of Rain", chapters = listOf(ChapterEntry(chapterId, "chapter.md"))),
            )
            container.database.bookDao().upsertRoot(
                BookRootEntity(bookId, "disk:/writing/alchemist", container.bookPaths.bookDirectory(bookId).absolutePath, 1L),
            )
            container.database.bookDao().upsertReadingPosition(ReadingPositionEntity(bookId, chapterId, 0, 0, 1L))
            app.getSharedPreferences("device_preferences", android.content.Context.MODE_PRIVATE).edit()
                .putString("last_book_id", bookId)
                .putBoolean("dark_theme", true)
                .commit()
        }
        compose.setContent { PocketEditorRoot() }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("The City of Brass")).fetchSemanticsNodes().isNotEmpty() }

        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$name.png", "Pictures/PocketEditorTask11%"),
        )
        val output = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketEditorTask11")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        resolver.openOutputStream(output).use { stream ->
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(stream)))
        }
        resolver.update(output, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private fun sampleState(reviewEnabled: Boolean): ReaderState {
        val copy = listOf(
            "At dusk, the sandstone walls kept the last warmth of the sun.",
            "Nadia listened to the market settle into whispers, then opened the letter again. The ink had faded at every fold, but the warning was still clear.",
            "Beyond the blue awnings, lamps appeared one by one. Their light gathered on brass trays and bowls of dark fruit.",
            "She had crossed three provinces to reach this city. Now that its gates stood behind her, the road felt easier than the answer waiting ahead.",
            "Every map is a promise made by someone who has already left.",
            "The tower bell sounded once. Nadia put the letter away and followed the narrow street toward the river.",
        )
        val blocks = buildList {
            add(block(0, BlockKind.HEADING, "The City of Brass"))
            copy.forEachIndexed { index, text ->
                add(block(index + 1, if (index == 4) BlockKind.QUOTE else BlockKind.PARAGRAPH, text))
            }
        }
        return ReaderState(
            bookId = "The Alchemist",
            chapterId = "chapter-02",
            title = "Chapter 2 · The City of Brass",
            document = ReaderDocument(blocks),
            reviewEnabled = reviewEnabled,
            chapterNote = "Keep the quiet pressure through the final paragraph. The bell should feel inevitable.",
            reviewItems = null,
            previousChapter = ReaderChapter("chapter-01", "The Salt Road"),
            nextChapter = ReaderChapter("chapter-03", "A Name in Smoke"),
            readingPosition = null,
            syncState = ReaderSyncState.SAVED,
        )
    }

    private fun block(index: Int, kind: BlockKind, text: String) = ReaderBlock(
        sourceIndex = index,
        kind = kind,
        canonicalText = text,
        rawRange = RawRange(index * 500, index * 500 + text.encodeToByteArray().size),
        runs = listOf(ReaderRun(text, ReaderRunKind.CANONICAL)),
    )
}
