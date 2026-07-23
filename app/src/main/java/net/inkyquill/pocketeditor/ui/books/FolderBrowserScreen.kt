package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.ui.russianPluralStringResource

@Composable
fun FolderBrowserScreen(
    listing: FolderListing?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onChooseThisFolder: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosingFolder by rememberSaveable(listing?.path) { mutableStateOf(false) }
    LaunchedEffect(error) {
        if (error != null) choosingFolder = false
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .widthIn(max = 900.dp)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_books)) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.choose_a_book_folder), style = MaterialTheme.typography.titleLarge)
                    Text(
                        listing?.path?.substringAfter("disk:")?.ifBlank { "/" } ?: stringResource(R.string.yandex_disk),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                loading -> {
                    val loadingDescription = stringResource(R.string.loading_yandex_disk_folders)
                    Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = loadingDescription },
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.loading_folders), modifier = Modifier.padding(top = 16.dp))
                }
                }
                error != null -> BrowserMessage(
                    stringResource(R.string.folder_open_failed),
                    stringResource(R.string.cached_books_unaffected, error),
                    stringResource(R.string.try_again),
                    onRetry,
                )
                listing == null -> BrowserMessage(
                    stringResource(R.string.no_folder_selected),
                    stringResource(R.string.open_yandex_disk_to_browse),
                    stringResource(R.string.open_disk),
                    onRetry,
                )
                else -> {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stringResource(R.string.this_folder), style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (listing.markdown.isEmpty()) stringResource(R.string.no_markdown_files)
                                else russianPluralStringResource(
                                    R.plurals.markdown_chapters_found,
                                    listing.markdown.size,
                                    listing.markdown.size,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                            )
                            if (listing.markdown.size > 8) {
                                Text(
                                    stringResource(R.string.more_files, listing.markdown.size - 8),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            if (listing.otherFiles > 0) {
                                Text(
                                    stringResource(R.string.other_files, listing.otherFiles),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            Button(
                                enabled = listing.markdown.isNotEmpty() && !choosingFolder,
                                onClick = { choosingFolder = true; onChooseThisFolder() },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                if (choosingFolder) {
                                    val readingDescription = stringResource(R.string.reading_selected_folder)
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp).semantics { contentDescription = readingDescription },
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.widthIn(min = 8.dp))
                                    Text(stringResource(R.string.reading_files))
                                } else {
                                    Text(stringResource(R.string.use_this_folder))
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        item {
                            Text(stringResource(R.string.folders), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                        }
                        if (listing.folders.isEmpty()) {
                            item {
                                Text(stringResource(R.string.no_subfolders), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp))
                            }
                        } else {
                            items(listing.folders, key = RemoteFolder::path) { folder ->
                                ListItem(
                                    headlineContent = { Text(folder.name) },
                                    leadingContent = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpenFolder(folder.path) },
                                )
                                HorizontalDivider()
                            }
                        }
                        item {
                            Text(stringResource(R.string.markdown_chapters), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                        }
                        items(listing.markdown.take(8), key = { it }) { filename ->
                            ListItem(headlineContent = { Text(filename) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserMessage(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(action) }
    }
}
