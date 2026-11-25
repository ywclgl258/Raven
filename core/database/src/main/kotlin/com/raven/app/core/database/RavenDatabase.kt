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
import com.raven.app.core.database.dao.MediaTagCacheDao
import com.raven.app.core.database.dao.PageBookmarkDao
import com.raven.app.core.database.dao.PlaybackPreferencesDao
import com.raven.app.core.database.dao.ReaderPreferencesDao
import com.raven.app.core.database.dao.ReadingProgressDao
import com.raven.app.core.database.dao.SavedSearchDao
import com.raven.app.core.database.dao.SearchHistoryDao
import com.raven.app.core.database.dao.ShelfDao
import com.raven.app.core.database.dao.ShelfItemDao
import com.raven.app.core.database.dao.SmartShelfDao
import com.raven.app.core.database.dao.TagSubscriptionDao
import com.raven.app.core.database.dao.TagTranslationDao
import com.raven.app.core.database.dao.TimeBookmarkDao
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.entity.BrowsePositionEntity
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.ImageFavoriteEntity
import com.raven.app.core.database.entity.MediaTagCacheEntity
import com.raven.app.core.database.entity.PageBookmarkEntity
import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.database.entity.ReadingProgressEntity
import com.raven.app.core.database.entity.SavedSearchEntity
import com.raven.app.core.database.entity.SearchHistoryEntity
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

    companion object {
        const val DATABASE_NAME: String = "raven.db"
        const val SCHEMA_VERSION: Int = 3
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(watchLaterSeedCallback)
                .build()
    }
}
