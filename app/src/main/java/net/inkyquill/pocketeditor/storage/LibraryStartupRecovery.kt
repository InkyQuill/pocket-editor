package net.inkyquill.pocketeditor.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.search.SearchChapterSource
import net.inkyquill.pocketeditor.search.SourceSearch

fun interface StartupSearchIndex {
    suspend fun rebuild(bookId: String, chapters: List<SearchChapterSource>)
}

/** Restores Room's derived index from the durable local book files exactly once per process. */
class LibraryStartupRecovery(
    private val scanner: RecoveryScanner,
    private val books: BookDao,
    private val store: AtomicBookStore,
    private val search: StartupSearchIndex,
) {
    constructor(scanner: RecoveryScanner, books: BookDao, store: AtomicBookStore, search: SourceSearch) : this(
        scanner,
        books,
        store,
        StartupSearchIndex(search::rebuildBook),
    )

    private val mutex = Mutex()
    private var completed = false

    suspend fun recover() = mutex.withLock {
        if (completed) return@withLock
        scanner.reconcile()
        books.getRoots().forEach { root ->
            val chapters = runCatching {
                val manifest = store.readManifest(root.bookId)
                require(manifest.bookId == root.bookId) { "Book identity does not match its cache directory" }
                manifest.chapters.map { chapter ->
                    val source = store.readSource(root.bookId, chapter.path)
                    StrictUtf8.decode(source, chapter.path)
                    SearchChapterSource(chapter.id, chapter.title, source)
                }
            }.getOrNull() ?: return@forEach
            // Database/index failures are retryable; do not mark startup recovery complete.
            search.rebuild(root.bookId, chapters)
        }
        completed = true
    }
}
