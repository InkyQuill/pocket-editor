package net.inkyquill.pocketeditor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.inkyquill.pocketeditor.R

val BookSerif = FontFamily(
    Font(R.font.book_serif, FontWeight.Normal),
    Font(R.font.book_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.book_serif_bold, FontWeight.Bold),
)

internal val PocketTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = BookSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 43.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = BookSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 35.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = BookSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = BookSerif,
        fontSize = 18.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BookSerif,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)
