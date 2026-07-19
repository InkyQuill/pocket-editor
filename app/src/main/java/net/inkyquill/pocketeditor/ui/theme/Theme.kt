package net.inkyquill.pocketeditor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Copper,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF321305),
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = PaperChrome,
    onSurfaceVariant = Umber,
    outline = Color(0xFF8F8072),
    error = LightReviewColors.changeNeeded,
)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF44230D),
    primaryContainer = Color(0xFF663719),
    onPrimaryContainer = Color(0xFFFFDBCA),
    background = Night,
    onBackground = WarmWhite,
    surface = NightRaised,
    onSurface = WarmWhite,
    surfaceVariant = NightChrome,
    onSurfaceVariant = WarmMuted,
    outline = NightOutline,
    error = DarkReviewColors.changeNeeded,
)

@Composable
fun PocketEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalReviewColors provides if (darkTheme) DarkReviewColors else LightReviewColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PocketTypography,
            content = content,
        )
    }
}
