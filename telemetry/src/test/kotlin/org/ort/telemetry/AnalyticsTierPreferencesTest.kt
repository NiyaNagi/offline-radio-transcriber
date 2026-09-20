package org.ort.telemetry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * FR-ANL-1/FR-ANL-9: tier 1 is on by default and can be turned off; tiers 2 and 3 are opt-in, off
 * until the operator turns each on individually.
 */
class InMemoryAnalyticsTierPreferencesTest {

    @org.junit.jupiter.api.Test
    @Requirement("AC-172", "AC-173", "AC-174", "FR-ANL-1")
    fun `AC_172_tier 1 defaults on, tiers 2 and 3 default off`() {
        val prefs = InMemoryAnalyticsTierPreferences()

        assertTrue(prefs.isEnabled(AnalyticsTier.TIER_1))
        assertFalse(prefs.isEnabled(AnalyticsTier.TIER_2))
        assertFalse(prefs.isEnabled(AnalyticsTier.TIER_3))
    }

    @org.junit.jupiter.api.Test
    @Requirement("AC-179", "FR-ANL-9")
    fun `AC_179_setEnabled takes effect immediately and independently per tier`() {
        val prefs = InMemoryAnalyticsTierPreferences()

        prefs.setEnabled(AnalyticsTier.TIER_2, true)

        assertTrue(prefs.isEnabled(AnalyticsTier.TIER_2))
        assertFalse(prefs.isEnabled(AnalyticsTier.TIER_3))
        assertTrue(prefs.isEnabled(AnalyticsTier.TIER_1))
    }
}

/** The real, `SharedPreferences`-backed store round-trips through Robolectric — the same
 * discipline `SetupStoreTest` already applies to `SharedPreferencesSetupStore`. */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesAnalyticsTierPreferencesTest {

    private fun realPrefs(): AnalyticsTierPreferences {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesAnalyticsTierPreferences.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return SharedPreferencesAnalyticsTierPreferences(prefs)
    }

    @Test
    fun `defaults match the closed spec — tier 1 on, tiers 2 and 3 off`() {
        val prefs = realPrefs()
        assert(prefs.isEnabled(AnalyticsTier.TIER_1))
        assert(!prefs.isEnabled(AnalyticsTier.TIER_2))
        assert(!prefs.isEnabled(AnalyticsTier.TIER_3))
    }

    @Test
    fun `a toggle survives being read back from a fresh store instance over the same prefs`() {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesAnalyticsTierPreferences.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SharedPreferencesAnalyticsTierPreferences(prefs).setEnabled(AnalyticsTier.TIER_3, true)

        val reopened = SharedPreferencesAnalyticsTierPreferences(prefs)

        assert(reopened.isEnabled(AnalyticsTier.TIER_3))
    }
}
