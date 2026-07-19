package net.inkyquill.pocketeditor.search

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.markdown.SelectionMapper
import net.inkyquill.pocketeditor.markdown.TextRange

class SourceSearch(private val dao: SearchDao) {
    fun query(bookId: String, query: String): Flow<List<SearchHit>> {
        val needle = query.trim()
        if (needle.isEmpty()) return flowOf(emptyList())
        val match = "\"${needle.replace("\"", "\"\"")}\""
        return dao.query(bookId, match).map { rows ->
            rows.mapNotNull { row -> row.toHit(needle) }
        }
    }

    suspend fun replaceChapter(
        bookId: String,
        chapterId: String,
        title: String,
        sourceBytes: ByteArray,
    ) {
        val source = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sourceBytes))
                .toString()
        }.getOrElse { throw IllegalArgumentException("Canonical source must be valid UTF-8", it) }
        val rendered = MarkdownParser.parse(source)
        val rows = rendered.blocks
            .asSequence()
            .filterNot { it.hidden || it.text.isBlank() }
            .map { block ->
                val boundaries = IntArray(block.text.length + 1) { INVALID_BOUNDARY }
                var offset = 0
                while (offset < block.text.length) {
                    val next = offset + Character.charCount(block.text.codePointAt(offset))
                    SelectionMapper.toRawRange(rendered, TextRange(block.index, offset, next))?.let { raw ->
                        boundaries[offset] = raw.startByte
                        boundaries[next] = raw.endByte
                    }
                    offset = next
                }
                SearchEntity(
                    bookId = bookId,
                    chapterId = chapterId,
                    title = title,
                    content = block.text,
                    rawBoundaries = boundaries.joinToString(","),
                )
            }
            .toList()
        dao.replaceChapter(bookId, chapterId, rows)
    }

    private fun SearchEntity.toHit(needle: String): SearchHit? {
        val start = content.indexOf(needle, ignoreCase = true).takeIf { it >= 0 } ?: return null
        val end = start + needle.length
        val boundaries = rawBoundaries.split(',').mapNotNull(String::toIntOrNull)
        val rawStart = boundaries.getOrNull(start)?.takeIf { it >= 0 } ?: return null
        val rawEnd = boundaries.getOrNull(end)?.takeIf { it >= rawStart } ?: return null
        return SearchHit(
            chapterId = chapterId,
            title = title,
            excerpt = excerpt(content, start, end),
            rawStartByte = rawStart,
            rawEndByte = rawEnd,
        )
    }

    private fun excerpt(content: String, start: Int, end: Int): String {
        val excerptStart = (start - EXCERPT_CONTEXT).coerceAtLeast(0)
        val excerptEnd = (end + EXCERPT_CONTEXT).coerceAtMost(content.length)
        return buildString {
            if (excerptStart > 0) append('…')
            append(content.substring(excerptStart, excerptEnd))
            if (excerptEnd < content.length) append('…')
        }
    }

    private companion object {
        const val INVALID_BOUNDARY = -1
        const val EXCERPT_CONTEXT = 48
    }
}
