package net.inkyquill.pocketeditor.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.database.BookDao

enum class InstallPhase { PREPARED, OLD_MOVED, SWAPPED, DATABASE_COMMITTED }

data class InstallJournalEntry(
    val bookId: String,
    val stageRootName: String,
    val backupName: String,
    val hadPrevious: Boolean,
    val phase: InstallPhase,
)

class InstallRecoveryJournal(
    private val paths: BookPaths,
    private val books: BookDao,
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
            appendLine("backup=${entry.backupName}")
            appendLine("had_previous=${entry.hadPrevious}")
            appendLine("phase=${entry.phase.name}")
        }.encodeToByteArray()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    fun delete(bookId: String) {
        Files.deleteIfExists(marker(bookId).toPath())
    }

    suspend fun recover() {
        paths.root.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            .sortedBy(File::getName)
            .forEach { journalFile -> recoverOne(journalFile, decode(journalFile)) }
    }

    private suspend fun recoverOne(journalFile: File, entry: InstallJournalEntry) {
        val stageRoot = File(paths.root, entry.stageRootName)
        val backup = File(paths.root, entry.backupName)
        val finalBook = paths.bookDirectory(entry.bookId)
        val registered = books.getRoot(entry.bookId) != null
        val finalMatches = runCatching {
            BookManifest.decode(File(finalBook, BookPaths.MANIFEST_NAME).readText()).bookId == entry.bookId
        }.getOrDefault(false)
        if (registered && finalMatches) {
            stageRoot.deleteRecursively()
            backup.deleteRecursively()
            Files.deleteIfExists(journalFile.toPath())
            return
        }

        if (registered) books.deleteRoot(entry.bookId)
        if (entry.phase == InstallPhase.SWAPPED || entry.phase == InstallPhase.DATABASE_COMMITTED) {
            finalBook.deleteRecursively()
        }
        if (entry.hadPrevious && backup.exists()) {
            Files.move(backup.toPath(), finalBook.toPath(), ATOMIC_MOVE)
        } else {
            backup.deleteRecursively()
        }
        stageRoot.deleteRecursively()
        Files.deleteIfExists(journalFile.toPath())
    }

    private fun decode(file: File): InstallJournalEntry {
        val values = file.readLines().associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0)
            line.substring(0, separator) to line.substring(separator + 1)
        }
        require(values.keys == setOf("version", "book_id", "stage_root", "backup", "had_previous", "phase"))
        require(values.getValue("version") == "1")
        return InstallJournalEntry(
            bookId = values.getValue("book_id"),
            stageRootName = values.getValue("stage_root"),
            backupName = values.getValue("backup"),
            hadPrevious = values.getValue("had_previous").toBooleanStrict(),
            phase = InstallPhase.valueOf(values.getValue("phase")),
        ).also(::validate)
    }

    private fun validate(entry: InstallJournalEntry) {
        paths.bookDirectory(entry.bookId)
        require(entry.stageRootName.matches(Regex("^\\.install-[0-9a-f-]{36}$")))
        require(entry.backupName.matches(Regex("^\\.backup-${Regex.escape(entry.bookId)}-[0-9a-f-]{36}$")))
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
