package net.inkyquill.pocketeditor.ui.search

import net.inkyquill.pocketeditor.search.SearchHit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchNavigationTest {
    @Test
    fun `search selection retains exact canonical raw range`() {
        val hit = SearchHit("chapter-2", "Прибытие", "…пахло дождём…", 7, 13, 48, 73)

        assertEquals(
            SearchNavigation("chapter-2", 48, 73),
            hit.toNavigation(),
        )
    }
}
