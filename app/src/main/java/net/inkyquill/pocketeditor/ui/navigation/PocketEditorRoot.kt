package net.inkyquill.pocketeditor.ui.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.inkyquill.pocketeditor.PocketEditorApp
import net.inkyquill.pocketeditor.reader.ReviewRecordKind
import net.inkyquill.pocketeditor.ui.books.BookDestination
import net.inkyquill.pocketeditor.ui.books.BookLibraryController
import net.inkyquill.pocketeditor.ui.books.BooksScreen
import net.inkyquill.pocketeditor.ui.books.FolderBrowserScreen
import net.inkyquill.pocketeditor.ui.books.ImportConfirmationScreen
import net.inkyquill.pocketeditor.ui.contents.ContentsPanel
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderRoute
import net.inkyquill.pocketeditor.ui.reader.ReaderViewModel
import net.inkyquill.pocketeditor.ui.reader.ReaderSearchTarget
import net.inkyquill.pocketeditor.ui.review.EditorialReviewController
import net.inkyquill.pocketeditor.ui.review.ReaderRepositoryEditorialActions
import net.inkyquill.pocketeditor.ui.review.readerCallbacks
import net.inkyquill.pocketeditor.ui.search.SearchNavigation
import net.inkyquill.pocketeditor.ui.settings.AppearanceScreen
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import net.inkyquill.pocketeditor.yandex.AuthSession

