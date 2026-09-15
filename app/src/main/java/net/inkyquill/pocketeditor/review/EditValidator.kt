package net.inkyquill.pocketeditor.review

import net.inkyquill.pocketeditor.anchor.sha256

object EditValidator {
    fun validate(edit: Edit, existing: List<Edit>, source: ByteArray) {
        require(edit.before.isNotEmpty()) { "Edit before text must not be empty" }
        require(edit.after != edit.before) { "Edit after text must differ from before text" }
        require(edit.anchor.sourceSha256 == source.sha256()) {
            "Edit anchor source does not match the current source"
        }

        val start = edit.anchor.startByte.toIntOffsetOrNull()
        val end = edit.anchor.endByte.toIntOffsetOrNull()
        require(start != null && end != null && start >= 0 && end > start && end <= source.size) {
            "Edit anchor range is outside the current source"
        }

        val before = edit.before.encodeToByteArray()
        require(end - start == before.size && source.matchesAt(before, start)) {
            "Edit before text does not match the anchored source bytes"
        }
        require(edit.anchor.selectionSha256 == before.sha256()) {
            "Edit before text does not match the anchor selection hash"
        }

        val existingDocument = ReviewDocument(
            chapterId = "00000000-0000-4000-8000-000000000000",
            sourcePath = "validation.md", edits = existing.filterNot { it.id == edit.id })
        val active = classifyReview(source, existingDocument).activeEdits.values
        require(active.none { edit.anchor.startByte < it.endByte && it.startByte < edit.anchor.endByte }) {
            "Edit source ranges must not overlap active edits"
        }
    }

    private fun Long.toIntOffsetOrNull(): Int? =
        if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null

    private fun ByteArray.matchesAt(value: ByteArray, offset: Int): Boolean =
        offset >= 0 && offset + value.size <= size && value.indices.all { index ->
            this[offset + index] == value[index]
        }
}
