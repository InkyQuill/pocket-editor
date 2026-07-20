package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
        Column(Modifier.fillMaxSize().widthIn(max = 900.dp).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to books") }
                Column(Modifier.weight(1f)) {
                    Text("Choose a book folder", style = MaterialTheme.typography.titleLarge)
                    Text(
                        listing?.path?.substringAfter("disk:")?.ifBlank { "/" } ?: "Yandex Disk",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Loading Yandex Disk folders" },
                ) {
                    CircularProgressIndicator()
                    Text("Loading folders…", modifier = Modifier.padding(top = 16.dp))
                }
                error != null -> BrowserMessage("Couldn’t open this folder", "Your cached books are unaffected. $error", "Try again", onRetry)
                listing == null -> BrowserMessage("No folder selected", "Open Yandex Disk to browse your folders.", "Open Disk", onRetry)
                else -> {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("This folder", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (listing.markdown.isEmpty()) "No Markdown files in this folder"
                                else "${listing.markdown.size} Markdown ${if (listing.markdown.size == 1) "chapter" else "chapters"} found. You’ll review them next.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                            )
                            if (listing.markdown.size > 8) {
                                Text(
                                    "+${listing.markdown.size - 8} more",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            if (listing.otherFiles > 0) {
                                Text(
                                    "Other files · ${listing.otherFiles}",
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
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp).semantics { contentDescription = "Reading selected folder" },
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.widthIn(min = 8.dp))
                                    Text("Reading files…")
                                } else {
                                    Text("Use this folder")
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        item {
                            Text("Folders", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                        }
                        if (listing.folders.isEmpty()) {
                            item {
                                Text("No subfolders", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp))
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
                            Text("Markdown chapters", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
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
