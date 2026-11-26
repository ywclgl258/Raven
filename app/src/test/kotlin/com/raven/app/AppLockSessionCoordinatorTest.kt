package com.raven.app

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.security.PrivacyLockStore
import com.raven.app.core.security.VaultCipher
import com.raven.app.core.security.VaultStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockSessionCoordinatorTest {

    private class FakeVaultCipher : VaultCipher {
        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray = plaintext.copyOf()
        override fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray = ciphertext.copyOf()
    }

    private class InMemoryVaultStorage : VaultStorage {
        private val map = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun keysWithPrefix(prefix: String): Set<String> = map.keys.filter { it.startsWith(prefix) }.toSet()
        override fun clear() {
            map.clear()
        }
    }

    private fun testDispatchers(dispatcher: CoroutineDispatcher): AppDispatchers = object : AppDispatchers {
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }

    private fun createCoordinator(
        pin: String = "123456",
        clockMs: () -> Long = { 1_000L },
    ): AppLockSessionCoordinator {
        val store = PrivacyLockStore(
            cipher = FakeVaultCipher(),
            storage = InMemoryVaultStorage(),
        )
        store.setPin(pin)
        return AppLockSessionCoordinator(
            privacyLockStore = store,
            dispatchers = testDispatchers(UnconfinedTestDispatcher()),
            elapsedRealtimeMs = clockMs,
        )
    }

    @Test
    fun coldStartLocksOnlyAfterRealSettingsEnableAppLock() {
        val coordinator = createCoordinator()
        assertFalse(coordinator.state.value.lockInitialized)
        assertFalse(coordinator.state.value.isLocked)

        coordinator.onSettingsLoaded(AppSettings(appLockEnabled = true))

        assertTrue(coordinator.state.value.lockInitialized)
        assertTrue(coordinator.state.value.isLocked)
    }

    @Test
    fun wrongPinFiveTimesStartsLockoutAndStatePersistsAcrossObservers() = runTest {
        var now = 10_000L
        val coordinator = createCoordinator(pin = "246810", clockMs = { now })
        coordinator.onSettingsLoaded(AppSettings(appLockEnabled = true))

        repeat(4) {
            assertFalse(coordinator.verifyPin("000000", now))
            assertEquals(0L, coordinator.state.value.lockoutUntilElapsedMs)
        }
        assertFalse(coordinator.verifyPin("000000", now))
        assertEquals(5, coordinator.state.value.failedAttempts)
        assertEquals(now + AppLockSessionCoordinator.LOCKOUT_DURATION_MS, coordinator.state.value.lockoutUntilElapsedMs)
        assertTrue(coordinator.state.value.isLocked)

        // Even the correct PIN is rejected while locked out
        assertFalse(coordinator.verifyPin("246810", now + 5_000L))
        assertTrue(coordinator.state.value.isLocked)

        // After lockout expires, valid PIN unlocks and resets counters
        now += AppLockSessionCoordinator.LOCKOUT_DURATION_MS + 1L
        coordinator.onLockoutExpired()
        assertTrue(coordinator.verifyPin("246810", now))
        assertFalse(coordinator.state.value.isLocked)
        assertEquals(0, coordinator.state.value.failedAttempts)
    }

    @Test
    fun internalTransitionDoesNotLockWhileTrueBackgroundTimeoutLocks() = runTest {
        var now = 1_000L
        val coordinator = createCoordinator(pin = "1234", clockMs = { now })
        coordinator.onSettingsLoaded(AppSettings(appLockEnabled = true, appLockTimeoutSeconds = 5))
        assertTrue(coordinator.verifyPin("1234", now))
        assertFalse(coordinator.state.value.isLocked)

        // Short background (< 5s) does not re-lock
        coordinator.onAppBackgrounded(now)
        now += 3_000L
        coordinator.onAppForegrounded(now)
        assertFalse(coordinator.state.value.isLocked)

        // Long background (>= 5s) re-locks
        coordinator.onAppBackgrounded(now)
        now += 6_000L
        coordinator.onAppForegrounded(now)
        assertTrue(coordinator.state.value.isLocked)
    }
}
