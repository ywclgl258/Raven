package com.raven.app.core.security

import android.content.Context
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteSessionStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin DI module for `:core:security`.
 * Binds [SessionVault] (and [SiteSessionStore]), [BackupValidator], and [BackupCodec].
 * Uses lazy `{ get<SiteRegistry>() }` resolution to prevent circular startup cycles with site plugins.
 */
val coreSecurityModule: Module = module {
    single<VaultCipher> { AndroidKeyStoreVaultCipher() }
    single<VaultStorage> {
        SharedPreferencesVaultStorage(
            androidContext().getSharedPreferences(SessionVault.DEFAULT_PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    single { SessionVault(cipher = get(), storage = get()) } bind SiteSessionStore::class
    single {
        PrivacyLockStore(
            cipher = get(),
            storage = SharedPreferencesVaultStorage(
                androidContext().getSharedPreferences(PrivacyLockStore.DEFAULT_PREFS_NAME, Context.MODE_PRIVATE),
            ),
        )
    }
    single {
        WebDavCredentialStore(
            cipher = get(),
            storage = SharedPreferencesVaultStorage(
                androidContext().getSharedPreferences(WebDavCredentialStore.DEFAULT_PREFS_NAME, Context.MODE_PRIVATE),
            ),
        )
    }
    single {
        LanSyncTrustStore(
            cipher = get(),
            storage = SharedPreferencesVaultStorage(
                androidContext().getSharedPreferences(LanSyncTrustStore.DEFAULT_PREFS_NAME, Context.MODE_PRIVATE),
            ),
        )
    }
    single { BackupValidator(siteRegistryProvider = { get<SiteRegistry>() }) }
    single { BackupCodec(validator = get(), siteRegistryProvider = { get<SiteRegistry>() }) }
}
