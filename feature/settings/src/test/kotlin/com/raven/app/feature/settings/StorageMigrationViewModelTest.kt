package com.raven.app.feature.settings

import app.cash.turbine.test
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.StorageLocation
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class StorageMigrationViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `blocks migration when shared permission is missing and runs SHA-256 verified migration once granted`() = runTest {
        val db = FakeDatabaseBundle()
        val privateDir = tempFolder.newFolder("private-storage")
        val sharedDir = tempFolder.newFolder("shared-storage")

        // Seed a realistic comic folder in PRIVATE storage
        val bookDir = File(privateDir, "gallery-downloads/nhentai/book1").apply { mkdirs() }
        File(bookDir, "book.json").writeText("""{"id":"book1"}""")
        File(bookDir, "0.image").writeBytes(ByteArray(4096) { 7 })
        File(bookDir, "complete").writeText("1")

        var permissionGranted = false
        val storageRootManager = StorageRootManager(
            context = null,
            settingsDataStore = db.settingsDataStore,
            privateRootOverride = privateDir,
            sharedRootOverride = sharedDir,
            sharedPermissionProvider = { permissionGranted },
            dispatchers = mainDispatcherRule.appDispatchers,
        )

        val vm = StorageMigrationViewModel(
            storageRootManager = storageRootManager,
            migrationExecutor = StorageMigrationExecutor { onProgress ->
                storageRootManager.migratePrivateToShared(
                    copyVideos = {},
                    onProgress = onProgress,
                )
            },
            dispatchers = mainDispatcherRule.appDispatchers,
        )

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(StorageLocation.PRIVATE, initial.status?.activeLocation)
            assertTrue(initial.canGrantSharedPermission)
            assertFalse(initial.canStartMigrationToShared)

            // Attempt without permission yields actionable error
            vm.startMigrationToShared()
            advanceUntilIdle()
            assertNotNull(expectMostRecentItem().errorMessage)

            // Grant permission and refresh
            permissionGranted = true
            vm.refreshStatus()
            advanceUntilIdle()
            val ready = expectMostRecentItem()
            assertFalse(ready.canGrantSharedPermission)
            assertTrue(ready.canStartMigrationToShared)

            // Execute SHA-256 verified migration
            vm.startMigrationToShared()
            advanceUntilIdle()

            val completed = expectMostRecentItem()
            assertFalse(completed.isMigrating)
            assertNull(completed.errorMessage)
            val result = completed.lastResult
            assertNotNull(result)
            assertEquals(3, result!!.copiedFiles)
            assertEquals(StorageLocation.SHARED, result.pendingLocation)
            // Media3 cache spans are copied by the cache owner, so the UI must not claim every
            // byte was SHA-256 verified just because the file walker succeeded.
            assertFalse(result.videoCachesVerified)
            val info = completed.infoMessage.orEmpty()
            assertTrue(info.contains("视频缓存由下载缓存自查"))
            assertFalse(info.contains("完整性校验全部通过"))
            assertEquals(StorageLocation.SHARED, completed.status?.pendingLocation)
            assertEquals(StorageLocation.SHARED, db.settingsDataStore.currentSettings().pendingStorageLocation)
            // Original private files remain intact for rollback
            assertTrue(File(bookDir, "0.image").isFile)
            assertTrue(File(sharedDir, "comics/nhentai/book1/0.image").isFile)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestSwitchToPrivateOnNextRestart schedules deferred switch when currently on SHARED`() = runTest {
        val db = FakeDatabaseBundle()
        val privateDir = tempFolder.newFolder("private-back")
        val sharedDir = tempFolder.newFolder("shared-back")

        db.settingsDataStore.setStorageLocation(StorageLocation.SHARED)
        val storageRootManager = StorageRootManager(
            context = null,
            settingsDataStore = db.settingsDataStore,
            privateRootOverride = privateDir,
            sharedRootOverride = sharedDir,
            sharedPermissionProvider = { true },
            dispatchers = mainDispatcherRule.appDispatchers,
        )
        // Cold start applies SHARED active root
        storageRootManager.ensure(StorageLocation.SHARED)
        storageRootManager.applyPendingOnColdStart()

        val vm = StorageMigrationViewModel(
            storageRootManager = storageRootManager,
            migrationExecutor = StorageMigrationExecutor { error("unused") },
            dispatchers = mainDispatcherRule.appDispatchers,
        )

        vm.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(StorageLocation.SHARED, state.status?.activeLocation)
            assertTrue(state.canScheduleSwitchToPrivate)

            vm.requestSwitchToPrivateOnNextRestart()
            advanceUntilIdle()

            val updated = expectMostRecentItem()
            assertEquals(StorageLocation.PRIVATE, updated.status?.pendingLocation)
            assertEquals(StorageLocation.PRIVATE, db.settingsDataStore.currentSettings().pendingStorageLocation)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
