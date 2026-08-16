package net.inkyquill.pocketeditor.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.markdown.RenderKind
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderChapter
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderRun
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderLoadState
import net.inkyquill.pocketeditor.reader.ReaderPosition
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.reader.flyoutPlacementIsBelow
import net.inkyquill.pocketeditor.ui.reader.anchoredHorizontalOffsetInRoot
import net.inkyquill.pocketeditor.ui.review.ReviewDraftSession
import net.inkyquill.pocketeditor.ui.review.ReviewSelection
import net.inkyquill.pocketeditor.ui.review.ReviewUiState
import net.inkyquill.pocketeditor.ui.review.NoteSaveStatus
import net.inkyquill.pocketeditor.ui.reader.ReaderRoute
import net.inkyquill.pocketeditor.ui.reader.ReaderViewModel
import net.inkyquill.pocketeditor.ui.reader.ReaderSearchTarget
import net.inkyquill.pocketeditor.ui.navigation.PocketEditorRoot
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow

class AdaptiveReaderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inAppTextScaleChangesReaderTypographyButNotTopBarChrome() {
        val state = sampleState(false).copy(
            title = "Reader chrome title",
            document = ReaderDocument(
                listOf(
                    block(0, BlockKind.HEADING, "Heading level one").copy(headingLevel = 1),
                    block(1, BlockKind.HEADING, "Heading level four").copy(headingLevel = 4),
                    block(2, BlockKind.PARAGRAPH, "Scaled paragraph prose."),
                ),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true, textScale = 1.3f) {
                ReaderScreen(state, ReaderCallbacks(), windowSize = DpSize(360.dp, 800.dp))
            }
        }

