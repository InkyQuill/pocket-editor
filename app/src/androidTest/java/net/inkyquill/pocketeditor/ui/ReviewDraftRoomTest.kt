package net.inkyquill.pocketeditor.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.inkyquill.pocketeditor.database.PocketEditorDatabase
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.ui.review.ReviewDraft
import net.inkyquill.pocketeditor.ui.review.ReviewDraftSession
import net.inkyquill.pocketeditor.ui.review.ReviewDraftStore
import net.inkyquill.pocketeditor.ui.review.ReviewSelection
import net.inkyquill.pocketeditor.ui.review.RoomReviewDraftPersistence
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewDraftRoomTest {
    @Test
    fun draftSurvivesDatabaseCloseAndProcessStyleReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "review-draft-process.db"
        context.deleteDatabase(name)
        val expected = ReviewDraftSession(
            ReviewDraft.Signal(
                null,
                ReviewSelection(0, 0, 5, RawRange(0, 5), "Plain"),
                SignalType.WARNING,
                "Unsaved thought",
            ),
        )
        val first = Room.databaseBuilder(context, PocketEditorDatabase::class.java, name).build()
        try {
            ReviewDraftStore(RoomReviewDraftPersistence(first.draftDao())).save("book", "chapter", expected)
        } finally {
            first.close()
        }

        val second = Room.databaseBuilder(context, PocketEditorDatabase::class.java, name).build()
        val restored = try {
            ReviewDraftStore(RoomReviewDraftPersistence(second.draftDao())).load("book", "chapter")
        } finally {
            second.close()
        }

        assertEquals(expected, restored)
        context.deleteDatabase(name)
        Unit
    }
}
