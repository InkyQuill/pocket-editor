package net.inkyquill.pocketeditor.ui.contents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.ui.books.DiscoveryNotice

@Composable
fun DiscoveryPanel(
    notices: List<DiscoveryNotice>,
    onAdd: (path: String, title: String, position: Int) -> Unit,
    onIgnore: (path: String) -> Unit,
    onUpdateRenamed: (chapterId: String, path: String) -> Unit,
    onLocateMissing: (chapterId: String, path: String) -> Unit,
    onRemoveMissing: (chapterId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notices.isEmpty()) return
    var addDraft by remember { mutableStateOf<DiscoveryNotice.NewFile?>(null) }
    var removeDraft by remember { mutableStateOf<DiscoveryNotice.MissingFile?>(null) }
    var locateDraft by remember { mutableStateOf<DiscoveryNotice.MissingFile?>(null) }

    Column(modifier) {
        Text(stringResource(R.string.book_updates), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.book_updates_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 8.dp).testTag("discovery-notices"),
        ) {
            items(notices, key = { notice -> notice.key() }) { notice ->
                when (notice) {
                    is DiscoveryNotice.NewFile -> NewFileCard(notice, { addDraft = notice }, onIgnore)
                    is DiscoveryNotice.MissingFile -> MissingFileCard(
                        notice,
                        onUpdateRenamed,
                        onLocate = { locateDraft = notice },
                        onRemove = { removeDraft = notice },
                    )
                }
            }
        }
    }

    addDraft?.let { draft ->
        AddChapterDialog(draft, onDismiss = { addDraft = null }) { title, position ->
            addDraft = null
            onAdd(draft.path, title, position)
        }
    }
    removeDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { removeDraft = null },
            title = {
                Text(
                    stringResource(
                        R.string.remove_chapter_from_book_title,
                        draft.chapterTitle ?: stringResource(R.string.chapter_title_unavailable),
                    ),
                )
            },
            text = { Text(stringResource(R.string.remove_chapter_explanation)) },
            confirmButton = {
                Button(onClick = { removeDraft = null; onRemoveMissing(draft.chapterId) }) { Text(stringResource(R.string.remove_from_book)) }
            },
            dismissButton = { TextButton(onClick = { removeDraft = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    locateDraft?.let { draft ->
        LocateChapterDialog(draft, onDismiss = { locateDraft = null }) { path ->
            locateDraft = null
            onLocateMissing(draft.chapterId, path)
        }
    }
}

@Composable
private fun NewFileCard(notice: DiscoveryNotice.NewFile, onAdd: () -> Unit, onIgnore: (String) -> Unit) {
    val addDescription = stringResource(R.string.add_file_to_book, notice.path)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.new_chapter_found), style = MaterialTheme.typography.titleSmall)
            Text(notice.path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = onAdd,
                    modifier = Modifier.semantics { contentDescription = addDescription },
                ) { Text(stringResource(R.string.add)) }
                TextButton(onClick = { onIgnore(notice.path) }) { Text(stringResource(R.string.ignore)) }
            }
        }
    }
}

@Composable
private fun MissingFileCard(
    notice: DiscoveryNotice.MissingFile,
    onUpdateRenamed: (String, String) -> Unit,
    onLocate: () -> Unit,
    onRemove: () -> Unit,
) {
    val chapterTitle = notice.chapterTitle ?: stringResource(R.string.chapter_title_unavailable)
    val updatePathDescription = notice.sameHashRenamePath?.let {
        stringResource(R.string.update_chapter_path_description, chapterTitle, it)
    }
    val locateDescription = stringResource(R.string.locate_missing_chapter, chapterTitle)
    val removeDescription = stringResource(R.string.remove_chapter_without_remote_delete, chapterTitle)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.chapter_file_missing), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.missing_chapter_details, chapterTitle, notice.previousPath),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notice.sameHashRenamePath?.let { candidate ->
                Button(
                    onClick = { onUpdateRenamed(notice.chapterId, candidate) },
                    modifier = Modifier.padding(top = 8.dp).semantics {
                        contentDescription = requireNotNull(updatePathDescription)
                    },
                ) { Text(stringResource(R.string.update_path_to, candidate)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = onLocate,
                    modifier = Modifier.semantics { contentDescription = locateDescription },
                ) { Text(stringResource(R.string.locate_another_file)) }
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.semantics {
                        contentDescription = removeDescription
                    },
                ) { Text(stringResource(R.string.remove)) }
            }
        }
    }
}

@Composable
private fun LocateChapterDialog(
    notice: DiscoveryNotice.MissingFile,
    onDismiss: () -> Unit,
    onConfirm: (path: String) -> Unit,
) {
    val chapterTitle = notice.chapterTitle ?: stringResource(R.string.chapter_title_unavailable)
    var path by rememberSaveable(notice.chapterId) { mutableStateOf(notice.sameHashRenamePath.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.locate_chapter, chapterTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.enter_markdown_filename))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.markdown_filename)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(enabled = path.isNotBlank(), onClick = { onConfirm(path.trim()) }) { Text(stringResource(R.string.use_located_file)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AddChapterDialog(
    notice: DiscoveryNotice.NewFile,
    onDismiss: () -> Unit,
    onConfirm: (title: String, position: Int) -> Unit,
) {
    var title by rememberSaveable(notice.path) { mutableStateOf(notice.suggestedTitle) }
    var position by rememberSaveable(notice.path) { mutableStateOf((notice.suggestedPosition + 1).toString()) }
    val positionIndex = position.toIntOrNull()?.minus(1)
    val confirmDescription = stringResource(R.string.confirm_add_chapter)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_chapter)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(notice.path, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.chapter_title)) }, singleLine = true)
                OutlinedTextField(
                    position,
                    { position = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.toc_position, notice.maxPosition + 1)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && positionIndex != null && positionIndex in 0..notice.maxPosition,
                onClick = { onConfirm(title.trim(), requireNotNull(positionIndex)) },
                modifier = Modifier.semantics { contentDescription = confirmDescription },
            ) { Text(stringResource(R.string.add_chapter)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun DiscoveryNotice.key(): String = when (this) {
    is DiscoveryNotice.NewFile -> "new:$path"
    is DiscoveryNotice.MissingFile -> "missing:$chapterId"
}
