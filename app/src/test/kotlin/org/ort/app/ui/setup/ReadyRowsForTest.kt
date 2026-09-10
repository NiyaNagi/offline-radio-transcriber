package org.ort.app.ui.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.RigStatus

/** R-080..R-084 (ui-conformance-plan WP9), extended by D33/P19 WPD (E2-E13) — [readyRowsFor]
 * builds S12's rows from real state only ([SetupStore], live battery-exemption, [RigStatus],
 * [AsrAvailability]), never fabricated. Pure, no Compose, no Context. */
class ReadyRowsForTest {

    private val noOpActions = ReadyActions(
        onFixInput = {},
        onFixLevel = {},
        onFixOvernight = {},
        onFixRadio = {},
        onChangeRadio = {},
        onInstallModel = {},
        onChangeMode = {},
    )

    private fun rows(
        store: SetupStore = InMemorySetupStore(),
        batteryExempt: Boolean = false,
        rigStatus: RigStatus.State = RigStatus.State.Absent,
        asrState: AsrAvailability.State = AsrAvailability.State.NotYetChecked,
    ) = readyRowsFor(store, batteryExempt, rigStatus, asrState, noOpActions)

    // --- D33/E2-E13: the new leading Mode row ----------------------------------------------------

    @Test
    fun `E2_E13 the Mode row leads the list`() {
        val row = rows(InMemorySetupStore(captureMode = CaptureMode.USB_RADIO)).first()

        assertEquals("Mode", row.label)
    }

    @Test
    fun `E2_E13 no mode chosen renders Not set, honest rather than fabricated, and is not ok`() {
        val row = rows(InMemorySetupStore(captureMode = null)).first { it.label == "Mode" }

        assertFalse(row.ok)
        assertEquals("Not set", row.value)
    }

    @Test
    fun `E2_E13 Bluetooth mode not overridden reads Bluetooth-connected radio audio by cable, per the board`() {
        val store = InMemorySetupStore(captureMode = CaptureMode.BLUETOOTH_RADIO, modeOverriddenAudio = false)
        val row = rows(store).first { it.label == "Mode" }

        assertTrue(row.ok)
        assertEquals("Bluetooth-connected radio · audio by cable", row.value)
    }

    @Test
    fun `E2_E13 local-microphone mode reads room audio`() {
        val store = InMemorySetupStore(captureMode = CaptureMode.LOCAL_MICROPHONE)
        val row = rows(store).first { it.label == "Mode" }

        assertEquals("Local microphone · room audio", row.value)
    }

    @Test
    fun `E2_E13 an overridden audio route says so rather than the mode's own default segment`() {
        val store = InMemorySetupStore(captureMode = CaptureMode.BLUETOOTH_RADIO, modeOverriddenAudio = true)
        val row = rows(store).first { it.label == "Mode" }

        assertEquals("Bluetooth-connected radio · audio route changed", row.value)
    }

    @Test
    fun `E2_E13 Change on the Mode row invokes onChangeMode, routing to S00`() {
        var changed = false
        val actions = ReadyActions(
            onFixInput = {},
            onFixLevel = {},
            onFixOvernight = {},
            onFixRadio = {},
            onChangeRadio = {},
            onInstallModel = {},
            onChangeMode = { changed = true },
        )
        val store = InMemorySetupStore(captureMode = CaptureMode.USB_RADIO)
        val row = readyRowsFor(store, false, RigStatus.State.Absent, AsrAvailability.State.NotYetChecked, actions)
            .first { it.label == "Mode" }

        assertEquals("Change", row.actionLabel)
        row.onAction?.invoke()
        assertTrue(changed)
    }

    @Test
    fun `R_081 an unverified input row is amber and offers Fix`() {
        val store = InMemorySetupStore(selectedInputLabel = "USB Audio Device", inputVerified = false)
        val row = rows(store).first { it.label == "Input" }

        assertFalse(row.ok)
        assertEquals("Fix", row.actionLabel)
        assertEquals(null, row.statusText)
    }

    @Test
    fun `R_081 a verified input row is green and reads verified`() {
        val store = InMemorySetupStore(selectedInputLabel = "USB Audio Device", inputVerified = true)
        val row = rows(store).first { it.label == "Input" }

        assertTrue(row.ok)
        assertEquals("verified", row.statusText)
        assertEquals(null, row.actionLabel)
    }

    @Test
    fun `R_083 overnight row reflects the live battery-exemption reading, not a stored flag`() {
        val exempt = rows(batteryExempt = true).first { it.label == "Overnight" }
        val notExempt = rows(batteryExempt = false).first { it.label == "Overnight" }

        assertTrue(exempt.ok)
        assertFalse(notExempt.ok)
        assertEquals("Fix", notExempt.actionLabel)
    }

