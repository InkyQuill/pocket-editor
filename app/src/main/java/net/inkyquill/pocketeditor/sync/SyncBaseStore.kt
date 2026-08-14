package net.inkyquill.pocketeditor.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import net.inkyquill.pocketeditor.storage.DirectoryFsync
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.storage.PlatformDirectoryFsync
import net.inkyquill.pocketeditor.storage.StrictUtf8

data class SyncBase(
    val bytes: ByteArray,
    val sha256: String,
    val remoteRevision: String,
    val directorySyncStatus: DirectorySyncStatus = DirectorySyncStatus.SYNCED,
)

interface SyncBaseStore {
    fun read(bookId: String, path: String): SyncBase?
    fun write(bookId: String, path: String, bytes: ByteArray, remoteRevision: String): SyncBase
    fun delete(bookId: String, path: String)
}

class AtomicSyncBaseStore internal constructor(
    private val root: File,
    private val beforeReplace: (temporary: File, target: File) -> Unit,
    private val directoryFsync: DirectoryFsync,
) : SyncBaseStore {
    constructor(root: File) : this(root, { _, _ -> }, PlatformDirectoryFsync)

    internal constructor(root: File, beforeReplace: (temporary: File, target: File) -> Unit) :
        this(root, beforeReplace, PlatformDirectoryFsync)

    override fun read(bookId: String, path: String): SyncBase? {
        val target = target(bookId, path)
        if (!target.exists()) return null
        val contents = target.readBytes()
        val firstNewline = contents.indexOf('\n'.code.toByte())
        val secondNewline = contents.indexOf('\n'.code.toByte(), firstNewline + 1)
        require(firstNewline > 0 && secondNewline > firstNewline + 1) { "Invalid sync base header" }
        val revision = StrictUtf8.decode(contents.copyOfRange(0, firstNewline), "Sync base revision")
        val expectedHash = StrictUtf8.decode(contents.copyOfRange(firstNewline + 1, secondNewline), "Sync base hash")
        val bytes = contents.copyOfRange(secondNewline + 1, contents.size)
        val actualHash = sha256(bytes)
        require(expectedHash == actualHash) { "Sync base hash does not match its content" }
        return SyncBase(bytes, actualHash, revision)
    }

    override fun write(bookId: String, path: String, bytes: ByteArray, remoteRevision: String): SyncBase {
        require(remoteRevision.isNotBlank() && '\n' !in remoteRevision && '\r' !in remoteRevision)
        val target = target(bookId, path)
        val hash = sha256(bytes)
        val contents = "$remoteRevision\n$hash\n".encodeToByteArray() + bytes
        Files.createDirectories(requireNotNull(target.parentFile).toPath())
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(contents)
                output.fd.sync()
            }
            beforeReplace(temporary, target)
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Sync base filesystem does not support atomic replacement", error)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        val directorySyncStatus = directoryFsync.sync(requireNotNull(target.parentFile))
        return SyncBase(bytes.copyOf(), hash, remoteRevision, directorySyncStatus)
    }

    override fun delete(bookId: String, path: String) {
        val target = target(bookId, path)
        if (Files.deleteIfExists(target.toPath())) {
            directoryFsync.sync(requireNotNull(target.parentFile))
        }
    }

    private fun target(bookId: String, path: String): File {
        require(runCatching { UUID.fromString(bookId).toString() == bookId }.getOrDefault(false))
        require(
            path == MANIFEST_PATH ||
                (path.endsWith(REVIEW_SUFFIX) && path.length > REVIEW_SUFFIX.length && '/' !in path && '\\' !in path),
        ) { "Sync bases accept only canonical Pocket Editor metadata paths" }
        return File(File(root, bookId), "$path.base")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST_PATH = ".pocket-editor.json"
        const val REVIEW_SUFFIX = ".review.json"
    }
}

private fun ByteArray.indexOf(value: Byte, startIndex: Int = 0): Int {
    for (index in startIndex until size) if (this[index] == value) return index
    return -1
}
