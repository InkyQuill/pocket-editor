package net.inkyquill.pocketeditor.review

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReviewDocument(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("chapter_id") val chapterId: String,
    @SerialName("source_path") val sourcePath: String,
    @SerialName("chapter_note") val chapterNote: String = "",
    val signals: List<Signal> = emptyList(),
    val edits: List<Edit> = emptyList(),
)

@Serializable
data class Signal(
    val id: String,
    val type: SignalType,
    @SerialName("selected_text") val selectedText: String,
    val anchor: Anchor,
    val comment: String = "",
)

@Serializable
data class Edit(
    val id: String,
    val before: String,
    val after: String,
    val anchor: Anchor,
)

@Serializable
data class Anchor(
    @SerialName("source_sha256") val sourceSha256: String,
    @SerialName("selection_sha256") val selectionSha256: String,
    @SerialName("start_byte") val startByte: Long,
    @SerialName("end_byte") val endByte: Long,
    @SerialName("start_line") val startLine: Int,
    @SerialName("end_line") val endLine: Int,
    val prefix: String,
    val suffix: String,
)

@Serializable
enum class SignalType {
    @SerialName("note")
    NOTE,

    @SerialName("change_required")
    CHANGE_REQUIRED,

    @SerialName("warning")
    WARNING,

    @SerialName("review")
    REVIEW,
}
