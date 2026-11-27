package com.raven.app.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RavenDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        RavenDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrateFrom1() = verifyUpgrade(1)
    @Test fun migrateFrom2() = verifyUpgrade(2)
    @Test fun migrateFrom3() = verifyUpgrade(3)
    @Test fun migrateFrom4() = verifyUpgrade(4)

    private fun verifyUpgrade(oldVersion: Int) {
        val name = "raven-upgrade-from-$oldVersion.db"
        val context = instrumentation.targetContext
        context.deleteDatabase(name)
        try {
            helper.createDatabase(name, oldVersion).use { db ->
                db.execSQL("""
                    INSERT INTO shelves (id, name, note, is_pinned, is_system, cover_composite_key, created_at, modified_at)
                    VALUES ('s', '保留的片单', '', 0, 0, NULL, 1000, 1000)
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO shelf_items (shelf_id, site_id, media_id, url, kind, title, cover_url, position, added_at, completed, duration_seconds, page_count)
                    VALUES ('s', 'r34v', 'v1', 'https://rule34video.com/v1', 'VIDEO', 'Demo', '', 0, 1000, 1, NULL, NULL)
                """.trimIndent())
                if (oldVersion >= 4) {
                    db.execSQL("INSERT INTO media_likes VALUES ('r34v', 'v1', 'VIDEO', 'Demo', '', '', '', '[]', 1234)")
                }
            }
            helper.runMigrationsAndValidate(
                name, RavenDatabase.SCHEMA_VERSION, true,
                RavenDatabase.MIGRATION_1_2, RavenDatabase.MIGRATION_2_3,
                RavenDatabase.MIGRATION_3_4, RavenDatabase.MIGRATION_4_5,
            ).close()
            val room = Room.databaseBuilder(context, RavenDatabase::class.java, name).build()
            try {
                val db = room.openHelper.writableDatabase
                db.query("SELECT name, title, completed, completion_changed_at FROM shelves JOIN shelf_items ON shelves.id = shelf_items.shelf_id").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("保留的片单", it.getString(0))
                    assertEquals("Demo", it.getString(1))
                    assertEquals(1, it.getInt(2))
                    assertTrue(it.isNull(3))
                    assertFalse(it.moveToNext())
                }
                db.query("SELECT liked_at FROM media_likes").use {
                    assertEquals(oldVersion >= 4, it.moveToFirst())
                    if (oldVersion >= 4) assertEquals(1234L, it.getLong(0))
                }
                db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally {
                room.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
