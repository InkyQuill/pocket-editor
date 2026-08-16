package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    signingOut: Boolean = false,
    signOutError: String? = null,
    onSignOut: () -> Unit = {},
    onRetryBook: (String) -> Unit = {},
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    val addDescription = stringResource(R.string.add_book)
                    IconButton(
                        enabled = signedIn,
                        onClick = onAddBook,
                        modifier = Modifier.semantics { contentDescription = addDescription },
                    ) {
                        Icon(Icons.Default.Add, null)
                    }
                    val appearanceDescription = stringResource(R.string.appearance)
                    IconButton(
                        onClick = onAppearance,
                        modifier = Modifier.semantics { contentDescription = appearanceDescription },
                    ) {
                        Icon(Icons.Default.Settings, null)
                    }
                    if (signedIn) {
                        val accountDescription = stringResource(
                            if (signOutError == null) R.string.sign_out_yandex_disk else R.string.retry_sign_out,
                        )
                        IconButton(
                            enabled = !signingOut,
                            onClick = { confirmSignOut = true },
                            modifier = Modifier.semantics { contentDescription = accountDescription },
                        ) {
                            if (signingOut) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.AccountCircle, null)
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(contentPadding)
                    .widthIn(max = 920.dp)
                    .padding(horizontal = 16.dp),
            ) {
                if (!signedIn) {
                    SignInCard(signingIn, signInError, onSignIn)
                    Spacer(Modifier.size(12.dp))
                }
                if (books.isEmpty()) {
                    EmptyBooks(signedIn, onAddBook, Modifier.testTag("empty-books"))
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("library-list"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                    ) {
                        items(books, key = BookSummary::bookId) { book ->
                            BookCard(
                                book = book,
                                onOpen = { onOpenBook(book.bookId) },
                                onForget = { onRequestForget(book.bookId) },
                                onRetry = { onRetryBook(book.bookId) },
                            )
                        }
                    }
                }
            }
        }
    }

    forgetBookId?.let { id ->
        val title = books.singleOrNull { it.bookId == id }?.title ?: stringResource(R.string.this_book)
        AlertDialog(
            onDismissRequest = onCancelForget,
            title = { Text(stringResource(R.string.forget_book_title, title)) },
            text = { Text(stringResource(R.string.forget_book_explanation)) },
            confirmButton = { Button(onClick = onConfirmForget) { Text(stringResource(R.string.forget_local_copy)) } },
            dismissButton = { TextButton(onClick = onCancelForget) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = {
                Text(
                    if (signOutError == null) stringResource(R.string.sign_out_explanation)
                    else stringResource(R.string.sign_out_failed, signOutError),
                )
            },
            confirmButton = {
                Button(onClick = { confirmSignOut = false; onSignOut() }) {
                    Text(stringResource(if (signOutError == null) R.string.sign_out else R.string.retry_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SignInCard(signingIn: Boolean, error: String?, onSignIn: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("sign-in-card"),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.connect_yandex_disk), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.connect_yandex_disk_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
                error?.let {
                    Text(
                        stringResource(R.string.sign_in_error, it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Button(enabled = !signingIn, onClick = onSignIn, modifier = Modifier.heightIn(min = 48.dp)) {
                if (signingIn) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (error == null) R.string.sign_in else R.string.retry_sign_in))
            }
        }
    }
}

@Composable
private fun EmptyBooks(signedIn: Boolean, onAddBook: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(stringResource(R.string.empty_books_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(if (signedIn) R.string.empty_books_signed_in else R.string.empty_books_signed_out),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 580.dp),
        )
        Button(
            enabled = signedIn,
            onClick = onAddBook,
            modifier = Modifier.padding(top = 6.dp).heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.choose_book_folder))
        }
    }
}

@Composable
private fun BookCard(book: BookSummary, onOpen: () -> Unit, onForget: () -> Unit, onRetry: () -> Unit) {
    val chapterCount = russianPluralStringResource(R.plurals.chapter_count, book.chapters.size, book.chapters.size)
    val availability = stringResource(R.string.available_offline)
    val relink = stringResource(R.string.relink_yandex_disk)
    Card(
        onClick = { if (book.recoveryError == null) onOpen() },
        modifier = Modifier.fillMaxWidth().testTag("book-card-${book.bookId}"),
    ) {
        ListItem(
            headlineContent = {
                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    book.recoveryError ?: buildString {
                        append("$chapterCount · $availability")
                        if (book.needsRelink) append(" · $relink")
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (book.recoveryError != null) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    } else {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                    BookOverflow(book.title, onForget)
                }
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun BookOverflow(title: String, onForget: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.book_actions, title)
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Icon(Icons.Default.MoreVert, null)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.forget_local_copy)) },
            onClick = { expanded = false; onForget() },
        )
    }
}
