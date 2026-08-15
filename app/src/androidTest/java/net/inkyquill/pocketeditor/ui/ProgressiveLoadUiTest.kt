package net.inkyquill.pocketeditor.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.MutableStateFlow
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.load.ProgressiveLoadSnapshot
import net.inkyquill.pocketeditor.reader.ReaderLoadState
import net.inkyquill.pocketeditor.ui.books.ProgressiveLoadCard
import net.inkyquill.pocketeditor.ui.books.ProgressiveLoadHost
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderRoute
import net.inkyquill.pocketeditor.ui.reader.ReaderViewModel
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ProgressiveLoadUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun progressCardIsDeterminatePoliteAndExposesContextActions() {
        compose.setContent {
            PocketEditorTheme {
                ProgressiveLoadCard(
                    snapshot = snapshot(cached = 7, activePath = "chapter-008-v2.md"),
                    nowMillis = 0L,
                    onPause = {}, onContinue = {}, onCancel = {}, onSignIn = {},
                )
            }
        }
        compose.onNodeWithText("Загружаем chapter-008-v2.md").assertIsDisplayed()
        compose.onNodeWithContentDescription("Загружено 7 из 52").assertIsDisplayed()
        compose.onNodeWithText("Приостановить").assertHasClickAction()
        compose.onNodeWithText("Отменить").assertHasClickAction()
    }

    @Test
    fun preparingDiscoveryCanBePausedOrCancelledWithoutLeavingTheDestination() {
        compose.setContent {
            PocketEditorTheme {
                ProgressiveLoadCard(
                    snapshot = snapshot(cached = 0, phase = ProgressiveLoadPhase.PREPARING, total = 0),
                    nowMillis = 0L,
                    onPause = {}, onContinue = {}, onCancel = {}, onSignIn = {},
                )
            }
        }
        compose.onNodeWithText("Готовим книгу…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Готовим книгу…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Загружено 0 из 0").assertDoesNotExist()
        compose.onNodeWithText("Приостановить").assertHasClickAction()
        compose.onNodeWithText("Отменить").assertHasClickAction()
    }

    @Test
    fun progressCardMapsPausedErrorsRetryAndCompletion() {
        val current = MutableStateFlow(snapshot(7, phase = ProgressiveLoadPhase.PAUSED))
        compose.setContent {
            PocketEditorTheme {
                val snapshot by current.collectAsState()
                ProgressiveLoadCard(snapshot, 0L, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Продолжить").assertHasClickAction()

        current.value = snapshot(7, error = ProgressiveLoadErrorCategory.UNAUTHORIZED)
        compose.onNodeWithText("Нужно войти в Яндекс Диск").assertIsDisplayed()
        compose.onNodeWithText("Войти").assertHasClickAction()
        compose.onNodeWithText("Продолжить").assertDoesNotExist()

        current.value = snapshot(7, error = ProgressiveLoadErrorCategory.OFFLINE)
        compose.onNodeWithText("Нет сети · продолжим автоматически").assertIsDisplayed()

        current.value = snapshot(7, error = ProgressiveLoadErrorCategory.RATE_LIMITED, retryAt = 2_001)
        compose.onNodeWithText("Лимит Яндекс Диска · повтор через 3 с").assertIsDisplayed()

        current.value = snapshot(7, error = ProgressiveLoadErrorCategory.TIMEOUT, retryAt = 2_001)
        compose.onNodeWithText("Яндекс Диск не ответил · повтор через 3 с").assertIsDisplayed()

        current.value = snapshot(7, error = ProgressiveLoadErrorCategory.SERVER, retryAt = 2_001)
        compose.onNodeWithText("Яндекс Диск временно недоступен · повтор через 3 с").assertIsDisplayed()

        current.value = snapshot(7, error = ProgressiveLoadErrorCategory.TEMPORARY_AVAILABILITY, retryAt = 2_001)
        compose.onNodeWithText("Файл временно недоступен · повтор через 3 с").assertIsDisplayed()

        current.value = snapshot(52, phase = ProgressiveLoadPhase.COMPLETE)
        compose.onNodeWithText("Книга доступна без сети").assertIsDisplayed()
    }

    @Test
    fun wholeCardPolitelyAnnouncesStatusChangesAtTheSameCount() {
        val current = MutableStateFlow(snapshot(7, error = ProgressiveLoadErrorCategory.OFFLINE))
        compose.setContent {
            PocketEditorTheme {
                val value by current.collectAsState()
                ProgressiveLoadCard(value, 0L, {}, {}, {}, {})
            }
        }

        fun assertCardStatus(expected: String) {
            val config = compose.onNodeWithTag("progressive-load-card").fetchSemanticsNode().config
            assertEquals(LiveRegionMode.Polite, config[SemanticsProperties.LiveRegion])
            assertEquals(expected, config[SemanticsProperties.StateDescription])
        }
        assertCardStatus("Нет сети · продолжим автоматически")
        current.value = snapshot(7, error = ProgressiveLoadErrorCategory.UNAUTHORIZED)
        compose.waitForIdle()
        assertCardStatus("Нужно войти в Яндекс Диск")
        current.value = snapshot(52, phase = ProgressiveLoadPhase.COMPLETE)
        compose.waitForIdle()
        assertCardStatus("Книга доступна без сети")
    }

    @Test
    fun hostKeepsProgressCardOutsideDestinationAndDismissesCompletion() {
        compose.setContent {
            PocketEditorTheme {
                ProgressiveLoadHost(
                    snapshot = snapshot(52, phase = ProgressiveLoadPhase.COMPLETE),
                    nowMillis = 0L,
                    onPause = {}, onContinue = {}, onCancel = {}, onSignIn = {},
                    completionDisplayMillis = 100L,
                ) { Text("Destination", Modifier.fillMaxSize().testTag("destination-body")) }
            }
        }
        val card = compose.onNodeWithTag("progressive-load-card").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("destination-body").fetchSemanticsNode().boundsInRoot
        assertTrue(card.bottom <= body.top)

        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithTag("progressive-load-card").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("progressive-load-card").assertDoesNotExist()
        compose.onNodeWithText("Destination").assertIsDisplayed()
    }

    @Test
    fun pendingReaderKeepsContentsChromeAndShowsSkeletonOnlyInBody() {
        compose.setContent {
            PocketEditorTheme {
                ReaderRoute(
                    viewModel = ReaderViewModel(
                        MutableStateFlow(ReaderLoadState.Pending("book", "chapter", "chapter")),
                        ReaderCallbacks(),
                    ),
                    contentsContent = { _, _ -> Text("Полное содержание") },
                )
            }
        }
        compose.onNodeWithContentDescription("Открыть оглавление").assertIsDisplayed()
        compose.onNodeWithText("Полное содержание").assertDoesNotExist()
        compose.onNodeWithTag("reader-body-skeleton").assertIsDisplayed()
        compose.onNodeWithText("Загружаем главу…").assertIsDisplayed()
    }

    private fun snapshot(
        cached: Int,
        activePath: String? = null,
        phase: ProgressiveLoadPhase = ProgressiveLoadPhase.BACKGROUND,
        error: ProgressiveLoadErrorCategory? = null,
        retryAt: Long? = null,
        total: Int = 52,
    ) = ProgressiveLoadSnapshot(
        bookId = "book",
        remoteRootPath = "disk:/Aria",
        phase = phase,
        totalFiles = total,
        completedFiles = cached,
        activePath = activePath,
        retryAttempt = 0,
        retryAt = retryAt,
        generation = 1,
        paused = phase == ProgressiveLoadPhase.PAUSED,
        cancelled = phase == ProgressiveLoadPhase.CANCELLED,
        lastErrorCategory = error,
        files = List(total) { index ->
            ProgressiveLoadFileEntity(
                "book", "chapter-$index.md", "chapter-$index", index, "r$index", null, null,
                if (index < cached) ProgressiveLoadFileState.CACHED else ProgressiveLoadFileState.PENDING,
                priority = 0,
            )
        },
    )
}
