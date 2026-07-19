package net.inkyquill.pocketeditor.ui.search

import net.inkyquill.pocketeditor.search.SearchHit

data class SearchNavigation(
    val chapterId: String,
    val rawStartByte: Int,
    val rawEndByte: Int,
)

fun SearchHit.toNavigation() = SearchNavigation(chapterId, rawStartByte, rawEndByte)
