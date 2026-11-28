package com.raven.app.core.media.reader

import com.raven.app.core.model.SiteId
import java.io.File

/**
 * Reading-cache accounting and eviction (LRU by modified time) shared with the gallery page
 * repository. Extracted so cache math can be tested without a repository instance.
 */
internal class GalleryReadingCacheStore(
    private val baseDir: File,
    private val legacyBaseDir: File?,
    private val siteDir: (SiteId) -> File,
) {
    private val trimLock = Any()

    fun stats(siteId: SiteId): Pair<Int, Long> {
        val files = imagesIn(siteDir(siteId))
        val uniquePages = files.distinctBy { it.nameWithoutExtension }.size
        return uniquePages to files.sumOf(File::length)
    }

    fun totalBytes(): Long {
        val current = baseDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sumOf { siteDir -> imagesIn(siteDir).sumOf(File::length) }
        val legacy = legacyBaseDir
            ?.takeIf { it.isDirectory && it.canonicalPath != baseDir.canonicalPath }
            ?.let { legacyRoot ->
                legacyRoot.listFiles().orEmpty()
                    .filter { it.isDirectory }
                    .sumOf { siteDir -> imagesIn(siteDir).sumOf(File::length) }
            } ?: 0L
        return current + legacy
    }

    fun clearSite(siteId: SiteId) {
        siteDir(siteId).listFiles().orEmpty()
            .filter { it.isFile }
            .forEach { file -> check(file.delete()) { "部分图页缓存无法删除，请重试" } }
    }

    fun clearAll() {
        synchronized(trimLock) {
            baseDir.listFiles().orEmpty().forEach { siteDir ->
                if (siteDir.isDirectory) {
                    siteDir.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
                }
            }
            legacyBaseDir
                ?.takeIf { it.isDirectory && it.canonicalPath != baseDir.canonicalPath }
                ?.deleteRecursively()
        }
    }

    private fun imagesIn(dir: File): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && (it.extension == "image" || it.extension == "strips") }

    fun trim() {
        synchronized(trimLock) {
            val files = baseDir.listFiles().orEmpty()
                .filter { it.isDirectory }
                .flatMap(::imagesIn)
                .sortedBy { it.lastModified() }
            var total = files.sumOf(File::length)
            for (file in files) {
                if (total <= GalleryPageRepository.READING_CACHE_MAX_BYTES) break
                val len = file.length()
                if (file.delete()) {
                    total -= len
                    file.parentFile?.let { parent ->
                        File(parent, "${file.nameWithoutExtension}.page.json").delete()
                    }
                }
            }
        }
    }
}
