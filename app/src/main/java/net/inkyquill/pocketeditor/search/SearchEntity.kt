package net.inkyquill.pocketeditor.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    notIndexed = ["book_id", "chapter_id", "title", "raw_boundaries"],
)
@Entity(tableName = "source_search")
data class SearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int = 0,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    val title: String,
    val content: String,
    @ColumnInfo(name = "raw_boundaries") val rawBoundaries: String,
)

data class SearchHit(
    val chapterId: String,
    val title: String,
    val excerpt: String,
    val excerptMatchStart: Int,
    val excerptMatchEnd: Int,
    val rawStartByte: Int,
    val rawEndByte: Int,
)
