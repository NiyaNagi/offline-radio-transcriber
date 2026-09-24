package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.LogFrequencyHeaderMapper
import org.ort.app.ui.data.LogFrequencyHeaderViewState
import org.ort.app.ui.data.RealLogFrequencyEditor
import org.ort.app.ui.failures.DebugFailureOverride
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

/**
 * **R-1181: the two frames the tour takes of the Log's frequency header, each proved to be a state
 * a real operator can actually reach.**
 *
 * The finding this file exists for is not a code defect at all — the mapper is right and its
 * twenty-three tests pass. It is that `overnight/L01-log` was about to be baked into the canonical
 * capture set showing **`Frequency / not set`** directly above quick-filter chips reading `145.230`
 * and `146.960` and a list whose every row carried a real `FREQ` value, because the fixture seeded
 * per-over frequencies but left [org.ort.pipeline.rig.CaptureConfigurationStore] — the store
 * `RealCaptureService` itself reads at session start, and the only one the header reads — empty.
 * A screenshot showed it; no test could, because nothing asserted what the *fixture* leaves the
 * header saying.
 *
 * So both frames are pinned here, through the real production read path
 * ([RealLogFrequencyEditor] over the real `SharedPreferences`, then the real mapper), never through
 * a hand-made facts object:
 *
 * 1. **`overnight` — a configured device.** It names a USB session on 145.230 MHz by hand, so the
 *    header must show that number. This is the frame the register found wrong.
 * 2. **`gap-call` — genuinely nothing entered anywhere.** Its own tour steps
 *    (`gap-call/L01-log-gap` at 1.0, 2.0 and 2.0-end) are where the unset header legitimately
 *    belongs, and they already exist, which is why R-1181's "add a fixture that genuinely has no
 *    frequency anywhere" needed no new scenario: one was already in the tour, and the copy is what
 *    had to change so that frame stops contradicting the rows beneath it.
 *
 * `ScenariosTest.kt` split — detekt's `LargeClass` finding, the same fix
 * `FailureOverrideScenariosTest.kt` and `ResolverOutputScenariosTest.kt` already establish as
 * house style: its own file, with `@Before`/`@After` duplicated rather than shared.
 */
@RunWith(RobolectricTestRunner::class)
class LogFrequencyScenarioSeedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    /** See `ScenariosTest.closeDatabase`'s own doc comment for why this matters across many loads. */
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
        DebugFailureOverride.clear()
        DebugBundledAssetSourceOverride.clear()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** The header exactly as `LogPolling` builds it for the screen, from the real store the
     * scenario wrote and the real process-wide rig/capture holders it published. */
    private fun headerAfterLoad() = LogFrequencyHeaderMapper.from(RealLogFrequencyEditor(context).facts())

    @Test
    @Requirement("R-1181", "AC-202", "constitution VIII")
    fun `R_1181 overnight seeds the store the header reads, so its capture shows the value it labels overs with`() =
        runTest {
            Scenarios.load(context, "overnight")

            assertEquals(
                "the fixture names a USB session on 145.230 MHz by hand; the store capture reads must say so",
                145_230_000L,
                RealLogFrequencyEditor(context).facts().activeManualHz,
            )
            val header = requireNotNull(headerAfterLoad()) { "no rig is reporting, so the row must be present" }
            assertTrue(
                "the canonical capture must not read 'not set' above overs that carry frequencies: $header",
                header.valueLabel != LogFrequencyHeaderViewState.NOT_SET_LABEL,
            )
            assertNull("a configured frequency and no rig is an ordinary state, with nothing extra to say", header.note)
        }

    @Test
    @Requirement("R-1181", "AC-202", "constitution I")
    fun `R_1181 gap-call leaves the frequency genuinely unset everywhere, and the note it earns is scoped`() = runTest {
        Scenarios.load(context, "gap-call")

        assertNull(
            "this is the honest empty frame — nothing may fabricate a frequency for it",
            RealLogFrequencyEditor(context).facts().activeManualHz,
        )
        val header = requireNotNull(headerAfterLoad())
        assertEquals(LogFrequencyHeaderViewState.NOT_SET_LABEL, header.valueLabel)
        val note = requireNotNull(header.note) { "an unset header has to say why" }
        // Its overs *do* carry `FREQ` values (`OvernightScenario`'s own `FREQ_A`), which is
        // precisely the arrangement R-1181 found the old, unscoped sentence false in.
        assertTrue("the claim has to be scoped to overs from here on: $note", note.contains("from here on"))
        assertTrue("capture runs fine with no frequency — this is a fact, not a fault", !header.noteIsWarning)
    }
}
