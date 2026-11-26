package com.raven.app.core.media.storage

import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.media.TestMediaFixtures
import com.raven.app.core.model.StorageLocation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LocalImporterAndStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (bytes.isNotEmpty()) zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `local importer sorts pages naturally, ignores MACOSX and dotfiles, and deduplicates via SHA-256`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val importer = LocalLibraryImporter(
                context = null,
                rootDir = tempFolder.newFolder("local-lib"),
                dispatchers = CustomAppDispatchers(dispatcher),
                requireAndroidImageDecoder = false,
                freeSpaceProvider = { 10L * 1024L * 1024L * 1024L },
            )

            val p1 = TestMediaFixtures.validPng("page-1")
            val p2 = TestMediaFixtures.validPng("page-2")
            val p10 = TestMediaFixtures.validPng("page-10")

            val archive = buildZip(
                listOf(
                    "__MACOSX/._page1.png" to TestMediaFixtures.validPng("junk"),
                    ".hidden.png" to TestMediaFixtures.validPng("hidden"),
                    "../evil/escape.png" to TestMediaFixtures.validPng("zipslip"),
                    "chapter/.DS_Store" to "mac".toByteArray(),
                    "notes.txt" to "readme".toByteArray(),
                    "chapter/page10.png" to p10,
                    "chapter/page2.png" to p2,
                    "chapter/page1.png" to p1,
                ),
            )

            val book = importer.importArchiveStream("Sample Comic", ByteArrayInputStream(archive))
            assertEquals(3, book.files.size)
            assertEquals(listOf("0.image", "1.image", "2.image"), book.files)

            // Verify natural order: page1 -> 0.image, page2 -> 1.image, page10 -> 2.image
            assertArrayEquals(p1, importer.getPageFile(book.id, 1).readBytes())
            assertArrayEquals(p2, importer.getPageFile(book.id, 2).readBytes())
            assertArrayEquals(p10, importer.getPageFile(book.id, 3).readBytes())

            // Re-importing same content under a different title deduplicates to same SHA-256 ID
            val duplicate = importer.importArchiveStream("Renamed Copy", ByteArrayInputStream(archive))
            assertEquals(book.id, duplicate.id)
            assertEquals(1, importer.listBooks().size)

            // Verify ravenlocal:// and legacy r34local:// URI parsing
            val mediaItem = importer.toMediaItem(book)
            assertEquals("ravenlocal://${book.id}", mediaItem.url)
            assertEquals(book.id, LocalLibraryImporter.parseLocalBookId("ravenlocal://${book.id}"))
            assertEquals(book.id, LocalLibraryImporter.parseLocalBookId("r34local://${book.id}/1"))
            assertNull(LocalLibraryImporter.parseLocalBookId("ravenlocal://not-a-sha256"))
        }

    @Test
    fun `local importer rejects Zip-Bomb entry count, per-entry size, total unpacked bytes, and deep directories`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val importer = LocalLibraryImporter(
                context = null,
                rootDir = tempFolder.newFolder("local-bomb"),
                dispatchers = CustomAppDispatchers(dispatcher),
                requireAndroidImageDecoder = false,
                freeSpaceProvider = { 10L * 1024L * 1024L * 1024L },
            )

            // 1. Entry count bomb (> maxEntries)
            val manyEntriesZip = buildZip((1..12).map { "f$it.txt" to "a".toByteArray() })
            var failedEntryCount = false
            try {
                importer.importArchiveStream("TooMany", ByteArrayInputStream(manyEntriesZip), maxEntries = 10)
            } catch (e: IllegalStateException) {
                failedEntryCount = e.message?.contains("条目过多") == true
            }
            assertTrue(failedEntryCount)

            // 2. Non-image decompression bomb (> maxBookBytes)
            val hugeHiddenEntry = buildZip(
                listOf(
                    "__MACOSX/bomb.bin" to ByteArray(4096) { 1 },
                    "page1.png" to TestMediaFixtures.validPng("ok"),
                ),
            )
            var failedTotalBytes = false
            try {
                importer.importArchiveStream(
                    title = "ByteBomb",
                    inputStream = ByteArrayInputStream(hugeHiddenEntry),
                    maxSingleImageBytes = 8192L,
                    maxBookBytes = 2048L,
                )
            } catch (e: IllegalStateException) {
                failedTotalBytes = e.message?.contains("超过上限") == true
            }
            assertTrue(failedTotalBytes)

            // 3. Directory depth > 8 guard
            var nested = tempFolder.newFolder("deep-root")
            repeat(10) { level ->
                nested = File(nested, "d$level").apply { mkdirs() }
            }
            File(nested, "1.png").writeBytes(TestMediaFixtures.validPng("deep"))
            var failedDepth = false
            try {
                importer.importLocalDirectory(File(tempFolder.root, "deep-root"))
            } catch (e: IllegalStateException) {
                failedDepth = e.message?.contains("目录层级") == true
            }
            assertTrue(failedDepth)
        }

    @Test
    fun `local importer checks video extension, 8GiB cap, 1GiB free space, and deduplicates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = tempFolder.newFolder("local-vid")

        // Low free space rejected
        val lowSpaceImporter = LocalLibraryImporter(
            context = null,
            rootDir = root,
            dispatchers = CustomAppDispatchers(dispatcher),
            requireAndroidImageDecoder = false,
            freeSpaceProvider = { 512L * 1024L * 1024L }, // 512 MiB < 1 GiB
            videoThumbExtractor = { _, thumb -> thumb.writeBytes(TestMediaFixtures.validPng("t")); true },
        )
        var rejectedSpace = false
        try {
            lowSpaceImporter.importVideoStream("clip", "mp4", ByteArrayInputStream("video-bytes".toByteArray()))
        } catch (_: IllegalStateException) {
            rejectedSpace = true
        }
        assertTrue(rejectedSpace)

        // Valid import + thumb + dedup
        val importer = LocalLibraryImporter(
            context = null,
            rootDir = root,
            dispatchers = CustomAppDispatchers(dispatcher),
            requireAndroidImageDecoder = false,
            freeSpaceProvider = { 4L * 1024L * 1024L * 1024L },
            videoThumbExtractor = { _, thumb -> thumb.writeBytes(TestMediaFixtures.validPng("thumb")); true },
        )
        val payload = "fake-mp4-content-12345".toByteArray()
        val imported = importer.importVideoStream("Demo Clip", "mp4", ByteArrayInputStream(payload))
        assertEquals("thumb.png", imported.thumbFileName)
        assertNotNull(importer.getVideoFile(imported.id))
        assertTrue(importer.isLocalVideoComplete(imported.id))

        val reimported = importer.importVideoStream("Another Name", "mp4", ByteArrayInputStream(payload))
        assertEquals(imported.id, reimported.id)
    }

    @Test
    fun `storage root manager creates nomedia, migrates with SHA-256 verification, and defers root swap`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val privateDir = tempFolder.newFolder("private-root")
            val sharedDir = tempFolder.newFolder("shared-root")

            val manager = StorageRootManager(
                context = null,
                settingsDataStore = null,
                privateRootOverride = privateDir,
                sharedRootOverride = sharedDir,
                sharedPermissionProvider = { true },
                dispatchers = CustomAppDispatchers(dispatcher),
            )

            // Seed a complete comic book in PRIVATE layout
            val privateLayout = manager.ensure(StorageLocation.PRIVATE)
            val bookDir = File(privateLayout.comicsDir, "ehentai/${StorageRootManager.sha256Hex("12345")}").apply {
                mkdirs()
            }
            val pageBytes = TestMediaFixtures.validPng("mig-p1")
            File(bookDir, "book.json").writeText("""{"id":"12345"}""")
            File(bookDir, "page1.image").writeBytes(pageBytes)
            File(bookDir, "complete").writeText("1")

            var videoCopyCalled = false
            val report = manager.migratePrivateToShared(
                copyVideos = { videoCopyCalled = true },
                isCompleteComicBook = { dir -> File(dir, "complete").isFile && File(dir, "book.json").isFile },
            )

            assertTrue(videoCopyCalled)
            assertEquals(3, report.copiedFiles)
            assertEquals(StorageLocation.SHARED, report.pendingLocation)
            // .nomedia placed in shared root
            assertTrue(File(sharedDir, ".nomedia").isFile)
            // Migrated file matches byte-for-byte and originals are preserved in PRIVATE
            val migratedBook = File(manager.layoutOf(StorageLocation.SHARED).comicsDir, "ehentai/${StorageRootManager.sha256Hex("12345")}")
            assertArrayEquals(pageBytes, File(migratedBook, "page1.image").readBytes())
            assertTrue(File(bookDir, "page1.image").isFile)
            // Active root remains PRIVATE until cold start applies pending_storage_location
            assertEquals(StorageLocation.PRIVATE, manager.activeLocation())
            assertFalse(manager.canWriteDownloads)

            // Cold start applies pending SHARED root
            val startup = manager.applyPendingOnColdStart()
            assertEquals(StorageLocation.SHARED, startup.activeLocation)
            assertTrue(startup.appliedPending)
            assertTrue(manager.canWriteDownloads)
        }

    @Test
    fun `sidecar overwrite keeps previous file and cleans temp when publish fails`() = runTest {
        val manager = StorageRootManager(
            context = null,
            settingsDataStore = null,
            privateRootOverride = tempFolder.newFolder("sidecar-private"),
            sharedRootOverride = null,
            dispatchers = CustomAppDispatchers(StandardTestDispatcher(testScheduler)),
        )
        val target = File(tempFolder.newFolder("sidecar-meta"), "record.json")
        manager.atomicWriteText(target, "old-valid-content")

        val failed = runCatching {
            manager.atomicWriteText(target, "new-content") { _, _ -> false }
        }

        assertTrue(failed.isFailure)
        assertEquals("old-valid-content", target.readText())
        assertTrue(target.parentFile?.listFiles().orEmpty().none { it.extension == "part" })
    }
}
