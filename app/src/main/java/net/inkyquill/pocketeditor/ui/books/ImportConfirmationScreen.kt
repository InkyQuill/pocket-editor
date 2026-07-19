package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ImportConfirmationScreen(
    draft: ImportDraft,
    importing: Boolean,
    onDraftChanged: (ImportDraft) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().widthIn(max = 920.dp).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                IconButton(enabled = !importing, onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to folder browser")
                }
                Column(Modifier.weight(1f)) {
                    Text("Review this book", style = MaterialTheme.typography.titleLarge)
                    Text("Nothing will be created until you confirm", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = draft.title,
                enabled = !importing,
                onValueChange = { onDraftChanged(draft.copy(title = it)) },
                label = { Text("Book title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Text("Table of contents", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(draft.chapters, key = { _, chapter -> chapter.path }) { index, chapter ->
                    ImportChapterRow(
                        chapter = chapter,
                        index = index,
                        count = draft.chapters.size,
                        enabled = !importing,
                        onChanged = { changed ->
                            onDraftChanged(draft.copy(chapters = draft.chapters.toMutableList().apply { set(index, changed) }))
                        },
                        onMove = { offset ->
                            val target = index + offset
                            if (target in draft.chapters.indices) {
                                onDraftChanged(
                                    draft.copy(chapters = draft.chapters.toMutableList().apply { add(target, removeAt(index)) }),
                                )
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                ) {
                    Text(
                        "${draft.chapters.count(ImportChapterDraft::included)} of ${draft.chapters.size} chapters",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = !importing && draft.title.isNotBlank() && draft.chapters.any(ImportChapterDraft::included),
                        onClick = onConfirm,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        if (importing) {
                            CircularProgressIndicator(Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                            Text("Caching complete book…")
                        } else Text("Create offline book")
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportChapterRow(
    chapter: ImportChapterDraft,
    index: Int,
    count: Int,
    enabled: Boolean,
    onChanged: (ImportChapterDraft) -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Checkbox(
            checked = chapter.included,
            enabled = enabled,
            onCheckedChange = { onChanged(chapter.copy(included = it)) },
            modifier = Modifier.semantics { contentDescription = "Include ${chapter.title}" },
        )
        Column(Modifier.weight(1f)) {
            OutlinedTextField(
                value = chapter.title,
                enabled = enabled && chapter.included,
                onValueChange = { onChanged(chapter.copy(title = it)) },
                label = { Text("Chapter ${index + 1} title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(chapter.path, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            IconButton(enabled = enabled && index > 0, onClick = { onMove(-1) }) {
                Icon(Icons.Default.KeyboardArrowUp, "Move ${chapter.title} earlier")
            }
            IconButton(enabled = enabled && index < count - 1, onClick = { onMove(1) }) {
                Icon(Icons.Default.KeyboardArrowDown, "Move ${chapter.title} later")
            }
        }
    }
}
