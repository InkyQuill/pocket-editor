package net.inkyquill.pocketeditor.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import androidx.compose.ui.test.performScrollToIndex
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
import net.inkyquill.pocketeditor.reader.ReaderLoadState
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.ui.books.AppearancePreference
import net.inkyquill.pocketeditor.ui.books.BookChapter
import net.inkyquill.pocketeditor.ui.books.BookSummary
import net.inkyquill.pocketeditor.ui.books.BooksScreen
import net.inkyquill.pocketeditor.ui.books.FolderBrowserScreen
import net.inkyquill.pocketeditor.ui.books.FolderListing
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.ui.books.RemoteFolder
import net.inkyquill.pocketeditor.ui.books.DiscoveryNotice
import net.inkyquill.pocketeditor.ui.books.ProgressiveLoadHost
import net.inkyquill.pocketeditor.ui.contents.ContentsPanel
import net.inkyquill.pocketeditor.ui.search.SearchNavigation
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.reader.ReaderRoute
import net.inkyquill.pocketeditor.ui.reader.ReaderViewModel
import net.inkyquill.pocketeditor.ui.settings.AppearanceScreen
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.load.ProgressiveLoadSnapshot
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.ui.books.BookDestination
import net.inkyquill.pocketeditor.ui.books.BookLibraryController
import net.inkyquill.pocketeditor.ui.books.BookLibraryData
import net.inkyquill.pocketeditor.ui.books.ResumeLocation
import net.inkyquill.pocketeditor.ui.books.selectVisibleLoad

class BookFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun productionControllerFlowLoadsZeroToThreePrioritizesThenPublishesReader() {
        val data = ProgressiveFlowData()
        val controller = BookLibraryController(data, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        runBlocking { controller.start() }
        var signIns = 0
        compose.setContent {
            PocketEditorTheme {
                val library by controller.state.collectAsState()
                val scope = rememberCoroutineScope()
                val visible = selectVisibleLoad(
                    library.loads,
                    (library.destination as? BookDestination.Reader)?.bookId,
                    library.recentLoadRoots,
                )
                ProgressiveLoadHost(
                    visible, 0L,
                    onPause = { visible?.let { scope.launch { controller.pauseLoad(it.bookId) } } },
                    onContinue = { visible?.let { scope.launch { controller.continueLoad(it.bookId) } } },
                    onCancel = { visible?.let { scope.launch { controller.cancelLoad(it.bookId) } } },
                    onSignIn = { signIns++ },
                ) {
                    when (val destination = library.destination) {
                        BookDestination.Books -> BooksScreen(
                            library.books, true, false, null, {},
                            { scope.launch { controller.openFolderBrowser() } }, {}, {}, {}, {}, {},
                        )
                        is BookDestination.FolderBrowser -> FolderBrowserScreen(
                            destination.listing, destination.loading, library.error,
                            onBack = {}, onOpenFolder = {},
                            onChooseThisFolder = { scope.launch { controller.openFolder("disk:/writing/aria") } },
                            onRetry = {},
                        )
                        is BookDestination.Reader -> ReaderRoute(
                            ReaderViewModel(data.reader, ReaderCallbacks()),
                            contentsContent = { closeLabel, onClose ->
                                ContentsPanel(
                                    library.books, destination.bookId, destination.chapterId, "", emptyList(), false,
                                    closeLabel, onClose,
                                    onChapterSelected = { scope.launch { controller.openChapter(destination.bookId, it.id) } },
                                    onQueryChanged = {}, onSearchResult = {}, onOpenBooks = { scope.launch { controller.openBooks() } },
                                    onAppearance = {},
                                )
                            },
                        )
                        else -> Text("loading")
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Добавить книгу").performClick()
        compose.onNodeWithText("Выбрать эту папку").performClick()
        compose.runOnIdle { data.publish(1) }
        compose.onNodeWithText("Читаем файлы…").assertIsDisplayed()
        compose.runOnIdle { data.publish(2) }
        compose.onNodeWithText("Читаем файлы…").assertIsDisplayed()
        compose.runOnIdle { data.publish(3) }
        compose.onNodeWithContentDescription("Открыть оглавление").assertIsDisplayed()
        compose.onNodeWithTag("progressive-load-card").assertIsDisplayed()

        compose.onNodeWithContentDescription("Открыть оглавление").performClick()
        compose.onNodeWithTag("contents-chapter-list").performScrollToIndex(40)
        compose.onNodeWithText("Chapter 41").performClick()
        compose.runOnIdle { assertEquals("chapter-40.md", data.prioritized.single()) }
        compose.onNodeWithTag("reader-body-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("progressive-load-card").assertIsDisplayed()

        compose.runOnIdle { data.reader.value = ReaderLoadState.Ready(readerState()) }
        compose.onNodeWithText("The road was quiet.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Открыть оглавление").performClick()
        compose.onNodeWithText("Управление книгами").performClick()
        compose.onNodeWithText("Библиотека").assertIsDisplayed()
        compose.onNodeWithTag("progressive-load-card").assertIsDisplayed()
        compose.onNodeWithTag("book-card-flow-book").performClick()
        compose.onNodeWithText("Приостановить").performClick()
        compose.onNodeWithText("Продолжить").performClick()
        compose.onNodeWithText("Отменить").performClick()
        compose.onNodeWithText("Продолжить").performClick()
        compose.runOnIdle { data.publishActionRequired() }
        compose.onNodeWithText("Требуется действие").assertIsDisplayed()
        compose.onNodeWithText("Продолжить").assertHasClickAction()
        compose.runOnIdle { data.publishUnauthorized() }
        compose.onNodeWithText("Войти").performClick()
        compose.runOnIdle { assertEquals(1, signIns) }
    }

    @Test
    fun compactProgressKeepsBooksNavigationAndLoadControlsUsable() {
        var addCount = 0
        var pauseCount = 0
        var cancelCount = 0
        compose.setContent {
            PocketEditorTheme {
                ProgressiveLoadHost(
                    snapshot = progressiveSnapshot(), nowMillis = 0L,
                    onPause = { pauseCount++ }, onContinue = {}, onCancel = { cancelCount++ }, onSignIn = {},
                ) {
                    BooksScreen(
                        books = BOOKS, signedIn = true, signingIn = false, forgetBookId = null,
                        onSignIn = {}, onAddBook = { addCount++ }, onOpenBook = {}, onRequestForget = {},
                        onConfirmForget = {}, onCancelForget = {}, onAppearance = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("progressive-load-card").assertIsDisplayed()
        compose.onNodeWithContentDescription("Добавить книгу").performClick()
        compose.onNodeWithText("Приостановить").performClick()
        compose.onNodeWithText("Отменить").performClick()
        compose.runOnIdle {
            assertEquals(1, addCount)
            assertEquals(1, pauseCount)
            assertEquals(1, cancelCount)
        }
    }

    @Test
    fun completeContentsPrioritizesPendingChapterThenPublishesReaderBody() {
        val chapters = List(52) { index ->
            BookChapter("chapter-$index", "chapter-$index.md", "Chapter ${index + 1}", index < 3)
        }
        val book = BookSummary("book-progress", "Aria", "disk:/writing/aria", chapters)
        val showContents = mutableStateOf(true)
        val reader = MutableStateFlow<ReaderLoadState>(ReaderLoadState.Pending(book.bookId, "chapter-40", "Chapter 41"))
        var prioritizedPath: String? = null
        compose.setContent {
            PocketEditorTheme {
                if (showContents.value) {
                    ContentsPanel(
                        books = listOf(book), currentBookId = book.bookId, currentChapterId = chapters.first().id,
                        query = "", searchResults = emptyList(), searching = false, closeLabel = "Close",
                        onClose = {}, onChapterSelected = {
                            prioritizedPath = it.path
                            showContents.value = false
                        },
                        onQueryChanged = {}, onSearchResult = {}, onOpenBooks = {}, onAppearance = {},
                    )
                } else {
                    ReaderRoute(
                        viewModel = ReaderViewModel(reader, ReaderCallbacks()),
                        contentsContent = { _, _ -> Text("Полное содержание") },
                    )
                }
            }
        }

        compose.onNodeWithTag("contents-chapter-list").performScrollToIndex(40)
        compose.onNodeWithText("Chapter 41").performClick()
        compose.runOnIdle { assertEquals("chapter-40.md", prioritizedPath) }
        compose.onNodeWithTag("reader-body-skeleton").assertIsDisplayed()
        compose.onNodeWithContentDescription("Открыть оглавление").assertIsDisplayed()

        reader.value = ReaderLoadState.Ready(readerState())
        compose.waitForIdle()
        compose.onNodeWithText("The road was quiet.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Открыть оглавление").assertIsDisplayed()
    }

    @Test
    fun libraryUsesCompactHierarchyAndKeepsDestructiveActionsSecondary() {
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
        compose.onNodeWithText("Pocket Editor").assertDoesNotExist()
        compose.onNodeWithTag("book-card-book-a").assertHasClickAction()
        compose.onNodeWithText("Забыть").assertDoesNotExist()
        compose.onNodeWithContentDescription("Действия с книгой Alchemy of Rain").performClick()
        compose.onNodeWithText("Забыть локальную копию").assertIsDisplayed()
        compose.onNodeWithText("Настроить книгу").assertDoesNotExist()
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

        compose.onNodeWithText("Найдены 2 главы Markdown. Порядок можно изменить позже.").assertIsDisplayed()
        compose.onNodeWithText("Выбрать эту папку").assertIsEnabled().performClick()
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
        compose.onNodeWithText("Выбрать эту папку").assertIsNotEnabled()
    }

    @Test
    fun folderBrowserPreviewsFilesAndShowsSelectionProgress() {
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
        compose.onNodeWithText("Выбрать эту папку").performClick()
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

        compose.onNodeWithText("Выбрать эту папку").performClick()
        compose.onNodeWithText("Читаем файлы…").assertIsDisplayed()
        compose.runOnIdle { error.value = "Не удалось выполнить действие. Попробуйте ещё раз." }
        compose.onNodeWithText("Не удалось открыть папку").assertIsDisplayed()
        compose.runOnIdle { error.value = null }
        compose.onNodeWithText("Выбрать эту папку").assertIsEnabled()

        compose.onNodeWithText("Выбрать эту папку").performClick()
        compose.runOnIdle { assertEquals(2, chooseCalls) }
        compose.onNodeWithText("Читаем файлы…").assertIsDisplayed()
        compose.runOnIdle {
            listing.value = FolderListing("disk:/other", emptyList(), listOf("other.md"))
        }
        compose.onNodeWithText("Выбрать эту папку").assertIsEnabled()
    }

    @Test
    fun choosingRawFolderStartsDeterministicLoadWithoutConfirmationEditor() {
        val data = ProgressiveFlowData(listOf("02.md", "01.md"))
        val controller = BookLibraryController(data, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        runBlocking { controller.start(); controller.openFolderBrowser() }
        compose.setContent {
            PocketEditorTheme {
                val library by controller.state.collectAsState()
                val scope = rememberCoroutineScope()
                val visible = selectVisibleLoad(library.loads, null, library.recentLoadRoots)
                ProgressiveLoadHost(visible, 0L, {}, {}, {}, {}) {
                    val destination = library.destination as BookDestination.FolderBrowser
                    FolderBrowserScreen(
                        destination.listing, destination.loading, library.error, {}, {},
                        { scope.launch { controller.openFolder(destination.listing!!.path) } }, {},
                    )
                }
            }
        }

        compose.onNodeWithText("Выбрать эту папку").performClick()
        compose.onNodeWithText("Проверьте книгу").assertDoesNotExist()
        compose.onNodeWithText("Название книги").assertDoesNotExist()
        compose.onNodeWithText("Исключить главу").assertDoesNotExist()
        compose.onNodeWithContentDescription("Загружено 0 из 2").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("01.md", "02.md"), data.installedManifest!!.chapters.map(ChapterEntry::path))
        }
    }

    @Test
    fun contentsStartsAtCurrentChapterDeepInLongSpine() {
        val chapters = List(80) { index ->
            val number = index + 1
            BookChapter("chapter-$number", "chapter-$number.md", "Chapter $number", true)
        }
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = listOf(BookSummary("long-book", "Long Book", "disk:/long", chapters)),
                    currentBookId = "long-book",
                    currentChapterId = "chapter-55",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("Chapter 55").assertIsDisplayed()
        compose.onNodeWithText("Chapter 1").assertDoesNotExist()
    }

    @Test
    fun contentsTracksCurrentChapterChangesWithoutRecreatingPanel() {
        val currentChapterId = mutableStateOf("chapter-1")
        val chapters = List(80) { index ->
            val number = index + 1
            BookChapter("chapter-$number", "chapter-$number.md", "Chapter $number", true)
        }
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = listOf(BookSummary("long-book", "Long Book", "disk:/long", chapters)),
                    currentBookId = "long-book",
                    currentChapterId = currentChapterId.value,
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("Chapter 1").assertIsDisplayed()
        compose.runOnIdle { currentChapterId.value = "chapter-55" }
        compose.onNodeWithText("Chapter 55").assertIsDisplayed()
        compose.onNodeWithText("Chapter 1").assertDoesNotExist()
    }

    @Test
    fun contentsResetsToCurrentChapterWhenBookChangesWithIdenticalSpineIds() {
        val currentBookId = mutableStateOf("book-a")
        val chapters = List(80) { index ->
            val number = index + 1
            BookChapter("chapter-$number", "chapter-$number.md", "Chapter $number", true)
        }
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = listOf(
                        BookSummary("book-a", "Book A", "disk:/a", chapters),
                        BookSummary("book-b", "Book B", "disk:/b", chapters),
                    ),
                    currentBookId = currentBookId.value,
                    currentChapterId = "chapter-55",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithTag("contents-chapter-list").performScrollToIndex(79)
        compose.onNodeWithText("Chapter 80").assertIsDisplayed()
        compose.runOnIdle { currentBookId.value = "book-b" }
        compose.onNodeWithText("Book B").assertIsDisplayed()
        compose.onNodeWithText("Chapter 55").assertIsDisplayed()
        compose.onNodeWithText("Chapter 80").assertDoesNotExist()
    }

    @Test
    fun longContentsReorderKeepsDraftViewportAwayFromCurrentChapter() {
        val chapters = List(80) { index ->
            val number = index + 1
            BookChapter("chapter-$number", "chapter-$number.md", "Chapter $number", true)
        }
        var saved: List<String>? = null
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = listOf(BookSummary("book", "Book", "disk:/book", chapters)),
                    currentBookId = "book",
                    currentChapterId = "chapter-1",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                    onSaveOrder = { _, order -> saved = order },
                )
            }
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        compose.onNodeWithTag("contents-chapter-list").performScrollToIndex(69)
        compose.onNodeWithContentDescription("Переместить Chapter 70 вверх").performClick()
        compose.onNodeWithText("Chapter 70").assertIsDisplayed()
        compose.onNodeWithText("Chapter 1").assertDoesNotExist()
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle {
            assertEquals("chapter-70", saved?.get(68))
            assertEquals("chapter-69", saved?.get(69))
        }
    }

    @Test
    fun contentsTracksCanonicalCurrentChapterChangeWhileEditingOrder() {
        val currentChapterId = mutableStateOf("chapter-1")
        val chapters = List(80) { index ->
            val number = index + 1
            BookChapter("chapter-$number", "chapter-$number.md", "Chapter $number", true)
        }
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = listOf(BookSummary("book", "Book", "disk:/book", chapters)),
                    currentBookId = "book",
                    currentChapterId = currentChapterId.value,
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        compose.onNodeWithTag("contents-chapter-list").performScrollToIndex(49)
        compose.runOnIdle { currentChapterId.value = "chapter-55" }
        compose.onNodeWithText("Chapter 55").assertIsDisplayed()
        compose.onNodeWithText("Chapter 1").assertDoesNotExist()
    }

    @Test
    fun contentsTracksCanonicalSpineChangeWhileEditingOrder() {
        val initialChapters = List(80) { index ->
            val number = index + 1
            BookChapter("chapter-$number", "chapter-$number.md", "Chapter $number", true)
        }
        val chapters = mutableStateOf(initialChapters)
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = listOf(BookSummary("book", "Book", "disk:/book", chapters.value)),
                    currentBookId = "book",
                    currentChapterId = "chapter-55",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        compose.onNodeWithTag("contents-chapter-list").performScrollToIndex(79)
        compose.runOnIdle {
            chapters.value = listOf(BookChapter("preface", "preface.md", "Preface", true)) + initialChapters
        }
        compose.onNodeWithText("Chapter 55").assertIsDisplayed()
        compose.onNodeWithText("Chapter 80").assertDoesNotExist()
    }

    @Test
    fun contentsRecoversWhenEmptySpineAndRemovedCurrentIdBecomeValid() {
        val chapters = mutableStateOf(emptyList<BookChapter>())
        val currentChapterId = mutableStateOf("removed-chapter")
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = listOf(BookSummary("book", "Book", "disk:/book", chapters.value)),
                    currentBookId = "book",
                    currentChapterId = currentChapterId.value,
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithTag("manage-books").assertIsDisplayed()
        compose.runOnIdle {
            chapters.value = List(80) { index ->
                val number = index + 1
                BookChapter("chapter-$number", "chapter-$number.md", "Chapter $number", true)
            }
            currentChapterId.value = "chapter-55"
        }
        compose.onNodeWithText("Chapter 55").assertIsDisplayed()
    }

    @Test
    fun contentsHasNoBookShortcutChipsAndKeepsManageBooks() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = listOf(
                        BookSummary(
                            "first-book",
                            "First Book",
                            "disk:/first",
                            listOf(BookChapter("chapter-1", "chapter-1.md", "Chapter 1", true)),
                        ),
                        BookSummary(
                            "second-book",
                            "Second Book",
                            "disk:/second",
                            listOf(BookChapter("chapter-2", "chapter-2.md", "Chapter 2", true)),
                        ),
                    ),
                    currentBookId = "first-book",
                    currentChapterId = "chapter-1",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("Second Book").assertDoesNotExist()
        compose.onNodeWithTag("manage-books").assertIsDisplayed()
    }

    @Test
    fun contentsOwnsChapterOrderAndExactSourceSearch() {
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
                    onChapterSelected = { selectedChapter = it.id },
                    onQueryChanged = {},
                    onSearchResult = { selectedSearch = it },
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("Salt Road").performClick()
        compose.onNodeWithContentDescription("Совпадение: дождём").assertIsDisplayed()
        compose.onNodeWithText("…пахло дождём…").performClick()
        compose.runOnIdle {
            assertEquals("chapter-a", selectedChapter)
            assertEquals(SearchNavigation("chapter-b", 48, 73), selectedSearch)
        }
    }

    @Test
    fun contentsReorderDraftRestoresAcrossRecreationThenCancelOrSaveIsDurable() {
        val chapters = listOf(
            BookChapter("one", "one.md", "One", true),
            BookChapter("two", "two.md", "Two", false),
            BookChapter("three", "three.md", "Three", false),
        )
        val durableOrder = mutableStateOf(chapters.map(BookChapter::id))
        var saved: List<String>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val byId = chapters.associateBy(BookChapter::id)
            ContentsPanel(
                books = listOf(
                    BookSummary(
                        "reorder-book",
                        "Reorder book",
                        "disk:/Reorder",
                        durableOrder.value.map(byId::getValue),
                    ),
                ),
                currentBookId = "reorder-book",
                currentChapterId = "one",
                query = "",
                searchResults = emptyList(),
                searching = false,
                closeLabel = "Close contents",
                onClose = {}, onChapterSelected = {}, onQueryChanged = {},
                onSearchResult = {}, onOpenBooks = {}, onAppearance = {},
                onSaveOrder = { _, order -> saved = order; durableOrder.value = order },
            )
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        compose.onNodeWithContentDescription("Переместить Three вверх").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Отмена").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(null, saved)
            assertEquals(listOf("one", "two", "three"), durableOrder.value)
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        compose.onNodeWithContentDescription("Переместить Three вверх").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(listOf("one", "three", "two"), durableOrder.value)
            assertEquals("three.md", chapters.single { it.id == "three" }.path)
        }
        val three = compose.onNodeWithText("Three").fetchSemanticsNode().boundsInRoot
        val two = compose.onNodeWithText("Two").fetchSemanticsNode().boundsInRoot
        assertTrue("saved chapter order must survive recreation", three.top < two.top)
    }

    @Test
    fun contentsReorderConflictKeepsDurableOrderAndShowsActionableCard() {
        var recoveries = 0
        val conflictMessage = "Порядок не сохранён: сначала разрешите конфликт книги"
        val chapters = listOf(
            BookChapter("one", "one.md", "One", true),
            BookChapter("two", "two.md", "Two", false),
        )
        val error = mutableStateOf<String?>(null)
        compose.setContent {
            ContentsPanel(
                books = listOf(BookSummary("book", "Book", "disk:/Book", chapters)),
                currentBookId = "book",
                currentChapterId = "one",
                query = "",
                searchResults = emptyList(),
                searching = false,
                closeLabel = "Close contents",
                onClose = {}, onChapterSelected = {}, onQueryChanged = {},
                onSearchResult = {}, onOpenBooks = {}, onAppearance = {},
                onSaveOrder = { _, _ -> error.value = conflictMessage },
                error = error.value,
                onDismissError = { error.value = null },
                onRetryOrder = { recoveries++ },
            )
        }

        compose.onNodeWithText("Изменить порядок").performClick()
        compose.onNodeWithContentDescription("Переместить Two вверх").performClick()
        compose.onNodeWithText("Сохранить").performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(conflictMessage).fetchSemanticsNodes()
                .any { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        }
        compose.onNodeWithText(conflictMessage).assertIsDisplayed()
        compose.onNodeWithText("Обновить основу и повторить").performClick()
        compose.onNodeWithContentDescription("Закрыть сообщение об ошибке").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("one", "two"), chapters.map(BookChapter::id))
            assertEquals(1, recoveries)
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
                    onClose = {}, onChapterSelected = {}, onQueryChanged = {}, onSearchResult = {},
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
        compose.onNodeWithContentDescription("Выбрать главу «Copper Gate»")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithContentDescription("Выбрана глава «Copper Gate»").assertIsDisplayed()
        compose.onNodeWithContentDescription("Подтвердить замену главы").performClick()
        compose.onNodeWithContentDescription("Изменить путь главы «Copper Gate» на renamed.md").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Найти файл главы «Copper Gate»").performScrollTo().performClick()
        compose.onNodeWithText("Использовать найденный файл").performClick()
        compose.onNodeWithContentDescription("Удалить главу «Copper Gate» из книги, не удаляя файл с диска").performScrollTo().performClick()
        compose.onNodeWithText("Удалить главу «Copper Gate» из книги?").assertIsDisplayed()
        compose.onNodeWithText("Удалить из книги").performClick()
        compose.runOnIdle {
            assertTrue(added && updated && located && removed)
            assertEquals("chapter-b" to "bonus.md", replaced)
        }
    }

    private class ProgressiveFlowData(
        chapterPaths: List<String> = List(52) { "chapter-$it.md" },
    ) : BookLibraryData {
        private val chapters = chapterPaths.mapIndexed { index, path ->
            BookChapter("chapter-$index", path, "Chapter ${index + 1}", index < 3)
        }
        private var cached = 0
        private val loadFlow = MutableStateFlow<List<ProgressiveLoadSnapshot>>(emptyList())
        val reader = MutableStateFlow<ReaderLoadState>(ReaderLoadState.Ready(readerState()))
        val prioritized = mutableListOf<String>()
        var installedManifest: BookManifest? = null

        override suspend fun books() = listOf(
            BookSummary(
                "flow-book", "Aria", "disk:/writing/aria",
                chapters.mapIndexed { index, chapter -> chapter.copy(cached = index < cached) },
            ),
        )
        override fun loadChanges(): Flow<List<ProgressiveLoadSnapshot>> = loadFlow
        override suspend fun currentLoads() = loadFlow.value
        override suspend fun startLoad(path: String): ProgressiveLoadSnapshot = snapshot(0).also {
            installedManifest = BookManifest(
                bookId = "flow-book",
                title = "Aria",
                chapters = chapters.sortedBy(BookChapter::path).map { ChapterEntry(it.id, it.path) },
            )
            loadFlow.value = listOf(it)
        }
        override suspend fun prioritizeChapter(bookId: String, path: String) {
            prioritized += path
            val chapter = chapters.single { it.path == path }
            reader.value = ReaderLoadState.Pending(bookId, chapter.id, chapter.title)
        }
        override suspend fun pauseLoad(bookId: String) { loadFlow.value = listOf(snapshot(cached, ProgressiveLoadPhase.PAUSED)) }
        override suspend fun continueLoad(bookId: String) { loadFlow.value = listOf(snapshot(cached, ProgressiveLoadPhase.BACKGROUND)) }
        override suspend fun cancelLoad(bookId: String) { loadFlow.value = listOf(snapshot(cached, ProgressiveLoadPhase.CANCELLED)) }
        override suspend fun resumeLocation(): ResumeLocation? = null
        override suspend fun resumeLocation(bookId: String): ResumeLocation? = null
        override suspend fun appearance() = AppearancePreference()
        override suspend fun browse(path: String) = FolderListing("disk:/writing/aria", emptyList(), listOf("chapter-0.md"))
        override suspend fun repairRegistered(bookId: String): BookSummary = error("unused")
        override suspend fun relinkRegistered(bookId: String, path: String): BookSummary = error("unused")
        override suspend fun persistResume(location: ResumeLocation) = Unit
        override suspend fun opened(bookId: String) = Unit
        override suspend fun discover(bookId: String) = emptyList<DiscoveryNotice>()
        override suspend fun add(bookId: String, path: String, position: Int) = Unit
        override suspend fun replace(bookId: String, chapterId: String, path: String) = Unit
        override suspend fun ignore(bookId: String, path: String) = Unit
        override suspend fun updatePath(bookId: String, chapterId: String, path: String, requireSameHash: Boolean) = Unit
        override suspend fun removeChapter(bookId: String, chapterId: String) = Unit
        override suspend fun forget(bookId: String) = Unit
        override suspend fun saveAppearance(value: AppearancePreference) = Unit

        fun publish(count: Int) {
            cached = count
            loadFlow.value = listOf(snapshot(count))
        }

        fun publishUnauthorized() {
            loadFlow.value = listOf(
                snapshot(cached, ProgressiveLoadPhase.PAUSED).copy(
                    paused = true,
                    lastErrorCategory = ProgressiveLoadErrorCategory.UNAUTHORIZED,
                ),
            )
        }

        fun publishActionRequired() {
            loadFlow.value = listOf(
                snapshot(cached, ProgressiveLoadPhase.ACTION_REQUIRED).copy(
                    lastErrorCategory = ProgressiveLoadErrorCategory.INVALID_REMOTE,
                ),
            )
        }

        private fun snapshot(count: Int, phase: ProgressiveLoadPhase = if (count < 3) ProgressiveLoadPhase.INITIAL else ProgressiveLoadPhase.BACKGROUND) =
            ProgressiveLoadSnapshot(
                "flow-book", "disk:/writing/aria", phase, chapters.size, count, null, 0, null, 1,
                phase == ProgressiveLoadPhase.PAUSED, phase == ProgressiveLoadPhase.CANCELLED, null,
                chapters.mapIndexed { index, chapter ->
                    ProgressiveLoadFileEntity(
                        "flow-book", chapter.path, chapter.id, index, "r$index", null, null,
                        if (index < count) ProgressiveLoadFileState.CACHED else ProgressiveLoadFileState.PENDING,
                        0,
                    )
                },
            )
    }

    private companion object {
        fun progressiveSnapshot() = ProgressiveLoadSnapshot(
            bookId = "book-progress",
            remoteRootPath = "disk:/writing/aria",
            phase = ProgressiveLoadPhase.BACKGROUND,
            totalFiles = 52,
            completedFiles = 3,
            activePath = "chapter-4.md",
            retryAttempt = 0,
            retryAt = null,
            generation = 1,
            paused = false,
            cancelled = false,
            lastErrorCategory = null,
            files = emptyList(),
        )

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
            BookSummary("book-a", "Alchemy of Rain", "disk:/alchemy", listOf(BookChapter("chapter-a", "chapter-a.md", "Salt Road", true), BookChapter("chapter-b", "chapter-b.md", "Copper Gate", true))),
            BookSummary("book-b", "Other Story", "disk:/other", listOf(BookChapter("chapter-c", "chapter-c.md", "First Light", true))),
        )
    }
}
