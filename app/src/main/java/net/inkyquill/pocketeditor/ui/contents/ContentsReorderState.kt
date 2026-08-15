package net.inkyquill.pocketeditor.ui.contents

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

@Stable
class ContentsReorderState private constructor(
    private val originalIds: List<String>,
    initialIds: List<String>,
) {
    var orderedChapterIds by mutableStateOf(initialIds)
        private set

    val changed: Boolean get() = orderedChapterIds != originalIds

    fun move(fromIndex: Int, toIndex: Int) {
        require(fromIndex in orderedChapterIds.indices && toIndex in orderedChapterIds.indices)
        if (fromIndex == toIndex) return
        orderedChapterIds = orderedChapterIds.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
    }

    fun cancel() {
        orderedChapterIds = originalIds
    }

    companion object {
        fun create(ids: List<String>): ContentsReorderState {
            require(ids.isNotEmpty() && ids.distinct().size == ids.size)
            return ContentsReorderState(ids.toList(), ids.toList())
        }

        fun saver(originalIds: List<String>) = Saver<ContentsReorderState, ArrayList<String>>(
            save = { ArrayList(it.orderedChapterIds) },
            restore = { restored ->
                restored
                    .takeIf { it.toSet() == originalIds.toSet() && it.size == originalIds.size }
                    ?.let { ContentsReorderState(originalIds.toList(), it.toList()) }
            },
        )
    }
}
