package com.raven.app.feature.settings

import android.net.Uri
import com.raven.app.core.model.SiteId
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Decoupled navigation and platform activity callback contract for `:feature:settings`.
 * `:app` wires this to Type-Safe Navigation Compose (`NavController`), `SiteAuthActivity`,
 * Storage Access Framework document launchers, and APK installer handoff.
 */
interface SettingsNavigator {
    fun openSiteAccount(siteId: SiteId)

    fun openBlockedTerms(siteId: SiteId)

    fun openTagDictionary()

    fun openBackupRestore()

    fun openStorageMigration()

    fun openDuplicateScanner()

    fun launchWebViewAuth(siteId: SiteId)

    fun launchCreateBackupDocument(
        suggestedFileName: String = SettingsExportDefaults.defaultBackupFileName(),
        onDocumentCreated: (Uri?) -> Unit,
    )

    fun launchOpenBackupDocument(
        onDocumentOpened: (Uri?) -> Unit,
    )

    fun openAppUpdateInstaller(apkFile: File)

    fun navigateBack()
}

object SettingsExportDefaults {
    const val BACKUP_MIME_TYPE: String = "application/octet-stream"
    const val BACKUP_FILE_EXTENSION: String = ".rvnbk"

    fun defaultBackupFileName(timestampMs: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
        return "raven-backup-${formatter.format(Date(timestampMs))}$BACKUP_FILE_EXTENSION"
    }
}
