package net.inkyquill.pocketeditor.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.BuildConfig
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.ui.books.AppearancePreference
import net.inkyquill.pocketeditor.ui.theme.LocalReaderTypography

@Composable
fun AppearanceScreen(
    appearance: AppearancePreference,
    onBack: () -> Unit,
    onDarkChanged: (Boolean) -> Unit,
    onDecrease: () -> Unit,
    onReset: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val darkThemeDescription = stringResource(R.string.dark_theme)
    val resetTextSizeDescription = stringResource(R.string.reset_text_size)
    val appVersion = stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 760.dp)
                    .windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 18.dp, vertical = 8.dp)
                    .testTag("appearance-content"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge)
                }
                Text(stringResource(R.string.appearance_headline), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
                Text(
                    stringResource(R.string.appearance_explanation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                )
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 18.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(if (appearance.dark) R.string.dark else R.string.light), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.reading_theme), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = appearance.dark,
                            onCheckedChange = onDarkChanged,
                            modifier = Modifier.semantics { contentDescription = darkThemeDescription },
                        )
                    }
                }
                Text(stringResource(R.string.text_size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(stringResource(R.string.sample_text), style = LocalReaderTypography.current.prose)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                        ) {
                            FilledTonalButton(onClick = onDecrease, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
                            FilledTonalButton(
                                onClick = onReset,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = resetTextSizeDescription },
                            ) { Icon(Icons.Default.Refresh, contentDescription = null) }
                            FilledTonalButton(onClick = onIncrease, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        }
                        Text(
                            stringResource(R.string.appearance_scale, (appearance.textScale * 100).toInt()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                Text(
                    appVersion,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { contentDescription = appVersion },
                )
            }
        }
    }
}
