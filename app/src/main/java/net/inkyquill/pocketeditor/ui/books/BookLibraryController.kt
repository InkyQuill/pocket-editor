package net.inkyquill.pocketeditor.ui.books

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BookChapter(val id: String, val title: String)

data class BookSummary(
    val bookId: String,
    val title: String,
    val remoteRootPath: String,
    val chapters: List<BookChapter>,
    val availableOffline: Boolean = true,
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
        val chapterTitle: String,
        val previousPath: String,
        val sameHashRenamePath: String?,
    ) : DiscoveryNotice
}

interface BookLibraryData {
    suspend fun books(): List<BookSummary>
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
    suspend fun add(bookId: String, path: String, title: String, position: Int)
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
    val appearance: AppearancePreference = AppearancePreference(),
    val discoveryNotices: List<DiscoveryNotice> = emptyList(),
    val forgetBookId: String? = null,
    val error: String? = null,
)

internal class BookLibraryUserError(message: String) : IllegalArgumentException(message)

class BookLibraryController(
    private val data: BookLibraryData,
    @Suppress("unused") private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(BookLibraryState())
    val state: StateFlow<BookLibraryState> = mutableState.asStateFlow()
    private val chapterNavigationGeneration = AtomicLong()
    private val chapterNavigationMutex = Mutex()

    suspend fun start() = runCatchingIo {
        val books = data.books()
        val readableBooks = books.filter { it.availableOffline && it.chapters.isNotEmpty() && it.recoveryError == null }
        val appearance = data.appearance().normalized()
        val resume = data.resumeLocation()?.takeIf { location ->
            readableBooks.any { book -> book.bookId == location.bookId && book.chapters.any { it.id == location.chapterId } }
        }
        val destination = resume?.toDestination()
            ?: readableBooks.firstOrNull()?.let { BookDestination.Reader(it.bookId, it.chapters.first().id) }
            ?: BookDestination.Books
        mutableState.value = BookLibraryState(
            books = books,
            appearance = appearance,
            destination = destination,
        )
        (destination as? BookDestination.Reader)?.let {
            data.opened(it.bookId)
            refreshDiscoveryQuietly(it.bookId)
        }
    }

    suspend fun openBooks() = refreshBooks(BookDestination.Books)

    suspend fun openFolderBrowser(path: String = "disk:/") = runCatchingIo(
        failureDestination = BookDestination.FolderBrowser(
            listing = (mutableState.value.destination as? BookDestination.FolderBrowser)?.listing,
            loading = false,
        ),
    ) {
        mutableState.value = mutableState.value.copy(
            destination = BookDestination.FolderBrowser(loading = true),
            error = null,
        )
        val listing = data.browse(path)
        mutableState.value = mutableState.value.copy(destination = BookDestination.FolderBrowser(listing))
    }

    suspend fun openFolder(path: String) {
        val fallback = mutableState.value.destination
        runCatchingIo(failureDestination = fallback) {
        val existing = data.existingRoot(path)
        if (existing != null) {
            val registered = data.books().firstOrNull { local ->
                local.bookId == existing.bookId || local.remoteRootPath.normalizedRemotePath() == path.normalizedRemotePath()
            }
            if (registered != null) {
                val ready = if (registered.needsRelink) {
                    data.relinkRegistered(registered.bookId, path)
                } else {
                    registered
                }
                val location = data.resumeLocation(ready.bookId)?.takeIf { saved ->
                    ready.chapters.any { it.id == saved.chapterId }
                } ?: ResumeLocation(ready.bookId, ready.chapters.first().id)
                data.persistResume(location)
                data.opened(ready.bookId)
                mutableState.value = mutableState.value.copy(
                    books = data.books(),
                    destination = location.toDestination(),
                    error = null,
                )
                refreshDiscoveryQuietly(ready.bookId)
                return@runCatchingIo
            }
            mutableState.value = mutableState.value.copy(
                destination = BookDestination.InstallingExisting(path, existing.title),
                error = null,
            )
            val installed = data.installExisting(path)
            val location = ResumeLocation(installed.bookId, installed.chapters.first().id)
            data.persistResume(location)
            data.opened(installed.bookId)
            mutableState.value = mutableState.value.copy(
                books = data.books(),
                destination = location.toDestination(),
            )
            refreshDiscoveryQuietly(installed.bookId)
            return@runCatchingIo
        }
        val draft = data.propose(path)
        if (draft.chapters.isEmpty()) {
            throw BookLibraryUserError("В этой папке нет обычных глав Markdown")
        }
        mutableState.value = mutableState.value.copy(
            destination = BookDestination.ImportConfirmation(draft),
            error = null,
        )
        }
    }

    fun updateImport(draft: ImportDraft) {
        mutableState.value = mutableState.value.copy(destination = BookDestination.ImportConfirmation(draft))
    }

    suspend fun confirmImport() {
        val draft = (mutableState.value.destination as? BookDestination.ImportConfirmation)?.draft
            ?: error("Нет импорта, ожидающего подтверждения")
        runCatchingIo(failureDestination = BookDestination.ImportConfirmation(draft)) {
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
                destination = location.toDestination(),
            )
        }
    }

    suspend fun switchBook(bookId: String) = runCatchingIo {
        val book = data.books().single { it.bookId == bookId && it.availableOffline && it.recoveryError == null }
        val location = data.resumeLocation(bookId)?.takeIf { saved ->
            book.chapters.any { it.id == saved.chapterId }
        } ?: ResumeLocation(book.bookId, book.chapters.first().id)
        data.persistResume(location)
        data.opened(book.bookId)
        mutableState.value = mutableState.value.copy(destination = location.toDestination(), error = null)
        refreshDiscoveryQuietly(book.bookId)
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
        val generation = chapterNavigationGeneration.incrementAndGet()
        runCatchingIo {
            val location = ResumeLocation(bookId, chapterId, blockIndex, byteOffset)
            var navigated = false
            chapterNavigationMutex.withLock {
                if (generation != chapterNavigationGeneration.get()) return@withLock
                data.persistResume(location)
                data.opened(bookId)
                if (generation != chapterNavigationGeneration.get()) return@withLock
                mutableState.value = mutableState.value.copy(
                    destination = BookDestination.Reader(bookId, chapterId, blockIndex, byteOffset, rawEndByte),
                    error = null,
                )
                navigated = true
            }
            if (navigated) refreshDiscoveryQuietly(bookId)
        }
    }

    suspend fun addDiscovered(bookId: String, path: String, title: String, position: Int) = runCatchingIo {
        if (title.isBlank()) {
            throw BookLibraryUserError("Название главы не может быть пустым")
        }
        data.add(bookId, path, title.trim(), position)
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
        mutableState.value = mutableState.value.copy(destination = BookDestination.Appearance)
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
        mutableState.value = mutableState.value.copy(error = null)
    }

    private suspend fun saveAppearance(value: AppearancePreference) = runCatchingIo {
        val normalized = value.normalized()
        data.saveAppearance(normalized)
        mutableState.value = mutableState.value.copy(appearance = normalized, error = null)
    }

    private suspend fun refreshBooks(destination: BookDestination) = runCatchingIo {
        mutableState.value = mutableState.value.copy(books = data.books(), destination = destination, error = null)
    }

    private suspend fun refreshBooksAndDiscovery(bookId: String) {
        mutableState.value = mutableState.value.copy(books = data.books(), error = null)
        refreshDiscoveryQuietly(bookId)
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
}
