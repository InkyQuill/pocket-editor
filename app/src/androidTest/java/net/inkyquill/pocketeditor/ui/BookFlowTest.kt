package net.inkyquill.pocketeditor.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import net.inkyquill.pocketeditor.ui.books.ImportDraftSummary
import net.inkyquill.pocketeditor.book.ImportDraftPhase
import net.inkyquill.pocketeditor.ui.books.RemoteFolder
import net.inkyquill.pocketeditor.ui.books.DiscoveryNotice
import net.inkyquill.pocketeditor.ui.contents.ContentsPanel
import net.inkyquill.pocketeditor.ui.search.SearchNavigation
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.settings.AppearanceScreen
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun libraryUsesCompactHierarchyAndKeepsDestructiveActionsSecondary() {
        var resumedDraft: String? = null
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                BooksScreen(
                    books = BOOKS,
                    importDrafts = listOf(
                        ImportDraftSummary(
                            "draft-a",
                            "disk:/alchemy-new",
                            "Alchemy, continued",
                            18,
                            ImportDraftPhase.READY,
                        ),
                    ),
                    signedIn = true,
                    signingIn = false,
                    forgetBookId = null,
                    discardDraftBookId = null,
                    onSignIn = {},
                    onAddBook = {},
                    onOpenBook = {},
                    onRequestForget = {},
                    onConfirmForget = {},
                    onCancelForget = {},
                    onAppearance = {},
                    onResumeDraft = { resumedDraft = it },
                    onRequestDiscardDraft = {},
                    onConfirmDiscardDraft = {},
                    onCancelDiscardDraft = {},
                )
            }
        }

        compose.onNodeWithText("Библиотека").assertIsDisplayed()
        compose.onNodeWithText("Pocket Editor").assertDoesNotExist()
        compose.onNodeWithTag("book-card-book-a").assertHasClickAction()
        compose.onNodeWithText("Забыть").assertDoesNotExist()
        compose.onNodeWithContentDescription("Действия с книгой Alchemy of Rain").performClick()
        compose.onNodeWithText("Забыть локальную копию").assertIsDisplayed()
        compose.onNodeWithTag("import-draft-card-draft-a").assertIsDisplayed()
        compose.onNodeWithText("Настроить книгу").performClick()
        compose.runOnIdle { assertEquals("draft-a", resumedDraft) }
    }

    @Test
    fun bookshelfUsesRussianInterfaceText() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                BooksScreen(
                    books = BOOKS,
                    signedIn = true,
                    signingIn = false,
                    forgetBookId = null,
                    onSignIn = {},
                    onAddBook = {},
                    onOpenBook = {},
                    onRequestForget = {},
                    onConfirmForget = {},
                    onCancelForget = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("Библиотека").assertIsDisplayed()
        compose.onAllNodesWithText("Books").assertCountEquals(0)
    }

    @Test
    fun readerKeepsContentsAccessibleWithoutPersistentChapterButtons() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(readerState(), ReaderCallbacks())
            }
        }

        compose.onNodeWithContentDescription("Открыть оглавление").assertIsDisplayed()
        compose.onAllNodesWithText("Previous").assertCountEquals(0)
        compose.onAllNodesWithText("Next").assertCountEquals(0)
    }

    @Test
    fun signedOutBooksKeepsOfflineRootReadableAndExplainsSignInBoundary() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                BooksScreen(
                    books = BOOKS,
                    signedIn = false,
                    signingIn = false,
                    forgetBookId = null,
                    onSignIn = {}, onAddBook = {}, onOpenBook = {}, onRequestForget = {},
                    onConfirmForget = {}, onCancelForget = {}, onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("Подключите Яндекс Диск").assertIsDisplayed()
        compose.onNodeWithText("Alchemy of Rain").assertIsDisplayed()
        compose.onNodeWithText("2 главы · Доступно без сети").assertIsDisplayed()
    }

    @Test
    fun signInFailureIsVisibleAndRetryable() {
        var retries = 0
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                BooksScreen(
                    books = emptyList(), signedIn = false, signingIn = false, forgetBookId = null,
                    onSignIn = { retries++ }, onAddBook = {}, onOpenBook = {}, onRequestForget = {},
                    onConfirmForget = {}, onCancelForget = {}, onAppearance = {},
                    signInError = "Не удалось войти. Попробуйте ещё раз.",
                )
            }
        }

        compose.onNodeWithText("Не удалось войти. Попробуйте ещё раз.").assertIsDisplayed()
        compose.onNodeWithText("Повторить вход").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun emptyStateSitsNearTheSignInCardInsteadOfCenteredInAllLeftoverSpace() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                BooksScreen(
                    books = emptyList(), signedIn = false, signingIn = false, forgetBookId = null,
                    onSignIn = {}, onAddBook = {}, onOpenBook = {}, onRequestForget = {},
                    onConfirmForget = {}, onCancelForget = {}, onAppearance = {},
                )
            }
        }

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val signInCard = compose.onNodeWithTag("sign-in-card").fetchSemanticsNode().boundsInRoot
        val emptyState = compose.onNodeWithTag("empty-books").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the empty state must start close under the sign-in card, not drift toward mid-screen; card=$signInCard empty=$emptyState",
            emptyState.top - signInCard.bottom < root.height / 4f,
        )
    }

    @Test
    fun signedInBooksOffersConfirmedSignOutWithoutRemovingShelf() {
        var signOutCount = 0
        val signOutError = mutableStateOf<String?>(null)
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                BooksScreen(
                    books = BOOKS, signedIn = true, signingIn = false, forgetBookId = null,
                    onSignIn = {}, onAddBook = {}, onOpenBook = {}, onRequestForget = {},
                    onConfirmForget = {}, onCancelForget = {}, onAppearance = {},
                    onSignOut = { signOutCount++ },
                    signOutError = signOutError.value,
                )
            }
        }

        compose.onNodeWithContentDescription("Выйти из Яндекс Диска").performClick()
        compose.onNodeWithText("Книги и рецензии останутся на устройстве. Синхронизация приостановится до следующего входа.")
            .assertIsDisplayed()
        compose.onNodeWithText("Выйти").performClick()
        compose.runOnIdle { assertEquals(1, signOutCount) }
        compose.onNodeWithText("Alchemy of Rain").assertIsDisplayed()

        compose.runOnIdle { signOutError.value = "Не удалось выйти. Попробуйте ещё раз." }
        compose.onNodeWithContentDescription("Повторить выход")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("Повторить выход").performClick()
        compose.runOnIdle { assertEquals(2, signOutCount) }
    }

    @Test
    fun folderBrowserUsesSelectedFolderItself() {
        var selected = false
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                FolderBrowserScreen(
                    listing = FolderListing("disk:/stories", listOf(RemoteFolder("disk:/stories/alchemy", "alchemy")), listOf("one.md", "two.md")),
                    loading = false,
                    error = null,
                    onBack = {}, onOpenFolder = {}, onChooseThisFolder = { selected = true }, onRetry = {},
                )
            }
        }

        compose.onNodeWithText("Найдены 2 главы Markdown. Далее вы сможете их проверить.").assertIsDisplayed()
        compose.onNodeWithText("Использовать эту папку").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(selected) }
    }

    @Test
    fun folderBrowserExplainsEmptyMarkdownAndDisablesChoosing() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                FolderBrowserScreen(
                    listing = FolderListing("disk:/empty", emptyList(), emptyList()),
                    loading = false,
                    error = null,
                    onBack = {}, onOpenFolder = {}, onChooseThisFolder = {}, onRetry = {},
                )
            }
        }

        compose.onNodeWithText("В этой папке нет файлов Markdown").assertIsDisplayed()
        compose.onNodeWithText("Использовать эту папку").assertIsNotEnabled()
    }

    @Test
    fun folderBrowserPreviewsFilesAndShowsLocalImportProgress() {
        var selected = false
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                FolderBrowserScreen(
                    listing = FolderListing(
                        path = "disk:/stories",
                        folders = listOf(RemoteFolder("disk:/stories/alchemy", "alchemy")),
                        markdown = listOf("chapter-01.md", "chapter-02.md", "chapter-03.md", "chapter-04.md", "chapter-05.md", "chapter-06.md", "chapter-07.md", "chapter-08.md", "chapter-09.md", "chapter-10.md"),
                        otherFiles = 3,
                    ),
                    loading = false,
                    error = null,
                    onBack = {}, onOpenFolder = {}, onChooseThisFolder = { selected = true }, onRetry = {},
                )
            }
        }

        compose.onNodeWithText("Главы Markdown").assertIsDisplayed()
        compose.onNodeWithText("chapter-01.md").assertIsDisplayed()
        compose.onNodeWithText("Ещё 2").assertIsDisplayed()
        compose.onNodeWithText("Другие файлы · 3").assertIsDisplayed()
        compose.onNodeWithText("Использовать эту папку").performClick()
        compose.onNodeWithText("Читаем файлы…").assertIsDisplayed()
        compose.onNodeWithText("Читаем файлы…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Читаем выбранную папку").assertIsDisplayed()
        compose.runOnIdle { assertTrue(selected) }
    }

    @Test
    fun folderBrowserEnablesChoosingAgainAfterErrorRecoveryOrPathChange() {
        val listing = mutableStateOf(FolderListing("disk:/stories", emptyList(), listOf("chapter.md")))
        val error = mutableStateOf<String?>(null)
        var chooseCalls = 0
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                FolderBrowserScreen(
                    listing = listing.value,
                    loading = false,
                    error = error.value,
                    onBack = {}, onOpenFolder = {}, onChooseThisFolder = { chooseCalls++ }, onRetry = {},
                )
            }
        }

        compose.onNodeWithText("Использовать эту папку").performClick()
        compose.onNodeWithText("Читаем файлы…").assertIsDisplayed()
        compose.runOnIdle { error.value = "Не удалось выполнить действие. Попробуйте ещё раз." }
        compose.onNodeWithText("Не удалось открыть папку").assertIsDisplayed()
        compose.runOnIdle { error.value = null }
        compose.onNodeWithText("Использовать эту папку").assertIsEnabled()

        compose.onNodeWithText("Использовать эту папку").performClick()
        compose.runOnIdle { assertEquals(2, chooseCalls) }
        compose.onNodeWithText("Читаем файлы…").assertIsDisplayed()
        compose.runOnIdle {
            listing.value = FolderListing("disk:/other", emptyList(), listOf("other.md"))
        }
        compose.onNodeWithText("Использовать эту папку").assertIsEnabled()
    }

    @Test
    fun importConfirmationEditsTitleInclusionAndSemanticOrderBeforeCaching() {
        val state = mutableStateOf(DRAFT)
        var confirmed = false
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ImportConfirmationScreen(
                    draft = state.value,
                    importing = false,
                    onDraftChanged = { state.value = it },
                    onBack = {},
                    onConfirm = { confirmed = true },
                )
            }
        }

        compose.onNodeWithText("Название книги").performTextClearance()
        compose.onNodeWithText("Название книги").performTextInput("The Alchemist")
        compose.onNodeWithContentDescription("Переместить «Salt Road» ниже").performClick()
        compose.onNodeWithContentDescription("Добавить главу «Copper Gate»").performScrollTo().performClick()
        compose.onNodeWithText("Добавить в библиотеку").performClick()

        compose.runOnIdle {
            assertEquals("The Alchemist", state.value.title)
            assertEquals(listOf("chapter-02.md", "chapter-01.md"), state.value.chapters.map { it.path })
            assertTrue(!state.value.chapters.first().included)
            assertTrue(confirmed)
        }
    }

    @Test
    fun importConfirmationLeadsWithSavedOfflineStatusAndCompactPrimaryAction() {
        val chapters = (1..18).map { index ->
            ImportChapterDraft("%02d.md".format(index), "Глава $index", true)
        }
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ImportConfirmationScreen(
                    ImportDraft("disk:/book01", "book01", chapters),
                    importing = false,
                    onDraftChanged = {},
                    onBack = {},
                    onConfirm = {},
                )
            }
        }

        compose.onNodeWithText("18 глав сохранены на устройстве").assertIsDisplayed()
        compose.onNodeWithText("До подтверждения ничего не будет создано").assertDoesNotExist()
        compose.onNodeWithTag("import-chapter-list").assertIsDisplayed()
        compose.onNodeWithTag("import-chapter-01.md").assertIsDisplayed()
        compose.onNodeWithText("Добавить в библиотеку").assertIsDisplayed()
        compose.onNodeWithText("Создать книгу для чтения без сети").assertDoesNotExist()
    }

    @Test
    fun importConfirmationAllowsUncheckingOnlyChapterAndDisablesCreate() {
        val state = mutableStateOf(
            ImportDraft("disk:/one", "One", listOf(ImportChapterDraft("one.md", "One", true))),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ImportConfirmationScreen(state.value, false, { state.value = it }, {}, {})
            }
        }

        compose.onNodeWithText("Выбрана 1 из 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Добавить главу «One»").performClick()

        compose.onNodeWithText("Выбрано 0 из 1 глав").assertIsDisplayed()
        compose.onNodeWithText("Добавить в библиотеку").assertIsNotEnabled()
    }

    @Test
    fun importFailureIsVisibleWithRetryAndBackControls() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ImportConfirmationScreen(
                    ImportDraft("disk:/one", "One", listOf(ImportChapterDraft("one.md", "One", true))),
                    importing = false,
                    onDraftChanged = {},
                    onBack = {},
                    onConfirm = {},
                    error = "Не удалось выполнить действие. Попробуйте ещё раз.",
                )
            }
        }

        compose.onNodeWithText("Не удалось выполнить действие. Попробуйте ещё раз.").assertIsDisplayed()
        compose.onNodeWithText("Добавить в библиотеку").assertIsEnabled()
        compose.onNodeWithContentDescription("Назад к библиотеке").assertIsEnabled()
    }

    @Test
    fun contentsOwnsBookSwitchChapterOrderAndExactSourceSearch() {
        var selectedBook: String? = null
        var selectedChapter: String? = null
        var selectedSearch: SearchNavigation? = null
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = BOOKS,
                    currentBookId = BOOKS.first().bookId,
                    currentChapterId = "chapter-a",
                    query = "дождём",
                    searchResults = listOf(SearchHit("chapter-b", "Copper Gate", "…пахло дождём…", 7, 13, 48, 73)),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onSwitchBook = { selectedBook = it },
                    onChapterSelected = { selectedChapter = it.id },
                    onQueryChanged = {},
                    onSearchResult = { selectedSearch = it },
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("Other Story").performClick()
        compose.onNodeWithText("Salt Road").performClick()
        compose.onNodeWithContentDescription("Совпадение: дождём").assertIsDisplayed()
        compose.onNodeWithText("…пахло дождём…").performClick()
        compose.runOnIdle {
            assertEquals("book-b", selectedBook)
            assertEquals("chapter-a", selectedChapter)
            assertEquals(SearchNavigation("chapter-b", 48, 73), selectedSearch)
        }
    }

    @Test
    fun chapterListSeparatesRowsWithDividersInsteadOfIndividualRowBackgrounds() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = BOOKS,
                    currentBookId = "book-a",
                    currentChapterId = "chapter-a",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onSwitchBook = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        // BOOKS.first() ("Alchemy of Rain") has 2 chapters -> exactly 1
        // divider between them, none before the first or after the last.
        compose.onAllNodesWithTag("chapter-divider").assertCountEquals(1)
    }

    @Test
    fun manageBooksSitsDirectlyBelowAShortChapterListInsteadOfAtTheDrawerBottom() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = BOOKS,
                    currentBookId = "book-b",
                    currentChapterId = "chapter-c",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onSwitchBook = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        // BOOKS[1] ("Other Story") has exactly 1 chapter, so the list is as
        // short as it gets.
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val lastChapter = compose.onNodeWithText("First Light").fetchSemanticsNode().boundsInRoot
        val manageBooks = compose.onNodeWithText("Управление книгами").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Manage books must sit close under the last chapter row, not near the drawer bottom; " +
                "lastChapter=$lastChapter manageBooks=$manageBooks root=$root",
            manageBooks.top - lastChapter.bottom < root.height / 4f,
        )
    }

    @Test
    fun searchResultRespondsToARealisticTouchDownAndUpNotJustASemanticClick() {
        var selectedSearch: SearchNavigation? = null
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = BOOKS,
                    currentBookId = BOOKS.first().bookId,
                    currentChapterId = "chapter-a",
                    query = "дождём",
                    searchResults = listOf(SearchHit("chapter-b", "Copper Gate", "…пахло дождём…", 7, 13, 48, 73)),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onSwitchBook = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = { selectedSearch = it },
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("…пахло дождём…").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(SearchNavigation("chapter-b", 48, 73), selectedSearch)
        }
    }

    @Test
    fun appearanceProvidesOneThemeSwitchAndBoundedTextControls() {
        val dark = mutableStateOf(false)
        var decrease = 0
        var reset = 0
        var increase = 0
        compose.setContent {
            PocketEditorTheme(darkTheme = false, textScale = 1.3f) {
                AppearanceScreen(
                    AppearancePreference(dark.value, 1.3f),
                    onBack = {},
                    onDarkChanged = { dark.value = it },
                    onDecrease = { decrease++ },
                    onReset = { reset++ },
                    onIncrease = { increase++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Тёмная тема").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("Сбросить размер текста").performScrollTo()
        compose.onNodeWithText("−").performClick()
        compose.onNodeWithContentDescription("Сбросить размер текста").performClick()
        compose.onNodeWithText("+").performClick()
        compose.runOnIdle {
            assertTrue(dark.value)
            assertEquals(1, decrease)
            assertEquals(1, reset)
            assertEquals(1, increase)
        }
    }

    @Test
    fun appearanceSampleTextScalesWithTheLiveTextScalePreference() {
        // Adaptation: the plan's literal test calls compose.setContent twice in one test.
        // createAndroidComposeRule throws IllegalStateException("...has already set content...")
        // on a second setContent call within the same test. Instead, content is set once and
        // driven by a mutable textScale state, matching how the app root actually recomposes
        // AppearanceScreen live as the preference changes.
        val textScale = mutableStateOf(1f)
        compose.setContent {
            PocketEditorTheme(darkTheme = false, textScale = textScale.value) {
                AppearanceScreen(
                    AppearancePreference(dark = false, textScale = textScale.value),
                    onBack = {},
                    onDarkChanged = {},
                    onDecrease = {},
                    onReset = {},
                    onIncrease = {},
                )
            }
        }
        val fontSizeAt100Percent = compose.onNodeWithText("Быстрая лисица пересекла залитый лунным светом двор.").fontSize()

        compose.runOnIdle { textScale.value = 1.3f }
        compose.waitForIdle()
        val fontSizeAt130Percent = compose.onNodeWithText("Быстрая лисица пересекла залитый лунным светом двор.").fontSize()

        assertTrue(
            "the sample sentence must visibly grow between 100% and 130%; 100%=$fontSizeAt100Percent 130%=$fontSizeAt130Percent",
            fontSizeAt130Percent > fontSizeAt100Percent,
        )
    }

    @Test
    fun appearanceContentDoesNotForceItselfToFillTheWholeViewport() {
        val size = DpSize(393.dp, 850.dp)
        val metrics = compose.activity.resources.displayMetrics
        val renderDensity = minOf(
            metrics.widthPixels / size.width.value,
            metrics.heightPixels / size.height.value,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, 1f)) {
                PocketEditorTheme(darkTheme = false, textScale = 1f) {
                    Box(Modifier.requiredSize(size)) {
                        AppearanceScreen(
                            AppearancePreference(dark = false, textScale = 1f),
                            onBack = {},
                            onDarkChanged = {},
                            onDecrease = {},
                            onReset = {},
                            onIncrease = {},
                        )
                    }
                }
            }
        }

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("appearance-content").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the content column must size to its own content, not the full viewport; root=$root content=$content",
            content.height < root.height * 0.9f,
        )
    }

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

    @Test
    fun contentsShowsQuietDiscoveryActionsWithExplicitNonRemoteSemantics() {
        var added = false
        var replaced: Pair<String, String>? = null
        var updated = false
        var located = false
        var removed = false
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = BOOKS,
                    currentBookId = "book-a",
                    currentChapterId = "chapter-a",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    discoveryNotices = listOf(
                        DiscoveryNotice.NewFile("book-a", "bonus.md", "Bonus", 2),
                        DiscoveryNotice.MissingFile("book-a", "chapter-b", "Copper Gate", "old.md", "renamed.md"),
                    ),
                    closeLabel = "Close contents",
                    onClose = {}, onSwitchBook = {}, onChapterSelected = {}, onQueryChanged = {}, onSearchResult = {},
                    onOpenBooks = {}, onAppearance = {}, onAddDiscovered = { _, _ -> added = true },
                    onReplaceDiscovered = { chapterId, path -> replaced = chapterId to path },
                    onIgnoreDiscovered = {}, onUpdateRenamed = { _, _ -> updated = true },
                    onLocateMissing = { _, _ -> located = true }, onRemoveMissing = { removed = true },
                )
            }
        }

        compose.onNodeWithText("Проверить 2 обновления книги").assertIsDisplayed().performClick()
        compose.onNodeWithText("Найдена новая глава").assertIsDisplayed()
        compose.onNodeWithContentDescription("Добавить bonus.md в книгу").performClick()
        compose.onNodeWithText("Bonus").assertIsDisplayed()
        compose.onNodeWithText("Название главы").assertDoesNotExist()
        compose.onNodeWithContentDescription("Подтвердить добавление главы").performClick()
        compose.onNodeWithContentDescription("Заменить главу файлом bonus.md").performClick()
        compose.onNodeWithContentDescription("Выбрана глава «Salt Road»").assertIsDisplayed()
        compose.onNodeWithContentDescription("Подтвердить замену главы").performClick()
        compose.onNodeWithContentDescription("Изменить путь главы «Copper Gate» на renamed.md").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Найти файл главы «Copper Gate»").performScrollTo().performClick()
        compose.onNodeWithText("Использовать найденный файл").performClick()
        compose.onNodeWithContentDescription("Удалить главу «Copper Gate» из книги, не удаляя файл с диска").performScrollTo().performClick()
        compose.onNodeWithText("Удалить главу «Copper Gate» из книги?").assertIsDisplayed()
        compose.onNodeWithText("Удалить из книги").performClick()
        compose.runOnIdle {
            assertTrue(added && updated && located && removed)
            assertEquals("chapter-a" to "bonus.md", replaced)
        }
    }

    private companion object {
        fun readerState() = ReaderState(
            bookId = "book-a",
            chapterId = "chapter-a",
            title = "Salt Road",
            document = ReaderDocument(
                listOf(
                    ReaderBlock(
                        sourceIndex = 0,
                        kind = BlockKind.PARAGRAPH,
                        canonicalText = "The road was quiet.",
                        rawRange = RawRange(0, 19),
                        runs = listOf(ReaderRun("The road was quiet.", ReaderRunKind.CANONICAL)),
                    ),
                ),
            ),
            reviewEnabled = false,
            chapterNote = null,
            reviewItems = null,
            previousChapter = ReaderChapter("previous", "Previous chapter"),
            nextChapter = ReaderChapter("next", "Next chapter"),
            readingPosition = null,
            syncState = ReaderSyncState.SAVED,
        )

        val BOOKS = listOf(
            BookSummary("book-a", "Alchemy of Rain", "disk:/alchemy", listOf(BookChapter("chapter-a", "Salt Road"), BookChapter("chapter-b", "Copper Gate"))),
            BookSummary("book-b", "Other Story", "disk:/other", listOf(BookChapter("chapter-c", "First Light"))),
        )
        val DRAFT = ImportDraft(
            "disk:/alchemy",
            "Alchemy",
            listOf(
                ImportChapterDraft("chapter-01.md", "Salt Road", true),
                ImportChapterDraft("chapter-02.md", "Copper Gate", true),
            ),
        )
    }
}
