package com.raven.app.feature.settings

import app.cash.turbine.test
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteId
import com.raven.app.core.security.BackupCodec
import com.raven.app.core.security.BackupFormat
import com.raven.app.core.security.BackupValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createEnv(): Triple<BackupRestoreViewModel, FakeDatabaseBundle, InMemoryBackupSafBridge> {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)
        val saf = InMemoryBackupSafBridge()
        val vm = BackupRestoreViewModel(
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            safBridge = saf,
            appInfo = RavenAppInfo("1.0.0", 1L, false, "com.raven.app"),
            dispatchers = mainDispatcherRule.appDispatchers,
        )
        return Triple(vm, db, saf)
    }

    @Test
    fun `native RVNBK01 export encrypts with valid magic releases SAF grant and round-trips through import preview and atomic merge`() = runTest {
        val (vm, db, saf) = createEnv()

        // Seed local data to export
        db.favoriteDao.upsert(
            FavoriteEntity(
                siteId = SiteId.R34V.value,
                kind = FavoriteKind.VIDEO,
                targetId = "v-100",
                title = "Exported Video",
                url = "https://rule34video.com/video/100/exported/",
                coverUrl = "https://rule34video.com/cover.jpg",
                addedAt = 1_730_000_000_100L,
                extra = emptyMap(),
            ),
        )
        db.watchProgressDao.upsert(
            WatchProgressEntity(
                siteId = SiteId.R34V.value,
                mediaId = "v-100",
                url = "https://rule34video.com/video/100/exported/",
                title = "Exported Video",
                coverUrl = "",
                positionMs = 30_000L,
                durationMs = 120_000L,
                updatedAt = 1_730_000_000_200L,
                completed = false,
                quality = "1080p",
            ),
        )

        val exportUri = "content://saf/raven-export.rvnbk"
        val pass = "RavenPass#2026"

        vm.uiState.test {
            awaitItem()

            // Weak password rejected before export
            vm.updateExportPassword("short7")
            vm.updateExportPasswordConfirm("short7")
            assertFalse(expectMostRecentItem().canStartExport)

            // Valid matching password
            vm.updateExportPassword(pass)
            vm.updateExportPasswordConfirm(pass)
            assertTrue(expectMostRecentItem().canStartExport)

            vm.exportToDocument(exportUri)
            advanceUntilIdle()

            val exportedState = expectMostRecentItem()
            assertNotNull(exportedState.exportCompleted)
            assertEquals(1, exportedState.exportCompleted!!.summary.favoritesCount)
            assertEquals("", exportedState.exportPassword)
            assertEquals("", exportedState.exportPasswordConfirm)
            assertTrue(exportUri in saf.releasedGrants)

            val encryptedBytes = saf.files[exportUri]!!
            assertArrayEquals(BackupCodec.RAVEN_MAGIC_BYTES, encryptedBytes.copyOfRange(0, 8))

            // Wipe local DB and re-import via SAF
            db.favoriteDao.deleteAll()
            db.watchProgressDao.deleteAll()
            saf.releasedGrants.clear()

            vm.selectImportDocument(exportUri)
            advanceUntilIdle()
            val selected = expectMostRecentItem()
            assertTrue(selected.hasSelectedImportFile)
            assertEquals(BackupFormat.RAVEN_V1, selected.detectedImportFormat)

            // Wrong password fails cleanly without releasing staged file
            vm.updateImportPassword("WrongPassword99")
            vm.decryptAndPreviewImport()
            advanceUntilIdle()
            val wrongPwdState = expectMostRecentItem()
            assertNotNull(wrongPwdState.importError)
            assertNull(wrongPwdState.importPreview)

            // Correct password produces BackupSummary preview
            vm.updateImportPassword(pass)
            vm.decryptAndPreviewImport()
            advanceUntilIdle()
            val previewState = expectMostRecentItem()
            val preview = previewState.importPreview
            assertNotNull(preview)
            assertEquals(BackupFormat.RAVEN_V1, preview!!.format)
            assertEquals(1, preview.summary.favoritesCount)
            assertEquals(1, preview.summary.watchProgressCount)

            // Explicit confirm atomically merges and releases SAF URI grant
            vm.confirmAndRestoreImport()
            advanceUntilIdle()
            val restoredState = expectMostRecentItem()
            val restored = restoredState.restoreCompleted
            assertNotNull(restored)
            assertEquals(1, restored!!.mergedSummary.favoritesCount)
            assertEquals(1, db.favoriteDao.getAll().size)
            assertEquals(1, db.watchProgressDao.getAll().size)
            assertTrue(exportUri in saf.releasedGrants)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `legacy R34VBK01 import renders structured preview with retired-site warnings and merges losslessly`() = runTest {
        val (vm, db, saf) = createEnv()
        val localHash = "a".repeat(64)
        val legacyJson = """
            {
              "schema": 1,
              "at": 1725000000000,
              "sites": [
                {
                  "site": "r34v",
                  "favorites": [
                    {"k":"video","t":"888","title":"Legacy Clip","path":"/video/888/clip/","thumb":"","sub":"","at":1725000000100,"site":"r34v"}
                  ],
                  "watches": [
                    {"id":"888","title":"Legacy Clip","thumb":"","path":"/video/888/clip/","pos":60,"dur":120,"at":1725000000200,"q":"720p","site":"r34v"}
                  ],
                  "subscriptions": [
                    {"slug":"cyberpunk","label":"赛博朋克","url":"/tags/cyberpunk/","site":"r34v","at":1725000000300}
                  ],
                  "blocked": ["spam-tag"]
                },
                {
                  "site": "kbj",
                  "favorites": [
                    {"k":"video","t":"kbj-1","title":"Retired Entry","path":"https://www.kbj.im/video/kbj-1","thumb":"","at":1725000000400,"site":"kbj"}
                  ]
                }
              ],
              "personal": {
                "shelves": [
                  {
                    "id": "watch-later",
                    "name": "稍后看",
                    "entries": [
                      {"video":{"id":"$localHash","title":"Local Comic","url":"r34local://$localHash","thumb":"","site":"local","mediaKind":"GALLERY"},"completed":true,"at":1725000000500,"addedAt":1725000000050}
                    ],
                    "note": "queue",
                    "pinned": true,
                    "modifiedAt": 1725000000600
                  }
                ],
                "bookmarks": [
                  {"video":{"id":"500","title":"Comic Book","url":"https://nhentai.net/g/500/","site":"nhentai","mediaKind":"GALLERY"},"page":9,"offset":40,"at":1725000000700}
                ]
              },
              "settings": {
                "translate_tags": "true",
                "download_unmetered": "true",
                "quality": "1080p",
                "native_speed": "1.5"
              }
            }
        """.trimIndent()

        val legacyPass = "LegacyR34v#99"
        val legacyUri = "content://saf/r34v-old.r34vbk"
        saf.files[legacyUri] = encryptLegacyR34vWire(legacyJson, legacyPass.toCharArray())

        vm.uiState.test {
            awaitItem()

            vm.selectImportDocument(legacyUri)
            advanceUntilIdle()
            val detected = expectMostRecentItem()
            assertEquals(BackupFormat.LEGACY_R34V_V1, detected.detectedImportFormat)

            vm.updateImportPassword(legacyPass)
            vm.decryptAndPreviewImport()
            advanceUntilIdle()

            val previewState = expectMostRecentItem()
            val preview = previewState.importPreview
            assertNotNull(preview)
            assertEquals(BackupFormat.LEGACY_R34V_V1, preview!!.format)
            assertEquals(2, preview.summary.favoritesCount)
            assertEquals(1, preview.summary.watchProgressCount)
            assertEquals(1, preview.summary.shelvesCount)
            assertEquals(1, preview.summary.totalBookmarksCount)
            assertEquals(1, preview.summary.tagSubscriptionsCount)
            assertTrue(SiteId.RETIRED_KBJ in preview.summary.retiredSiteIdsPresent)
            assertTrue(preview.warnings.any { it.contains("R34VBK01") })
            assertTrue(preview.warnings.any { it.contains("kbj") })

            vm.confirmAndRestoreImport()
            advanceUntilIdle()

            val done = expectMostRecentItem()
            assertNotNull(done.restoreCompleted)
            assertEquals(BackupFormat.LEGACY_R34V_V1, done.restoreCompleted!!.format)
            assertEquals(2, db.favoriteDao.getAll().size)
            assertTrue(db.favoriteDao.getAll().any { it.siteId == SiteId.RETIRED_KBJ.value })
            assertTrue(db.shelfDao.getAll().any { it.id == Shelf.WATCH_LATER_ID })
            assertTrue(db.shelfItemDao.getAll().any { it.url == "r34local://$localHash" })
            assertEquals(1, db.pageBookmarkDao.getAll().size)
            assertEquals(1, db.blockedTermDao.getAll().size)
            assertTrue(legacyUri in saf.releasedGrants)
            val restoredSettings = db.settingsDataStore.currentSettings()
            assertTrue(restoredSettings.downloadUnmeteredOnly)
            assertEquals("1080p", restoredSettings.preferredQuality)
            assertEquals(1.5f, restoredSettings.defaultPlaybackSpeed, 0.001f)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rejects unrecognized magic immediately and cancelStagedImport releases SAF grant`() = runTest {
        val (vm, _, saf) = createEnv()
        val junkUri = "content://saf/not-a-backup.bin"
        saf.files[junkUri] = "HELLO_WORLD_NOT_A_BACKUP_FILE_1234567890".toByteArray()

        vm.selectImportDocument(junkUri)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasSelectedImportFile)
        assertNotNull(vm.uiState.value.importError)
        assertTrue(junkUri in saf.releasedGrants)

        // Valid format file cancelled before restore releases SAF grant cleanly
        val validUri = "content://saf/valid-cancel.rvnbk"
        val stubHeader = BackupCodec.RAVEN_MAGIC_BYTES + ByteArray(64)
        saf.files[validUri] = stubHeader
        vm.selectImportDocument(validUri)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasSelectedImportFile)

        vm.cancelStagedImport()
        assertFalse(vm.uiState.value.hasSelectedImportFile)
        assertTrue(validUri in saf.releasedGrants)
    }
}
