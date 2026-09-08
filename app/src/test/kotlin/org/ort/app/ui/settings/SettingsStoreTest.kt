package org.ort.app.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R-090 (FR-CFG-1, FR-CON-1, FR-STO-3a, constitution V): [SharedPreferencesSettingsStore]'s
 * off-by-default and null-by-default contract, and that a write survives a fresh store instance
 * reading the same `SharedPreferences` file (the property this store exists to give every other
 * WP10 screen: a setting persists across process death, not just across recomposition). Follows
 * `ui/setup/SetupStoreTest.kt`'s exact shape — the nearest precedent for a settings-store test in
 * this codebase.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private fun realStore(): SettingsStore {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return SharedPreferencesSettingsStore(prefs)
    }

    @Test
    fun `R_090 contribution and every one of its categories default off`() {
        val store = realStore()
        assert(!store.contributionEnabled)
        assert(!store.contributeAudioOfLabelled)
        assert(!store.contributeTranscriptsAndCorrections)
        assert(!store.contributeRejectedSegments)
        assert(!store.contributeResolverStatistics)
    }

    @Test
    fun `R_090 auto-prune defaults off, per FR-STO-3a opt-in`() {
        assert(!realStore().autoPruneEnabled)
    }

    @Test
    fun `R_090 no audio budget, no tier override, no manual frequency is the honest default`() {
        val store = realStore()
        assert(store.audioBudgetGb == null)
        assert(store.tierOverrideName == null)
        assert(store.manualFrequencyMhz == null)
    }

    @Test
    fun `R_090 enhancement toggles match the board's own defaults`() {
        val store = realStore()
        assert(store.noiseReductionEnabled)
        assert(!store.bandPassFilterEnabled)
        assert(store.levelWarnEnabled)
    }

    @Test
    fun `R_090 a write survives a fresh store reading the same preferences file`() {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first = SharedPreferencesSettingsStore(prefs)
        first.contributionEnabled = true
        first.contributeAudioOfLabelled = true
        first.audioBudgetGb = 30
        first.tierOverrideName = "T2"

        val second = SharedPreferencesSettingsStore(prefs)
        assert(second.contributionEnabled)
        assert(second.contributeAudioOfLabelled)
        assert(second.audioBudgetGb == 30)
        assert(second.tierOverrideName == "T2")
    }

    @Test
    fun `R_090 clearing a budget back to null is representable, not merely zero`() {
        val store = realStore()
        store.audioBudgetGb = 30
        store.audioBudgetGb = null
        assert(store.audioBudgetGb == null)
    }

    @Test
    fun `R_090 in-memory fake matches the real store defaults`() {
        val fake = InMemorySettingsStore()
        assert(!fake.contributionEnabled)
        assert(!fake.autoPruneEnabled)
        assert(fake.audioBudgetGb == null)
        assert(fake.noiseReductionEnabled)
        assert(!fake.bandPassFilterEnabled)
        assert(fake.levelWarnEnabled)
    }
}
