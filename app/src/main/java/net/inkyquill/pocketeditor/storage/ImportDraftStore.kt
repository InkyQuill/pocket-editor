package net.inkyquill.pocketeditor.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ImportDraftStore internal constructor(
    private val root: File,
    private val directoryFsync: DirectoryFsync,
) {
    constructor(root: File) : this(root, PlatformDirectoryFsync)

    fun directory(bookId: String): File = validatedPaths().bookDirectory(bookId)

    suspend fun writeSource(
        bookId: String,
        path: String,
        bytes: ByteArray,
        remoteRevision: String,
    ): LocalRevision {
        require(remoteRevision.isNotBlank()) { "Remote revision must not be blank" }
        StrictUtf8.decode(bytes, "Imported source $path")
        val source = validatedPaths().source(bookId, path)
        val revision = replace(source, bytes)
        val metadata = ImportDraftSourceMetadata(
            remoteRevision = remoteRevision,
            sha256 = revision.sha256,
            byteSize = revision.byteSize,
        )
        replace(metadataFile(source), json.encodeToString(metadata).encodeToByteArray())
        return revision
    }

    suspend fun readSource(bookId: String, path: String): ByteArray =
        validatedPaths().source(bookId, path).readBytes()

    suspend fun readMatchingSource(
        bookId: String,
        path: String,
        remoteRevision: String,
        sha256: String,
    ): ByteArray? {
        val source = validatedPaths().source(bookId, path)
        val metadataFile = metadataFile(source)
        if (!source.isFile || !metadataFile.isFile) return null
        val metadata = runCatching {
            json.decodeFromString<ImportDraftSourceMetadata>(
                StrictUtf8.decode(metadataFile.readBytes(), "Import cache metadata"),
            )
        }.getOrNull() ?: return null
        val bytes = runCatching {
            source.readBytes().also { StrictUtf8.decode(it, "Imported source $path") }
        }.getOrNull() ?: return null
        return bytes.takeIf {
            metadata.remoteRevision == remoteRevision &&
                metadata.sha256 == sha256 &&
                metadata.byteSize == it.size.toLong() &&
                it.sha256() == sha256
        }
    }

    suspend fun promoteTo(bookId: String, destination: File) {
        val source = directory(bookId)
        require(source.isDirectory) { "Draft cache does not exist" }
        require(!destination.exists()) { "Registered book destination already exists" }
        val destinationParent = requireNotNull(destination.parentFile) { "Destination must have a parent" }
        Files.createDirectories(destinationParent.toPath())
        try {
            Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Cache filesystem does not support atomic promotion", unsupported)
        }
        directoryFsync.sync(destinationParent)
    }

    suspend fun delete(bookId: String) {
        val target = directory(bookId)
        requireInsideRoot(target)
        if (target.exists()) {
            target.deleteRecursively()
            check(!target.exists()) { "Unable to delete import draft cache" }
            target.parentFile?.let(directoryFsync::sync)
        }
    }

    private fun replace(target: File, bytes: ByteArray): LocalRevision {
        requireInsideRoot(target)
        val parent = requireNotNull(target.parentFile)
        Files.createDirectories(parent.toPath())
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Cache filesystem does not support atomic replacement", unsupported)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        val directorySyncStatus = directoryFsync.sync(parent)
        return LocalRevision(target.name, bytes.sha256(), bytes.size.toLong(), directorySyncStatus)
    }

    private fun metadataFile(source: File): File = File(source.parentFile, ".${source.name}.import-cache.json")

    private fun validatedPaths(): BookPaths {
        requireRootIsSafe()
        return BookPaths(root)
    }

    private fun requireRootIsSafe() {
        require(root.path.isNotBlank()) { "Import draft root must not be blank" }
        require(root.parentFile != null) { "Import draft root must not be a filesystem root" }
    }

    private fun requireInsideRoot(file: File) {
        requireRootIsSafe()
        val rootPath = root.canonicalFile.toPath()
        require(file.canonicalFile.toPath().startsWith(rootPath)) { "Draft path escapes its storage root" }
    }

    private companion object {
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    }
}

@Serializable
private data class ImportDraftSourceMetadata(
    val remoteRevision: String,
    val sha256: String,
    val byteSize: Long,
)
