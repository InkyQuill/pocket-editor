package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.load.ProgressiveLoadSnapshot
import kotlinx.coroutines.delay

const val COMPLETION_CARD_MILLIS = 5_000L

fun selectVisibleLoad(
    loads: List<ProgressiveLoadSnapshot>,
    selectedBookId: String?,
    recentRoots: List<String>,
): ProgressiveLoadSnapshot? {
    val rootRanks = recentRoots.withIndex().associate { it.value.canonicalLoadRoot() to it.index }
    val ordering = compareBy<ProgressiveLoadSnapshot>(
        { if (it.bookId == selectedBookId) 1 else 0 },
        { rootRanks[it.remoteRootPath.canonicalLoadRoot()] ?: -1 },
        ProgressiveLoadSnapshot::generation,
        ProgressiveLoadSnapshot::remoteRootPath,
    )
    return loads.filter { it.phase != ProgressiveLoadPhase.COMPLETE }.maxWithOrNull(ordering)
        ?: loads.filter { it.phase == ProgressiveLoadPhase.COMPLETE }.maxWithOrNull(ordering)
}

private fun String.canonicalLoadRoot(): String = trim().let { value ->
    if (value.endsWith('/') && value.length > "disk:/".length) value.dropLast(1) else value
}

@Composable
fun ProgressiveLoadHost(
    snapshot: ProgressiveLoadSnapshot?,
    nowMillis: Long,
    onPause: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    completionDisplayMillis: Long = COMPLETION_CARD_MILLIS,
    content: @Composable () -> Unit,
) {
    var hiddenCompletionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot?.bookId, snapshot?.phase) {
        if (snapshot?.phase == ProgressiveLoadPhase.COMPLETE) {
            delay(completionDisplayMillis)
            hiddenCompletionId = snapshot.bookId
        } else if (snapshot != null) {
            hiddenCompletionId = null
        }
    }
    Column(modifier.fillMaxSize()) {
        if (snapshot != null && !(snapshot.phase == ProgressiveLoadPhase.COMPLETE && hiddenCompletionId == snapshot.bookId)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                ProgressiveLoadCard(
                    snapshot, nowMillis, onPause, onContinue, onCancel, onSignIn,
                    Modifier.align(Alignment.Center).fillMaxWidth().widthIn(max = 520.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

fun ProgressiveLoadSnapshot.primaryText(nowMillis: Long): String = when {
    phase == ProgressiveLoadPhase.COMPLETE -> "Книга доступна без сети"
    lastErrorCategory == ProgressiveLoadErrorCategory.UNAUTHORIZED -> "Нужно войти в Яндекс Диск"
    lastErrorCategory == ProgressiveLoadErrorCategory.INVALID_REMOTE -> "Требуется действие"
    lastErrorCategory == ProgressiveLoadErrorCategory.OFFLINE -> "Нет сети · продолжим автоматически"
    lastErrorCategory == ProgressiveLoadErrorCategory.RATE_LIMITED && retryAt != null ->
        "Лимит Яндекс Диска · повтор через ${((retryAt - nowMillis).coerceAtLeast(0) + 999) / 1000} с"
    activePath != null -> "Загружаем $activePath"
    completedFiles > 0 -> "Загружено $completedFiles из $totalFiles"
    else -> "Готовим книгу…"
}

@Composable
fun ProgressiveLoadCard(
    snapshot: ProgressiveLoadSnapshot,
    nowMillis: Long,
    onPause: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val incomplete = snapshot.phase != ProgressiveLoadPhase.COMPLETE
    val progressDescription = "Загружено ${snapshot.completedFiles} из ${snapshot.totalFiles}"
    val status = snapshot.primaryText(nowMillis)
    Card(
        modifier.testTag("progressive-load-card").semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = progressDescription
            stateDescription = status
        },
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = {
                    if (snapshot.totalFiles <= 0) 0f
                    else snapshot.completedFiles.toFloat() / snapshot.totalFiles.toFloat()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (incomplete) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (snapshot.phase == ProgressiveLoadPhase.PREPARING ||
                        snapshot.phase == ProgressiveLoadPhase.INITIAL ||
                        snapshot.phase == ProgressiveLoadPhase.BACKGROUND
                    ) {
                        TextButton(onClick = onPause) { Text("Приостановить") }
                    }
                    if (snapshot.phase == ProgressiveLoadPhase.PAUSED ||
                        snapshot.phase == ProgressiveLoadPhase.CANCELLED ||
                        snapshot.phase == ProgressiveLoadPhase.ACTION_REQUIRED
                    ) {
                        TextButton(onClick = onContinue) { Text("Продолжить") }
                    }
                    if (!snapshot.cancelled) {
                        TextButton(onClick = onCancel) { Text("Отменить") }
                    }
                    if (snapshot.lastErrorCategory == ProgressiveLoadErrorCategory.UNAUTHORIZED) {
                        TextButton(onClick = onSignIn) { Text("Войти") }
                    }
                }
            }
        }
    }
}
