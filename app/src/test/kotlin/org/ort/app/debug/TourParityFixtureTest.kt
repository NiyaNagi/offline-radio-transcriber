package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.failures.FailureMapper
import org.ort.app.ui.failures.FailurePresentation
import org.ort.app.ui.failures.FailureSignalsPolling
import org.ort.app.ui.settings.InMemorySettingsStore
import org.ort.app.ui.settings.SettingsPolling
import org.ort.app.ui.settings.SharedPreferencesSettingsStore
import org.ort.app.ui.setup.RadioChoice
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * R-440/R-411/R-410 (register, WP12's own tour finding): `ScreenshotTourActivity` and
 * `ScenarioReceiver` both run only [Scenarios.load] — a scenario that relies on state an *earlier
 * Setup run* left on a shared AVD, rather than seeding it itself, renders correctly during a manual
 * pass and wrong on a clean install (the tour's own environment). Every case here starts from a
 * clean [SharedPreferencesSetupStore]/[SharedPreferencesSettingsStore]/model-file state (this
 * class's own `@Before`), the same as a clean install, so a fixture that still passes here is
 * provably not relying on carried-over state.
 */
@RunWith(RobolectricTestRunner::class)
class TourParityFixtureTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        ScenarioFixtures.uninstallEveryModelFixture(context)
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @After
    fun resetProcessWideAvailability() {
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        ScenarioFixtures.uninstallEveryModelFixture(context)
    }

    @Test
    @Requirement("R-440")
    fun `R_440 overnight seeds a verified input, a radio choice, a storage budget and every model`() = runTest {
        Scenarios.load(context, "overnight")

        val setupStore = SharedPreferencesSetupStore(
            context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        )
        assertEquals("USB Audio Device", setupStore.selectedInputLabel)
        assertTrue("expected the input to be verified", setupStore.inputVerified)
        assertEquals(RadioChoice.NONE, setupStore.radioChoice)
        assertEquals(145_230_000L, setupStore.manualFrequencyHz)

        val settingsStore = SharedPreferencesSettingsStore(
            context.getSharedPreferences(
                SharedPreferencesSettingsStore.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            ),
        )
        assertEquals(60, settingsStore.audioBudgetGb)

        ModelCatalog.entries.forEach { entry ->
            val destination = entry.destination(context.filesDir)
            val marker = File(destination.parentFile, destination.name + ".sha256")
            assertTrue("expected ${entry.id} installed on disk", destination.isFile && marker.isFile)
        }
    }

    @Test
    @Requirement("R-440")
    fun `R_440 overnight-live and stations-14-nights also seed the configured device state`() = runTest {
        Scenarios.load(context, "overnight-live")
        val liveStore = SharedPreferencesSetupStore(
            context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        )
        assertTrue(liveStore.inputVerified)

        Scenarios.load(context, "stations-14-nights")
        val stationsStore = SharedPreferencesSetupStore(
            context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        )
        assertTrue(stationsStore.inputVerified)
    }

    @Test
    @Requirement("R-440")
    fun `R_440 model-missing keeps nothing installed, even after an earlier scenario installed every model`() =
        runTest {
            Scenarios.load(context, "overnight")
            Scenarios.load(context, "model-missing")

            ModelCatalog.entries.forEach { entry ->
                val destination = entry.destination(context.filesDir)
                assertTrue("expected ${entry.id} not installed", !destination.isFile)
            }
        }

    @Test
    @Requirement("R-411")
    fun `R_411 setup-level publishes a real LevelStatus so S07's meter has something to render`() = runTest {
        Scenarios.load(context, "setup-level")

        val state = LevelStatus.state
        assertTrue(state is LevelStatus.State.Measured)
        state as LevelStatus.State.Measured
        assertEquals(-14f, state.peakDbfs)
        assertEquals(-58f, state.noiseFloorDbfs)
    }

    @Test
    @Requirement("R-410")
    fun `R_410 gap-call raises the real, live F15 call-gap banner through FailureMapper end to end`() = runTest {
        val result = Scenarios.load(context, "gap-call")
        val sessionId = requireNotNull(result.primarySessionId)

        assertTrue("expected gap-call to be a genuinely live, capturing session", CaptureState.isCapturing)
        val signals = FailureSignalsPolling.current(context, sessionId)
        val presentation = FailureMapper.map(signals)

        assertTrue(
            "expected FailureMapper to map gap-call's own newest CALL gap to Fail-Call, got $presentation",
            presentation is FailurePresentation.Call,
        )
    }

    @Test
    @Requirement("R-494")
    fun `R_494 overnight's Input rows read the real verified device, through SettingsPolling end to end`() = runTest {
        Scenarios.load(context, "overnight")

        // CF01's own root-list summary line — confirmed by reading SettingsPolling.kt before this
        // fix: it reads the live InputStatus holder, never SetupStore directly (the register's own
        // guess), so seeding SetupStore alone (last round's own R-440 fix) could never move this.
        val root = SettingsPolling.root(context, InMemorySettingsStore())
        val inputRow = root.sections.flatMap { it.rows }.single { it.label == "Input and level" }
        assertEquals("USB Audio Device · verified", inputRow.subLine)

        // CF02's own Input row.
        val capture = SettingsPolling.capture(InMemorySettingsStore())
        assertEquals("USB Audio Device", capture.inputLabel)
        assertTrue("expected the route to read verified", capture.inputSubLine.contains("verified"))
    }

    @Test
    @Requirement("R-494")
    fun `R_494 overnight's radio stays honestly Absent, the correct state for no rig chosen`() = runTest {
        Scenarios.load(context, "overnight")

        // CF06 (SettingsRigScreen.kt, WP10's own file, register R-444) already renders "No rig
        // module is connected" / "No radio support in this build yet..." for any non-Connected
        // RigStatus — confirmed here, not assumed, that Absent (this object's own honest default)
        // is what overnight's own "no rig, 145.230 MHz by hand" configuration actually produces,
        // so that existing copy is what a validator sees, not a fabricated Connected state FR-RIG's
        // own unbuilt module could never really report.
        val rig = SettingsPolling.rig()
        assertTrue("expected RigStatus to stay honestly Absent/not-connected", !rig.connected)
    }
}
