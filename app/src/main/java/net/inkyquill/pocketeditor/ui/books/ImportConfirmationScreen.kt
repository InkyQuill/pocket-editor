package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.ui.russianPluralStringResource

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ImportConfirmationScreen(
    draft: ImportDraft,
    importing: Boolean,
    onDraftChanged: (ImportDraft) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    val includedCount = draft.chapters.count(ImportChapterDraft::included)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(enabled = !importing, onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_library))
                    }
                },
                title = { Text(stringResource(R.string.configure_book_title)) },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        russianPluralStringResource(
                            R.plurals.selected_chapters_count,
                            includedCount,
                            includedCount,
                            draft.chapters.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = !importing && draft.title.isNotBlank() && draft.chapters.any(ImportChapterDraft::included),
                        onClick = onConfirm,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-import"),
                    ) {
                        if (importing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.adding_to_library), Modifier.padding(start = 8.dp))
                        } else {
                            Text(stringResource(R.string.add_to_library))
                        }
                    }
                }
            }
        },
    ) { contentPadding ->
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(contentPadding)
                    .widthIn(max = 920.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        russianPluralStringResource(
                            R.plurals.draft_chapters_saved,
                            draft.chapters.size,
                            draft.chapters.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = draft.title,
                    enabled = !importing,
                    onValueChange = { onDraftChanged(draft.copy(title = it)) },
                    label = { Text(stringResource(R.string.book_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                error?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(message, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    stringResource(R.string.table_of_contents),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("import-chapter-list"),
                ) {
                    itemsIndexed(draft.chapters, key = { _, chapter -> chapter.path }) { index, chapter ->
                        ImportChapterRow(
                            chapter = chapter,
                            index = index,
                            count = draft.chapters.size,
                            enabled = !importing,
                            onChanged = { changed ->
                                onDraftChanged(
                                    draft.copy(
                                        chapters = draft.chapters.toMutableList().apply { set(index, changed) },
                                    ),
                                )
                            },
                            onMove = { offset ->
                                val target = index + offset
                                if (target in draft.chapters.indices) {
                                    onDraftChanged(
                                        draft.copy(
                                            chapters = draft.chapters.toMutableList().apply {
                                                add(target, removeAt(index))
                                            },
                                        ),
                                    )
                                }
                            },
                        )
                        HorizontalDivider()
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
    val includeDescription = stringResource(R.string.include_chapter, chapter.title)
    val titleDescription = stringResource(R.string.chapter_title_label, index + 1)
    val moveEarlierDescription = stringResource(R.string.move_chapter_earlier, chapter.title)
    val moveLaterDescription = stringResource(R.string.move_chapter_later, chapter.title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("import-chapter-${chapter.path}"),
    ) {
        Checkbox(
            checked = chapter.included,
            enabled = enabled,
            onCheckedChange = { onChanged(chapter.copy(included = it)) },
            modifier = Modifier.semantics { contentDescription = includeDescription },
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            BasicTextField(
                value = chapter.title,
                enabled = enabled && chapter.included,
                onValueChange = { onChanged(chapter.copy(title = it)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (chapter.included) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 28.dp)
                    .semantics { contentDescription = titleDescription },
            )
            Text(
                chapter.path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(enabled = enabled && index > 0, onClick = { onMove(-1) }) {
            Icon(Icons.Default.KeyboardArrowUp, moveEarlierDescription)
        }
        IconButton(enabled = enabled && index < count - 1, onClick = { onMove(1) }) {
            Icon(Icons.Default.KeyboardArrowDown, moveLaterDescription)
        }
    }
}
