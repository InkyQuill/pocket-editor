package net.inkyquill.pocketeditor.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PocketEditorMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PocketEditorDatabase::class.java,
    )

    @Test
    fun versionOneExportedSchemaCanBeCreatedAndValidated() {
        helper.createDatabase(DATABASE_NAME, 1).close()
        helper.runMigrationsAndValidate(DATABASE_NAME, 1, true).close()
    }

    private companion object {
        const val DATABASE_NAME = "migration-version-one"
    }
}
