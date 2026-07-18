package net.inkyquill.pocketeditor.review

enum class DiffKind {
    UNCHANGED,
    DELETED,
    ADDED,
}

data class DiffRun(val kind: DiffKind, val text: String)

object EditDiff {
    fun compute(before: String, after: String): List<DiffRun> {
        val beforePoints = before.codePoints().toArray()
        val afterPoints = after.codePoints().toArray()

        val commonPrefix = commonPrefixLength(beforePoints, afterPoints)
        val commonSuffix = commonSuffixLength(beforePoints, afterPoints, commonPrefix)

        return buildList {
            addRun(DiffKind.UNCHANGED, beforePoints.sliceText(0, commonPrefix))
            addRun(
                DiffKind.DELETED,
                beforePoints.sliceText(commonPrefix, beforePoints.size - commonSuffix),
            )
            addRun(
                DiffKind.ADDED,
                afterPoints.sliceText(commonPrefix, afterPoints.size - commonSuffix),
            )
            addRun(
                DiffKind.UNCHANGED,
                beforePoints.sliceText(beforePoints.size - commonSuffix, beforePoints.size),
            )
        }
    }

    private fun commonPrefixLength(left: IntArray, right: IntArray): Int {
        val limit = minOf(left.size, right.size)
        return (0 until limit).firstOrNull { left[it] != right[it] } ?: limit
    }

    private fun commonSuffixLength(left: IntArray, right: IntArray, prefixLength: Int): Int {
        val limit = minOf(left.size, right.size) - prefixLength
        return (0 until limit).firstOrNull { offset ->
            left[left.lastIndex - offset] != right[right.lastIndex - offset]
        } ?: limit
    }

    private fun MutableList<DiffRun>.addRun(kind: DiffKind, text: String) {
        if (text.isNotEmpty()) add(DiffRun(kind, text))
    }

    private fun IntArray.sliceText(start: Int, end: Int): String =
        String(this, start, end - start)
}
