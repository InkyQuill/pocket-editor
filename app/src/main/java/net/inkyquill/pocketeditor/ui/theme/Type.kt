package net.inkyquill.pocketeditor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.inkyquill.pocketeditor.R

val LiterataFamily = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_semibold, FontWeight.SemiBold),
    Font(R.font.literata_bold, FontWeight.Bold),
)

val ManropeFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
)

data class ReaderTypography(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val prose: TextStyle,
    val searchExcerpt: TextStyle,
) {
    fun scaled(scale: Float): ReaderTypography = copy(
        h1 = h1.scaled(scale),
        h2 = h2.scaled(scale),
        h3 = h3.scaled(scale),
        h4 = h4.scaled(scale),
        h5 = h5.scaled(scale),
        h6 = h6.scaled(scale),
        prose = prose.scaled(scale),
        searchExcerpt = searchExcerpt.scaled(scale),
    )
}

internal val DefaultReaderTypography = ReaderTypography(
    h1 = readerStyle(28, 35, FontWeight.SemiBold),
    h2 = readerStyle(23, 30, FontWeight.SemiBold),
    h3 = readerStyle(19, 26, FontWeight.SemiBold),
    h4 = readerStyle(17, 24, FontWeight.SemiBold),
    h5 = readerStyle(16, 23, FontWeight.Bold),
    h6 = readerStyle(14, 21, FontWeight.Bold),
    prose = readerStyle(16, 25, FontWeight.Normal),
    searchExcerpt = readerStyle(14, 21, FontWeight.Normal),
)

val LocalReaderTypography = staticCompositionLocalOf { DefaultReaderTypography }

internal val PocketTypography = Typography().withManrope().copy(
    titleLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

private fun readerStyle(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = LiterataFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

private fun TextStyle.withManrope() = copy(fontFamily = ManropeFamily)

private fun Typography.withManrope() = copy(
    displayLarge = displayLarge.withManrope(),
    displayMedium = displayMedium.withManrope(),
    displaySmall = displaySmall.withManrope(),
    headlineLarge = headlineLarge.withManrope(),
    headlineMedium = headlineMedium.withManrope(),
    headlineSmall = headlineSmall.withManrope(),
    titleLarge = titleLarge.withManrope(),
    titleMedium = titleMedium.withManrope(),
    titleSmall = titleSmall.withManrope(),
    bodyLarge = bodyLarge.withManrope(),
    bodyMedium = bodyMedium.withManrope(),
    bodySmall = bodySmall.withManrope(),
    labelLarge = labelLarge.withManrope(),
    labelMedium = labelMedium.withManrope(),
    labelSmall = labelSmall.withManrope(),
)

private fun TextStyle.scaled(scale: Float) = copy(
    fontSize = fontSize * scale,
    lineHeight = lineHeight * scale,
)
