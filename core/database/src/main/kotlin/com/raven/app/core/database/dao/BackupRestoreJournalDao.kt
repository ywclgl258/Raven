package com.raven.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.raven.app.core.database.entity.BackupRestoreJournalEntity

/**
 * Access to the single-row backup restore journal. Every mutation here is meant to run inside the
 * same Room transaction as the library-row replacement (see [com.raven.app.core.database.repository.BackupDataRepository]).
 */
@Dao
interface BackupRestoreJournalDao {
    @Query("SELECT * FROM backup_restore_journal WHERE id = 1")
    suspend fun get(): BackupRestoreJournalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: BackupRestoreJournalEntity)

    @Query("DELETE FROM backup_restore_journal")
    suspend fun clear()
}
