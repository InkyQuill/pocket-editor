package net.inkyquill.pocketeditor.storage

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class ImportDraftStore internal constructor(
    private val root: File,
    private val directoryFsync: DirectoryFsync,
) {
    constructor(root: File) : this(root, PlatformDirectoryFsync)

    suspend fun readMatchingSource(
        bookId: String,
        path: String,
        remoteRevision: String,
        sha256: String,
    ): ByteArray? {
        val source = validatedPaths().source(bookId, path)
        val metadataFile = metadataFile(source)
        requireInsideRoot(source)
        requireInsideRoot(metadataFile)
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

    suspend fun delete(bookId: String) {
        val target = validatedPaths().bookDirectory(bookId)
        requireInsideRoot(target)
        if (target.exists()) {
            target.deleteRecursively()
            check(!target.exists()) { "Unable to delete import draft cache" }
            target.parentFile?.let(directoryFsync::sync)
        }
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
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            "Draft path escapes its storage root"
        }
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
