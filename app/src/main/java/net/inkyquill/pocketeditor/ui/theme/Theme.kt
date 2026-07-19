package net.inkyquill.pocketeditor.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

internal val LightPocketColors = lightColorScheme(
    primary = Copper,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF321305),
    secondary = Color(0xFF6B5947),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E5CF),
    onSecondaryContainer = Color(0xFF251A0E),
    tertiary = Color(0xFF536047),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE8C8),
    onTertiaryContainer = Color(0xFF151E0C),
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = PaperChrome,
    onSurfaceVariant = Umber,
    outline = Color(0xFF8F8072),
    outlineVariant = Color(0xFFD8CABC),
    error = LightReviewColors.changeNeeded,
    onError = Color.White,
    errorContainer = LightReviewColors.deletionContainer,
    onErrorContainer = Color(0xFF410006),
    inverseSurface = Color(0xFF342F2A),
    inverseOnSurface = Color(0xFFF9EFE4),
    inversePrimary = Color(0xFFFFB694),
    surfaceTint = Copper,
    scrim = LightOverlayScrim,
    surfaceBright = Color(0xFFFFFCF5),
    surfaceDim = Color(0xFFE5DCCE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF3E7),
    surfaceContainer = PaperChrome,
    surfaceContainerHigh = Color(0xFFEEE4D6),
    surfaceContainerHighest = Color(0xFFE7DCCF),
    primaryFixed = Color(0xFFFFDBCA),
    primaryFixedDim = Color(0xFFFFB694),
    onPrimaryFixed = Color(0xFF321305),
    onPrimaryFixedVariant = Color(0xFF6F351C),
    secondaryFixed = Color(0xFFF2E5CF),
    secondaryFixedDim = Color(0xFFD9C9AF),
    onSecondaryFixed = Color(0xFF251A0E),
    onSecondaryFixedVariant = Color(0xFF524535),
    tertiaryFixed = Color(0xFFDCE8C8),
    tertiaryFixedDim = Color(0xFFC0CDAA),
    onTertiaryFixed = Color(0xFF151E0C),
    onTertiaryFixedVariant = Color(0xFF3C4932),
)

internal val DarkPocketColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF44230D),
    primaryContainer = Color(0xFF663719),
    onPrimaryContainer = Color(0xFFFFDBCA),
    secondary = Color(0xFFD9C2A5),
    onSecondary = Color(0xFF382C1F),
    secondaryContainer = Color(0xFF514332),
    onSecondaryContainer = Color(0xFFF5DFC3),
    tertiary = Color(0xFFB8C99E),
    onTertiary = Color(0xFF26331C),
    tertiaryContainer = Color(0xFF3B492F),
    onTertiaryContainer = Color(0xFFD4E6BB),
    background = Night,
    onBackground = WarmWhite,
    surface = NightRaised,
    onSurface = WarmWhite,
    surfaceVariant = NightChrome,
    onSurfaceVariant = WarmMuted,
    outline = NightOutline,
    outlineVariant = Color(0xFF3D3732),
    error = DarkReviewColors.changeNeeded,
    onError = Color(0xFF5F1413),
    errorContainer = DarkReviewColors.deletionContainer,
    onErrorContainer = Color(0xFFFFDAD7),
    inverseSurface = Color(0xFFE9DFD3),
    inverseOnSurface = Color(0xFF332E29),
    inversePrimary = Copper,
    surfaceTint = Amber,
    scrim = DarkOverlayScrim,
    surfaceBright = Color(0xFF332F2B),
    surfaceDim = Color(0xFF141210),
    surfaceContainerLowest = Color(0xFF100E0D),
    surfaceContainerLow = Color(0xFF1C1917),
    surfaceContainer = NightRaised,
    surfaceContainerHigh = NightChrome,
    surfaceContainerHighest = Color(0xFF35302C),
    primaryFixed = Color(0xFFFFDBCA),
    primaryFixedDim = Color(0xFFFFB694),
    onPrimaryFixed = Color(0xFF321305),
    onPrimaryFixedVariant = Color(0xFF6F351C),
    secondaryFixed = Color(0xFFF2E5CF),
    secondaryFixedDim = Color(0xFFD9C9AF),
    onSecondaryFixed = Color(0xFF251A0E),
    onSecondaryFixedVariant = Color(0xFF524535),
    tertiaryFixed = Color(0xFFDCE8C8),
    tertiaryFixedDim = Color(0xFFC0CDAA),
    onTertiaryFixed = Color(0xFF151E0C),
    onTertiaryFixedVariant = Color(0xFF3C4932),
)

@Composable
fun PocketEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    SideEffect {
        view.context.findActivity()?.enableEdgeToEdge(
            statusBarStyle = if (darkTheme) {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            },
            navigationBarStyle = if (darkTheme) {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            },
        )
    }
    CompositionLocalProvider(
        LocalReviewColors provides if (darkTheme) DarkReviewColors else LightReviewColors,
        LocalOverlayScrim provides if (darkTheme) DarkOverlayScrim else LightOverlayScrim,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkPocketColors else LightPocketColors,
            typography = PocketTypography,
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
