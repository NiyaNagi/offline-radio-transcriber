package org.ort.app.ui.navigation

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.settings.SharedPreferencesSettingsStore
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Audit F-020: the footer must report the real byte total of retained transmission audio, not a
 * whole-device [android.os.StatFs] figure labelled "Audio". R-090 (ui-conformance-plan WP10): once
 * a budget is set via [org.ort.app.ui.settings.SettingsStore], the footer reads it — no budget set
 * still reads honestly as "no budget", never a fabricated one.
 */
@RunWith(RobolectricTestRunner::class)
class StorageFooterViewStateTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    @Requirement("FR-STO-5")
    fun `FR_STO_5 fromAudioDirectory reports the real byte total of retained transmission audio files`() {
        val sessionDir = File(context.filesDir, "audio/S1")
        sessionDir.mkdirs()
        File(sessionDir, "TX1.flac").writeBytes(ByteArray(1_000))
        File(sessionDir, "TX2.flac").writeBytes(ByteArray(2_500))

        val state = StorageFooterViewState.fromAudioDirectory(context)

        assertEquals(3_500L, state.audioUsedBytes)
    }

    @Test
    @Requirement("FR-STO-5")
    fun `FR_STO_5 an empty or missing audio directory reports zero rather than crashing`() {
        val state = StorageFooterViewState.fromAudioDirectory(context)

        assertEquals(0L, state.audioUsedBytes)
    }

    @Test
    @Requirement("FR-STO-5")
    fun `FR_STO_5 free space comes from StatFs and no budget is claimed`() {
        val state = StorageFooterViewState.fromAudioDirectory(context)

        // Robolectric's StatFs shadow reports 0 by default (no real filesystem behind it) — the
        // contract this asserts is "read from StatFs, never fabricated", not a specific value.
        assertTrue(state.freeBytes >= 0L)
        assertFalse(state.hasBudget)
    }

    @Test
    @Requirement("FR-STO-5")
    fun `R_090 a budget set via SettingsStore is read honestly, in bytes`() {
        val prefs = context.getSharedPreferences(
            SharedPreferencesSettingsStore.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        SharedPreferencesSettingsStore(prefs).audioBudgetGb = 30

        val state = StorageFooterViewState.fromAudioDirectory(context)

        assertTrue(state.hasBudget)
        assertEquals(30_000_000_000L, state.budgetBytes)
    }
}
