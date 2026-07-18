package net.inkyquill.pocketeditor.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        BookRootEntity::class,
        RemoteRevisionEntity::class,
        MergeBaseEntity::class,
        OutboxEntity::class,
        ReadingPositionEntity::class,
        DraftEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class PocketEditorDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun syncDao(): SyncDao
    abstract fun draftDao(): DraftDao
}

internal class DatabaseConverters {
    @TypeConverter
    fun fromOutboxState(value: OutboxState): String = value.name

    @TypeConverter
    fun toOutboxState(value: String): OutboxState = OutboxState.valueOf(value)
}