@Composable
fun PocketEditorRoot() {
    val context = LocalContext.current
    val activity = context.findActivity()
    val container = (context.applicationContext as PocketEditorApp).container
    val scope = rememberCoroutineScope()
    val controller = remember(container) { BookLibraryController(container.libraryData, scope) }
    val library by controller.state.collectAsStateWithLifecycle()
    val authSession by container.auth.session.collectAsStateWithLifecycle()
    var signInState by remember { mutableStateOf(SignInUiState()) }
    var appearanceReturn by remember { mutableStateOf<BookDestination>(BookDestination.Books) }

    LaunchedEffect(controller) { controller.start() }

    PocketEditorTheme(darkTheme = library.appearance.dark, textScale = library.appearance.textScale) {
        when (val destination = library.destination) {
            BookDestination.Loading -> LoadingLibrary()
            BookDestination.Books -> BooksScreen(
                books = library.books,
                signedIn = authSession is AuthSession.SignedIn,
                signingIn = signInState.loading,
                signInError = signInState.error,
                forgetBookId = library.forgetBookId,
                onSignIn = {
                    if (activity != null) scope.launch {
                        performSignIn(onState = { signInState = it }) { container.auth.signIn(activity) }
                    }
                },
                onAddBook = { scope.launch { controller.openFolderBrowser() } },
                onOpenBook = { scope.launch { controller.switchBook(it) } },
                onRequestForget = controller::requestForget,
                onConfirmForget = { scope.launch { controller.confirmForget() } },
                onCancelForget = controller::cancelForget,
                onAppearance = {
                    appearanceReturn = BookDestination.Books
                    controller.openAppearance()
                },
            )
            is BookDestination.FolderBrowser -> FolderBrowserScreen(
                listing = destination.listing,
                loading = destination.loading,
                error = library.error,
                onBack = { scope.launch { controller.openBooks() } },
                onOpenFolder = { scope.launch { controller.openFolderBrowser(it) } },
                onChooseThisFolder = { scope.launch { controller.openFolder(it) } },
                onRetry = { scope.launch { controller.openFolderBrowser(destination.listing?.path ?: "disk:/") } },
            )
            is BookDestination.ImportConfirmation -> ImportConfirmationScreen(
                draft = destination.draft,
                importing = false,
                onDraftChanged = controller::updateImport,
                onBack = { scope.launch { controller.openFolderBrowser(destination.draft.remoteRootPath) } },
                onConfirm = { scope.launch { controller.confirmImport() } },
                error = library.error,
            )
            is BookDestination.Importing -> ImportConfirmationScreen(
                draft = destination.draft,
                importing = true,
                onDraftChanged = {},
                onBack = {},
                onConfirm = {},
            )
            is BookDestination.InstallingExisting -> LoadingLibrary("Caching ${destination.title}")
            is BookDestination.Reader -> ReaderDestination(
                destination = destination,
                controller = controller,
                onAppearance = {
                    appearanceReturn = destination
                    controller.openAppearance()
                },
                container = container,
            )
            BookDestination.Appearance -> AppearanceScreen(
                appearance = library.appearance,
                onBack = {
                    scope.launch {
                        when (val back = appearanceReturn) {
                            is BookDestination.Reader -> controller.openChapter(
                                back.bookId,
                                back.chapterId,
                                back.blockIndex,
                                back.byteOffset,
                                back.rawEndByte,
                            )
                            else -> controller.openBooks()
                        }
                    }
                },
                onDarkChanged = { scope.launch { controller.setDark(it) } },
                onDecrease = { scope.launch { controller.decreaseTextSize() } },
                onReset = { scope.launch { controller.resetTextSize() } },
                onIncrease = { scope.launch { controller.increaseTextSize() } },
            )
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
private fun ReaderDestination(
    destination: BookDestination.Reader,
    controller: BookLibraryController,
    onAppearance: () -> Unit,
    container: net.inkyquill.pocketeditor.AppContainer,
) {
    val scope = rememberCoroutineScope()
    val books by controller.state.collectAsStateWithLifecycle()
    var latestReadingPosition by remember(destination.bookId, destination.chapterId) {
        mutableStateOf<net.inkyquill.pocketeditor.reader.ReaderPosition?>(null)
    }
    suspend fun flushLatestReadingPosition() {
        latestReadingPosition?.let { position ->
            container.readerRepository.saveReadingPosition(
                destination.bookId, destination.chapterId, position.blockIndex, position.byteOffset,
            )
        }
    }
    val reviewEnabled = remember(destination.bookId, destination.chapterId) { MutableStateFlow(false) }
    val readerState = rememberChapterState(
        destination.bookId,
        destination.chapterId,
        scope,
    ) {
        reviewEnabled.flatMapLatest { enabled ->
            container.readerRepository.observeChapter(destination.bookId, destination.chapterId, enabled)
        }
    }
    val actions = remember(destination.bookId, destination.chapterId) {
        ReaderRepositoryEditorialActions(
            repository = container.readerRepository,
            syncEngine = container.syncEngine,
            conflicts = container.conflicts,
            bookId = destination.bookId,
            chapterId = destination.chapterId,
            recordKind = { id ->
                if (readerState.value?.reviewItems?.signals?.any { it.id == id } == true) ReviewRecordKind.SIGNAL
                else ReviewRecordKind.EDIT
            },
        )
    }
    val reviewController = remember(destination.bookId, destination.chapterId) {
        EditorialReviewController(
            bookId = destination.bookId,
            chapterId = destination.chapterId,
            renderedDocument = { requireNotNull(readerState.value?.selectionDocument) },
            occupiedEditRanges = {
                readerState.value?.reviewItems?.edits.orEmpty().mapNotNull { edit ->
                    edit.anchor?.let { net.inkyquill.pocketeditor.markdown.RawRange(it.startByte.toInt(), it.endByte.toInt()) }
                }
            },
            actions = actions,
            drafts = container.reviewDraftStore,
            scope = scope,
        )
    }
    val callbacks = remember(destination.bookId, destination.chapterId) {
        reviewController.readerCallbacks(
            scope,
            ReaderCallbacks(
                onReviewModeChanged = { reviewEnabled.value = it },
                onPreviousChapter = { scope.launch { flushLatestReadingPosition(); controller.openChapter(destination.bookId, it.id) } },
                onNextChapter = { scope.launch { flushLatestReadingPosition(); controller.openChapter(destination.bookId, it.id) } },
                onChapterSelected = { scope.launch { flushLatestReadingPosition(); controller.openChapter(destination.bookId, it.id) } },
                onReadingPositionObserved = { latestReadingPosition = it },
                onReadingPositionChanged = { position ->
                    val stillCurrent = controller.state.value.destination.let { current ->
                        current is BookDestination.Reader && current.bookId == destination.bookId && current.chapterId == destination.chapterId
                    }
                    if (stillCurrent) scope.launch {
                        container.readerRepository.saveReadingPosition(
                            destination.bookId,
                            destination.chapterId,
                            position.blockIndex,
                            position.byteOffset,
                        )
                    }
                },
            ),
        )
    }
    val viewModel = remember(destination.bookId, destination.chapterId) {
        ReaderViewModel(readerState, callbacks, reviewController.state)
    }
    LaunchedEffect(reviewController) {
        val state = readerState.filterNotNull().first()
        reviewController.restore(state.chapterNote, state.syncState)
        readerState.filterNotNull().collect { current ->
            reviewController.updateChapterContext(current.chapterNote.orEmpty(), current.syncState)
        }
    }
    LaunchedEffect(destination.bookId, destination.chapterId, destination.byteOffset) {
        if (destination.byteOffset > 0) {
            val state = readerState.filterNotNull().first()
            val exactBlock = state.selectionDocument?.blocks?.firstOrNull {
                it.rawRange.startByte <= destination.byteOffset && destination.byteOffset < it.rawRange.endByte
            }?.index
            if (exactBlock != null && exactBlock != destination.blockIndex) {
                controller.openChapter(
                    destination.bookId,
                    destination.chapterId,
                    exactBlock,
                    destination.byteOffset,
                    destination.rawEndByte,
                )
            }
        }
    }
    var query by rememberSaveable(destination.bookId) { mutableStateOf("") }
    val hits by remember(destination.bookId, query) {
        container.sourceSearch.query(destination.bookId, query)
    }.collectAsState(initial = emptyList())

    ReaderRoute(
        viewModel = viewModel,
        contentsContent = { closeLabel, onClose ->
            ContentsPanel(
                books = books.books,
                currentBookId = destination.bookId,
                currentChapterId = destination.chapterId,
                query = query,
                searchResults = hits,
                searching = false,
                closeLabel = closeLabel,
                onClose = onClose,
                onSwitchBook = { scope.launch { controller.switchBook(it) } },
                onChapterSelected = { scope.launch { flushLatestReadingPosition(); controller.openChapter(destination.bookId, it.id) } },
                onQueryChanged = { query = it },
                onSearchResult = { navigation ->
                    val block = if (navigation.chapterId == destination.chapterId) {
                        readerState.value?.selectionDocument?.blocks?.firstOrNull {
                            it.rawRange.startByte <= navigation.rawStartByte && navigation.rawStartByte < it.rawRange.endByte
                        }?.index ?: 0
                    } else 0
                    scope.launch {
                        flushLatestReadingPosition()
                        controller.openChapter(
                            destination.bookId,
                            navigation.chapterId,
                            block,
                            navigation.rawStartByte,
                            navigation.rawEndByte,
                        )
                    }
                },
                onOpenBooks = { scope.launch { controller.openBooks() } },
                onAppearance = onAppearance,
                discoveryNotices = books.discoveryNotices,
                onAddDiscovered = { path, title, position ->
                    scope.launch { controller.addDiscovered(destination.bookId, path, title, position) }
                },
                onIgnoreDiscovered = { path -> scope.launch { controller.ignoreDiscovered(destination.bookId, path) } },
                onUpdateRenamed = { chapterId, path ->
                    scope.launch { controller.updateRenamed(destination.bookId, chapterId, path) }
                },
                onLocateMissing = { chapterId, path ->
                    scope.launch { controller.locateMissing(destination.bookId, chapterId, path) }
                },
                onRemoveMissing = { chapterId -> scope.launch { controller.removeMissing(destination.bookId, chapterId) } },
            )
        },
        searchTarget = destination.rawEndByte?.let { ReaderSearchTarget(destination.byteOffset, it) },
    )
}

@Composable
internal fun <T> rememberChapterState(
    bookId: String,
    chapterId: String,
    parentScope: CoroutineScope,
    source: () -> kotlinx.coroutines.flow.Flow<T>,
): kotlinx.coroutines.flow.StateFlow<T?> {
    val childScope = remember(bookId, chapterId, parentScope) {
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    }
    DisposableEffect(childScope) {
        onDispose { childScope.cancel() }
    }
    return remember(bookId, chapterId, childScope) {
        source().stateIn(childScope, SharingStarted.Eagerly, null)
    }
}

@Composable
private fun LoadingLibrary(message: String = "Opening your library") {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.titleLarge)
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
