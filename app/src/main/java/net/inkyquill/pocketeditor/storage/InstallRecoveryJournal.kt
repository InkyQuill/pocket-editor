package net.inkyquill.pocketeditor.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.database.BookDao

enum class InstallPhase { PREPARED, OLD_MOVED, SWAPPED, DATABASE_COMMITTED }

data class InstallJournalEntry(
    val bookId: String,
    val stageRootName: String,
    val phase: InstallPhase,
)

internal class InstallRecoveryJournal(
    private val paths: BookPaths,
    private val books: BookDao,
    private val directoryFsync: DirectoryFsync = PlatformDirectoryFsync,
) {
    fun write(entry: InstallJournalEntry) {
        validate(entry)
        Files.createDirectories(paths.root.toPath())
        val target = marker(entry.bookId)
        val temporary = File(paths.root, ".${target.name}.${UUID.randomUUID()}.tmp")
        val bytes = buildString {
            appendLine("version=1")
            appendLine("book_id=${entry.bookId}")
            appendLine("stage_root=${entry.stageRootName}")
            appendLine("phase=${entry.phase.name}")
        }.encodeToByteArray()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
            directoryFsync.sync(paths.root)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    fun delete(bookId: String) {
        if (Files.deleteIfExists(marker(bookId).toPath())) directoryFsync.sync(paths.root)
    }

    fun discard(bookId: String) {
        val marker = marker(bookId)
        if (marker.exists()) {
            val entry = decode(marker)
            removeTree(File(paths.root, entry.stageRootName))
            delete(bookId)
        }
        val temporaryPrefix = ".${marker.name}."
        val removedTemporary = paths.root.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(temporaryPrefix) && it.name.endsWith(".tmp") }
            .fold(false) { removed, file -> Files.deleteIfExists(file.toPath()) || removed }
        if (removedTemporary) directoryFsync.sync(paths.root)
    }

    fun moveIntoLibrary(source: File, target: File) {
        require(source.parentFile?.parentFile?.canonicalFile == paths.root.canonicalFile)
        require(target.parentFile?.canonicalFile == paths.root.canonicalFile)
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
        directoryFsync.sync(paths.root)
    }

    fun removeTree(file: File) {
        require(file.parentFile?.canonicalFile == paths.root.canonicalFile)
        if (file.exists()) {
            check(file.deleteRecursively()) { "Could not remove install artifact: ${file.name}" }
            directoryFsync.sync(paths.root)
        }
    }

    suspend fun recover() {
        paths.root.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            .sortedBy(File::getName)
            .forEach { journalFile -> recoverOne(decode(journalFile)) }
    }

    private suspend fun recoverOne(entry: InstallJournalEntry) {
        val stageRoot = File(paths.root, entry.stageRootName)
        val finalBook = paths.bookDirectory(entry.bookId)
        val registered = books.getRoot(entry.bookId) != null
        val finalMatches = runCatching {
            val bytes = File(finalBook, BookPaths.MANIFEST_NAME).readBytes()
            BookManifest.decode(StrictUtf8.decode(bytes, "Book manifest")).bookId == entry.bookId
        }.getOrDefault(false)
        if (registered && finalMatches) {
            removeTree(stageRoot)
            delete(entry.bookId)
            return
        }

        check(!registered) { "First-install recovery found a registered root without its matching cache" }
        // The rename can complete while the durable marker still says OLD_MOVED.
        // Any final cache without a matching registered root belongs to this incomplete first install.
        removeTree(finalBook)
        removeTree(stageRoot)
        delete(entry.bookId)
    }

    private fun decode(file: File): InstallJournalEntry {
        val values = file.readLines().associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0)
            line.substring(0, separator) to line.substring(separator + 1)
        }
        require(values.keys == setOf("version", "book_id", "stage_root", "phase"))
        require(values.getValue("version") == "1")
        return InstallJournalEntry(
            bookId = values.getValue("book_id"),
            stageRootName = values.getValue("stage_root"),
            phase = InstallPhase.valueOf(values.getValue("phase")),
        ).also(::validate)
    }

    private fun validate(entry: InstallJournalEntry) {
        paths.bookDirectory(entry.bookId)
        require(entry.stageRootName.matches(Regex("^\\.install-[0-9a-f-]{36}$")))
    }

    private fun marker(bookId: String): File {
        paths.bookDirectory(bookId)
        return File(paths.root, "$PREFIX$bookId$SUFFIX")
    }

    private companion object {
        const val PREFIX = ".install-journal-"
        const val SUFFIX = ".state"
    }
}

class InstallRecoveryCoordinator internal constructor(
    private val recoverAction: suspend () -> Unit,
) {
    internal constructor(journal: InstallRecoveryJournal) : this(journal::recover)

    private val mutex = Mutex()
    private var completed = false

    suspend fun recoverOnce() = mutex.withLock {
        if (completed) return@withLock
        recoverAction()
        completed = true
    }
}
