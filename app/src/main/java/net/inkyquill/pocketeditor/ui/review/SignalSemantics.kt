package net.inkyquill.pocketeditor.ui.review

import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.theme.ReviewColors

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

fun ReviewColors.signalColor(type: SignalType) = when (type) {
    SignalType.NOTE -> note
    SignalType.CHANGE_REQUIRED -> changeNeeded
    SignalType.WARNING -> warning
    SignalType.REVIEW -> review
}
