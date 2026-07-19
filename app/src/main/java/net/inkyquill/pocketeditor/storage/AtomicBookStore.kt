package net.inkyquill.pocketeditor.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.UUID
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewJson

data class LocalRevision(
    val path: String,
    val sha256: String,
    val byteSize: Long,
    val directorySyncStatus: DirectorySyncStatus,
)

enum class DirectorySyncStatus {
    SYNCED,
    UNSUPPORTED,
}

internal fun interface DirectoryFsync {
    fun sync(directory: File): DirectorySyncStatus
}

interface BookStore {
    suspend fun readSource(bookId: String, path: String): ByteArray
    suspend fun readManifest(bookId: String): BookManifest
    suspend fun writeManifest(bookId: String, value: BookManifest): LocalRevision
    suspend fun readReview(bookId: String, path: String): ReviewDocument?
    suspend fun writeReview(bookId: String, path: String, value: ReviewDocument): LocalRevision
}

internal interface SourceCache {
    suspend fun replaceDownloadedSource(bookId: String, path: String, bytes: ByteArray): LocalRevision
}

class AtomicBookStore internal constructor(
    private val paths: BookPaths,
    private val beforeReplace: (temporary: File, target: File) -> Unit,
    private val directoryFsync: DirectoryFsync,
) : BookStore, SourceCache {
    constructor(paths: BookPaths) : this(paths, { _, _ -> }, PlatformDirectoryFsync)

    internal constructor(
        paths: BookPaths,
        beforeReplace: (temporary: File, target: File) -> Unit,
    ) : this(paths, beforeReplace, PlatformDirectoryFsync)

    override suspend fun readSource(bookId: String, path: String): ByteArray =
        paths.source(bookId, path).readBytes()

    override suspend fun readManifest(bookId: String): BookManifest =
        BookManifest.decode(paths.manifest(bookId).readText(StandardCharsets.UTF_8))

    override suspend fun writeManifest(bookId: String, value: BookManifest): LocalRevision {
        require(value.bookId == bookId) { "Manifest book_id must match its cache directory" }
        val bytes = BookManifest.encode(value).toByteArray(StandardCharsets.UTF_8)
        return replace(paths.manifest(bookId), BookPaths.MANIFEST_NAME, bytes)
    }

    internal fun replaceDownloadedManifest(bookId: String, bytes: ByteArray): LocalRevision {
        val manifest = BookManifest.decode(bytes.toString(StandardCharsets.UTF_8))
        require(manifest.bookId == bookId) { "Manifest book_id must match its cache directory" }
        return replace(paths.manifest(bookId), BookPaths.MANIFEST_NAME, bytes)
    }

    override suspend fun readReview(bookId: String, path: String): ReviewDocument? {
        val file = paths.review(bookId, path)
        if (!file.exists()) return null
        val sourcePath = path.removeSuffix(BookPaths.REVIEW_SUFFIX)
        val raw = file.readText(StandardCharsets.UTF_8)
        return ReviewJson.decode(raw, expectedChapterId(bookId, sourcePath), sourcePath)
    }

    override suspend fun writeReview(bookId: String, path: String, value: ReviewDocument): LocalRevision {
        require(path == value.sourcePath + BookPaths.REVIEW_SUFFIX) {
            "Review path must correspond to source_path"
        }
        require(value.chapterId == expectedChapterId(bookId, value.sourcePath)) {
            "Review chapter_id does not match its manifest entry"
        }
        val bytes = ReviewJson.encode(value).toByteArray(StandardCharsets.UTF_8)
        return replace(paths.review(bookId, path), path, bytes)
    }

    internal suspend fun replaceDownloadedReview(bookId: String, path: String, bytes: ByteArray): LocalRevision {
        require(path.endsWith(BookPaths.REVIEW_SUFFIX))
        val sourcePath = path.removeSuffix(BookPaths.REVIEW_SUFFIX)
        val chapterId = expectedChapterId(bookId, sourcePath)
        ReviewJson.decode(bytes.toString(StandardCharsets.UTF_8), chapterId, sourcePath)
        return replace(paths.review(bookId, path), path, bytes)
    }

    override suspend fun replaceDownloadedSource(bookId: String, path: String, bytes: ByteArray): LocalRevision {
        require(path != BookPaths.MANIFEST_NAME && !path.endsWith(BookPaths.REVIEW_SUFFIX)) {
            "Source cache accepts canonical source files only"
        }
        return replace(paths.source(bookId, path), path, bytes)
    }

    private fun replace(target: File, relativePath: String, bytes: ByteArray): LocalRevision {
        val parent = requireNotNull(target.parentFile) { "Cache target must have a parent directory" }
        Files.createDirectories(parent.toPath())
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            beforeReplace(temporary, target)
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Cache filesystem does not support atomic replacement", unsupported)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        val directorySyncStatus = directoryFsync.sync(parent)
        return LocalRevision(relativePath, bytes.sha256(), bytes.size.toLong(), directorySyncStatus)
    }

    private suspend fun expectedChapterId(bookId: String, sourcePath: String): String =
        readManifest(bookId).chapters.singleOrNull { it.path == sourcePath }?.id
            ?: throw IllegalArgumentException("Review source_path is not registered in the manifest")
}

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

internal object PlatformDirectoryFsync : DirectoryFsync {
    override fun sync(directory: File): DirectorySyncStatus =
        if (isAndroidRuntime) syncAndroidDirectory(directory) else syncNioDirectory(directory)

    private fun syncAndroidDirectory(directory: File): DirectorySyncStatus {
        val descriptor = try {
            Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        } catch (error: ErrnoException) {
            return error.asUnsupportedOrThrow()
        }
        return try {
            Os.fsync(descriptor)
            DirectorySyncStatus.SYNCED
        } catch (error: ErrnoException) {
            error.asUnsupportedOrThrow()
        } finally {
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
                // The durability result above remains accurate even if descriptor cleanup reports an error.
            }
        }
    }

    private fun syncNioDirectory(directory: File): DirectorySyncStatus = try {
        FileChannel.open(directory.toPath(), READ).use { channel -> channel.force(true) }
        DirectorySyncStatus.SYNCED
    } catch (_: UnsupportedOperationException) {
        DirectorySyncStatus.UNSUPPORTED
    } catch (_: FileSystemException) {
        DirectorySyncStatus.UNSUPPORTED
    }

    private val isAndroidRuntime: Boolean =
        System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == true

    private fun ErrnoException.asUnsupportedOrThrow(): DirectorySyncStatus =
        if (errno == OsConstants.EINVAL || errno == OsConstants.ENOTSUP || errno == OsConstants.EOPNOTSUPP) {
            DirectorySyncStatus.UNSUPPORTED
        } else {
            throw this
        }
}
