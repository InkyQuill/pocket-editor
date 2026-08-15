package net.inkyquill.pocketeditor.ui.books

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import net.inkyquill.pocketeditor.load.ProgressiveLoadSnapshot
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import net.inkyquill.pocketeditor.book.ImportDraftPhase
import net.inkyquill.pocketeditor.yandex.YandexDiskError

data class BookChapter(
    val id: String,
    val path: String,
    val title: String,
    val cached: Boolean,
)

data class BookSummary(
    val bookId: String,
    val title: String,
    val remoteRootPath: String,
    val chapters: List<BookChapter>,
    val availableOffline: Boolean = chapters.any(BookChapter::cached),
    val fullyCached: Boolean = chapters.isNotEmpty() && chapters.all(BookChapter::cached),
    val recoveryError: String? = null,
    val needsRelink: Boolean = false,
)

data class ResumeLocation(
    val bookId: String,
    val chapterId: String,
    val blockIndex: Int = 0,
    val byteOffset: Int = 0,
)

data class AppearancePreference(val dark: Boolean = true, val textScale: Float = 1f)

data class RemoteFolder(val path: String, val name: String)

data class FolderListing(
    val path: String,
    val folders: List<RemoteFolder>,
    val markdown: List<String> = emptyList(),
    val otherFiles: Int = 0,
    val fromCache: Boolean = false,
)

data class ImportChapterDraft(
    val path: String,
    val title: String,
    val included: Boolean,
)

data class ImportDraft(
    val remoteRootPath: String,
    val title: String,
    val chapters: List<ImportChapterDraft>,
    val bookId: String = UUID.randomUUID().toString(),
    val phase: ImportDraftPhase = ImportDraftPhase.READY,
)

data class ImportDraftSummary(
    val bookId: String,
    val remoteRootPath: String,
    val title: String,
    val downloadedChapters: Int,
    val phase: ImportDraftPhase,
)

data class ImportProgress(
    val completed: Int,
    val total: Int,
    val phase: ImportDraftPhase,
)

sealed interface DiscoveryNotice {
    val bookId: String

    data class NewFile(
        override val bookId: String,
        val path: String,
        val suggestedTitle: String,
        val suggestedPosition: Int,
        val maxPosition: Int = suggestedPosition,
    ) : DiscoveryNotice

    data class MissingFile(
        override val bookId: String,
        val chapterId: String,
        val chapterTitle: String?,
        val previousPath: String,
        val sameHashRenamePath: String?,
    ) : DiscoveryNotice
}

interface BookLibraryData {
    suspend fun books(): List<BookSummary>
    fun bookChanges(): Flow<String> = emptyFlow()
    fun loadChanges(): Flow<List<ProgressiveLoadSnapshot>> = emptyFlow()
    suspend fun currentLoads(): List<ProgressiveLoadSnapshot> = emptyList()
    suspend fun startLoad(path: String): ProgressiveLoadSnapshot = error("Progressive loading is not supported")
    suspend fun prioritizeChapter(bookId: String, path: String) = Unit
    suspend fun pauseLoad(bookId: String) = Unit
    suspend fun continueLoad(bookId: String) = Unit
    suspend fun cancelLoad(bookId: String) = Unit
    suspend fun reorder(bookId: String, orderedChapterIds: List<String>) = Unit
    suspend fun refreshReorderBase(bookId: String, isCurrent: () -> Boolean = { true }) = Unit
    suspend fun importDrafts(): List<ImportDraftSummary> = emptyList()
    suspend fun resumeImport(bookId: String): ImportDraft = error("Import drafts are not supported")
    suspend fun updateImport(draft: ImportDraft) = Unit
    suspend fun discardImport(bookId: String): Unit = error("Import drafts are not supported")
    suspend fun resumeLocation(): ResumeLocation?
    suspend fun resumeLocation(bookId: String): ResumeLocation?
    suspend fun appearance(): AppearancePreference
    suspend fun browse(path: String): FolderListing
    suspend fun propose(path: String): ImportDraft
    suspend fun existingRoot(path: String): BookSummary?
    suspend fun installExisting(path: String): BookSummary
    suspend fun repairRegistered(bookId: String): BookSummary
    suspend fun relinkRegistered(bookId: String, path: String): BookSummary
    suspend fun import(draft: ImportDraft): BookSummary
    suspend fun persistResume(location: ResumeLocation)
    suspend fun opened(bookId: String)
    suspend fun discover(bookId: String): List<DiscoveryNotice>
    suspend fun add(bookId: String, path: String, position: Int)
    suspend fun replace(bookId: String, chapterId: String, path: String)
    suspend fun ignore(bookId: String, path: String)
    suspend fun updatePath(bookId: String, chapterId: String, path: String, requireSameHash: Boolean)
    suspend fun removeChapter(bookId: String, chapterId: String)
    suspend fun forget(bookId: String)
    suspend fun saveAppearance(value: AppearancePreference)
}