    @Test
    fun `R_082 a level-in-band flag with no measured peak never renders as a green in-band row`() {
        // The exact drift a stored-preference test-only setup once produced: KEY_LEVEL_IN_BAND
        // true with KEY_LEVEL_PEAK_DBFS never written (validator finding, register R-120..R-125).
        val store = InMemorySetupStore(levelInBand = true, levelPeakDbfs = null)
        val row = rows(store).first { it.label == "Level" }

        assertFalse(row.ok, "ok must never be true without a measured peak backing it")
        assertEquals("Not measured", row.value)
        assertEquals(null, row.statusText)
        assertEquals("Fix", row.actionLabel)
    }

    @Test
    fun `R_082 a real measured peak in band renders the green row with its value`() {
        val store = InMemorySetupStore(levelInBand = true, levelPeakDbfs = -14.0)
        val row = rows(store).first { it.label == "Level" }

        assertTrue(row.ok)
        assertEquals("in band", row.statusText)
        assertTrue(row.value.contains("-14"))
    }

    @Test
    fun `R_084 choosing no radio is a valid, green row naming the manual frequency`() {
        val store = InMemorySetupStore(radioChoice = RadioChoice.NONE, manualFrequencyHz = 145_230_000L)
        val row = rows(store).first { it.label == "Radio" }

        assertTrue(row.ok, "a deliberate choice of no radio must not read as a problem")
        assertTrue(row.value.contains("145.230"))
    }

    /** R-285 (validator pass 3): an `ok` row must still offer a way back into S09..S11 — before
     * this, choosing "no radio" left the row with no action at all. */
    @Test
    fun `R_285 choosing no radio still offers Change, wired to onChangeRadio not onFixRadio`() {
        var changed = false
        var fixed = false
        val actions = ReadyActions(
            onFixInput = {},
            onFixLevel = {},
            onFixOvernight = {},
            onFixRadio = { fixed = true },
            onChangeRadio = { changed = true },
            onInstallModel = {},
            onChangeMode = {},
        )
        val store = InMemorySetupStore(radioChoice = RadioChoice.NONE, manualFrequencyHz = 145_230_000L)
        val row = readyRowsFor(store, false, RigStatus.State.Absent, AsrAvailability.State.NotYetChecked, actions)
            .first { it.label == "Radio" }

        assertEquals("Change", row.actionLabel)
        assertEquals(null, row.statusText)
        row.onAction?.invoke()
        assertTrue(changed)
        assertFalse(fixed)
    }

    @Test
    fun `R_084 a chosen rig with no real rig support renders amber and honest, never a fabricated connection`() {
        val store = InMemorySetupStore(radioChoice = RadioChoice.TH_D75A)
        val row = rows(store, rigStatus = RigStatus.State.Absent).first { it.label == "Radio" }

        assertFalse(row.ok)
        assertTrue(row.value.contains("No rig support"))
    }

    @Test
    fun `R_084 a genuinely connected rig renders green and verified`() {
        val store = InMemorySetupStore(radioChoice = RadioChoice.TH_D75A)
        val band = RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)
        val connected = RigStatus.State.Connected("Kenwood TH-D75A", listOf(band))
        val row = rows(store, rigStatus = connected).first { it.label == "Radio" }

        assertTrue(row.ok)
        assertEquals("verified", row.statusText)
    }

    /** R-285 (validator pass 3): a genuinely connected rig is still `ok` -- no `Fix` makes sense --
     * but must still offer `Change`, the one row in this whole screen where [ReadyRow.statusText]
     * and [ReadyRow.actionLabel] are both set together ([radioRow]'s own doc comment), wired to
     * [ReadyActions.onChangeRadio], not the broken-rig [ReadyActions.onFixRadio]. */
    @Test
    fun `R_285 a genuinely connected rig also offers Change, wired to onChangeRadio not onFixRadio`() {
        var changed = false
        var fixed = false
        val actions = ReadyActions(
            onFixInput = {},
            onFixLevel = {},
            onFixOvernight = {},
            onFixRadio = { fixed = true },
            onChangeRadio = { changed = true },
            onInstallModel = {},
            onChangeMode = {},
        )
        val store = InMemorySetupStore(radioChoice = RadioChoice.TH_D75A)
        val band = RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)
        val connected = RigStatus.State.Connected("Kenwood TH-D75A", listOf(band))
        val row = readyRowsFor(store, false, connected, AsrAvailability.State.NotYetChecked, actions)
            .first { it.label == "Radio" }

        assertEquals("verified", row.statusText)
        assertEquals("Change", row.actionLabel)
        row.onAction?.invoke()
        assertTrue(changed)
        assertFalse(fixed)
    }

    @Test
    fun `E2_E13 no model installed renders amber with an Install action, never a fabricated model name`() {
        val row = rows(asrState = AsrAvailability.State.NotYetChecked).first { it.label == "Models" }

        assertFalse(row.ok)
        assertEquals("Install", row.actionLabel)
        assertEquals("No transcription model yet", row.value)
    }

    @Test
    fun `E2_E13 an available model renders green with its real model reference`() {
        val row = rows(asrState = AsrAvailability.State.Available("whisper-tiny-en")).first { it.label == "Models" }

        assertTrue(row.ok)
        assertEquals("whisper-tiny-en", row.value)
    }
}
