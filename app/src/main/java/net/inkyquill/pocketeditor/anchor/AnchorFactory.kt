package net.inkyquill.pocketeditor.anchor

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import net.inkyquill.pocketeditor.review.Anchor

object AnchorFactory {
    fun create(source: ByteArray, startByte: Int, endByte: Int): Anchor {
        require(startByte >= 0 && endByte > startByte && endByte <= source.size) {
            "Anchor byte range must be non-empty, non-negative, and within the source"
        }

        source.decodeUtf8Strict()
        val prefixText = source.copyOfRange(0, startByte).decodeUtf8StrictRange()
        val selection = source.copyOfRange(startByte, endByte)
        selection.decodeUtf8StrictRange()
        val suffixText = source.copyOfRange(endByte, source.size).decodeUtf8StrictRange()

        return Anchor(
            sourceSha256 = source.sha256(),
            selectionSha256 = selection.sha256(),
            startByte = startByte.toLong(),
            endByte = endByte.toLong(),
            startLine = source.lineAtByteOffset(startByte),
            endLine = source.lineAtByteOffset(endByte - 1),
            prefix = prefixText.takeLastCodePoints(MAX_CONTEXT_CODE_POINTS),
            suffix = suffixText.takeCodePoints(MAX_CONTEXT_CODE_POINTS),
        )
    }

    private fun ByteArray.lineAtByteOffset(byteOffset: Int): Int =
        copyOfRange(0, byteOffset).count { it == NEWLINE_BYTE } + 1

    private fun String.takeCodePoints(count: Int): String {
        val end = offsetByCodePoints(0, codePointCount(0, length).coerceAtMost(count))
        return substring(0, end)
    }

    private fun String.takeLastCodePoints(count: Int): String {
        val total = codePointCount(0, length)
        val start = offsetByCodePoints(0, (total - count).coerceAtLeast(0))
        return substring(start)
    }

    private const val MAX_CONTEXT_CODE_POINTS = 128
    private const val NEWLINE_BYTE: Byte = 0x0A
}

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun ByteArray.decodeUtf8Strict(): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()

private fun ByteArray.decodeUtf8StrictRange(): String =
    try {
        decodeUtf8Strict()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Anchor byte offsets must align to UTF-8 code-point boundaries")
    }
