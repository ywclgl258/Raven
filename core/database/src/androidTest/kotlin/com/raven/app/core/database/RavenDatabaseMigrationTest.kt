package com.raven.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RavenDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RavenDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To3PreservesDataAndCreatesExpectedIndices() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO shelves (id, name, note, is_pinned, is_system, cover_composite_key, created_at, modified_at)
                VALUES ('watch-later', '稍后看', '', 0, 1, NULL, 1000, 1000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO shelf_items (shelf_id, site_id, media_id, url, kind, title, cover_url, position, added_at, completed, duration_seconds, page_count)
                VALUES ('watch-later', 'r34v', 'v1', 'https://rule34video.com/v1', 'VIDEO', 'Demo', '', 0, 1000, 0, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            RavenDatabase.MIGRATION_1_2,
            RavenDatabase.MIGRATION_2_3,
        )
        db.query("SELECT media_id FROM shelf_items WHERE site_id = 'r34v'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        db.close()
    }

    @Test
    fun migrate2To3ValidatesNewIndices() {
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            RavenDatabase.MIGRATION_2_3,
        ).close()
    }

    companion object {
        private const val TEST_DB = "raven-migration-test.db"
    }
}
