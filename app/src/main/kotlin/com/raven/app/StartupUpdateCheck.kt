package com.raven.app

import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.feature.settings.RavenUpdater
import com.raven.app.feature.settings.UpdateCheckOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Latest automatic update result retained for the current process, without persisting credentials. */
internal object StartupUpdateNotifier {
    private val _latestUpdate = MutableStateFlow<UpdateCheckOutcome.UpdateAvailable?>(null)
    val latestUpdate: StateFlow<UpdateCheckOutcome.UpdateAvailable?> = _latestUpdate.asStateFlow()

    fun publish(outcome: UpdateCheckOutcome) {
        if (outcome is UpdateCheckOutcome.UpdateAvailable) {
            _latestUpdate.value = outcome
        }
    }
}

/** Runs after DI initialization and respects the existing release-build and 12-hour auto-check gates. */
internal suspend fun runStartupUpdateCheck(
    updater: RavenUpdater,
    autoUpdateEnabled: Boolean,
): UpdateCheckOutcome {
    if (!updater.shouldRunAutoCheck(autoUpdateEnabled)) return UpdateCheckOutcome.Throttled
    return updater.checkForUpdate(manual = false).also(StartupUpdateNotifier::publish)
}

internal suspend fun runStartupUpdateCheck(
    updater: RavenUpdater,
    preferencesRepository: PreferencesRepository,
): UpdateCheckOutcome = runStartupUpdateCheck(
    updater = updater,
    autoUpdateEnabled = preferencesRepository.currentSettings().autoUpdateCheck,
)
