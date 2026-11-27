package com.raven.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row journal binding a committed backup restore to the settings snapshot that still has
 * to reach Preferences DataStore.
 *
 * Room and Preferences DataStore cannot share a transaction, so a restore writes this row **inside
 * the same Room transaction that replaces the library rows**. That makes the two inseparable:
 * - process dies before the transaction commits -> neither the library rows nor the journal exist;
 * - process dies after it commits -> the journal exists together with the new data and is replayed
 *   on the next start, export, or restore.
 *
 * The row is deleted only after the DataStore settings write succeeded. Replaying the same
 * snapshot twice is idempotent, so a crash between the DataStore write and the delete is harmless.
 */
@Entity(tableName = "backup_restore_journal")
data class BackupRestoreJournalEntity(
    /** Always [SINGLETON_ID]; kept as a primary key so the journal can never hold two restores. */
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "restore_id") val restoreId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Portable settings map serialized as JSON (`Map<String, String>`). */
    @ColumnInfo(name = "settings_json") val settingsJson: String,
    /** `siteId -> ComicLanguage.name` serialized as JSON. */
    @ColumnInfo(name = "site_languages_json") val siteLanguagesJson: String,
) {
    companion object {
        const val SINGLETON_ID: Int = 1
        const val TABLE_NAME: String = "backup_restore_journal"
    }
}
