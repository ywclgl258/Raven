package com.raven.app.core.media.storage

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reclaims `incoming-*` staging folders left behind when the process was killed mid-import.
 *
 * Imports copy into `libraryRoot/incoming-<uuid>` and only then rename it into place, so any
 * staging folder that survives a crash is uncommitted data. Folders are only removed once they are
 * older than [MIN_ORPHAN_AGE_MS] and carry the import naming scheme, so a live import running in
 * another process is never deleted.
 */
object OrphanedImportStaging {
    const val PREFIX: String = "incoming-"
    val MIN_ORPHAN_AGE_MS: Long = TimeUnit.HOURS.toMillis(1)

    data class ReclaimResult(
        val removedFolders: Int,
        val reclaimedBytes: Long,
    )

    fun isStagingName(name: String): Boolean =
        name.startsWith(PREFIX) && name.length > PREFIX.length

    /**
     * @param nowMs injected clock so the age rule stays deterministic in tests.
     * @param minAgeMs minimum age before a staging folder counts as orphaned.
     */
    fun reclaim(
        libraryRoot: File,
        nowMs: Long = System.currentTimeMillis(),
        minAgeMs: Long = MIN_ORPHAN_AGE_MS,
    ): ReclaimResult {
        val rootCanonical = runCatching { libraryRoot.canonicalPath }.getOrNull() ?: return ReclaimResult(0, 0L)
        var removed = 0
        var reclaimed = 0L
        for (child in libraryRoot.listFiles().orEmpty()) {
            if (!child.isDirectory || !isStagingName(child.name)) continue
            val canonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
            // Only direct children of the library root are import staging.
            if (canonical.parentFile?.path != rootCanonical) continue
            val ageMs = nowMs - child.lastModified()
            if (ageMs < minAgeMs) continue
            val size = runCatching {
                child.walkTopDown().filter { it.isFile }.sumOf(File::length)
            }.getOrDefault(0L)
            val deleted = runCatching { child.deleteRecursively() }.getOrDefault(false)
            if (deleted) {
                removed += 1
                reclaimed += size
            }
        }
        return ReclaimResult(removed, reclaimed)
    }
}
