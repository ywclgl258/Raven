package com.raven.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.raven.app.core.database.converter.RavenTypeConverters
import com.raven.app.core.database.dao.BlockedTermDao
import com.raven.app.core.database.dao.BrowsePositionDao
import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.dao.FavoriteDao
import com.raven.app.core.database.dao.ImageFavoriteDao
import com.raven.app.core.database.dao.MediaLikeDao
import com.raven.app.core.database.dao.MediaTagCacheDao
import com.raven.app.core.database.dao.PageBookmarkDao
import com.raven.app.core.database.dao.PlaybackPreferencesDao
import com.raven.app.core.database.dao.ReaderPreferencesDao
import com.raven.app.core.database.dao.ReadingProgressDao
import com.raven.app.core.database.dao.SavedSearchDao
import com.raven.app.core.database.dao.SeriesFollowDao
import com.raven.app.core.database.dao.SearchHistoryDao
import com.raven.app.core.database.dao.ShelfDao
import com.raven.app.core.database.dao.ShelfItemDao
import com.raven.app.core.database.dao.SmartShelfDao
import com.raven.app.core.database.dao.TagSubscriptionDao
import com.raven.app.core.database.dao.TagTranslationDao
import com.raven.app.core.database.dao.TimeBookmarkDao
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.dao.BackupRestoreJournalDao
import com.raven.app.core.database.entity.BackupRestoreJournalEntity
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.entity.BrowsePositionEntity
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.ImageFavoriteEntity
import com.raven.app.core.database.entity.MediaLikeEntity
import com.raven.app.core.database.entity.MediaTagCacheEntity
import com.raven.app.core.database.entity.PageBookmarkEntity
import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.database.entity.ReadingProgressEntity
import com.raven.app.core.database.entity.SavedSearchEntity
import com.raven.app.core.database.entity.SearchHistoryEntity
import com.raven.app.core.database.entity.SeriesFollowEntity
import com.raven.app.core.database.entity.ShelfEntity
import com.raven.app.core.database.entity.ShelfItemEntity
import com.raven.app.core.database.entity.SmartShelfEntity
import com.raven.app.core.database.entity.TagSubscriptionEntity
import com.raven.app.core.database.entity.TagTranslationEntity
import com.raven.app.core.database.entity.TimeBookmarkEntity
import com.raven.app.core.database.entity.UpdateInboxEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.model.Shelf

