package net.inkyquill.pocketeditor.ui.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import net.inkyquill.pocketeditor.search.SearchHit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchExcerptTest {
    @Test
    fun `decorates only the exact visible match`() {
        val background = Color(0xFFFFD54F)
        val hit = SearchHit("chapter", "Глава", "…тихий дождь ночью…", 7, 12, 48, 60)

        val annotated = highlightSearchExcerpt(hit, background)

        val spans = annotated.spanStyles.filter { it.start == 7 && it.end == 12 }
        assertTrue(spans.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(spans.any { it.item.background == background })
        assertEquals("дождь", annotated.substring(7, 12))
    }
}
