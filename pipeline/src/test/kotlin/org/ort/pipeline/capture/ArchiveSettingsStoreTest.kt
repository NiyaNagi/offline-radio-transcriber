package org.ort.pipeline.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/** WPARC (FR-SEG-9, FR-STO-3d, D39). */
@RunWith(RobolectricTestRunner::class)
class ArchiveSettingsStoreTest {

    private fun prefs(context: Context = ApplicationProvider.getApplicationContext()) =
        context.getSharedPreferences(SharedPreferencesArchiveSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    @Requirement("D39")
    fun `D39 the archive defaults on at a 60 GB budget`() {
        val store = SharedPreferencesArchiveSettingsStore(prefs())
        assertTrue("the continuous archive defaults ON (D39)", store.archiveEnabled)
        assertEquals(60, store.archiveBudgetGb)
    }

    @Test
    @Requirement("D39")
    fun `a written value round-trips through the real SharedPreferences file`() {
        val store = SharedPreferencesArchiveSettingsStore(prefs())
        store.archiveEnabled = false
        store.archiveBudgetGb = 30
        assertEquals(false, store.archiveEnabled)
        assertEquals(30, store.archiveBudgetGb)
    }

    @Test
    @Requirement("D39")
    fun `a value written through one store instance is visible through a second instance over the same file`() {
        val first = SharedPreferencesArchiveSettingsStore(prefs())
        first.archiveEnabled = false
        first.archiveBudgetGb = 15

        // A "second reader" -- a fresh SharedPreferences handle and a fresh store instance, the
        // same pattern `RealCaptureService.Dependencies.captureConfigurationStore`'s own kdoc
        // documents for the shared-file contract with `:app`'s settings screens.
        val second = SharedPreferencesArchiveSettingsStore(prefs())
        assertEquals(false, second.archiveEnabled)
        assertEquals(15, second.archiveBudgetGb)
    }

    @Test
    @Requirement("D39")
    fun `the in-memory fake defaults match the real store's D39 defaults`() {
        val fake = InMemoryArchiveSettingsStore()
        assertTrue(fake.archiveEnabled)
        assertEquals(60, fake.archiveBudgetGb)
    }
}
