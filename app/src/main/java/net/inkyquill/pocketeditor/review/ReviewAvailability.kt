package net.inkyquill.pocketeditor.review

import net.inkyquill.pocketeditor.anchor.AnchorResolution
import net.inkyquill.pocketeditor.anchor.AnchorResolver
import net.inkyquill.pocketeditor.anchor.Resolved

data class ReviewAvailability(
    val activeEdits: Map<String, Resolved>,
    val activeSignals: Map<String, Resolved>,
    val unavailable: Map<String, AnchorResolution>,
    val conflictingEdits: Set<String>,
)

fun classifyReview(source: ByteArray, review: ReviewDocument): ReviewAvailability {
    val signalLocations = linkedMapOf<String, Resolved>()
    val unavailable = linkedMapOf<String, AnchorResolution>()
    review.signals.forEach { signal ->
        when (val resolution = AnchorResolver.resolve(source, signal.anchor, signal.selectedText)) {
            is Resolved -> signalLocations[signal.id] = resolution
            else -> unavailable[signal.id] = resolution
        }
    }

    val editLocations = linkedMapOf<String, Resolved>()
    review.edits.forEach { edit ->
        when (val resolution = AnchorResolver.resolve(source, edit.anchor, edit.before)) {
            is Resolved -> editLocations[edit.id] = resolution
            else -> unavailable[edit.id] = resolution
        }
    }

    val conflicting = mutableSetOf<String>()
    val resolved = editLocations.entries.toList()
    for (i in resolved.indices) {
        for (j in i + 1 until resolved.size) {
            val a = resolved[i]
            val b = resolved[j]
            if (a.value.startByte < b.value.endByte && b.value.startByte < a.value.endByte) {
                conflicting += a.key
                conflicting += b.key
            }
        }
    }

    return ReviewAvailability(
        activeEdits = editLocations.filterKeys { it !in conflicting },
        activeSignals = signalLocations,
        unavailable = unavailable,
        conflictingEdits = conflicting,
    )
}
