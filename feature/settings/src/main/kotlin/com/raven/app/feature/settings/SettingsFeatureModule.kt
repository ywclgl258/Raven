package com.raven.app.feature.settings

import com.raven.app.core.common.TagDictionary
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.storage.DuplicateSource
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.network.RavenOkHttpPool
import java.io.File
import android.os.Build
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI module for `:feature:settings` per `ARCHITECTURE.md` §3.1.
 *
 * Registers [RavenUpdater], [BackupSafBridge], [StorageMigrationExecutor], [DuplicateCopyDeleter],
 * and all 6 Settings ViewModels:
 * [SettingsViewModel], [SiteAccountViewModel], [BlockedTermsViewModel],
 * [BackupRestoreViewModel], [StorageMigrationViewModel], and [DuplicateScannerViewModel].
 */
val settingsFeatureModule: Module = module {
    single<BackupSafBridge> {
        AndroidBackupSafBridge(
            context = androidContext(),
            dispatchers = get(),
        )
    }

    single<ApkSignatureVerifier> {
        val info = getOrNull<RavenAppInfo>() ?: RavenAppInfo.fromContext(androidContext())
        AndroidApkSignatureVerifier(
            context = androidContext(),
            appInfo = info,
        )
    }

    single {
        val appContext = androidContext()
        val info = getOrNull<RavenAppInfo>() ?: RavenAppInfo.fromContext(appContext)
        val updatesDir = File(appContext.cacheDir, "updates")
        RavenUpdater(
            appInfo = info,
            metadataClient = get<RavenOkHttpPool>().externalApiClient,
            downloadClient = get<RavenOkHttpPool>().externalDownloadClient,
            updatesDir = updatesDir,
            signatureVerifier = get(),
            stateStore = FileUpdateStateStore(
                stateFile = File(updatesDir, "updater-state.json"),
                dispatchers = get(),
            ),
            dispatchers = get(),
        )
    }

    single {
        val info = getOrNull<RavenAppInfo>() ?: RavenAppInfo.fromContext(androidContext())
        FeedbackClient(
            httpClient = FeedbackClient.diagnosticClient(get<RavenOkHttpPool>().externalApiClient),
            appInfo = info,
            dispatchers = get(),
        )
    }

    single {
        val appContext = androidContext()
        val info = getOrNull<RavenAppInfo>() ?: RavenAppInfo.fromContext(appContext)
        EhTagTranslationSyncer(
            apiClient = get<RavenOkHttpPool>().externalApiClient,
            tagDictionaryRepository = get(),
            preferencesRepository = get(),
            appInfo = info,
            dispatchers = get(),
        )
    }

    single {
        val appContext = androidContext()
        val info = getOrNull<RavenAppInfo>() ?: RavenAppInfo.fromContext(appContext)
        WebDavSyncClient(
            apiClient = get<RavenOkHttpPool>().externalApiClient,
            backupDataRepository = get(),
            backupCodec = get(),
            backupValidator = get(),
            preferencesRepository = get(),
            appInfo = info,
            dispatchers = get(),
        )
    }

    single<StorageMigrationExecutor> {
        val coordinator = get<DownloadCoordinator>()
        StorageMigrationExecutor { onProgress ->
            coordinator.migrateToSharedStorage(onProgress)
        }
    }

    single<DuplicateCopyDeleter> {
        val coordinator = get<DownloadCoordinator>()
        val localImporter = get<LocalLibraryImporter>()
        DuplicateCopyDeleter { copy ->
            when (copy.source) {
                DuplicateSource.DOWNLOAD -> coordinator.delete(copy.id, sourceRoot = copy.sourceRoot)
                DuplicateSource.LOCAL -> localImporter.removeBook(copy.mediaId)
            }
        }
    }

    viewModel {
        val appContext = androidContext()
        val info = getOrNull<RavenAppInfo>() ?: RavenAppInfo.fromContext(appContext)
        FeedbackViewModel(
            feedbackClient = get(),
            appInfo = info,
            diagnostics = getOrNull<FeedbackDiagnosticSource>() ?: EmptyFeedbackDiagnosticSource(),
            deviceSnapshot = FeedbackDeviceSnapshot(
                model = Build.MODEL ?: "unknown",
                androidRelease = Build.VERSION.RELEASE ?: "unknown",
                sdkInt = Build.VERSION.SDK_INT,
                locale = java.util.Locale.getDefault().toLanguageTag(),
            ),
            dispatchers = get(),
        )
    }

    viewModel {
        val info = getOrNull<RavenAppInfo>() ?: RavenAppInfo.fromContext(androidContext())
        SettingsViewModel(
            preferencesRepository = get(),
            blockedTermsRepository = get(),
            siteRegistry = get(),
            galleryPageRepository = get(),
            storageRootManager = get(),
            networkDiagnostics = get(),
            networkMonitor = get(),
            ravenUpdater = get(),
            appInfo = info,
            dispatchers = get(),
            tagDictionaryRepository = get(),
            downloadCaches = getOrNull(),
            imageLoader = getOrNull(),
            privacyLockStore = getOrNull(),
        )
    }

    viewModel {
        TagDictionaryViewModel(
            tagDictionaryRepository = get(),
            preferencesRepository = get(),
            syncer = get(),
            dispatchers = get(),
        )
    }

    viewModel { params ->
        SiteAccountViewModel(
            route = params.get<RavenRoute.SiteAccount>(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }

    viewModel { params ->
        BlockedTermsViewModel(
            route = params.get<RavenRoute.BlockedTerms>(),
            siteRegistry = get(),
            blockedTermsRepository = get(),
            tagDictionary = getOrNull<TagDictionary>() ?: TagDictionary.default,
            dispatchers = get(),
            preferencesRepository = get(),
            tagDictionaryRepository = get(),
        )
    }

    viewModel {
        val info = getOrNull<RavenAppInfo>() ?: RavenAppInfo.fromContext(androidContext())
        BackupRestoreViewModel(
            backupDataRepository = get(),
            backupCodec = get(),
            backupValidator = get(),
            safBridge = get(),
            appInfo = info,
            dispatchers = get(),
            webDavCredentialStore = getOrNull(),
            webDavSyncClient = getOrNull(),
            preferencesRepository = getOrNull(),
        )
    }

    viewModel {
        StorageMigrationViewModel(
            storageRootManager = get(),
            migrationExecutor = get(),
            dispatchers = get(),
        )
    }

    viewModel {
        DuplicateScannerViewModel(
            duplicateScanner = get(),
            copyDeleter = get(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }
}
