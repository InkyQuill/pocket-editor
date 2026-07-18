package net.inkyquill.pocketeditor.storage

import java.io.File

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
        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
