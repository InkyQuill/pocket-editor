package net.inkyquill.pocketeditor.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderComment
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderRun
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.review.ConflictCard
import net.inkyquill.pocketeditor.ui.review.ReviewDraft
import net.inkyquill.pocketeditor.ui.review.ReviewDraftSession
import net.inkyquill.pocketeditor.ui.review.ReviewSelection
import net.inkyquill.pocketeditor.ui.review.ReviewUiState
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ReviewScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureReviewScene() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captureScreenshots", "false").toBoolean())
        val scene = arguments.getString("scene", "signal")
        val dark = arguments.getString("dark", "true").toBoolean()
        val fontScale = arguments.getString("fontScale", "1").toFloat()
        val name = arguments.getString("screenshotName", "review-$scene")
        val uiState = sceneState(scene)

        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                PocketEditorTheme(darkTheme = dark) {
                    ReaderScreen(readerState(), ReaderCallbacks(), uiState)
                }
            }
        }
        if (scene != "overlay") {
            compose.onNodeWithContentDescription("Open review panel").performClick()
            when (scene) {
                "signal", "edit" -> compose.onNodeWithTag("save-draft").performScrollTo()
                "conflict" -> compose.onNodeWithContentDescription("Keep mine for signal-7, selected").performScrollTo()
            }
        } else {
            compose.onNodeWithContentDescription("Warning signal").assertIsDisplayed()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$name.png", "Pictures/PocketEditorTask10%"),
        )
        val output = requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketEditorTask10")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        resolver.openOutputStream(output).use { stream ->
            val screenshot = if (scene == "overlay") {
                compose.onNodeWithTag("reader-root").captureToImage().asAndroidBitmap()
            } else {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            }
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(stream)))
        }
        resolver.update(output, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private fun sceneState(scene: String): ReviewUiState = when (scene) {
        "signal" -> ReviewUiState(
            draftSession = ReviewDraftSession(
                ReviewDraft.Signal(
                    null,
                    ReviewSelection(1, 10, 25, RawRange(40, 55), "the copper gate"),
                    SignalType.REVIEW,
                    "Check whether this image arrives too early.",
                ),
            ),
        )
        "edit" -> ReviewUiState(
            draftSession = ReviewDraftSession(
                ReviewDraft.Edit(
                    null,
                    ReviewSelection(1, 0, 24, RawRange(30, 54), "The market fell silent."),
                    "The market quieted around her.",
                ),
            ),
        )
        "conflict" -> ReviewUiState(
            conflicts = listOf(
                ConflictCard(
                    key = "review:chapter.review.json:signal-7",
                    path = "chapter.review.json",
                    recordId = "signal-7",
                    identity = "review-v1",
                    localPreview = "Keep the bell subdued",
                    yandexPreview = "Make the bell explicit",
                    selectedChoice = net.inkyquill.pocketeditor.sync.ConflictChoice.KEEP_MINE,
                ),
            ),
        )
        else -> ReviewUiState()
    }

    private fun readerState() = ReaderState(
        "Alchemy",
        "chapter-04",
        "The Copper Gate",
        ReaderDocument(
            listOf(
                block(0, BlockKind.HEADING, "The Copper Gate"),
                ReaderBlock(
                    1,
                    BlockKind.PARAGRAPH,
                    "The market fell silent as Nadia crossed beneath the copper gate.",
                    RawRange(30, 96),
                    listOf(
                        ReaderRun("The market fell silent ", ReaderRunKind.CANONICAL, setOf("warning"), setOf(SignalType.WARNING)),
                        ReaderRun("as Nadia crossed", ReaderRunKind.DELETED, setOf("review"), setOf(SignalType.REVIEW)),
                        ReaderRun("when Nadia stepped", ReaderRunKind.ADDED),
                        ReaderRun(" beneath the copper gate.", ReaderRunKind.CANONICAL, setOf("warning", "review"), setOf(SignalType.WARNING, SignalType.REVIEW)),
                    ),
                    listOf(
                        ReaderComment("warning", SignalType.WARNING, "The silence may need a cause.", RawRange(30, 52)),
                        ReaderComment("review", SignalType.REVIEW, "Recheck the gate image against chapter two.", RawRange(48, 96)),
                    ),
                ),
                block(2, BlockKind.PARAGRAPH, "Behind her, the evening traffic resumed in a low murmur."),
            ),
        ),
        true,
        "Keep the final page quiet and let the gate carry the unease.",
        null,
        null,
        null,
        null,
        ReaderSyncState.WAITING_TO_SYNC,
    )

    private fun block(index: Int, kind: BlockKind, text: String) = ReaderBlock(
        index,
        kind,
        text,
        RawRange(index * 100, index * 100 + text.encodeToByteArray().size),
        listOf(ReaderRun(text, ReaderRunKind.CANONICAL)),
    )
}
