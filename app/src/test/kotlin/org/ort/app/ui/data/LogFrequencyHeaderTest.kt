package org.ort.app.ui.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-1167, D58, **AC-202** ("first-run setup never asks for the manual frequency; the value remains
 * editable in the log's own header and continues to label overs exactly as before") and **AC-131**
 * (configuration is frozen at session start; a mid-session write becomes `pendingConfiguration`).
 *
 * The three cases this suite exists for are the three places the header could lie: claiming a
 * frequency nobody entered, inviting a silent override of a radio that is reporting its own, and
 * implying an edit applies to the session already running.
 */
@RunWith(RobolectricTestRunner::class)
class LogFrequencyHeaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun facts(
        rigReportedHz: Long? = null,
        activeManualHz: Long? = null,
        pendingManualHz: Long? = null,
        hasPendingConfiguration: Boolean = false,
        isCapturing: Boolean = false,
    ) = LogFrequencyFacts(
        rigReportedHz = rigReportedHz,
        activeManualHz = activeManualHz,
        pendingManualHz = pendingManualHz,
        hasPendingConfiguration = hasPendingConfiguration,
        isCapturing = isCapturing,
    )

    /** Two stores over the *same* freshly-cleared preferences file, differing only in whether they
     * believe capture is running — which is exactly how a real session's frozen configuration is
     * written before the session and edited during it. Returning both makes the freeze rule
     * testable without reaching past [SharedPreferencesCaptureConfigurationStore] into its keys. */
    private fun freshStores(
        name: String,
    ): Pair<
        SharedPreferencesCaptureConfigurationStore,
        SharedPreferencesCaptureConfigurationStore,
        > {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        return SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { false }) to
            SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { true })
    }

    private fun freshStore(name: String) = freshStores(name).first

    // -------------------------------------------------------------------------------------------
    // The mapper's three cases.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("AC-202", "FR-RIG-8")
    fun `AC_202 the header is absent when a rig reports a frequency and nothing is overriding it`() {
        assertNull(
            "a reporting rig with no hand-entered value leaves the header nothing to add",
            LogFrequencyHeaderMapper.from(facts(rigReportedHz = 146_520_000L)),
        )
    }

    @Test
    @Requirement("AC-202", "constitution I")
    fun `AC_202 with no rig and nothing entered the header reads not set and never a fabricated number`() {
        val state = requireNotNull(LogFrequencyHeaderMapper.from(facts()))

        assertEquals(LogFrequencyHeaderViewState.NOT_SET_LABEL, state.valueLabel)
        assertEquals(LogFrequencyHeaderAction.EDIT, state.action)
        assertTrue("an unset header has nothing to attribute", state.sourceLabel == null)
        assertTrue("it says why it is unset", state.note != null)
        // Capture runs fine with no frequency (R-1167), so the absence is a fact, never a warning.
        assertTrue("an honest absence must not be dressed as a fault", !state.noteIsWarning)
        assertTrue("there is nothing to clear yet", !state.hasValue)
    }

    @Test
    @Requirement("AC-202")
    fun `AC_202 a hand-entered value is shown with its provenance and offers an edit`() {
        val state = requireNotNull(LogFrequencyHeaderMapper.from(facts(activeManualHz = 145_230_000L)))

        assertEquals("145.230 MHz", state.valueLabel)
        assertEquals("by hand", state.sourceLabel)
        assertEquals(LogFrequencyHeaderAction.EDIT, state.action)
        assertTrue("nothing extra is true about a plain hand-entered value", state.note == null)
        assertTrue(state.hasValue)
    }

    @Test
    @Requirement("R-1167", "FR-RIG-8", "constitution I")
    fun `R_1167 a hand-entered value beside a reporting rig names both numbers and offers only to stop overriding`() {
        val state = requireNotNull(
            LogFrequencyHeaderMapper.from(facts(rigReportedHz = 146_520_000L, activeManualHz = 145_230_000L)),
        )

        // The header must not offer an edit here: RigSupervisor gives the manual value precedence
        // over the radio (FR-RIG-8), so an editable field beside a live rig is a silent override.
        assertEquals(LogFrequencyHeaderAction.CLEAR_OVERRIDE, state.action)
        val note = requireNotNull(state.note) { "the conflict must be stated, not hidden" }
        assertTrue("the note names the radio's own reading: $note", note.contains("146.520"))
        assertTrue("an override that relabels every over is a real problem, not a fact", state.noteIsWarning)
        val winning = state.valueLabel
        assertTrue("the header names the value that is winning: $winning", winning.contains("145.230"))
    }

    // -------------------------------------------------------------------------------------------
    // AC-131 — when a change takes effect, stated rather than implied.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("AC-131")
    fun `AC_131 a change recorded while capture ran says this session keeps the value it started with`() {
        val state = requireNotNull(
            LogFrequencyHeaderMapper.from(
                facts(
                    activeManualHz = 145_230_000L,
                    pendingManualHz = 146_520_000L,
                    hasPendingConfiguration = true,
                    isCapturing = true,
                ),
            ),
        )

        val note = requireNotNull(state.note)
        assertTrue("it names what was changed to: $note", note.contains("146.520"))
        assertTrue("it names what this session keeps: $note", note.contains("145.230"))
        // The value the header *shows* is still this session's own — never the pending one, which
        // would claim overs are being labelled with a number they are not.
        assertEquals("145.230 MHz", state.valueLabel)
    }

    @Test
    @Requirement("AC-131")
    fun `AC_131 the editor's own note differs by whether a session is actually running`() {
        val idle = requireNotNull(LogFrequencyHeaderMapper.from(facts())).editorNote
        val live = requireNotNull(LogFrequencyHeaderMapper.from(facts(isCapturing = true))).editorNote

        // Deliberately not an assertion on either sentence's wording (constitution II) — what must
        // hold is that a running session is told something different from an idle one, because
        // AC-131 makes the two genuinely different facts. A single shared sentence would be the
        // defect: it could only be honest about one of them.
        assertTrue("both paths say something", idle.isNotBlank() && live.isNotBlank())
        assertTrue("a live session must not be told the idle sentence", idle != live)
    }

    @Test
    @Requirement("AC-131", "constitution I")
    fun `AC_131 a pending write that does not change the frequency is never reported as one`() {
        // A pending *mode* change carries the same manual frequency. Reporting that as a frequency
        // change would be a fabricated fact.
        val state = requireNotNull(
            LogFrequencyHeaderMapper.from(
                facts(activeManualHz = 145_230_000L, pendingManualHz = 145_230_000L, hasPendingConfiguration = true),
            ),
        )

        assertNull("no frequency change is pending, so nothing is claimed", state.note)
    }

    // -------------------------------------------------------------------------------------------
    // The real editor, against the real store — the freeze rule is the store's, not this file's.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("AC-131", "FR-CAP-12")
    fun `AC_131 a write made while capture is running never touches the running session's configuration`() {
        val (idleStore, liveStore) = freshStores("log-freq-live-${System.nanoTime()}")
        // The session's own frozen value, written before it started.
        idleStore.update(CaptureConfiguration.DEFAULT.copy(manualFrequencyHz = 145_230_000L))

        RealLogFrequencyEditor(liveStore, rigState = { RigStatus.State.Absent }, isCapturing = { true })
            .save(146_520_000L)

        assertEquals(
            "current() must still read the value the running session started with",
            145_230_000L,
            liveStore.current().manualFrequencyHz,
        )
        assertEquals(
            "the write is recorded as pending, not applied",
            146_520_000L,
            liveStore.pendingConfiguration()?.manualFrequencyHz,
        )
        // And it lands on the next session start, exactly once, through the store's own promotion.
        assertEquals(146_520_000L, liveStore.activateForNewSession().manualFrequencyHz)
    }

    @Test
    @Requirement("AC-131", "FR-CAP-12")
    fun `AC_131 a frequency write preserves a mode change already pending rather than dropping it`() {
        val (_, liveStore) = freshStores("log-freq-pending-mode-${System.nanoTime()}")
        liveStore.update(CaptureConfiguration.DEFAULT.copy(mode = CaptureMode.USB_RADIO))
        assertEquals(CaptureMode.USB_RADIO, liveStore.pendingConfiguration()?.mode)

        RealLogFrequencyEditor(liveStore, rigState = { RigStatus.State.Absent }, isCapturing = { true })
            .save(145_230_000L)

        val pending = requireNotNull(liveStore.pendingConfiguration())
        assertEquals("the pending mode change must survive a frequency edit", CaptureMode.USB_RADIO, pending.mode)
        assertEquals(145_230_000L, pending.manualFrequencyHz)
    }

    @Test
    @Requirement("AC-202", "constitution III")
    fun `AC_202 clearing writes a real null rather than leaving a stale value behind`() {
        val store = freshStore("log-freq-clear-${System.nanoTime()}")
        store.update(CaptureConfiguration.DEFAULT.copy(manualFrequencyHz = 145_230_000L))

        RealLogFrequencyEditor(store, rigState = { RigStatus.State.Absent }, isCapturing = { false }).save(null)

        assertNull(store.current().manualFrequencyHz)
    }

    @Test
    @Requirement("AC-202", "FR-RIG-8")
    fun `AC_202 the real editor reads the rig's frequency only while it is actually connected`() {
        val store = freshStore("log-freq-rig-${System.nanoTime()}")
        val band = RigStatus.BandState(band = "A", frequencyHz = 146_520_000L, mode = "FM", squelchOpen = false)
        val connected = RigStatus.State.Connected(descriptor = "Kenwood TH-D75A", bands = listOf(band))

        assertEquals(
            146_520_000L,
            RealLogFrequencyEditor(store, rigState = { connected }, isCapturing = { false }).facts().rigReportedHz,
        )
        // Stale is the radio no longer reporting — never carried forward as a live reading.
        assertNull(
            RealLogFrequencyEditor(
                store,
                rigState = { RigStatus.State.Stale(lastKnown = connected, sinceMillis = 0L) },
                isCapturing = { false },
            ).facts().rigReportedHz,
        )
    }

    // -------------------------------------------------------------------------------------------
    // Parsing and formatting.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("R-1167", "constitution I", "constitution II")
    fun `R_1167 an unparseable frequency is refused rather than turned into a fabricated number`() {
        assertNull(parseMegahertzToHz(""))
        assertNull(parseMegahertzToHz("   "))
        assertNull(parseMegahertzToHz("0"))
        assertNull(parseMegahertzToHz("-145.230"))
        assertNull(parseMegahertzToHz("one four five"))
        // A comma decimal is not accepted, which is exactly why `megahertzFieldText` formats with
        // Locale.ROOT — the field's own seeded text must always parse back.
        assertNull(parseMegahertzToHz("145,230"))
        assertEquals(145_230_000L, parseMegahertzToHz("145.230"))
        assertEquals(145_230_000L, parseMegahertzToHz(" 145.230 "))
    }

    @Test
    @Requirement("R-1167", "constitution II")
    fun `R_1167 the field's own seeded text round-trips exactly, including a 12_5 kHz step`() {
        // 446.00625 MHz is a real PMR446 channel and is exactly what `"%.3f"` — the rendering every
        // other frequency label in this app uses — silently rounds to 446.006. Seeding the editor
        // with a rounded value would let an untouched `Save` rewrite the operator's own entry.
        assertEquals(446_006_250L, parseMegahertzToHz(megahertzFieldText(446_006_250L)))
        assertEquals("446.00625", megahertzFieldText(446_006_250L))
        // The ordinary case still reads with three decimals, matching every other surface.
        assertEquals("145.230", megahertzFieldText(145_230_000L))
        assertEquals("145.230 MHz", megahertzLabel(145_230_000L))
        assertEquals(145_230_000L, parseMegahertzToHz(megahertzFieldText(145_230_000L)))
    }
}
