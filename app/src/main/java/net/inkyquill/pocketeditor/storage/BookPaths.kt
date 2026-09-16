package net.inkyquill.pocketeditor.storage

import java.io.File
import net.inkyquill.pocketeditor.book.BookManifest

class BookPaths(val root: File) {
    fun bookDirectory(bookId: String): File {
        require(UUID.matches(bookId)) { "bookId must be a UUID string" }
        return File(root, bookId)
    }

    fun manifest(bookId: String): File = File(bookDirectory(bookId), MANIFEST_NAME)

    fun source(bookId: String, path: String): File = child(bookId, path)

    fun review(bookId: String, path: String): File {
        require(path.endsWith(REVIEW_SUFFIX)) { "Review path must end with $REVIEW_SUFFIX" }
        return child(bookId, path)
    }

    internal fun child(bookId: String, path: String): File {
        require(
            path.isNotEmpty() &&
                path != "." &&
                path != ".." &&
                !path.startsWith('/') &&
                !path.startsWith('\\') &&
                '/' !in path &&
                '\\' !in path &&
                '\u0000' !in path,
        ) { "Path must be a normalized relative direct-child filename" }
        return File(bookDirectory(bookId), path)
    }

    companion object {
        const val MANIFEST_NAME = ".pocket-editor.json"
        const val REVIEW_SUFFIX = ".review.json"
        /** Canonical first; the legacy full-source name remains a supported identity. */
        fun reviewCandidates(sourcePath: String): List<String> =
            listOf(sourcePath.removeSuffix(".md") + REVIEW_SUFFIX, sourcePath + REVIEW_SUFFIX).distinct()

        fun selectReviewPath(sourcePath: String, existingPaths: Set<String>): String {
            val candidates = reviewCandidates(sourcePath)
            val existing = candidates.filter { it in existingPaths }
            check(existing.size <= 1) { "Conflicting review filenames for $sourcePath: ${existing.joinToString()}. Preserve both files and resolve the duplicate explicitly." }
            return existing.singleOrNull() ?: candidates.first()
        }

        fun reviewSourcePath(manifest: BookManifest, reviewPath: String): String =
            manifest.chapters.singleOrNull { reviewPath in reviewCandidates(it.path) }?.path
                ?: throw IllegalArgumentException("Review filename has no unique source in the manifest: $reviewPath")

        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