        assertEquals(36.4f, compose.onNodeWithText("Heading level one").fontSize(), 0.01f)
        assertEquals(22.1f, compose.onNodeWithText("Heading level four").fontSize(), 0.01f)
        assertEquals(20.8f, compose.onNodeWithText("Scaled paragraph prose.").fontSize(), 0.01f)
        assertEquals(18f, compose.onNodeWithTag("reader-topbar-title").fontSize(), 0.01f)
        assertEquals(13f, compose.onNodeWithTag("reader-topbar-sync", useUnmergedTree = true).fontSize(), 0.01f)
    }

    @Test
    fun proseRendersInlineStylesQuoteRuleAndHangingListMarker() {
        val inlineText = "Тихий вечер и ссылка"
        val inlineBlock = ReaderBlock(
            sourceIndex = 0,
            kind = BlockKind.PARAGRAPH,
            canonicalText = inlineText,
            rawRange = RawRange(0, inlineText.encodeToByteArray().size),
            runs = listOf(
                ReaderRun("Тихий ", ReaderRunKind.CANONICAL),
                ReaderRun("вечер", ReaderRunKind.CANONICAL, renderKind = RenderKind.EMPHASIS),
                ReaderRun(" и ", ReaderRunKind.CANONICAL),
                ReaderRun("ссылка", ReaderRunKind.CANONICAL, renderKind = RenderKind.LINK),
            ),
        )
        val state = sampleState(false).copy(
            document = ReaderDocument(
                listOf(
                    inlineBlock,
                    block(1, BlockKind.QUOTE, "Цитата без обязательного курсива"),
                    block(2, BlockKind.LIST_ITEM, "Пункт списка с висячим отступом"),
                ),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(state, ReaderCallbacks(), windowSize = DpSize(360.dp, 800.dp))
            }
        }

        val spans = compose.onNodeWithText(inlineText).textLayout().layoutInput.text.spanStyles
        assertTrue(spans.any { it.start == 6 && it.end == 11 && it.item.fontStyle == FontStyle.Italic })
        assertTrue(spans.any { it.start == 14 && it.end == 20 && it.item.textDecoration == TextDecoration.Underline })
        assertTrue(spans.none { it.item.fontWeight == FontWeight.Bold && it.start == 6 && it.end == 11 })
        compose.onNodeWithTag("reader-quote-marker-1").assertIsDisplayed()
        compose.onNodeWithTag("reader-list-marker-2").assertTextContains("•")
    }

    @Test
    fun footnoteReferenceOpensCompactPopover() {
        val footnoteBlock = ReaderBlock(
            sourceIndex = 0,
            kind = BlockKind.PARAGRAPH,
            canonicalText = "1",
            rawRange = RawRange(0, 4),
            runs = listOf(
                ReaderRun(
                    text = "1",
                    kind = ReaderRunKind.CANONICAL,
                    renderKind = RenderKind.FOOTNOTE_REFERENCE,
                    footnoteLabel = "note",
                ),
            ),
        )
        val state = sampleState(false).copy(
            document = ReaderDocument(
                blocks = listOf(footnoteBlock),
                footnotes = mapOf("note" to "Важное примечание."),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(state, ReaderCallbacks(), windowSize = DpSize(360.dp, 800.dp))
            }
        }

        compose.onNodeWithTag("reader-text-0").performClick()

        compose.onNodeWithTag("footnote-popover").assertIsDisplayed()
        compose.onNodeWithText("Важное примечание.").assertIsDisplayed()
    }

    @Test
    fun rootOpensBooksWhenNoUsableRootExists() {
        compose.setContent { PocketEditorRoot() }

        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText("Библиотека")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Библиотека").assertIsDisplayed()
    }

    @Test
    fun readerRouteObservesViewModelOwnedState() {
        val state = MutableStateFlow<ReaderLoadState?>(ReaderLoadState.Ready(sampleState(false)))
        val viewModel = ReaderViewModel(state, ReaderCallbacks())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderRoute(viewModel, windowSize = DpSize(360.dp, 800.dp))
            }
        }

        compose.onNodeWithText("Глава на устройстве").assertIsDisplayed()
        compose.runOnIdle { state.value = ReaderLoadState.Ready(sampleState(false).copy(title = "The Glass Orchard")) }
        compose.onNodeWithText("The Glass Orchard").assertIsDisplayed()
    }

    @Test
    fun narrowReaderShowsEveryYandexStateWithoutClippingTheLocalChapterStatus() {
        val size = DpSize(320.dp, 720.dp)
        val metrics = compose.activity.resources.displayMetrics
        val renderDensity = minOf(
            metrics.widthPixels / size.width.value,
            metrics.heightPixels / size.height.value,
        )
        val state = mutableStateOf(sampleState(false))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, 1f)) {
                PocketEditorTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(size)) {
                        ReaderScreen(state.value, ReaderCallbacks(), windowSize = size)
                    }
                }
            }
        }

        val cases = listOf(
            ReaderSyncState.SAVED to "Яндекс Диск: синхронизировано",
            ReaderSyncState.WAITING_TO_SYNC to "Яндекс Диск: ждёт отправки",
            ReaderSyncState.SYNCING to "Яндекс Диск: синхронизация",
            ReaderSyncState.SIGN_IN_REQUIRED to "Яндекс Диск: нужен вход",
            ReaderSyncState.ACTION_REQUIRED to "Яндекс Диск: требуется действие",
        )
        cases.forEach { (syncState, remoteLabel) ->
            compose.runOnIdle { state.value = state.value.copy(syncState = syncState) }
            compose.onNodeWithContentDescription("Глава на устройстве. $remoteLabel").assertIsDisplayed()
            val localLayout = compose.onNodeWithTag("reader-topbar-local", useUnmergedTree = true).textLayout()
            assertFalse(
                "local chapter status must fit at 320dp for $syncState; " +
                    "size=${localLayout.size}, lines=${localLayout.lineCount}, " +
                    "widthOverflow=${localLayout.didOverflowWidth}, heightOverflow=${localLayout.didOverflowHeight}",
                localLayout.hasVisualOverflow,
            )
            assertFalse(
                "Yandex status must fit at 320dp for $syncState",
                compose.onNodeWithTag("reader-topbar-sync", useUnmergedTree = true).textLayout().hasVisualOverflow,
            )
        }
    }

    @Test
    fun chapterNoteSaveStatusRemainsSeparateFromYandexSyncStatus() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    state = sampleState(true).copy(syncState = ReaderSyncState.WAITING_TO_SYNC),
                    callbacks = ReaderCallbacks(),
                    reviewUiState = ReviewUiState(
                        chapterNote = "Локальный черновик",
                        noteSaveStatus = NoteSaveStatus.SAVING,
                    ),
                    windowSize = DpSize(1280.dp, 800.dp),
                )
            }
        }

        compose.onNodeWithText("Яндекс Диск: ждёт отправки").assertIsDisplayed()
        compose.onNodeWithContentDescription("Заметка к главе: Сохраняем").assertIsDisplayed()
    }

    @Test
    fun phoneUsesScrollableReaderAndModalPanels() {
        val state = sampleState(false).copy(
            document = ReaderDocument(
                sampleState(false).document.blocks + (10..24).map { index ->
                    block(index, BlockKind.PARAGRAPH, "The road carried another quiet detail into the evening.")
                },
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(state, ReaderCallbacks(), windowSize = DpSize(360.dp, 800.dp))
            }
        }

        compose.onNodeWithTag("reader-scroll").assert(hasScrollAction())
        val heading = hasText("The City of Brass") and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        val beforeScroll = compose.onNode(heading).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            scroll(0f, 24f)
        }
        compose.waitForIdle()
        val afterScroll = compose.onNode(heading).fetchSemanticsNode().boundsInRoot.top
        assertTrue("A real scroll action must move prose", afterScroll < beforeScroll)
        compose.onNodeWithContentDescription("Открыть оглавление").performClick()
        compose.onNodeWithTag("contents-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть оглавление").assertHasClickAction()
        compose.onNodeWithContentDescription("Закрыть оглавление").performClick()
        compose.onNodeWithTag("contents-sheet").assertIsNotDisplayed()

        compose.onNodeWithContentDescription("Режим рецензирования выключен").assert(role(Role.Button)).assertIsOff().performClick()
        compose.onNodeWithContentDescription("Режим рецензирования включён").assertIsOn()
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
        compose.onNodeWithContentDescription("Открыть панель рецензии").assertHasClickAction()
    }

    @Test
    fun phoneReviewToggleChangesTextWithoutOpeningPanelsOrShowingTheOldEdgeControl() {
        setReviewOverlayReader(DpSize(360.dp, 800.dp))

        compose.onNodeWithContentDescription("Режим рецензирования выключен").performClick()

        compose.onNodeWithContentDescription("Base text. Добавленный текст замены: review overlay").fetchSemanticsNode()
        compose.onAllNodesWithTag("contents-sheet").assertCountEquals(0)
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
        compose.onNodeWithContentDescription("Открыть панель рецензии").assertHasClickAction()
        compose.onAllNodes(hasContentDescription("Развернуть панель рецензии")).assertCountEquals(0)
    }

    @Test
    fun phonePreservesExpandedReviewPanelWhileReviewModeChanges() {
        val reviewEnabled = setReviewPanelPreservationReader(DpSize(360.dp, 800.dp))

        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
        compose.runOnIdle { reviewEnabled.value = false }
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
        compose.runOnIdle { reviewEnabled.value = true }
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
    }

    @Test
    fun reviewFabRendersInTheBottomRightQuadrantOnPhoneAndTabletPortrait() {
        val sizeState = mutableStateOf(DpSize(360.dp, 800.dp))
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                key(sizeState.value) {
                    ReaderScreen(
                        state = sampleState(true),
                        callbacks = ReaderCallbacks(),
                        windowSize = sizeState.value,
                    )
                }
            }
        }

        listOf(DpSize(360.dp, 800.dp), DpSize(800.dp, 1_280.dp)).forEach { size ->
            compose.runOnIdle { sizeState.value = size }

            val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
            val fab = compose.onNodeWithContentDescription("Открыть панель рецензии").fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density

            assertTrue(
                "FAB must be in the right half of the screen at size=$size; fab=$fab root=$root",
                fab.left > root.left + root.width / 2f,
            )
            assertTrue(
                "FAB must be in the bottom half of the screen at size=$size; fab=$fab root=$root",
                fab.top > root.top + root.height / 2f,
            )
            assertTrue(
                "FAB keeps a 44dp minimum touch target at size=$size",
                fab.width / density >= 44f && fab.height / density >= 44f,
            )
        }
    }

    @Test
    fun lastParagraphScrollsFullyClearOfTheReviewFabWhenReviewIsEnabled() {
        val sizeState = mutableStateOf(DpSize(360.dp, 800.dp))
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                key(sizeState.value) {
                    ReaderScreen(
                        state = sampleState(true),
                        callbacks = ReaderCallbacks(),
                        windowSize = sizeState.value,
                    )
                }
            }
        }

        listOf(DpSize(360.dp, 800.dp), DpSize(800.dp, 1_280.dp)).forEach { size ->
            compose.runOnIdle { sizeState.value = size }
            compose.waitForIdle()

            compose.onNodeWithTag("reader-scroll").performScrollToIndex(9)
            compose.waitForIdle()

            val lastBlock = compose.onNodeWithTag("reader-block-9", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val fab = compose.onNodeWithContentDescription("Открыть панель рецензии").fetchSemanticsNode().boundsInRoot

            assertTrue(
                "the last paragraph must be fully above the FAB once scrolled to the end at size=$size; lastBlock=$lastBlock fab=$fab",
                lastBlock.bottom <= fab.top,
            )
        }
    }

    @Test
    fun selectionFlyoutFlipsAboveWhenTheSelectionIsNearTheBottomOfTheViewport() {
        val state = sampleState(false)
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                Box(Modifier.requiredSize(360.dp, 520.dp)) {
                    ReaderScreen(
                        state,
                        ReaderCallbacks(
                            onTextSelected = { selected ->
                                reviewUi.value = selected?.let {
                                    ReviewUiState(
                                        draftSession = ReviewDraftSession(
                                            pendingSelection = ReviewSelection(0, 0, it.selectedText.length, it.rawRange, it.selectedText),
                                        ),
                                    )
                                } ?: ReviewUiState()
                            },
                        ),
                        reviewUi.value,
                        windowSize = DpSize(360.dp, 520.dp),
                    )
                }
            }
        }

        compose.onNodeWithTag("reader-scroll").performScrollToIndex(9)
        compose.waitForIdle()
        val selectedText = compose.onNodeWithTag("reader-text-9", useUnmergedTree = true)
        val cursor = selectedText.textLayout().getCursorRect(68)
        val selectedEndpointTop = selectedText.fetchSemanticsNode().boundsInRoot.top + cursor.top
        selectedText.performTouchInput {
            longClick(Offset(cursor.center.x, cursor.center.y))
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("selection-flyout", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        val selectionBlockBounds = compose.onNodeWithTag("reader-block-9", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val flyoutBounds = compose.onNodeWithTag("selection-flyout", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val columnBounds = compose.onNodeWithTag("reader-column", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the flyout must render above the active selection endpoint when there is no room below; endpointTop=$selectedEndpointTop selection=$selectionBlockBounds flyout=$flyoutBounds column=$columnBounds",
            flyoutBounds.bottom <= selectedEndpointTop + 1f,
        )
    }

    @Test
    fun actualScrollPersistsBlockAndRawByteAndRecreationRestoresIt() {
        val saved = mutableListOf<ReaderPosition>()
        val state = mutableStateOf(
            sampleState(false).copy(
                document = ReaderDocument(
                    sampleState(false).document.blocks + (10..24).map { index ->
                        block(index, BlockKind.PARAGRAPH, "The road carried another quiet detail into the evening.")
                    },
                ),
            ),
        )
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) PocketEditorTheme(darkTheme = true) {
                key(state.value.readingPosition) {
                    ReaderScreen(
                        state.value,
                        ReaderCallbacks(onReadingPositionChanged = saved::add),
                        windowSize = DpSize(360.dp, 800.dp),
                    )
                }
            }
        }

        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollToIndex) { scrollToIndex ->
            scrollToIndex(8)
        }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(3_000) { (saved.lastOrNull()?.blockIndex ?: 0) > 0 }
        val position = saved.last()
        assertTrue(position.byteOffset == position.blockIndex * 100)

        compose.runOnIdle {
            state.value = state.value.copy(readingPosition = position)
            visible.value = true
        }
        compose.onNodeWithTag("reader-block-${position.blockIndex}").assertIsDisplayed()
    }

    @Test
    fun exactSearchTargetScrollsToDeepLineInsideSingleLongParagraph() {
        val positioned = mutableListOf<Int>()
        val text = (0 until 90).joinToString("\n") { "line $it ordinary words" }
        val selected = "line 78 ordinary"
        val start = text.indexOf(selected)
        val longBlock = ReaderBlock(
            sourceIndex = 0,
            kind = BlockKind.PARAGRAPH,
            canonicalText = text,
            rawRange = RawRange(0, text.length),
            runs = listOf(
                ReaderRun(
                    text,
                    ReaderRunKind.CANONICAL,
                    sourceByteBoundaries = (0..text.length).toList(),
                ),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(false).copy(document = ReaderDocument(listOf(longBlock))),
                    ReaderCallbacks(onSearchTargetPositioned = positioned::add),
                    windowSize = DpSize(360.dp, 800.dp),
                    searchTarget = ReaderSearchTarget(start, start + selected.length),
                )
            }
        }

        compose.waitUntil(3_000) { (positioned.lastOrNull() ?: 0) > 500 }
        compose.onNodeWithContentDescription("Результат поиска: line 78 ordinary", substring = true).fetchSemanticsNode()
    }

    @Test
    fun rotatedPhoneKeepsModalFullWidthReader() {
        setReader(DpSize(800.dp, 360.dp), dark = true, fontScale = 1f)

        compose.onAllNodesWithTag("contents-sidebar").assertCountEquals(0)
        compose.onAllNodesWithTag("review-sidebar").assertCountEquals(0)
        compose.onNodeWithContentDescription("Открыть оглавление").performClick()
        compose.onNodeWithTag("contents-sheet").assertIsDisplayed()
    }

    @Test
    fun portraitTabletUsesContentsMenuAndNonNarrowingReviewOverlay() {
        setReader(DpSize(800.dp, 1280.dp), dark = true, fontScale = 1.5f)

        val before = compose.onNodeWithTag("reader-column", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Открыть оглавление").performClick()
        compose.onNodeWithTag("contents-drawer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть оглавление").assertHasClickAction()
        compose.onNodeWithContentDescription("Закрыть оглавление").performClick()

        compose.onNodeWithContentDescription("Режим рецензирования выключен").performClick()
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        val fabWidth = compose.onNodeWithContentDescription("Открыть панель рецензии").fetchSemanticsNode().boundsInRoot.width
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("The review FAB keeps a 44dp touch target", fabWidth / density >= 44f)
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть панель рецензии").assertHasClickAction()
        val after = compose.onNodeWithTag("reader-column", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Review overlay must not narrow prose", before.width == after.width)
    }

    @Test
    fun portraitTabletReviewToggleChangesTextWithoutOpeningPanels() {
        setReviewOverlayReader(DpSize(800.dp, 1280.dp))

        compose.onNodeWithContentDescription("Режим рецензирования выключен").performClick()

        compose.onNodeWithContentDescription("Base text. Добавленный текст замены: review overlay").fetchSemanticsNode()
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.onNodeWithContentDescription("Открыть панель рецензии").assertHasClickAction()
    }

    @Test
    fun portraitTabletPreservesExpandedReviewPanelWhileReviewModeChanges() {
        val reviewEnabled = setReviewPanelPreservationReader(DpSize(800.dp, 1280.dp))

        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
        compose.runOnIdle { reviewEnabled.value = false }
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.runOnIdle { reviewEnabled.value = true }
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
    }

    @Test
    fun portraitPanelsAreAccessibleModalsWithBackScrimAndReopen() {
        setReaderInLogicalRoot(
            DpSize(800.dp, 1280.dp),
            dark = true,
            fontScale = 1f,
            reviewEnabled = false,
        )

        compose.onNodeWithContentDescription("Открыть оглавление").performClick()
        compose.onNodeWithTag("contents-drawer")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Оглавление"))
        compose.onAllNodes(hasContentDescription("Режим рецензирования выключен")).assertCountEquals(0)
        compose.onNodeWithTag("contents-scrim").assertHasClickAction().performClick()
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.onNodeWithContentDescription("Режим рецензирования выключен").assertIsDisplayed()

        compose.onNodeWithContentDescription("Открыть оглавление").performClick()
        compose.onNodeWithTag("contents-drawer").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)

        compose.onNodeWithContentDescription("Режим рецензирования выключен").performClick()
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.onNodeWithTag("review-overlay")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Рецензия"))
        compose.onAllNodes(hasContentDescription("Открыть оглавление")).assertCountEquals(0)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.onNodeWithContentDescription("Открыть панель рецензии").assertIsDisplayed().performClick()
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
    }

    @Test
    fun portraitContentsModalHidesEveryReviewAndReaderAffordance() {
        setReader(DpSize(800.dp, 1280.dp), dark = true, fontScale = 1f, reviewEnabled = true)

        compose.onNodeWithContentDescription("Открыть оглавление").performClick()

        compose.onNodeWithTag("contents-drawer")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Оглавление"))
        compose.onNodeWithTag("contents-scrim").assertHasClickAction()
        compose.onAllNodes(hasContentDescription("Открыть панель рецензии")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Режим рецензирования включён")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Открыть оглавление")).assertCountEquals(0)
        compose.onAllNodes(hasText("The City of Brass") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertCountEquals(0)
    }

    @Test
    fun portraitBackFallsThroughWithoutModalAndDismissesOnlyVisibleModal() {
        var fallthroughCount = 0
        compose.activity.runOnUiThread {
            compose.activity.onBackPressedDispatcher.addCallback(
                compose.activity,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        fallthroughCount += 1
                    }
                },
            )
        }
        setReader(DpSize(800.dp, 1280.dp), dark = true, fontScale = 1f)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue("Back without a modal must fall through", fallthroughCount == 1) }

        compose.onNodeWithContentDescription("Открыть оглавление").performClick()
        compose.onNodeWithTag("contents-drawer").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.runOnIdle { assertTrue("Visible modal must consume Back", fallthroughCount == 1) }
    }

    @Test
    fun landscapeTabletOwnsSidebarControlsAndLeavesEdgeControls() {
        setReader(DpSize(1280.dp, 800.dp), dark = true, fontScale = 1f, reviewEnabled = true)

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Свернуть оглавление").assertHasClickAction().performClick()
        compose.onNodeWithTag("contents-sidebar").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Развернуть оглавление").assertIsDisplayed().performClick()
        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Свернуть панель рецензии").assertHasClickAction().performClick()
        compose.onNodeWithTag("review-sidebar").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Развернуть панель рецензии").assertIsDisplayed()
    }

    @Test
    fun landscapeTabletReviewToggleChangesTextWithoutChangingPanelExpansion() {
        setReviewOverlayReader(DpSize(1280.dp, 800.dp))

        compose.onNodeWithContentDescription("Режим рецензирования выключен").performClick()

        compose.onNodeWithContentDescription("Base text. Добавленный текст замены: review overlay").fetchSemanticsNode()
        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onAllNodesWithTag("review-sidebar").assertCountEquals(0)
        compose.onNodeWithContentDescription("Развернуть панель рецензии").assertHasClickAction()
    }

    @Test
    fun landscapeTabletPreservesExpandedReviewPanelWhileReviewModeChanges() {
        val reviewEnabled = setReviewPanelPreservationReader(DpSize(1280.dp, 800.dp))

        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
        compose.runOnIdle { reviewEnabled.value = false }
        compose.onAllNodesWithTag("review-sidebar").assertCountEquals(0)
        compose.runOnIdle { reviewEnabled.value = true }
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
    }

    @Test
    fun liveLandscapeToPortraitToPhoneTransitionRetainsOnlyReviewThenDismissesOnce() {
        val size = mutableStateOf(DpSize(1280.dp, 800.dp))
        val metrics = compose.activity.resources.displayMetrics
        compose.setContent {
            val logicalSize = size.value
            val renderDensity = minOf(
                metrics.widthPixels / logicalSize.width.value,
                metrics.heightPixels / logicalSize.height.value,
            )
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, 1f)) {
                PocketEditorTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(logicalSize)) {
                        ReaderScreen(sampleState(reviewEnabled = true), ReaderCallbacks(), windowSize = logicalSize)
                    }
                }
            }
        }

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()

        compose.runOnIdle { size.value = DpSize(800.dp, 1280.dp) }
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.onAllNodesWithTag("contents-scrim").assertCountEquals(0)
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
        compose.onNodeWithTag("review-scrim").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).assertCountEquals(1)

        compose.runOnIdle { size.value = DpSize(360.dp, 800.dp) }
        compose.onAllNodesWithTag("contents-sheet").assertCountEquals(0)
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("review-sheet").assertIsNotDisplayed() }.isSuccess
        }
        compose.onNodeWithContentDescription("Открыть панель рецензии").assertIsDisplayed()
    }

    @Test
    fun liveTransitionWithReviewDisabledRetainsOnlyContentsAcrossPortraitAndPhone() {
        val size = mutableStateOf(DpSize(1280.dp, 800.dp))
        val reviewEnabled = mutableStateOf(true)
        val metrics = compose.activity.resources.displayMetrics
        compose.setContent {
            val logicalSize = size.value
            val renderDensity = minOf(
                metrics.widthPixels / logicalSize.width.value,
                metrics.heightPixels / logicalSize.height.value,
            )
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, 1f)) {
                PocketEditorTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(logicalSize)) {
                        ReaderScreen(
                            sampleState(reviewEnabled = reviewEnabled.value),
                            ReaderCallbacks(),
                            windowSize = logicalSize,
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()

        compose.runOnIdle {
            reviewEnabled.value = false
            size.value = DpSize(800.dp, 1280.dp)
        }
        compose.onNodeWithTag("contents-drawer").assertIsDisplayed()
        compose.onNodeWithTag("contents-scrim").assertIsDisplayed()
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.onAllNodesWithTag("review-scrim").assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).assertCountEquals(1)

        compose.runOnIdle { size.value = DpSize(360.dp, 800.dp) }
        compose.onNodeWithTag("contents-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("contents-sheet").assertIsNotDisplayed() }.isSuccess
        }
    }

    @Test
    fun adaptiveThemeAndFontMatrixKeepsPanelsReadableAndControlsVisible() {
        val darkState = mutableStateOf(false)
        val fontScaleState = mutableStateOf(1f)
        val sizeState = mutableStateOf(DpSize(360.dp, 800.dp))
        val revision = mutableStateOf(0)
        val metrics = compose.activity.resources.displayMetrics
        val viewportWidthPx = metrics.widthPixels.toFloat()
        val viewportHeightPx = metrics.heightPixels.toFloat()
        compose.setContent {
            val size = sizeState.value
            val renderDensity = minOf(
                viewportWidthPx / size.width.value,
                viewportHeightPx / size.height.value,
            )
            CompositionLocalProvider(
                LocalDensity provides Density(renderDensity, fontScaleState.value),
            ) {
                PocketEditorTheme(darkTheme = darkState.value) {
                    key(revision.value) {
                        Box(Modifier.requiredSize(size)) {
                            ReaderScreen(sampleState(true), ReaderCallbacks(), windowSize = size)
                        }
                    }
                }
            }
        }

        listOf(DpSize(800.dp, 1280.dp), DpSize(1280.dp, 800.dp)).forEach { size ->
            listOf(false, true).forEach { dark ->
                listOf(1f, 1.5f, 2f).forEach { fontScale ->
                    compose.runOnIdle {
                        sizeState.value = size
                        darkState.value = dark
                        fontScaleState.value = fontScale
                        revision.value += 1
                    }
                    assertTextNodeInsideRoot("Глава на устройстве", size, dark, fontScale)
                    assertInsideRoot("Режим рецензирования включён")
                    val width = compose.onNodeWithTag("reader-column").fetchSemanticsNode().boundsInRoot.width
                    val logicalDensity = minOf(
                        viewportWidthPx / size.width.value,
                        viewportHeightPx / size.height.value,
                    )
                    val logicalWidth = width / logicalDensity
                    assertTrue(
                        "Reader measure must remain bounded at $size, dark=$dark, fontScale=$fontScale: ${logicalWidth}dp",
                        logicalWidth <= 720f + (1f / logicalDensity),
                    )
                    if (size.width < size.height) {
                        assertInsideRoot("Открыть оглавление")
                        assertInsideRoot("Открыть панель рецензии")
                    } else {
                        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
                        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
                        assertInsideRoot("Свернуть оглавление")
                        assertInsideRoot("Свернуть панель рецензии")
                    }
                }
            }
        }
    }

    @Test
    fun controlsStayInsideWindowAtTwoHundredPercentFontScale() {
        setReader(DpSize(800.dp, 1280.dp), dark = false, fontScale = 2f, reviewEnabled = true)

        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        listOf("Открыть оглавление", "Режим рецензирования включён").forEach { label ->
            val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$label must remain inside the window",
                bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom,
            )
        }
        compose.onNodeWithTag("reader-scroll").assert(hasScrollAction())
    }

    @Test
    fun landscapeTabletControlsStayInsideRealLogicalRootAtTwoHundredPercentFontScale() {
        setReaderInLogicalRoot(
            size = DpSize(1280.dp, 800.dp),
            dark = true,
            fontScale = 2f,
            reviewEnabled = true,
        )

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
        listOf("Свернуть оглавление", "Режим рецензирования включён", "Свернуть панель рецензии").forEach(::assertInsideRoot)
        assertTextNodeInsideRoot("Глава на устройстве", DpSize(1280.dp, 800.dp), dark = true, fontScale = 2f)
    }

    @Test
    fun portraitPanelsStayInsideRealLogicalRootAtTwoHundredPercentFontScale() {
        setReaderInLogicalRoot(
            size = DpSize(800.dp, 1280.dp),
            dark = true,
            fontScale = 2f,
            reviewEnabled = true,
        )

        compose.onNodeWithContentDescription("Открыть оглавление").performClick()
        assertTaggedNodeInsideRoot("contents-drawer")
        assertDescriptionInsideTaggedPanel("Закрыть оглавление", "contents-drawer")
        assertTextInsideTaggedPanel("Главы", "contents-drawer")
        compose.onNodeWithContentDescription("Закрыть оглавление").performClick()

        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        assertTaggedNodeInsideRoot("review-overlay")
        assertDescriptionInsideTaggedPanel("Закрыть панель рецензии", "review-overlay")
        assertTextInsideTaggedPanel("Полный редакторский слой", "review-overlay")
        assertTextInsideTaggedPanel("Keep the quiet pressure through the final paragraph.", "review-overlay")
    }

    private fun setReader(
        size: DpSize,
        dark: Boolean,
        fontScale: Float,
        reviewEnabled: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                PocketEditorTheme(darkTheme = dark) {
                    ReaderScreen(
                        state = sampleState(reviewEnabled),
                        callbacks = ReaderCallbacks(),
                        windowSize = size,
                    )
                }
            }
        }
    }

    private fun setReviewOverlayReader(size: DpSize) {
        val state = sampleState(false).copy(
            document = ReaderDocument(
                listOf(
                    block(0, BlockKind.PARAGRAPH, "Base text").copy(
                        runs = listOf(
                            ReaderRun("Base text", ReaderRunKind.CANONICAL),
                            ReaderRun("review overlay", ReaderRunKind.ADDED),
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(state, ReaderCallbacks(), windowSize = size)
            }
        }
    }

    private fun setReviewPanelPreservationReader(size: DpSize): MutableState<Boolean> {
        val reviewEnabled = mutableStateOf(true)
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(sampleState(reviewEnabled.value), ReaderCallbacks(), windowSize = size)
            }
        }
        return reviewEnabled
    }

    private fun setReaderInLogicalRoot(
        size: DpSize,
        dark: Boolean,
        fontScale: Float,
        reviewEnabled: Boolean,
    ) {
        val metrics = compose.activity.resources.displayMetrics
        val renderDensity = minOf(
            metrics.widthPixels / size.width.value,
            metrics.heightPixels / size.height.value,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, fontScale)) {
                PocketEditorTheme(darkTheme = dark) {
                    Box(Modifier.requiredSize(size)) {
                        ReaderScreen(
                            state = sampleState(reviewEnabled),
                            callbacks = ReaderCallbacks(),
                            windowSize = size,
                        )
                    }
                }
            }
        }
    }

    private fun sampleState(reviewEnabled: Boolean) = ReaderState(
        bookId = "alchemy",
        chapterId = "chapter-02",
        title = "The City of Brass",
        document = ReaderDocument(
            blocks = listOf(
                block(0, BlockKind.HEADING, "The City of Brass"),
                block(1, BlockKind.PARAGRAPH, "At dusk, the sandstone walls kept the last warmth of the sun."),
                block(2, BlockKind.PARAGRAPH, "Nadia listened to the market settle into whispers, then opened the letter again."),
                block(3, BlockKind.QUOTE, "Every map is a promise made by someone who has already left."),
                block(4, BlockKind.PARAGRAPH, "Beyond the blue awnings, lamps appeared one by one along the market road."),
                block(5, BlockKind.PARAGRAPH, "Their light gathered on brass trays and bowls of dark fruit."),
                block(6, BlockKind.PARAGRAPH, "She had crossed three provinces to reach the city before the gates closed."),
                block(7, BlockKind.PARAGRAPH, "Now the road behind her felt easier than the answer waiting ahead."),
                block(8, BlockKind.PARAGRAPH, "The tower bell sounded once and every merchant looked toward the river."),
                block(9, BlockKind.PARAGRAPH, "Nadia folded the letter and followed the narrow street into the evening."),
            ),
        ),
        reviewEnabled = reviewEnabled,
        chapterNote = "Keep the quiet pressure through the final paragraph.",
        reviewItems = null,
        previousChapter = ReaderChapter("chapter-01", "The Salt Road"),
        nextChapter = ReaderChapter("chapter-03", "A Name in Smoke"),
        readingPosition = null,
        syncState = ReaderSyncState.SAVED,
        selectionDocument = net.inkyquill.pocketeditor.markdown.MarkdownParser.parse(
            listOf(
                "The City of Brass",
                "At dusk, the sandstone walls kept the last warmth of the sun.",
                "Nadia listened to the market settle into whispers, then opened the letter again.",
                "Every map is a promise made by someone who has already left.",
                "Beyond the blue awnings, lamps appeared one by one along the market road.",
                "Their light gathered on brass trays and bowls of dark fruit.",
                "She had crossed three provinces to reach the city before the gates closed.",
                "Now the road behind her felt easier than the answer waiting ahead.",
                "The tower bell sounded once and every merchant looked toward the river.",
                "Nadia folded the letter and followed the narrow street into the evening.",
            ).joinToString("\n\n"),
        ),
    )

    private fun block(index: Int, kind: BlockKind, text: String) = ReaderBlock(
        sourceIndex = index,
        kind = kind,
        canonicalText = text,
        rawRange = RawRange(index * 100, index * 100 + text.encodeToByteArray().size),
        runs = listOf(
            ReaderRun(
                text,
                ReaderRunKind.CANONICAL,
                // Byte offset of each character prefix, not the character index itself -
                // text.length undercounts multibyte (e.g. Cyrillic) text and would drift out
                // of sync with rawRange.endByte, which is already byte-accurate above.
                sourceByteBoundaries = (0..text.length).map { index * 100 + text.substring(0, it).encodeToByteArray().size },
                sourceDisplayStart = 0,
            ),
        ),
    )

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        var results: List<TextLayoutResult> = emptyList()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val captured = mutableListOf<TextLayoutResult>()
            check(action(captured))
            results = captured
        }
        return results.single()
    }

    private fun SemanticsNodeInteraction.fontSize(): Float = textLayout().layoutInput.style.fontSize.value

    private fun assertInsideRoot(label: String) {
        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$label must remain inside the window",
            bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom,
        )
    }

    private fun assertTextNodeInsideRoot(text: String, size: DpSize, dark: Boolean, fontScale: Float) {
        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$text must stay inside root at $size, dark=$dark, fontScale=$fontScale; node=$node root=$root",
            node.left >= root.left && node.top >= root.top && node.right <= root.right && node.bottom <= root.bottom,
        )
    }

    private fun assertTaggedNodeInsideRoot(tag: String) {
        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag must stay inside the reader root; node=$node root=$root",
            node.left >= root.left && node.top >= root.top && node.right <= root.right && node.bottom <= root.bottom,
        )
    }

    private fun assertDescriptionInsideTaggedPanel(description: String, panelTag: String) {
        val panel = compose.onNodeWithTag(panelTag).fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$description must stay inside $panelTag; node=$node panel=$panel",
            node.left >= panel.left && node.top >= panel.top && node.right <= panel.right && node.bottom <= panel.bottom,
        )
    }

    private fun assertTextInsideTaggedPanel(text: String, panelTag: String) {
        val panel = compose.onNodeWithTag(panelTag).fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$text must stay inside $panelTag; node=$node panel=$panel",
            node.left >= panel.left && node.top >= panel.top && node.right <= panel.right && node.bottom <= panel.bottom,
        )
    }
    @Test
    fun flyoutPrefersBelowButFlipsAboveWithExtraReservedRoomForTheSystemSelectionMenu() {
        val viewport = Rect(0f, 0f, 600f, 1_000f)

        // Plenty of room below: stays below.
        assertTrue(
            flyoutPlacementIsBelow(
                selection = Rect(200f, 100f, 300f, 150f),
                viewport = viewport,
                flyoutHeightPx = 120f,
                gapPx = 16f,
                reservedAbovePx = 56f,
            ),
        )

        // No room below, but more than enough above even with the reserved
        // system-menu buffer: flips above.
        assertFalse(
            flyoutPlacementIsBelow(
                selection = Rect(200f, 900f, 300f, 950f),
                viewport = viewport,
                flyoutHeightPx = 120f,
                gapPx = 16f,
                reservedAbovePx = 56f,
            ),
        )

        // No room below (selection.bottom is close to the viewport bottom),
        // and the room above is enough for the flyout itself but not enough
        // once the reserved system-menu buffer is added: the reserved buffer
        // must be the deciding factor, not just raw space.
        assertTrue(
            flyoutPlacementIsBelow(
                selection = Rect(200f, 180f, 300f, 900f),
                viewport = viewport,
                flyoutHeightPx = 120f,
                gapPx = 16f,
                reservedAbovePx = 56f,
            ),
        )
    }

    @Test
    fun anchoredHorizontalOffsetClampsFlyoutInReaderRootSpace() {
        val readerColumn = Rect(280f, 0f, 1_000f, 1_000f)
        val composerWidth = 320f

        listOf(
            Rect(950f, 100f, 980f, 150f),
            Rect(950f, 650f, 980f, 700f),
        ).forEach { selection ->
            val composerLeft = anchoredHorizontalOffsetInRoot(selection, readerColumn, composerWidth).toFloat()
            assertEquals(readerColumn.right - composerWidth, composerLeft)
            assertTrue(composerLeft >= readerColumn.left)
            assertTrue(composerLeft + composerWidth <= readerColumn.right)
        }

        val marginPx = with(compose.density) { 12.dp.toPx() }
        listOf(
            Rect(950f, 100f, 980f, 150f),
            Rect(950f, 650f, 980f, 700f),
        ).forEach { selection ->
            val marginedLeft = anchoredHorizontalOffsetInRoot(selection, readerColumn, composerWidth, marginPx)
            assertEquals((readerColumn.right - composerWidth - marginPx).toInt(), marginedLeft)
            assertTrue(marginedLeft >= readerColumn.left + marginPx)
        }
    }

    @Test
    fun anchoredHorizontalOffsetKeepsAMarginFromTheViewportEdgeWhenRequested() {
        val viewport = Rect(0f, 0f, 600f, 1_000f)
        val contentWidth = 320f
        val marginPx = 12f

        // Selection far to the right: clamp must stop `marginPx` short of the
        // right edge, not flush against it.
        val rightClamped = anchoredHorizontalOffsetInRoot(
            anchor = Rect(590f, 100f, 600f, 150f),
            viewport = viewport,
            contentWidthPx = contentWidth,
            marginPx = marginPx,
        )
        assertEquals((viewport.right - contentWidth - marginPx).toInt(), rightClamped)

        // Selection far to the left: clamp must stop `marginPx` past the left
        // edge, not flush against it.
        val leftClamped = anchoredHorizontalOffsetInRoot(
            anchor = Rect(0f, 100f, 10f, 150f),
            viewport = viewport,
            contentWidthPx = contentWidth,
            marginPx = marginPx,
        )
        assertEquals((viewport.left + marginPx).toInt(), leftClamped)

        // Default margin (0f) preserves the exact previous flush-to-edge
        // behavior for any caller that doesn't pass one (the flyout, Task 9).
        val flushClamped = anchoredHorizontalOffsetInRoot(
            anchor = Rect(590f, 100f, 600f, 150f),
            viewport = viewport,
            contentWidthPx = contentWidth,
        )
        assertEquals((viewport.right - contentWidth).toInt(), flushClamped)
    }
}