@Database(
    entities = [
        WatchProgressEntity::class,
        ReadingProgressEntity::class,
        FavoriteEntity::class,
        ShelfEntity::class,
        ShelfItemEntity::class,
        SmartShelfEntity::class,
        PageBookmarkEntity::class,
        TimeBookmarkEntity::class,
        ImageFavoriteEntity::class,
        TagSubscriptionEntity::class,
        SavedSearchEntity::class,
        UpdateInboxEntity::class,
        BlockedTermEntity::class,
        SearchHistoryEntity::class,
        DownloadItemEntity::class,
        PlaybackPreferencesEntity::class,
        ReaderPreferencesEntity::class,
        BrowsePositionEntity::class,
        TagTranslationEntity::class,
        MediaTagCacheEntity::class,
        MediaLikeEntity::class,
        BackupRestoreJournalEntity::class,
        SeriesFollowEntity::class,
    ],
    version = RavenDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(RavenTypeConverters::class)
abstract class RavenDatabase : RoomDatabase() {
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun shelfDao(): ShelfDao
    abstract fun shelfItemDao(): ShelfItemDao
    abstract fun smartShelfDao(): SmartShelfDao
    abstract fun pageBookmarkDao(): PageBookmarkDao
    abstract fun timeBookmarkDao(): TimeBookmarkDao
    abstract fun imageFavoriteDao(): ImageFavoriteDao
    abstract fun tagSubscriptionDao(): TagSubscriptionDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun updateInboxDao(): UpdateInboxDao
    abstract fun blockedTermDao(): BlockedTermDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun downloadItemDao(): DownloadItemDao
    abstract fun playbackPreferencesDao(): PlaybackPreferencesDao
    abstract fun readerPreferencesDao(): ReaderPreferencesDao
    abstract fun browsePositionDao(): BrowsePositionDao
    abstract fun tagTranslationDao(): TagTranslationDao
    abstract fun mediaTagCacheDao(): MediaTagCacheDao
    abstract fun mediaLikeDao(): MediaLikeDao
    abstract fun backupRestoreJournalDao(): BackupRestoreJournalDao
    abstract fun seriesFollowDao(): SeriesFollowDao

    companion object {
        const val DATABASE_NAME: String = "raven.db"
        const val SCHEMA_VERSION: Int = 7
        const val DEFAULT_WATCH_LATER_NAME: String = "稍后看"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tag_translations` (
                        `namespace` TEXT NOT NULL,
                        `raw_slug` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `chinese_name` TEXT NOT NULL,
                        `intro` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source`, `namespace`, `raw_slug`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_translations_raw_slug` ON `tag_translations` (`raw_slug`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_translations_chinese_name` ON `tag_translations` (`chinese_name`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_translations_source` ON `tag_translations` (`source`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_tag_cache` (
                        `site_id` TEXT NOT NULL,
                        `media_id` TEXT NOT NULL,
                        `tags_json` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`site_id`, `media_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_tag_cache_updated_at` ON `media_tag_cache` (`updated_at` DESC)",
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_shelf_items_site_id_media_id` ON `shelf_items` (`site_id`, `media_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_update_inbox_site_id_discovered_at` ON `update_inbox` (`site_id` ASC, `discovered_at` DESC)",
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_likes` (
                        `site_id` TEXT NOT NULL,
                        `media_id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `cover_url` TEXT NOT NULL,
                        `author_name` TEXT NOT NULL,
                        `tags_json` TEXT NOT NULL,
                        `liked_at` INTEGER NOT NULL,
                        PRIMARY KEY(`site_id`, `media_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_likes_liked_at` ON `media_likes` (`liked_at` DESC)",
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Shelf manual-completion override timestamp; null means "never toggled", which
                // keeps legacy rows losing to any explicit choice during backup merge.
                db.execSQL("ALTER TABLE `shelf_items` ADD COLUMN `completion_changed_at` INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `backup_restore_journal` (
                        `id` INTEGER NOT NULL,
                        `restore_id` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `settings_json` TEXT NOT NULL,
                        `site_languages_json` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `series_follows` (
                        `site_id` TEXT NOT NULL,
                        `series_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `cover_url` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `last_checked_at` INTEGER,
                        `seen_chapter_ids` TEXT NOT NULL,
                        PRIMARY KEY(`site_id`, `series_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_follows_site_id` ON `series_follows` (`site_id`)")
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `series_id` TEXT")
                db.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `series_title` TEXT")
                db.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `series_url` TEXT")
                // Legacy serial chapters encode the work as `seriesId:chapterId`; keep upgrades
                // consistent with `ReadingProgress.workId`/`toEntity()` without guessing URLs.
                db.execSQL(
                    """
                    UPDATE `reading_progress`
                    SET `series_id` = substr(`media_id`, 1, instr(`media_id`, ':') - 1)
                    WHERE `series_id` IS NULL
                      AND `kind` = 'SERIAL_COMIC'
                      AND instr(`media_id`, ':') > 1
                    """.trimIndent(),
                )
            }
        }

        private val watchLaterSeedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO shelves
                    (id, name, note, is_pinned, is_system, cover_composite_key, created_at, modified_at)
                    VALUES (?, ?, '', 0, 1, NULL, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any>(Shelf.WATCH_LATER_ID, DEFAULT_WATCH_LATER_NAME, now, now),
                )
            }
        }

        fun build(context: Context): RavenDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                RavenDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .addCallback(watchLaterSeedCallback)
                .build()
    }
}
