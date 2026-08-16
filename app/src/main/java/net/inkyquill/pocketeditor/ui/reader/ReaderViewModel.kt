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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.reader.ReaderLoadState
import net.inkyquill.pocketeditor.ui.review.ReviewUiState
import net.inkyquill.pocketeditor.ui.review.EditorialReviewController

class ReaderViewModel(
    val state: StateFlow<ReaderLoadState?>,
    val callbacks: ReaderCallbacks,
    val reviewState: StateFlow<ReviewUiState> = MutableStateFlow(ReviewUiState()),
    private val reviewController: EditorialReviewController? = null,
) : ViewModel() {
    val readyState = state.map { (it as? ReaderLoadState.Ready)?.state }

    init {
        reviewController?.let { controller ->
            viewModelScope.launch {
                state.filterIsInstance<ReaderLoadState.Ready>().collect { ready ->
                    val reader = ready.state
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
            val loadingDescription = stringResource(R.string.loading_chapter)
            CircularProgressIndicator(Modifier.semantics { contentDescription = loadingDescription })
            Text(stringResource(R.string.opening_chapter), style = MaterialTheme.typography.titleLarge)
        }
    } else when (state) {
        is ReaderLoadState.Ready -> ReaderScreen(
            state = state.state,
            callbacks = viewModel.callbacks,
            reviewUiState = reviewState,
            modifier = modifier,
            windowSize = windowSize,
            contentsContent = contentsContent,
            searchTarget = searchTarget,
        )
        is ReaderLoadState.Pending -> PendingReaderScreen(
            state = state,
            modifier = modifier,
            windowSize = windowSize,
            contentsContent = contentsContent,
        )
    }
}
