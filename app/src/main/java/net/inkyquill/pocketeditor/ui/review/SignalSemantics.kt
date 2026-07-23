package net.inkyquill.pocketeditor.ui.review

import androidx.annotation.StringRes
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.theme.ReviewColors

@get:StringRes
val SignalType.labelResource: Int
    get() = when (this) {
        SignalType.NOTE -> R.string.signal_note
        SignalType.CHANGE_REQUIRED -> R.string.signal_change_required
        SignalType.WARNING -> R.string.signal_warning
        SignalType.REVIEW -> R.string.signal_review
    }

@get:StringRes
val SignalType.helpResource: Int
    get() = when (this) {
        SignalType.NOTE -> R.string.signal_note_help
        SignalType.CHANGE_REQUIRED -> R.string.signal_change_required_help
        SignalType.WARNING -> R.string.signal_warning_help
        SignalType.REVIEW -> R.string.signal_review_help
    }

fun ReviewColors.signalColor(type: SignalType) = when (type) {
    SignalType.NOTE -> note
    SignalType.CHANGE_REQUIRED -> changeNeeded
    SignalType.WARNING -> warning
    SignalType.REVIEW -> review
}
