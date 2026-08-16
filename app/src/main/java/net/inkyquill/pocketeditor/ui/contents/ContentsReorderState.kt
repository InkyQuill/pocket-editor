package net.inkyquill.pocketeditor.ui.contents

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

@Stable
class ContentsReorderState private constructor(
    originalIds: List<String>,
    initialIds: List<String>,
) {
    private var originalIds by mutableStateOf(originalIds)

    var orderedChapterIds by mutableStateOf(initialIds)
        private set

    val expectedOriginalChapterIds: List<String> get() = originalIds
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

    fun reconcileCanonical(canonicalIds: List<String>) {
        require(canonicalIds.distinct().size == canonicalIds.size)
        if (canonicalIds == originalIds) return
        val hadDraftChanges = changed
        val reconciled = if (hadDraftChanges) {
            reconcileOrder(orderedChapterIds, canonicalIds)
        } else {
            canonicalIds.toList()
        }
        originalIds = canonicalIds.toList()
        orderedChapterIds = reconciled
    }

    fun orderForSave(canonicalIds: List<String>): List<String>? = orderedChapterIds
        .takeIf { canonicalIds == originalIds }
        ?.toList()

    companion object {
        fun create(ids: List<String>): ContentsReorderState {
            require(ids.isNotEmpty() && ids.distinct().size == ids.size)
            return ContentsReorderState(ids.toList(), ids.toList())
        }

        fun saver(originalIds: List<String>) = Saver<ContentsReorderState, ArrayList<String>>(
            save = { ArrayList(it.orderedChapterIds) },
            restore = { restored ->
                restored
                    .takeIf { it.isNotEmpty() && it.distinct().size == it.size }
                    ?.let {
                        ContentsReorderState(
                            originalIds = originalIds.toList(),
                            initialIds = reconcileOrder(it, originalIds),
                        )
                    }
            },
        )

        private fun reconcileOrder(draftIds: List<String>, canonicalIds: List<String>): List<String> {
            val canonicalSet = canonicalIds.toSet()
            val survivingDraft = draftIds.filter(canonicalSet::contains)
            val draftSet = survivingDraft.toSet()
            return survivingDraft + canonicalIds.filterNot(draftSet::contains)
        }
    }
}
