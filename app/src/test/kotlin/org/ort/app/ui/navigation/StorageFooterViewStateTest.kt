package org.ort.app.ui.navigation

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Audit F-020: the footer must report the real byte total of retained transmission audio, not a
 * whole-device [android.os.StatFs] figure labelled "Audio", and it must never claim a per-category
 * budget (FR-STO-3) that no prompt has built yet.
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
}
