package net.inkyquill.pocketeditor.markdown

object SelectionMapper {
    fun toRawRange(document: RenderedDocument, range: TextRange): RawRange? {
        val ordered = range.normalized()
        val firstPosition = document.blocks.indexOfFirst { it.index == ordered.startBlock }
        val lastPosition = document.blocks.indexOfFirst { it.index == ordered.endBlock }
        if (firstPosition < 0 || lastPosition < firstPosition) return null
        val selectedBlocks = document.blocks.subList(firstPosition, lastPosition + 1)
        if (selectedBlocks.any { it.hidden }) return null

        val first = selectedBlocks.first()
        val last = selectedBlocks.last()
        if (
            ordered.start < 0 || ordered.start > first.text.length ||
            ordered.end < 0 || ordered.end > last.text.length ||
            (first === last && ordered.start >= ordered.end)
        ) {
            return null
        }

        val firstSelectionEnd = if (first === last) ordered.end else first.text.length
        if (splitsSyntax(first, ordered.start, firstSelectionEnd)) return null
        if (first !== last && splitsSyntax(last, 0, ordered.end)) return null

        if (first === last) first.syntaxSpans
            .filter { span -> ordered.start == span.start && ordered.end == span.end }
            .maxByOrNull { span -> span.rawRange.endByte - span.rawRange.startByte }
            ?.let { return it.rawRange }

        val startByte = first.byteBoundaries.getOrNull(ordered.start)?.takeIf { it >= 0 } ?: return null
        val endByte = last.byteBoundaries.getOrNull(ordered.end)?.takeIf { it >= 0 } ?: return null
        if (startByte >= endByte) return null
        return RawRange(startByte, endByte)
    }

    private fun splitsSyntax(block: RenderedBlock, start: Int, end: Int): Boolean =
        block.syntaxSpans.any { span ->
            start < span.end && span.start < end &&
                !(start <= span.start && end >= span.end)
        }
}
