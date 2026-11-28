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
    @Test fun migrateFrom5() = verifyUpgrade(5)
    @Test fun migrateFrom6() = verifyUpgrade(6)
    @Test fun migrateFrom7() = verifyUpgrade(7)

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
                if (oldVersion >= 6) {
                    db.execSQL("INSERT INTO reading_progress (site_id, media_id, url, title, cover_url, kind, current_page, total_pages, scroll_offset_px, updated_at, completed) VALUES ('r34v', 'series-1:chapter-2', 'https://rule34video.com/g/1/', 'Serial', '', 'SERIAL_COMIC', 1, 2, 0, 1000, 0)")
                    db.execSQL("INSERT INTO reading_progress (site_id, media_id, url, title, cover_url, kind, current_page, total_pages, scroll_offset_px, updated_at, completed) VALUES ('r34v', 'plain-serial', 'https://rule34video.com/g/2/', 'Plain', '', 'SERIAL_COMIC', 0, 0, 0, 1001, 0)")
                    db.execSQL("INSERT INTO reading_progress (site_id, media_id, url, title, cover_url, kind, current_page, total_pages, scroll_offset_px, updated_at, completed) VALUES ('r34v', 'gallery:1', 'https://rule34video.com/g/3/', 'Gallery', '', 'COMIC', 1, 2, 0, 1002, 0)")
                }
            }
            helper.runMigrationsAndValidate(
                name, RavenDatabase.SCHEMA_VERSION, true,
                RavenDatabase.MIGRATION_1_2, RavenDatabase.MIGRATION_2_3,
                RavenDatabase.MIGRATION_3_4, RavenDatabase.MIGRATION_4_5, RavenDatabase.MIGRATION_5_6, RavenDatabase.MIGRATION_6_7, RavenDatabase.MIGRATION_7_8,
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
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='series_follows'").use {
                    assertTrue(it.moveToFirst())
                }
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='inbox_dismissed'").use {
                    assertTrue("迁移后应存在 inbox_dismissed 墓碑表", it.moveToFirst())
                }
                if (oldVersion >= 6) {
                    db.query("SELECT series_id FROM reading_progress WHERE media_id = 'series-1:chapter-2'").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("series-1", it.getString(0))
                    }
                    db.query("SELECT series_id FROM reading_progress WHERE media_id = 'plain-serial'").use {
                        assertTrue(it.moveToFirst())
                        assertTrue(it.isNull(0))
                    }
                    db.query("SELECT series_id FROM reading_progress WHERE media_id = 'gallery:1'").use {
                        assertTrue(it.moveToFirst())
                        assertTrue(it.isNull(0))
                    }
                }
            } finally {
                room.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
