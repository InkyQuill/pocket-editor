package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.review.SignalType

val SignalType.label: String
    get() = when (this) {
        SignalType.NOTE -> "Note"
        SignalType.CHANGE_REQUIRED -> "Change required"
        SignalType.WARNING -> "Warning"
        SignalType.REVIEW -> "Review"
    }

val SignalType.help: String
    get() = when (this) {
        SignalType.NOTE -> "Something to keep in mind"
        SignalType.CHANGE_REQUIRED -> "This passage needs changing"
        SignalType.WARNING -> "Something seems strange or puzzling"
        SignalType.REVIEW -> "Recheck this passage on a hunch"
    }
