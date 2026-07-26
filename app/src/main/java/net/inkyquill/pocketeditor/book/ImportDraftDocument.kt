package net.inkyquill.pocketeditor.book

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ImportDraftDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val bookId: String,
    val remoteRootPath: String,
    val title: String,
    val phase: ImportDraftPhase,
    val chapters: List<ImportDraftChapter>,
    val lastError: ImportDraftError? = null,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported import draft schema version" }
        requireUuid(bookId, "bookId")
        require(remoteRootPath.startsWith("disk:/")) { "Remote root must be an absolute Yandex Disk path" }
        require(chapters.map(ImportDraftChapter::path).distinct().size == chapters.size) {
            "Draft chapter paths must be unique"
        }
        require(chapters.map(ImportDraftChapter::id).distinct().size == chapters.size) {
            "Draft chapter IDs must be unique"
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

        fun encode(value: ImportDraftDocument): String = json.encodeToString(value)

        fun decode(value: String): ImportDraftDocument = json.decodeFromString(value)
    }
}

@Serializable
data class ImportDraftChapter(
    val id: String,
    val path: String,
    val title: String,
    val included: Boolean,
    val remoteRevision: String,
    val sha256: String,
    val byteSize: Long,
) {
    init {
        requireUuid(id, "chapter id")
        requireDirectChildPath(path, "chapter path")
        require(remoteRevision.isNotBlank()) { "Chapter remote revision must not be blank" }
        require(sha256.isNotBlank()) { "Chapter SHA-256 must not be blank" }
        require(byteSize >= 0) { "Chapter byte size must not be negative" }
    }
}

@Serializable
enum class ImportDraftPhase {
    DOWNLOADING,
    READY,
    PROMOTING,
    FAILED,
}

@Serializable
data class ImportDraftError(
    val category: ImportDraftErrorCategory,
    val retryable: Boolean,
)

@Serializable
enum class ImportDraftErrorCategory {
    OFFLINE,
    UNAUTHORIZED,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    UNKNOWN,
}