sealed interface BookDestination {
    data object Loading : BookDestination
    data object Books : BookDestination
    data class FolderBrowser(val listing: FolderListing? = null, val loading: Boolean = false) : BookDestination
    data class ImportConfirmation(val draft: ImportDraft) : BookDestination
    data class Importing(val draft: ImportDraft) : BookDestination
    data class InstallingExisting(val path: String, val title: String) : BookDestination
    data class Reader(
        val bookId: String,
        val chapterId: String,
        val blockIndex: Int = 0,
        val byteOffset: Int = 0,
        val rawEndByte: Int? = null,
    ) : BookDestination
    data object Appearance : BookDestination
}

data class BookLibraryState(
    val destination: BookDestination = BookDestination.Loading,
    val books: List<BookSummary> = emptyList(),
    val importDrafts: List<ImportDraftSummary> = emptyList(),
    val appearance: AppearancePreference = AppearancePreference(),
    val loads: List<ProgressiveLoadSnapshot> = emptyList(),
    val pendingLoadRoot: String? = null,
    val recentLoadRoots: List<String> = emptyList(),
    val discoveryNotices: List<DiscoveryNotice> = emptyList(),
    val forgetBookId: String? = null,
    val discardDraftBookId: String? = null,
    val error: String? = null,
    val reorderRecoveryAvailable: Boolean = false,
    val reorderRecoveryLoading: Boolean = false,
)

internal class BookLibraryUserError(message: String) : IllegalArgumentException(message)

