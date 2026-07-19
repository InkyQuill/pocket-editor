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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.ui.review.ReviewUiState
import net.inkyquill.pocketeditor.ui.review.EditorialReviewController

class ReaderViewModel(
    val state: StateFlow<ReaderState?>,
    val callbacks: ReaderCallbacks,
    val reviewState: StateFlow<ReviewUiState> = MutableStateFlow(ReviewUiState()),
    private val reviewController: EditorialReviewController? = null,
) : ViewModel() {
    init {
        reviewController?.let { controller ->
            viewModelScope.launch {
                state.filterNotNull().collect { reader ->
                    controller.updateChapterContext(reader.chapterNote.orEmpty(), reader.syncState)
                }
            }
        }
    }
}

@Composable
fun ReaderRoute(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
    windowSize: DpSize? = null,
    contentsContent: (@Composable (closeLabel: String, onClose: () -> Unit) -> Unit)? = null,
    searchTarget: ReaderSearchTarget? = null,
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val reviewState = viewModel.reviewState.collectAsStateWithLifecycle().value
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
            reviewUiState = reviewState,
            modifier = modifier,
            windowSize = windowSize,
            contentsContent = contentsContent,
            searchTarget = searchTarget,
        )
    }
}
