package net.inkyquill.pocketeditor.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import net.inkyquill.pocketeditor.reader.ReaderState

class ReaderViewModel(
    val state: StateFlow<ReaderState?>,
    val callbacks: ReaderCallbacks,
) : ViewModel()

@Composable
fun ReaderRoute(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
    windowSize: DpSize? = null,
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    if (state == null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = modifier.fillMaxSize(),
        ) {
            CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading chapter" })
            Text("Opening chapter", style = MaterialTheme.typography.titleLarge)
        }
    } else {
        ReaderScreen(
            state = state,
            callbacks = viewModel.callbacks,
            modifier = modifier,
            windowSize = windowSize,
        )
    }
}
