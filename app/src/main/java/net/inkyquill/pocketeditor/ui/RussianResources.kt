package net.inkyquill.pocketeditor.ui

import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun russianPluralStringResource(
    @PluralsRes id: Int,
    count: Int,
    vararg formatArgs: Any,
): String {
    val context = LocalContext.current
    val localConfiguration = LocalConfiguration.current
    val resources = remember(context, localConfiguration) {
        val configuration = Configuration(localConfiguration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        }
        context.createConfigurationContext(configuration).resources
    }
    return resources.getQuantityString(id, count, *formatArgs)
}
