package net.inkyquill.pocketeditor.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import net.inkyquill.pocketeditor.R

@Composable
fun ChapterNote(
    text: String,
    status: NoteSaveStatus,
    onTextChange: (String) -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var localText by remember(text) { mutableStateOf(text) }
    var wasFocused by remember { mutableStateOf(false) }
    val chapterNoteDescription = stringResource(R.string.chapter_note)
    Column(modifier) {
        OutlinedTextField(
            value = localText,
            onValueChange = { localText = it; onTextChange(it) },
            label = { Text(stringResource(R.string.chapter_note)) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
                .testTag("chapter-note")
                .semantics { contentDescription = chapterNoteDescription }
                .onFocusChanged { focus ->
                    if (focus.isFocused) {
                        wasFocused = true
                    } else if (wasFocused) {
                        wasFocused = false
                        onFocusLost()
                    }
                },
        )
        val statusLabel = when (status) {
            NoteSaveStatus.SAVED -> stringResource(R.string.saved)
            NoteSaveStatus.SAVING -> stringResource(R.string.saving)
            NoteSaveStatus.WAITING -> stringResource(R.string.waiting_to_sync)
            NoteSaveStatus.ERROR -> stringResource(R.string.save_failed_retry)
        }
        val statusDescription = stringResource(R.string.chapter_note_status, statusLabel)
        Text(
            statusLabel,
            modifier = Modifier.clearAndSetSemantics { contentDescription = statusDescription },
        )
    }
}
