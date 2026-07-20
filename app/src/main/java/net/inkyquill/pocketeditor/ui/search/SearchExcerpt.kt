package net.inkyquill.pocketeditor.ui.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import net.inkyquill.pocketeditor.search.SearchHit

internal fun highlightSearchExcerpt(hit: SearchHit, background: Color): AnnotatedString = buildAnnotatedString {
    append(hit.excerpt)
    if (
        hit.excerptMatchStart in 0 until hit.excerptMatchEnd &&
        hit.excerptMatchEnd <= hit.excerpt.length
    ) {
        addStyle(
            style = SpanStyle(fontWeight = FontWeight.Bold, background = background),
            start = hit.excerptMatchStart,
            end = hit.excerptMatchEnd,
        )
    }
}
