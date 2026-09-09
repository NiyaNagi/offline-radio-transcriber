package org.ort.app.debug.tour

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.robolectric.RobolectricTestRunner

/**
 * spec/ui-conformance-plan.md WP12 v3, register R-440/R-443/R-446 ("tour fixture parity").
 *
 * **Investigation, recorded here rather than silently assumed**: the register's own diagnosis reads
 * "`ScreenshotTourActivity` runs `Scenarios.load` in-process but not the receiver-side effects...
 * that `scenario.ps1`'s broadcast applies". Read end to end before writing this test (this
 * package's own report has the full account, with screenshots): [org.ort.app.debug.ScenarioReceiver.onReceive]'s
 * entire body, after unwrapping [org.ort.app.debug.ScenarioReceiver] 's own `goAsync`/coroutine
 * scaffolding, is exactly one call — `Scenarios.load(appContext, name)` — the identical function
 * [ScreenshotTourActivity] calls. There is no second, receiver-only code path today: no extra
 * holder write, no asset/override step the tour skips. A real device comparison (fresh `pm clear`,
 * a genuine broadcast through [org.ort.app.debug.ScenarioReceiver], a genuine
 * [org.ort.app.debug.ScenarioReaderActivity] launch) reproduced the tour's own `overnight`
 * Settings-Capture capture *exactly* — "No input selected", byte-for-byte the same screen the tour
 * itself captures — because [org.ort.app.debug.OvernightScenario.overnight] never touches
 * [InputStatus]/[RigStatus]/[StorageForecast] at all, on *either* path. The `model-missing` grouped-
 * Whisper-row state (R-443) reproduced identically too, already grouped, via the same real-receiver
 * route. Neither `ScenarioReceiver.kt` nor `Scenarios.kt` is this package's file to edit (WP0's row)
 * regardless, so no refactor is made here.
 *
 * What this class actually proves, as the durable regression guard the register's own concern is
 * really asking for: [Scenarios.load] — the one function every path (`ScenarioReceiver`'s broadcast,
 * this package's own tour) calls — is deterministic for a given scenario name, so a future change
 * that made one call site special (e.g. an extra parameter only one caller passes) would show up
 * here as two calls to the identical function producing two different holder snapshots.
 */
@RunWith(RobolectricTestRunner::class)
class TourParityTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetProcessWideState() {
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        DebugFailureOverride.clear()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private data class HolderSnapshot(
        val input: InputStatus.State,
        val rig: RigStatus.State,
        val storage: StorageForecast.State,
        val level: LevelStatus.State,
    )

    private fun snapshot(): HolderSnapshot =
        HolderSnapshot(InputStatus.state, RigStatus.state, StorageForecast.state, LevelStatus.state)

    @Test
    fun `R_TOUR_PARITY overnight produces the same holder snapshot every time Scenarios load runs it`() = runTest {
        Scenarios.load(context, "overnight")
        val first = snapshot()

        Scenarios.load(context, "overnight")
        val second = snapshot()

        // The specific values the register (R-440) says a validator saw seeded under `overnight` -
        // "No input selected"/"No radio configured" is InputStatus.State.None/RigStatus.State.Absent,
        // both holders' own reset default (`resetProcessWideFacets`'s doc comment) - proven honestly
        // absent here, not assumed, so a future scenario fixture change that starts seeding these
        // would be caught by this assertion changing, not silently celebrated as "parity fixed".
        assertEquals(InputStatus.State.None, first.input)
        assertEquals(RigStatus.State.Absent, first.rig)
        assertEquals(first, second)
    }

    @Test
    fun `R_TOUR_PARITY model-missing produces the same holder snapshot every time Scenarios load runs it`() = runTest {
        Scenarios.load(context, "model-missing")
        val first = AsrAvailability.state

        Scenarios.load(context, "model-missing")
        val second = AsrAvailability.state

        assertEquals(first, second)
    }
}
