package net.inkyquill.pocketeditor.search

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.markdown.SelectionMapper
import net.inkyquill.pocketeditor.markdown.TextRange

data class SearchChapterSource(
    val chapterId: String,
    val title: String,
    val sourceBytes: ByteArray,
)

class SourceSearch(private val dao: SearchDao) {
    fun query(bookId: String, query: String): Flow<List<SearchHit>> {
        val needle = query.trim()
        if (needle.isEmpty()) return flowOf(emptyList())
        val match = "\"${needle.replace("\"", "\"\"")}\""
        return dao.query(bookId, match).map { rows ->
            rows.flatMap { row -> row.toHits(needle) }
        }
    }

    suspend fun replaceChapter(
        bookId: String,
        chapterId: String,
        title: String,
        sourceBytes: ByteArray,
    ) {
        dao.replaceChapter(bookId, chapterId, buildRows(bookId, SearchChapterSource(chapterId, title, sourceBytes)))
    }

    suspend fun rebuildBook(bookId: String, chapters: List<SearchChapterSource>) {
        val rows = chapters.flatMap { buildRows(bookId, it) }
        dao.replaceBook(bookId, rows)
    }

    suspend fun clearBook(bookId: String) = dao.deleteBook(bookId)

    private fun buildRows(bookId: String, chapter: SearchChapterSource): List<SearchEntity> {
        val source = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(chapter.sourceBytes))
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
                    chapterId = chapter.chapterId,
                    title = chapter.title,
                    content = block.text,
                    rawBoundaries = boundaries.joinToString(","),
                )
            }
            .toList()
        return rows
    }

    private fun SearchEntity.toHits(needle: String): List<SearchHit> {
        val normalizedContent = normalizeWithMap(content)
        val normalizedNeedle = normalizeForSearch(needle)
        if (normalizedNeedle.isEmpty()) return emptyList()
        val boundaries = rawBoundaries.split(',').mapNotNull(String::toIntOrNull)
        return buildList {
            var cursor = 0
            while (cursor <= normalizedContent.text.length - normalizedNeedle.length) {
                val match = normalizedContent.text.indexOf(normalizedNeedle, cursor)
                if (match < 0) break
                val start = normalizedContent.originalBoundaries[match]
                val end = normalizedContent.originalBoundaries[match + normalizedNeedle.length]
                val rawStart = boundaries.getOrNull(start)?.takeIf { it >= 0 }
                val rawEnd = boundaries.getOrNull(end)?.takeIf { rawStart != null && it >= rawStart }
                if (rawStart != null && rawEnd != null) {
                    add(SearchHit(chapterId, title, excerpt(content, start, end), rawStart, rawEnd))
                }
                cursor = match + normalizedNeedle.length.coerceAtLeast(1)
            }
        }
    }

    private fun excerpt(content: String, start: Int, end: Int): String {
        val beforeCount = content.codePointCount(0, start).coerceAtMost(EXCERPT_CONTEXT)
        val afterCount = content.codePointCount(end, content.length).coerceAtMost(EXCERPT_CONTEXT)
        val excerptStart = content.offsetByCodePoints(start, -beforeCount)
        val excerptEnd = content.offsetByCodePoints(end, afterCount)
        return buildString {
            if (excerptStart > 0) append('…')
            append(content.substring(excerptStart, excerptEnd))
            if (excerptEnd < content.length) append('…')
        }
    }

    private fun normalizeWithMap(value: String): NormalizedText {
        val normalized = StringBuilder()
        val boundaries = mutableListOf(0)
        var offset = 0
        while (offset < value.length) {
            val next = offset + Character.charCount(value.codePointAt(offset))
            val piece = normalizeForSearch(value.substring(offset, next))
            if (piece.isEmpty()) {
                boundaries[boundaries.lastIndex] = next
            } else {
                normalized.append(piece)
                repeat(piece.length) { index -> boundaries += if (index == piece.lastIndex) next else offset }
            }
            offset = next
        }
        return NormalizedText(normalized.toString(), boundaries.toIntArray())
    }

    private fun normalizeForSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)

    private data class NormalizedText(val text: String, val originalBoundaries: IntArray)

    private companion object {
        const val INVALID_BOUNDARY = -1
        const val EXCERPT_CONTEXT = 48
    }
}
