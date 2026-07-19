package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BooksScreen(
    books: List<BookSummary>,
    signedIn: Boolean,
    signingIn: Boolean,
    forgetBookId: String?,
    onSignIn: () -> Unit,
    onAddBook: () -> Unit,
    onOpenBook: (String) -> Unit,
    onRequestForget: (String) -> Unit,
    onConfirmForget: () -> Unit,
    onCancelForget: () -> Unit,
    onAppearance: () -> Unit,
    signInError: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize().widthIn(max = 920.dp).padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Pocket Editor", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                        Text(
                            "Your offline story shelf",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    IconButton(onClick = onAppearance, modifier = Modifier.semantics { contentDescription = "Appearance" }) {
                        Icon(Icons.Default.Settings, null)
                    }
                }
                Spacer(Modifier.height(28.dp))
                if (!signedIn) {
                    SignInCard(signingIn, signInError, onSignIn)
                    Spacer(Modifier.height(20.dp))
                }
                if (books.isEmpty()) {
                    EmptyBooks(signedIn, onAddBook, Modifier.weight(1f))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Books", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        FilledTonalButton(enabled = signedIn, onClick = onAddBook) {
                            Icon(Icons.Default.Add, null)
                            Text("Add book", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp),
                    ) {
                        items(books, key = BookSummary::bookId) { book ->
                            BookCard(book, { onOpenBook(book.bookId) }, { onRequestForget(book.bookId) })
                        }
                    }
                }
            }
        }
    }
    forgetBookId?.let { id ->
        val title = books.singleOrNull { it.bookId == id }?.title ?: "this book"
        AlertDialog(
            onDismissRequest = onCancelForget,
            title = { Text("Forget $title?") },
            text = { Text("This removes only Pocket Editor’s local registration and cache. Nothing on Yandex Disk will be deleted.") },
            confirmButton = { Button(onClick = onConfirmForget) { Text("Forget local copy") } },
            dismissButton = { TextButton(onClick = onCancelForget) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SignInCard(signingIn: Boolean, error: String?, onSignIn: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Connect Yandex Disk", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Sign in to add folders or synchronize. Cached books stay readable while signed out.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                error?.let {
                    Text(
                        "Could not sign in: $it",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Button(enabled = !signingIn, onClick = onSignIn) {
                if (signingIn) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (error == null) "Sign in" else "Retry sign in")
            }
        }
    }
}

@Composable
private fun EmptyBooks(signedIn: Boolean, onAddBook: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth().padding(28.dp),
    ) {
        Text("A quiet place for your stories", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (signedIn) "Choose a Yandex Disk folder containing Markdown chapters. You’ll confirm the table of contents before anything is created."
            else "Sign in to choose your first book folder. Once cached, the whole book works offline.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp).widthIn(max = 580.dp),
        )
        Button(enabled = signedIn, onClick = onAddBook) { Text("Choose book folder") }
    }
}

@Composable
private fun BookCard(book: BookSummary, onOpen: () -> Unit, onForget: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${book.chapters.size} chapters · Available offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            OutlinedButton(onClick = onForget, modifier = Modifier.heightIn(min = 48.dp)) { Text("Forget") }
        }
    }
}
