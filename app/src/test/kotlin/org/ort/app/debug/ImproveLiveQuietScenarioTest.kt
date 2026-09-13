package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.LevelStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1061 (coordinator round 2): the `improve-live-quiet` scenario's own case, split out
 * of `ScenariosTest.kt` purely to keep that file under detekt's `LargeClass` threshold — the same
 * split that file's own doc comment already established for `FieldTier1AudioTest.kt`/
 * `FailureOverrideScenariosTest.kt` (moved verbatim, not suppressed).
 *
 * `Scenarios.kt`'s own `improveLiveQuiet` doc comment has the full account of what this seeds and
 * why neither `field-tier1` (records, not live, no `LevelStatus`) nor `level-low` (live, quiet,
 * zero transmissions) alone stood in for the register's own reported combination.
 */
@RunWith(RobolectricTestRunner::class)
class ImproveLiveQuietScenarioTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @After
    fun resetProcessWideAvailability() {
        CaptureState.idle(clearSession = true)
        LevelStatus.reset()
    }

    @Test
    @Requirement("R-1061")
    fun `R_1061 improve-live-quiet seeds field-tier1s own real Improve records, live, genuinely too quiet`() = runTest {
        val result = Scenarios.load(context, "improve-live-quiet")
        val session = db.sessionDao().getById(requireNotNull(result.primarySessionId))

        // The three factors the register's own evidence needs together — `field-tier1` alone has
        // only the first, `level-low` alone only the second and third (see this scenario's own
        // doc comment for exactly which existing scenario covers which, and why neither stands in).
        assertEquals("T1", session?.deviceTier)
        assertEquals(12, result.transmissionCount)
        val state = LevelStatus.state
        assertTrue(state is LevelStatus.State.Measured)
        state as LevelStatus.State.Measured
        assertEquals(-34f, state.peakDbfs)
        assertFalse(state.clipped)
        assertTrue(CaptureState.isCapturing)
    }
}
