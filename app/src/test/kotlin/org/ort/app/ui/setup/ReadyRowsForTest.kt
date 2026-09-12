package org.ort.app.ui.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelRowViewState
import org.ort.app.ui.data.ModelsViewState
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.RigVerification
import org.ort.rig.RigCapability

/** R-080..R-084 (ui-conformance-plan WP9), extended by D33/P19 WPD (E2-E13) — [readyRowsFor]
 * builds S12's rows from real state only ([SetupStore], live battery-exemption, [RigStatus],
 * WPG's [ModelsViewState]), never fabricated. Pure, no Compose, no Context. */
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
        modelsState: ModelsViewState = ModelsViewState(emptyList()),
    ) = readyRowsFor(store, batteryExempt, rigStatus, modelsState, noOpActions)

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
        val row = readyRowsFor(store, false, RigStatus.State.Absent, ModelsViewState(emptyList()), actions)
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
        val row = readyRowsFor(store, false, RigStatus.State.Absent, ModelsViewState(emptyList()), actions)
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
        // R-1019: this test's own intent is the *fully* verified case -- constructed explicitly now
        // that a bare Connected() defaults to RigVerification.Unknown, never Full.
        val connected = RigStatus.State.Connected(
            "Kenwood TH-D75A",
            listOf(band),
            verification = RigVerification.Full,
        )
        val row = rows(store, rigStatus = connected).first { it.label == "Radio" }

        assertTrue(row.ok)
        assertEquals("verified", row.statusText)
    }

    // --- R-1019 (register): S12 must not assert more than RigStatus.verification establishes -----

    @Test
    fun `R_1019 RigVerification Partial renders partially confirmed, never verified, naming what was missed`() {
        val store = InMemorySetupStore(radioChoice = RadioChoice.TH_D75A)
        val band = RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)
        val connected = RigStatus.State.Connected(
            "Kenwood TH-D75A",
            listOf(band),
            verification = RigVerification.Partial(setOf(RigCapability.SIGNAL_STRENGTH, RigCapability.SQUELCH_STATE)),
        )
        val row = rows(store, rigStatus = connected).first { it.label == "Radio" }

        assertTrue(row.ok, "a partially verified rig is still connected and usable")
        assertEquals("partially confirmed", row.statusText)
        assertFalse(row.value.contains("verified"), "must never claim 'verified' for a partial link")
        assertTrue(row.value.contains("squelch"))
        assertTrue(row.value.contains("signal strength"))
    }

    /** The exact case a future careless change would reintroduce: a `Connected` reading whose
     * verification was never stated (the honest default) must never render as "verified". */
    @Test
    fun `R_1019 RigVerification Unknown never renders verified`() {
        val store = InMemorySetupStore(radioChoice = RadioChoice.TH_D75A)
        val band = RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)
        val connected = RigStatus.State.Connected(
            "Kenwood TH-D75A",
            listOf(band),
            verification = RigVerification.Unknown,
        )
        val row = rows(store, rigStatus = connected).first { it.label == "Radio" }

        assertTrue(row.ok, "still genuinely connected")
        assertFalse(row.statusText == "verified", "must never claim 'verified' when nothing stated the fact")
        assertEquals(null, row.statusText)
    }

    @Test
    fun `R_1019 a bare Connected reading (no verification stated) defaults to Unknown, never verified`() {
        val store = InMemorySetupStore(radioChoice = RadioChoice.TH_D75A)
        val band = RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)
        // No `verification` argument at all -- proves the *default* itself is safe end-to-end, not
        // only an explicitly-passed Unknown.
        val connected = RigStatus.State.Connected("Kenwood TH-D75A", listOf(band))
        val row = rows(store, rigStatus = connected).first { it.label == "Radio" }

        assertFalse(row.statusText == "verified")
    }

    /** R-882 (register, validator V11, halt): the value string this row carries is what
     * [ReadyFactColumns] must fit into its `weight(1f)` column at font scale 2.0 -- the same
     * manufacturer-prefix strip [org.ort.app.ui.settings.SettingsPolling.stripManufacturerPrefix]
     * already gives S09b's title (R-941) and S11's row (R-903), so "Kenwood TH-D75A" reads
     * "TH-D75A" here too, shortening the exact value that used to collapse to one letter per
     * line on the real device. */
    @Test
    fun `R_882 a connected rig's value strips the manufacturer prefix from the descriptor`() {
        val store = InMemorySetupStore(radioChoice = RadioChoice.TH_D75A)
        val band = RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)
        val connected = RigStatus.State.Connected("Kenwood TH-D75A", listOf(band))
        val row = rows(store, rigStatus = connected).first { it.label == "Radio" }

        assertEquals("TH-D75A · 1 band", row.value)
    }

    @Test
    fun `R_882 a stale rig's value also strips the manufacturer prefix from its last-known descriptor`() {
        val store = InMemorySetupStore(radioChoice = RadioChoice.TH_D75A)
        val band = RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)
        val lastKnown = RigStatus.State.Connected("Kenwood TH-D75A", listOf(band))
        val stale = RigStatus.State.Stale(lastKnown, sinceMillis = 0L)
        val row = rows(store, rigStatus = stale).first { it.label == "Radio" }

        assertEquals("TH-D75A · stale", row.value)
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
        // R-1019: this test's own intent is the action wiring on a genuinely fully verified row,
        // not the verification branch itself -- constructed explicitly now that a bare Connected()
        // defaults to RigVerification.Unknown.
        val connected = RigStatus.State.Connected(
            "Kenwood TH-D75A",
            listOf(band),
            verification = RigVerification.Full,
        )
        val row = readyRowsFor(store, false, connected, ModelsViewState(emptyList()), actions)
            .first { it.label == "Radio" }

        assertEquals("verified", row.statusText)
        assertEquals("Change", row.actionLabel)
        row.onAction?.invoke()
        assertTrue(changed)
        assertFalse(fixed)
    }

    @Test
    fun `E2_E13 no model installed renders amber with an Install action, never a fabricated model name`() {
        val row = rows(modelsState = ModelsViewState(emptyList())).first { it.label == "Models" }

        assertFalse(row.ok)
        assertEquals("Install", row.actionLabel)
        assertEquals("No transcription model yet", row.value)
    }

    @Test
    fun `E2_E13 every model bundled and verified renders green with N bundled checksums verified`() {
        val installed = ModelRowViewState(
            id = ModelId.ASR_ENCODER,
            label = ModelId.ASR_ENCODER.label,
            status = ModelRowStatus.INSTALLED,
            detail = null,
            bundled = true,
        )
        val row = rows(modelsState = ModelsViewState(listOf(installed))).first { it.label == "Models" }

        assertTrue(row.ok)
        assertEquals("ready", row.statusText)
        assertEquals("1 bundled · checksums verified", row.value)
    }

    @Test
    fun `E2_E13 a not-yet-installed row among otherwise-bundled ones still reads amber, never partially ready`() {
        val installed = ModelRowViewState(
            id = ModelId.ASR_ENCODER,
            label = ModelId.ASR_ENCODER.label,
            status = ModelRowStatus.INSTALLED,
            detail = null,
            bundled = true,
        )
        val notInstalled = ModelRowViewState(
            id = ModelId.ASR_DECODER,
            label = ModelId.ASR_DECODER.label,
            status = ModelRowStatus.NOT_INSTALLED,
            detail = null,
            bundled = true,
        )
        val row = rows(modelsState = ModelsViewState(listOf(installed, notInstalled))).first { it.label == "Models" }

        assertFalse(row.ok)
        assertEquals("Install", row.actionLabel)
    }

    // --- R-862 (register, halt, AC-138): the LLM is optional -- amber is reserved for a real ------
    // --- tier >=1 transcription asset missing or unverified, never the gated Gemma model alone -----

    private fun modelRow(id: ModelId, status: ModelRowStatus) =
        ModelRowViewState(id = id, label = id.label, status = status, detail = null, bundled = true)

    private val everyModelInstalled = listOf(
        modelRow(ModelId.ASR_ENCODER, ModelRowStatus.INSTALLED),
        modelRow(ModelId.ASR_DECODER, ModelRowStatus.INSTALLED),
        modelRow(ModelId.ASR_TOKENS, ModelRowStatus.INSTALLED),
        modelRow(ModelId.VAD, ModelRowStatus.INSTALLED),
        modelRow(ModelId.LLM_GEMMA3_1B, ModelRowStatus.INSTALLED),
    )

    @Test
    fun `R_862 5 of 5 installed, Gemma included, renders the plain green N bundled line`() {
        val row = rows(modelsState = ModelsViewState(everyModelInstalled)).first { it.label == "Models" }

        assertTrue(row.ok)
        assertEquals("ready", row.statusText)
        assertEquals("5 bundled · checksums verified", row.value)
        assertEquals(null, row.actionLabel)
    }

    /** AC-138: a no-`HF_TOKEN` build never fetches the gated Gemma asset at all -- every real
     * transcription asset (tier >=1) is genuinely installed, so this must read green, naming the
     * one real, honest reason the count is 4 of 5, never amber for an asset this build never
     * offered in the first place. */
    @Test
    fun `R_862 4 of 5 with only the gated Gemma model absent renders green and names it`() {
        val rowsWithoutGemma = everyModelInstalled.map {
            if (it.id == ModelId.LLM_GEMMA3_1B) modelRow(it.id, ModelRowStatus.NOT_INSTALLED) else it
        }
        val row = rows(modelsState = ModelsViewState(rowsWithoutGemma)).first { it.label == "Models" }

        assertTrue(row.ok, "the required transcription set is complete -- the optional LLM must never gate this")
        assertEquals("ready", row.statusText)
        assertEquals("4 of 5 bundled · ready — Gemma 3 1B not in this build", row.value)
        assertEquals(null, row.actionLabel)
    }

    @Test
    fun `R_862 3 of 5 with the encoder corrupt renders amber and names the encoder, not Gemma`() {
        val rowsWithCorruptEncoderAndNoGemma = everyModelInstalled.map {
            when (it.id) {
                ModelId.ASR_ENCODER -> modelRow(it.id, ModelRowStatus.NOT_INSTALLED)
                ModelId.LLM_GEMMA3_1B -> modelRow(it.id, ModelRowStatus.NOT_INSTALLED)
                else -> it
            }
        }
        val row = rows(modelsState = ModelsViewState(rowsWithCorruptEncoderAndNoGemma)).first { it.label == "Models" }

        assertFalse(row.ok, "the encoder is required for tier >=1 transcription -- this must stay amber")
        assertEquals("Install", row.actionLabel)
        assertEquals("No transcription model yet — ${ModelId.ASR_ENCODER.label} not installed", row.value)
    }

    @Test
    fun `R_862 0 of 5 installed renders the plain amber No transcription model yet line`() {
        val rowsNoneInstalled = everyModelInstalled.map { modelRow(it.id, ModelRowStatus.NOT_INSTALLED) }
        val row = rows(modelsState = ModelsViewState(rowsNoneInstalled)).first { it.label == "Models" }

        assertFalse(row.ok)
        assertEquals("Install", row.actionLabel)
        assertEquals("No transcription model yet", row.value)
    }
}
