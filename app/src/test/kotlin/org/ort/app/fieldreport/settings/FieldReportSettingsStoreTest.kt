package org.ort.app.fieldreport.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FR-OBS-10: "default it to the safe position" — [FieldReportSettingsStore.publicDestinationGuardEnabled]
 * defaults `true` (the guard active), for both the real store and its in-memory fake, and a write
 * survives a fresh store instance reading the same `SharedPreferences` file — the same contract
 * `SettingsStoreTest` already proves for `SettingsStore`.
 */
@RunWith(RobolectricTestRunner::class)
class FieldReportSettingsStoreTest {

    private fun realStore(): FieldReportSettingsStore {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesFieldReportSettingsStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return SharedPreferencesFieldReportSettingsStore(prefs)
    }

    @Test
    fun `FR_OBS_10 the real store defaults the guard enabled — the safe position`() {
        assertTrue(realStore().publicDestinationGuardEnabled)
    }

    @Test
    fun `FR_OBS_10 the in-memory fake defaults the guard enabled too`() {
        assertTrue(InMemoryFieldReportSettingsStore().publicDestinationGuardEnabled)
    }

    @Test
    fun `turning the guard off persists across a fresh store instance reading the same prefs`() {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesFieldReportSettingsStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SharedPreferencesFieldReportSettingsStore(prefs).publicDestinationGuardEnabled = false

        assertTrue(!SharedPreferencesFieldReportSettingsStore(prefs).publicDestinationGuardEnabled)
    }
}
