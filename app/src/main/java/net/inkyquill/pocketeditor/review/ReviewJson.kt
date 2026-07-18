package net.inkyquill.pocketeditor.review

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import net.inkyquill.pocketeditor.book.requireDirectChildPath
import net.inkyquill.pocketeditor.book.requireUuid

object ReviewJson {
    fun decode(value: String, expectedChapterId: String, expectedSourcePath: String): ReviewDocument {
        val document = json.decodeFromString(ReviewDocument.serializer(), value)
        validate(document)
        require(document.chapterId == expectedChapterId) { "Review chapter_id does not match its manifest entry" }
        require(document.sourcePath == expectedSourcePath) { "Review source_path does not match its source chapter" }
        return document
    }

    fun encode(value: ReviewDocument): String {
        validate(value)
        val canonical = value.copy(
            signals = value.signals.sortedBy(Signal::id),
            edits = value.edits.sortedBy(Edit::id),
        )
        return json.encodeToString(ReviewDocument.serializer(), canonical) + "\n"
    }

    private fun validate(document: ReviewDocument) {
        require(document.schemaVersion == 1) { "Unsupported review schema version: ${document.schemaVersion}" }
        requireUuid(document.chapterId, "chapter_id")
        requireDirectChildPath(document.sourcePath, "source_path")

        val recordIds = document.signals.map(Signal::id) + document.edits.map(Edit::id)
        require(recordIds.distinct().size == recordIds.size) { "Record IDs must be unique" }

        document.signals.forEach { signal ->
            requireUuid(signal.id, "signal id")
            validate(signal.anchor)
        }
        document.edits.forEach { edit ->
            requireUuid(edit.id, "edit id")
            require(edit.before.isNotEmpty()) { "Edit before text must not be empty" }
            require(edit.after != edit.before) { "Edit after text must differ from before text" }
            validate(edit.anchor)
        }

        document.edits
            .sortedBy { it.anchor.startByte }
            .zipWithNext()
            .forEach { (left, right) ->
                require(right.anchor.startByte >= left.anchor.endByte) { "Edit source ranges must not overlap" }
            }
    }

    private fun validate(anchor: Anchor) {
        require(sha256Pattern.matches(anchor.sourceSha256)) { "source_sha256 must be lowercase SHA-256 hex" }
        require(sha256Pattern.matches(anchor.selectionSha256)) { "selection_sha256 must be lowercase SHA-256 hex" }
        require(anchor.startByte >= 0 && anchor.endByte > anchor.startByte) {
            "Anchor byte range must be non-empty, non-negative, and half-open"
        }
        require(anchor.startLine >= 1 && anchor.endLine >= anchor.startLine) {
            "Anchor line hints must be one-based and ordered"
        }
        require(anchor.prefix.codePointCount() <= MAX_CONTEXT_CODE_POINTS) {
            "Anchor prefix must not exceed $MAX_CONTEXT_CODE_POINTS Unicode code points"
        }
        require(anchor.suffix.codePointCount() <= MAX_CONTEXT_CODE_POINTS) {
            "Anchor suffix must not exceed $MAX_CONTEXT_CODE_POINTS Unicode code points"
        }
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)

    private const val MAX_CONTEXT_CODE_POINTS = 128
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
