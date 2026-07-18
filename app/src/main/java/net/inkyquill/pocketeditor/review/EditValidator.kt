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

        existing.asSequence()
            .filterNot { it.id == edit.id }
            .forEach { other ->
                require(!rangesIntersect(edit.anchor, other.anchor)) {
                    "Edit source ranges must not overlap"
                }
            }
    }

    private fun Long.toIntOffsetOrNull(): Int? =
        if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null

    private fun ByteArray.matchesAt(value: ByteArray, offset: Int): Boolean =
        offset >= 0 && offset + value.size <= size && value.indices.all { index ->
            this[offset + index] == value[index]
        }

    private fun rangesIntersect(left: Anchor, right: Anchor): Boolean =
        left.startByte < right.endByte && right.startByte < left.endByte
}
