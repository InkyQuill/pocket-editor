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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
fun ChapterNote(
    text: String,
    status: NoteSaveStatus,
    onTextChange: (String) -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var localText by remember(text) { mutableStateOf(text) }
    Column(modifier) {
        OutlinedTextField(
            value = localText,
            onValueChange = { localText = it; onTextChange(it) },
            label = { Text("Chapter note") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
                .testTag("chapter-note")
                .semantics { contentDescription = "Chapter note" }
                .onFocusChanged { if (!it.isFocused) onFocusLost() },
        )
        val statusLabel = when (status) {
            NoteSaveStatus.SAVED -> "Saved"
            NoteSaveStatus.SAVING -> "Saving"
            NoteSaveStatus.WAITING -> "Waiting to sync"
            NoteSaveStatus.ERROR -> "Save failed — retry"
        }
        Text(
            statusLabel,
            modifier = Modifier.clearAndSetSemantics { contentDescription = "Chapter note: $statusLabel" },
        )
    }
}
