package net.inkyquill.pocketeditor.reader

import net.inkyquill.pocketeditor.database.ReadingPositionEntity
import net.inkyquill.pocketeditor.markdown.RenderedDocument

object ReadingPositionClamp {
    fun clamp(position: ReadingPositionEntity, rendered: RenderedDocument): ReadingPositionEntity {
        val visible = rendered.blocks.filterNot { it.hidden }
        val block = visible.singleOrNull { it.index == position.blockIndex }
            ?: visible.lastOrNull { it.index <= position.blockIndex }
            ?: visible.firstOrNull()
            ?: return position.copy(blockIndex = 0, byteOffset = 0)
        return position.copy(
            blockIndex = block.index,
            byteOffset = position.byteOffset.coerceIn(block.rawRange.startByte, block.rawRange.endByte),
        )
    }
}
