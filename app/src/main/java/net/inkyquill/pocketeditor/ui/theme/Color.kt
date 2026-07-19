package net.inkyquill.pocketeditor.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val Ink = Color(0xFF29231F)
internal val Paper = Color(0xFFFFF9EE)
internal val PaperRaised = Color(0xFFFFFCF5)
internal val PaperChrome = Color(0xFFF4EBDD)
internal val Umber = Color(0xFF675B50)
internal val Copper = Color(0xFF8C4D2E)

internal val Night = Color(0xFF171513)
internal val NightRaised = Color(0xFF211E1B)
internal val NightChrome = Color(0xFF2A2622)
internal val NightOutline = Color(0xFF514940)
internal val WarmWhite = Color(0xFFF4EBDD)
internal val WarmMuted = Color(0xFFC9BBAA)
internal val Amber = Color(0xFFE9A66A)
internal val LightOverlayScrim = Color(0x733D2F24)
internal val DarkOverlayScrim = Color(0xA6000000)

@Immutable
data class ReviewColors(
    val note: Color,
    val changeNeeded: Color,
    val warning: Color,
    val review: Color,
    val addition: Color,
    val additionContainer: Color,
    val deletion: Color,
    val deletionContainer: Color,
)

internal val LightReviewColors = ReviewColors(
    note = Color(0xFF1E5A9B),
    changeNeeded = Color(0xFFA62B2B),
    warning = Color(0xFF795900),
    review = Color(0xFF7545A5),
    addition = Color(0xFF286B3A),
    additionContainer = Color(0xFFDCEEDD),
    deletion = Color(0xFFA42E2E),
    deletionContainer = Color(0xFFFFDFDC),
)

internal val DarkReviewColors = ReviewColors(
    note = Color(0xFF86B9F2),
    changeNeeded = Color(0xFFFF9290),
    warning = Color(0xFFF2CB68),
    review = Color(0xFFD2A7FF),
    addition = Color(0xFF8BD69C),
    additionContainer = Color(0xFF203B28),
    deletion = Color(0xFFFF9B97),
    deletionContainer = Color(0xFF4A2524),
)

val LocalReviewColors = staticCompositionLocalOf { LightReviewColors }
val LocalOverlayScrim = staticCompositionLocalOf { LightOverlayScrim }
