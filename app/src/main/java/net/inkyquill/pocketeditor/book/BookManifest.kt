package net.inkyquill.pocketeditor.book

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class BookManifest(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("book_id") val bookId: String,
    val title: String,
    val chapters: List<ChapterEntry> = emptyList(),
    @SerialName("ignored_files") val ignoredFiles: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 2

        fun decode(value: String): BookManifest =
            manifestJson.decodeFromString(ManifestWire.serializer(), value).let { wire ->
                require(wire.schemaVersion in setOf(1, SCHEMA_VERSION)) {
                    "Unsupported manifest schema version: ${wire.schemaVersion}"
                }
                BookManifest(
                    bookId = wire.bookId,
                    title = wire.title,
                    chapters = wire.chapters.map { chapter -> ChapterEntry(chapter.id, chapter.path) },
                    ignoredFiles = wire.ignoredFiles,
                ).also(BookManifest::validate)
            }

        fun encode(value: BookManifest): String {
            value.validate()
            val canonical = value.copy(ignoredFiles = value.ignoredFiles.sorted())
            return manifestJson.encodeToString(
                ManifestWire.serializer(),
                ManifestWire(
                    schemaVersion = SCHEMA_VERSION,
                    bookId = canonical.bookId,
                    title = canonical.title,
                    chapters = canonical.chapters.map { chapter -> ChapterWire(chapter.id, chapter.path) },
                    ignoredFiles = canonical.ignoredFiles,
                ),
            ) + "\n"
        }
    }

    private fun validate() {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported manifest schema version: $schemaVersion" }
        requireUuid(bookId, "book_id")
        require(chapters.map(ChapterEntry::id).distinct().size == chapters.size) {
            "Chapter IDs must be unique"
        }
        require(chapters.map(ChapterEntry::path).distinct().size == chapters.size) {
            "Chapter paths must be unique"
        }
        chapters.forEach { chapter ->
            requireUuid(chapter.id, "chapter id")
            requireDirectChildPath(chapter.path, "chapter path")
        }
        require(ignoredFiles.distinct().size == ignoredFiles.size) { "Ignored paths must be unique" }
        ignoredFiles.forEach { requireDirectChildPath(it, "ignored path") }
        require(chapters.map(ChapterEntry::path).toSet().intersect(ignoredFiles.toSet()).isEmpty()) {
            "A chapter path cannot also be ignored"
        }
    }
}

data class ChapterEntry(
    val id: String,
    val path: String,
)

@Serializable
private data class ManifestWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("book_id") val bookId: String,
    val title: String,
    val chapters: List<ChapterWire>,
    @SerialName("ignored_files") val ignoredFiles: List<String> = emptyList(),
)

@Serializable
private data class ChapterWire(
    val id: String,
    val path: String,
    val title: String? = null,
)

private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

internal fun requireUuid(value: String, field: String) {
    require(uuidPattern.matches(value)) { "$field must be a UUID string" }
}

internal fun requireDirectChildPath(value: String, field: String) {
    require(
        value.isNotEmpty() &&
            value != "." &&
            value != ".." &&
            !value.startsWith('/') &&
            !value.startsWith('\\') &&
            '/' !in value &&
            '\\' !in value &&
            '\u0000' !in value,
    ) { "$field must be a normalized relative direct-child filename" }
}

@OptIn(ExperimentalSerializationApi::class)
private val manifestJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = false
    prettyPrint = true
    prettyPrintIndent = "  "
}