class BookLibraryController(
    private val data: BookLibraryData,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(BookLibraryState())
    val state: StateFlow<BookLibraryState> = mutableState.asStateFlow()
    private val chapterNavigationGeneration = AtomicLong()
    private val navigationIntentGeneration = AtomicLong()
    private val chapterNavigationMutex = Mutex()
    private val priorityMutex = Mutex()
    private val prioritizedChapters = mutableSetOf<Pair<String, String>>()
    private val readinessByRoot = mutableMapOf<String, Boolean>()
    private val reorderOperationMutex = Mutex()
    private val reorderRecoveryMutex = Mutex()
    private val reorderGeneration = AtomicLong()
    @Volatile private var pendingReorder: PendingReorder? = null
    private var activeReorderRecovery: Pair<Long, CompletableDeferred<Unit>>? = null

    init {
        scope.launch {
            data.bookChanges().collect { changedBookId ->
                val expected = state.value.destination as? BookDestination.Reader ?: return@collect
                if (expected.bookId != changedBookId) return@collect
                val generation = chapterNavigationGeneration.get()
                try {
                    withContext(dispatcher) { refreshPublishedBook(changedBookId, expected, generation) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    mutableState.update { current ->
                        if (generation == chapterNavigationGeneration.get() && current.destination == expected) {
                            current.copy(
                                error = (failure as? BookLibraryUserError)?.message
                                    ?: "Не удалось выполнить действие. Попробуйте ещё раз.",
                            )
                        } else {
                            current
                        }
                    }
                }
            }
        }
        scope.launch {
            data.loadChanges().collect { loads ->
                val refreshed = try {
                    withContext(dispatcher) { data.books() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    mutableState.update { it.copy(loads = loads) }
                    return@collect
                }
                prioritizedChapters.removeAll { (bookId, path) ->
                    refreshed.singleOrNull { it.bookId == bookId }
                        ?.chapters?.singleOrNull { it.path == path }?.cached == true
                }
                val previousReadiness = readinessByRoot.toMap()
                loads.groupBy { it.remoteRootPath.normalizedRemotePath() }.forEach { (root, snapshots) ->
                    readinessByRoot[root] = snapshots.any(ProgressiveLoadSnapshot::initialReady)
                }
                mutableState.update { it.copy(books = refreshed, loads = loads) }
                val pendingRoot = mutableState.value.pendingLoadRoot ?: return@collect
                val ready = loads.firstOrNull { snapshot ->
                    snapshot.remoteRootPath.normalizedRemotePath() == pendingRoot && snapshot.initialReady
                } ?: return@collect
                if (previousReadiness[pendingRoot] == true) return@collect
                val current = mutableState.value
                if (current.destination !is BookDestination.FolderBrowser && current.destination != BookDestination.Books) {
                    return@collect
                }
                val book = refreshed.singleOrNull { it.bookId == ready.bookId } ?: return@collect
                val first = book.chapters.firstOrNull()?.takeIf(BookChapter::cached) ?: return@collect
                publishAutoOpen(book, first, pendingRoot, navigationIntentGeneration.get())
            }
        }
    }

    suspend fun start() = runCatchingIo {
        val books = data.books()
        val importDrafts = data.importDrafts()
        val loads = data.currentLoads()
        loads.groupBy { it.remoteRootPath.normalizedRemotePath() }.forEach { (root, snapshots) ->
            readinessByRoot[root] = snapshots.any(ProgressiveLoadSnapshot::initialReady)
        }
        val readableBooks = books.filter { book ->
            book.chapters.isNotEmpty() && book.recoveryError == null && (
                book.fullyCached || loads.singleOrNull { it.bookId == book.bookId }?.initialReady == true
            )
        }
        val appearance = data.appearance().normalized()
        val resume = data.resumeLocation()?.takeIf { location ->
            readableBooks.any { book -> book.bookId == location.bookId && book.chapters.any { it.id == location.chapterId } }
        }
        val destination = resume?.toDestination()
            ?: readableBooks.firstOrNull()?.let { BookDestination.Reader(it.bookId, it.chapters.first().id) }
            ?: BookDestination.Books
        mutableState.value = BookLibraryState(
            books = books,
            importDrafts = importDrafts,
            appearance = appearance,
            loads = loads,
            pendingLoadRoot = loads
                .filter { !it.initialReady && !it.cancelled && it.phase != net.inkyquill.pocketeditor.load.ProgressiveLoadPhase.COMPLETE }
                .maxWithOrNull(compareBy<ProgressiveLoadSnapshot>({ it.generation }, { it.remoteRootPath }))
                ?.remoteRootPath?.normalizedRemotePath(),
            recentLoadRoots = loads.map { it.remoteRootPath.normalizedRemotePath() }.distinct(),
            destination = destination,
        )
        (destination as? BookDestination.Reader)?.let {
            data.opened(it.bookId)
        }
    }

    suspend fun openBooks() {
        invalidateAutoOpen()
        mutableState.update { it.copy(pendingLoadRoot = null) }
        chapterNavigationGeneration.incrementAndGet()
        refreshBooks(BookDestination.Books)
    }

    suspend fun openFolderBrowser(path: String = "disk:/") = runCatchingIo(
        failureDestination = BookDestination.FolderBrowser(
            listing = (mutableState.value.destination as? BookDestination.FolderBrowser)?.listing,
            loading = false,
        ),
    ) {
        invalidateAutoOpen()
        mutableState.update { it.copy(pendingLoadRoot = null) }
        mutableState.value = mutableState.value.copy(
            destination = BookDestination.FolderBrowser(loading = true),
            error = null,
        )
        val listing = data.browse(path)
        mutableState.value = mutableState.value.copy(destination = BookDestination.FolderBrowser(listing))
    }

    suspend fun openFolder(path: String) {
        val fallback = mutableState.value.destination
        val root = path.normalizedRemotePath()
        val intent = navigationIntentGeneration.incrementAndGet()
        readinessByRoot[root] = false
        mutableState.update { it.copy(pendingLoadRoot = root, error = null) }
        runCatchingIo(
            failureDestination = fallback,
            failureMessage = Throwable::toImportUserMessage,
        ) {
        val load = data.startLoad(path)
        mutableState.update { current ->
            current.copy(
                loads = current.loads.filterNot { it.bookId == load.bookId } + load,
                destination = if (navigationIntentGeneration.get() == intent && current.destination == fallback) {
                    current.destination as? BookDestination.FolderBrowser ?: BookDestination.FolderBrowser()
                } else {
                    current.destination
                },
                pendingLoadRoot = if (navigationIntentGeneration.get() == intent) root else current.pendingLoadRoot,
                recentLoadRoots = current.recentLoadRoots.filterNot { it == root } + root,
                error = null,
            )
        }
        if (load.initialReady && navigationIntentGeneration.get() == intent) {
            val book = data.books().singleOrNull { it.bookId == load.bookId }
            val first = book?.chapters?.firstOrNull()?.takeIf(BookChapter::cached)
            if (book != null && first != null && mutableState.value.pendingLoadRoot == root) {
                publishAutoOpen(book, first, root, intent)
            }
        }
        }
    }

    suspend fun updateImport(draft: ImportDraft) = runCatchingIo(
        failureDestination = mutableState.value.destination,
    ) {
        data.updateImport(draft)
        mutableState.value = mutableState.value.copy(
            importDrafts = data.importDrafts(),
            destination = BookDestination.ImportConfirmation(draft),
            error = null,
        )
    }

    suspend fun resumeImport(bookId: String) = runCatchingIo(failureDestination = BookDestination.Books) {
        mutableState.value = mutableState.value.copy(
            destination = BookDestination.ImportConfirmation(data.resumeImport(bookId)),
            error = null,
        )
    }

    fun requestDiscardDraft(bookId: String) {
        mutableState.value = mutableState.value.copy(discardDraftBookId = bookId)
    }

    fun cancelDiscardDraft() {
        mutableState.value = mutableState.value.copy(discardDraftBookId = null)
    }

    suspend fun confirmDiscardDraft() = runCatchingIo(failureDestination = BookDestination.Books) {
        val bookId = requireNotNull(mutableState.value.discardDraftBookId)
        data.discardImport(bookId)
        mutableState.value = mutableState.value.copy(
            importDrafts = data.importDrafts(),
            discardDraftBookId = null,
            destination = BookDestination.Books,
            error = null,
        )
    }

    suspend fun confirmImport() {
        val draft = (mutableState.value.destination as? BookDestination.ImportConfirmation)?.draft
            ?: error("Нет импорта, ожидающего подтверждения")
        runCatchingIo(
            failureDestination = BookDestination.ImportConfirmation(draft),
            failureMessage = Throwable::toImportUserMessage,
        ) {
            if (draft.title.isBlank()) {
                throw BookLibraryUserError("Название книги не может быть пустым")
            }
            if (draft.chapters.none { it.included }) {
                throw BookLibraryUserError("Добавьте хотя бы одну главу")
            }
            mutableState.value = mutableState.value.copy(destination = BookDestination.Importing(draft), error = null)
            val imported = data.import(draft)
            val books = data.books()
            val chapter = imported.chapters.first()
            val location = ResumeLocation(imported.bookId, chapter.id)
            data.persistResume(location)
            mutableState.value = mutableState.value.copy(
                books = books,
                importDrafts = data.importDrafts(),
                destination = location.toDestination(),
            )
        }
    }

    suspend fun switchBook(bookId: String) {
        invalidateAutoOpen()
        mutableState.update { it.copy(pendingLoadRoot = null) }
        val generation = chapterNavigationGeneration.incrementAndGet()
        runCatchingIo {
            chapterNavigationMutex.withLock {
                if (generation != chapterNavigationGeneration.get()) return@withLock
                val book = data.books().single { it.bookId == bookId && it.availableOffline && it.recoveryError == null }
                val location = data.resumeLocation(bookId)?.takeIf { saved ->
                    book.chapters.any { it.id == saved.chapterId }
                } ?: ResumeLocation(book.bookId, book.chapters.first().id)
                data.persistResume(location)
                data.opened(book.bookId)
                if (generation != chapterNavigationGeneration.get()) return@withLock
                mutableState.value = mutableState.value.copy(destination = location.toDestination(), error = null)
            }
        }
    }

    suspend fun retryBook(bookId: String) = runCatchingIo(failureDestination = BookDestination.Books) {
        val repaired = data.repairRegistered(bookId)
        val location = ResumeLocation(repaired.bookId, repaired.chapters.first().id)
        data.persistResume(location)
        mutableState.value = mutableState.value.copy(books = data.books(), destination = location.toDestination(), error = null)
    }

    suspend fun openChapter(
        bookId: String,
        chapterId: String,
        blockIndex: Int = 0,
        byteOffset: Int = 0,
        rawEndByte: Int? = null,
    ) {
        invalidateAutoOpen()
        mutableState.update { it.copy(pendingLoadRoot = null) }
        val generation = chapterNavigationGeneration.incrementAndGet()
        runCatchingIo {
            val location = ResumeLocation(bookId, chapterId, blockIndex, byteOffset)
            chapterNavigationMutex.withLock {
                if (generation != chapterNavigationGeneration.get()) return@withLock
                val book = mutableState.value.books.singleOrNull { it.bookId == bookId }
                    ?: data.books().singleOrNull { it.bookId == bookId }
                val chapter = book?.chapters?.singleOrNull { it.id == chapterId }
                    ?: throw BookLibraryUserError("Глава не найдена")
                if (!chapter.cached) {
                    priorityMutex.withLock {
                        val key = bookId to chapter.path
                        if (key !in prioritizedChapters) {
                            data.prioritizeChapter(bookId, chapter.path)
                            prioritizedChapters += key
                        }
                    }
                }
                data.persistResume(location)
                data.opened(bookId)
                if (generation != chapterNavigationGeneration.get()) return@withLock
                mutableState.value = mutableState.value.copy(
                    destination = BookDestination.Reader(bookId, chapterId, blockIndex, byteOffset, rawEndByte),
                    error = null,
                )
            }
        }
    }

    suspend fun pauseLoad(bookId: String) = controlLoad(bookId) { data.pauseLoad(bookId) }

    suspend fun continueLoad(bookId: String) = controlLoad(bookId) { data.continueLoad(bookId) }

    suspend fun cancelLoad(bookId: String) = controlLoad(bookId) { data.cancelLoad(bookId) }

    suspend fun reorder(bookId: String, orderedChapterIds: List<String>) {
        reorderOperationMutex.withLock {
            val pending = PendingReorder(reorderGeneration.incrementAndGet(), bookId, orderedChapterIds.toList())
            pendingReorder = pending
            runReorderRecovery(pending, rebuildBase = false)
        }
    }

    suspend fun retryReorder() {
        val pending = pendingReorder ?: return
        val (completion, leader) = reorderRecoveryMutex.withLock {
            activeReorderRecovery?.takeIf { it.first == pending.generation }
                ?.let { it.second to false }
                ?: CompletableDeferred<Unit>().also { created ->
                    activeReorderRecovery = pending.generation to created
                }.let { it to true }
        }
        if (leader) {
            try {
                reorderOperationMutex.withLock {
                    if (pendingReorder?.generation == pending.generation) {
                        runReorderRecovery(pending, rebuildBase = true)
                    }
                }
                completion.complete(Unit)
            } catch (failure: Throwable) {
                completion.completeExceptionally(failure)
                throw failure
            } finally {
                reorderRecoveryMutex.withLock {
                    if (activeReorderRecovery?.second === completion) activeReorderRecovery = null
                }
            }
        } else {
            completion.await()
        }
    }

    private suspend fun runReorderRecovery(pending: PendingReorder, rebuildBase: Boolean) {
        val isCurrent = { pendingReorder?.generation == pending.generation }
        if (rebuildBase && isCurrent()) {
            mutableState.update { it.copy(reorderRecoveryLoading = true) }
        }
        try {
            withContext(dispatcher) {
                if (rebuildBase) data.refreshReorderBase(pending.bookId, isCurrent)
                if (!isCurrent()) return@withContext
                data.reorder(pending.bookId, pending.orderedChapterIds)
                if (!isCurrent()) return@withContext
                val refreshed = data.books()
                mutableState.update { current ->
                    if (isCurrent()) {
                        current.copy(
                            books = refreshed,
                            error = null,
                            reorderRecoveryAvailable = false,
                            reorderRecoveryLoading = false,
                        )
                    } else current
                }
            }
            if (isCurrent()) pendingReorder = null
        } catch (cancelled: CancellationException) {
            if (isCurrent()) mutableState.update { it.copy(reorderRecoveryLoading = false) }
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.update { current ->
                if (isCurrent()) {
                    current.copy(
                        error = (failure as? BookLibraryUserError)?.message ?: failure.toImportUserMessage(),
                        reorderRecoveryAvailable = true,
                        reorderRecoveryLoading = false,
                    )
                } else current
            }
        }
    }

    private suspend fun controlLoad(bookId: String, action: suspend () -> Unit) {
        val destination = mutableState.value.destination
        runCatchingIo(failureDestination = destination) {
            action()
            mutableState.update { current ->
                val root = current.loads.singleOrNull { it.bookId == bookId }
                    ?.remoteRootPath?.normalizedRemotePath() ?: return@update current
                current.copy(recentLoadRoots = current.recentLoadRoots.filterNot { it == root } + root)
            }
        }
    }

    suspend fun addDiscovered(bookId: String, path: String, position: Int) = runCatchingIo {
        data.add(bookId, path, position)
        refreshBooksAndDiscovery(bookId)
    }

    suspend fun replaceDiscovered(bookId: String, chapterId: String, path: String) = runCatchingIo {
        data.replace(bookId, chapterId, path)
        refreshBooksAndDiscovery(bookId)
    }

    suspend fun ignoreDiscovered(bookId: String, path: String) = runCatchingIo {
        data.ignore(bookId, path)
        refreshBooksAndDiscovery(bookId)
    }

    suspend fun updateRenamed(bookId: String, chapterId: String, path: String) = runCatchingIo {
        data.updatePath(bookId, chapterId, path, requireSameHash = true)
        refreshBooksAndDiscovery(bookId)
    }

    suspend fun locateMissing(bookId: String, chapterId: String, path: String) = runCatchingIo {
        data.updatePath(bookId, chapterId, path, requireSameHash = false)
        refreshBooksAndDiscovery(bookId)
    }

    suspend fun removeMissing(bookId: String, chapterId: String) = runCatchingIo {
        data.removeChapter(bookId, chapterId)
        refreshBooksAndDiscovery(bookId)
    }

    fun requestForget(bookId: String) {
        mutableState.value = mutableState.value.copy(forgetBookId = bookId)
    }

    fun cancelForget() {
        mutableState.value = mutableState.value.copy(forgetBookId = null)
    }

    suspend fun confirmForget() = runCatchingIo {
        val bookId = requireNotNull(mutableState.value.forgetBookId)
        data.forget(bookId)
        val books = data.books()
        mutableState.value = mutableState.value.copy(
            books = books,
            forgetBookId = null,
            destination = books.firstOrNull()?.let { BookDestination.Reader(it.bookId, it.chapters.first().id) }
                ?: BookDestination.Books,
        )
    }

    fun openAppearance() {
        invalidateAutoOpen()
        mutableState.value = mutableState.value.copy(
            destination = BookDestination.Appearance,
            pendingLoadRoot = null,
        )
    }

    private fun invalidateAutoOpen() {
        navigationIntentGeneration.incrementAndGet()
    }

    private fun publishAutoOpen(
        book: BookSummary,
        chapter: BookChapter,
        root: String,
        intent: Long,
    ) {
        if (!autoOpenIsCurrent(intent, root)) return
        val location = ResumeLocation(book.bookId, chapter.id)
        mutableState.update { current ->
            if (autoOpenIsCurrent(intent, root)) {
                current.copy(
                    books = current.books.filterNot { it.bookId == book.bookId } + book,
                    destination = location.toDestination(),
                    pendingLoadRoot = null,
                    error = null,
                )
            } else current
        }
    }

    private fun autoOpenIsCurrent(intent: Long, root: String): Boolean {
        if (navigationIntentGeneration.get() != intent) return false
        val current = mutableState.value
        return current.pendingLoadRoot == root &&
            (current.destination is BookDestination.FolderBrowser || current.destination == BookDestination.Books)
    }

    suspend fun setDark(dark: Boolean) = saveAppearance(mutableState.value.appearance.copy(dark = dark))
    suspend fun increaseTextSize() = saveAppearance(
        mutableState.value.appearance.copy(textScale = mutableState.value.appearance.textScale + TEXT_STEP),
    )
    suspend fun decreaseTextSize() = saveAppearance(
        mutableState.value.appearance.copy(textScale = mutableState.value.appearance.textScale - TEXT_STEP),
    )
    suspend fun resetTextSize() = saveAppearance(mutableState.value.appearance.copy(textScale = 1f))

    fun clearError() {
        mutableState.value = mutableState.value.copy(
            error = null,
            reorderRecoveryAvailable = false,
            reorderRecoveryLoading = false,
        )
    }

    private suspend fun saveAppearance(value: AppearancePreference) = runCatchingIo {
        val normalized = value.normalized()
        data.saveAppearance(normalized)
        mutableState.value = mutableState.value.copy(appearance = normalized, error = null)
    }

    private suspend fun refreshBooks(destination: BookDestination) = runCatchingIo {
        mutableState.value = mutableState.value.copy(
            books = data.books(),
            importDrafts = data.importDrafts(),
            destination = destination,
            error = null,
        )
    }

    private suspend fun refreshBooksAndDiscovery(bookId: String) {
        mutableState.value = mutableState.value.copy(
            books = data.books(),
            importDrafts = data.importDrafts(),
            error = null,
        )
        refreshDiscoveryQuietly(bookId)
    }

    private suspend fun refreshPublishedBook(
        bookId: String,
        expected: BookDestination.Reader,
        generation: Long,
    ) {
        val books = data.books()
        val importDrafts = data.importDrafts()
        val notices = try {
            data.discover(bookId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            state.value.discoveryNotices
        }
        if (generation != chapterNavigationGeneration.get() || state.value.destination != expected) return
        val refreshed = books.singleOrNull { it.bookId == bookId }
        when {
            refreshed == null || !refreshed.availableOffline || refreshed.recoveryError != null || refreshed.chapters.isEmpty() ->
                publishRefreshedBook(books, importDrafts, BookDestination.Books, notices, expected, generation)
            refreshed.chapters.any { it.id == expected.chapterId } ->
                publishRefreshedBook(books, importDrafts, expected, notices, expected, generation)
            else -> {
                val fallback = ResumeLocation(bookId, refreshed.chapters.first().id, blockIndex = 0, byteOffset = 0)
                chapterNavigationMutex.withLock {
                    if (generation != chapterNavigationGeneration.get() || state.value.destination != expected) return@withLock
                    data.persistResume(fallback)
                    if (generation != chapterNavigationGeneration.get() || state.value.destination != expected) return@withLock
                    publishRefreshedBook(books, importDrafts, fallback.toDestination(), notices, expected, generation)
                }
            }
        }
    }

    private fun publishRefreshedBook(
        books: List<BookSummary>,
        importDrafts: List<ImportDraftSummary>,
        destination: BookDestination,
        notices: List<DiscoveryNotice>,
        expected: BookDestination.Reader,
        generation: Long,
    ) {
        mutableState.update { current ->
            if (generation == chapterNavigationGeneration.get() && current.destination == expected) {
                current.copy(
                    books = books,
                    importDrafts = importDrafts,
                    destination = destination,
                    discoveryNotices = notices,
                    error = null,
                )
            } else {
                current
            }
        }
    }

    private suspend fun refreshDiscoveryQuietly(bookId: String) {
        try {
            mutableState.value = mutableState.value.copy(discoveryNotices = data.discover(bookId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Discovery is opportunistic: an offline reader remains quiet and usable.
        }
    }

    private suspend fun runCatchingIo(
        failureDestination: BookDestination? = null,
        failureMessage: ((Throwable) -> String)? = null,
        block: suspend () -> Unit,
    ) {
        try {
            withContext(dispatcher) { block() }
        } catch (cancelled: CancellationException) {
            failureDestination?.let { destination ->
                mutableState.value = mutableState.value.copy(destination = destination)
            }
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(
                destination = failureDestination ?: mutableState.value.destination,
                error = (failure as? BookLibraryUserError)?.message
                    ?: failureMessage?.invoke(failure)
                    ?: "Не удалось выполнить действие. Попробуйте ещё раз.",
            )
        }
    }

    private fun AppearancePreference.normalized() = copy(textScale = textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE))
    private fun String.normalizedRemotePath() = trim().trimEnd('/')
    private fun ResumeLocation.toDestination() = BookDestination.Reader(bookId, chapterId, blockIndex, byteOffset)

    private companion object {
        const val MIN_TEXT_SCALE = .8f
        const val MAX_TEXT_SCALE = 1.3f
        const val TEXT_STEP = .1f
    }

    private data class PendingReorder(
        val generation: Long,
        val bookId: String,
        val orderedChapterIds: List<String>,
    )
}

internal fun Throwable.toImportUserMessage(): String = when (this) {
    is YandexDiskError.Offline -> "Нет подключения к Яндекс Диску. Загруженные главы сохранены."
    is YandexDiskError.Unauthorized -> "Войдите в Яндекс Диск ещё раз."
    is YandexDiskError.NotFound -> "Папка или одна из глав больше недоступна."
    is YandexDiskError.RateLimited -> "Яндекс Диск временно ограничил запросы. Повторите позже."
    is YandexDiskError.ServerFailure -> "Яндекс Диск временно недоступен."
    else -> "Не удалось продолжить импорт. Загруженные главы сохранены."
}
