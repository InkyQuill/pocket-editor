package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.ui.russianPluralStringResource

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
    signingOut: Boolean = false,
    signOutError: String? = null,
    onSignOut: () -> Unit = {},
    onRetryBook: (String) -> Unit = {},
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .widthIn(max = 920.dp)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .then(if (books.isEmpty()) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                        Text(
                            stringResource(R.string.app_tagline),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val appearanceDescription = stringResource(R.string.appearance)
                    IconButton(onClick = onAppearance, modifier = Modifier.semantics { contentDescription = appearanceDescription }) {
                        Icon(Icons.Default.Settings, null)
                    }
                    if (signedIn) {
                        val signOutDescription = stringResource(
                            if (signOutError == null) R.string.sign_out_yandex_disk else R.string.retry_sign_out,
                        )
                        TextButton(
                            enabled = !signingOut,
                            onClick = { confirmSignOut = true },
                            modifier = Modifier.semantics { contentDescription = signOutDescription },
                        ) { Text(stringResource(if (signOutError == null) R.string.sign_out else R.string.retry_sign_out)) }
                    }
                }
                Spacer(Modifier.height(28.dp))
                if (!signedIn) {
                    SignInCard(signingIn, signInError, onSignIn)
                    Spacer(Modifier.height(20.dp))
                }
                if (books.isEmpty()) {
                    EmptyBooks(signedIn, onAddBook, Modifier.testTag("empty-books"))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.books_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        FilledTonalButton(enabled = signedIn, onClick = onAddBook) {
                            Icon(Icons.Default.Add, null)
                            Text(stringResource(R.string.add_book), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp),
                    ) {
                        items(books, key = BookSummary::bookId) { book ->
                            BookCard(
                                book,
                                { onOpenBook(book.bookId) },
                                { onRequestForget(book.bookId) },
                                { onRetryBook(book.bookId) },
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
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .76f)).padding(24.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                ) {
                    Text(stringResource(R.string.sign_out_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (signOutError == null) stringResource(R.string.sign_out_explanation)
                        else stringResource(R.string.sign_out_failed, signOutError),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
                        TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = { confirmSignOut = false; onSignOut() }) { Text(stringResource(R.string.sign_out)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInCard(signingIn: Boolean, error: String?, onSignIn: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.testTag("sign-in-card"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        ) {
            Column {
                Text(stringResource(R.string.connect_yandex_disk), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.connect_yandex_disk_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                error?.let {
                    Text(
                        stringResource(R.string.sign_in_error, it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Button(enabled = !signingIn, onClick = onSignIn, modifier = Modifier.align(Alignment.End)) {
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
        verticalArrangement = Arrangement.Top,
        modifier = modifier.fillMaxWidth().padding(28.dp),
    ) {
        Text(stringResource(R.string.empty_books_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(if (signedIn) R.string.empty_books_signed_in else R.string.empty_books_signed_out),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp).widthIn(max = 580.dp),
        )
        Button(enabled = signedIn, onClick = onAddBook) { Text(stringResource(R.string.choose_book_folder)) }
    }
}

@Composable
private fun BookCard(book: BookSummary, onOpen: () -> Unit, onForget: () -> Unit, onRetry: () -> Unit) {
    val chapterCount = russianPluralStringResource(R.plurals.chapter_count, book.chapters.size, book.chapters.size)
    val availability = stringResource(R.string.available_offline)
    val relink = stringResource(R.string.relink_yandex_disk)
    Card(onClick = { if (book.recoveryError == null) onOpen() }, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    book.recoveryError ?: buildString {
                        append("$chapterCount · $availability")
                        if (book.needsRelink) append(" · $relink")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (book.recoveryError != null) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
            }
            OutlinedButton(onClick = onForget, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.forget)) }
        }
    }
}
