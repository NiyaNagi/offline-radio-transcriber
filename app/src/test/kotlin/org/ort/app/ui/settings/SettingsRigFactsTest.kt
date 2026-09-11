package org.ort.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.realCaptureConfigurationStore
import org.ort.core.SystemClock
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.DefaultRigTransportFactory
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.ort.rig.RigTransportKind
import org.ort.rig.descriptor.BundledDescriptors
import org.robolectric.RobolectricTestRunner

/**
 * CF06 ("Rig link") and CF11's own Rig-link row — the [SettingsRigFacts] half of
 * [SettingsPollingTest], split out purely to keep that test class under detekt's `LargeClass`
 * threshold (the same reason [SettingsRigFacts] itself was split out of [SettingsPolling]) — every
 * test here moved verbatim, nothing renamed or reworded.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRigFactsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearHolders() {
        RigStatus.reset()
        clearConfigStore()
    }

    @After
    fun resetHolders() {
        RigStatus.reset()
        clearConfigStore()
    }

    private fun clearConfigStore() {
        context.getSharedPreferences(SharedPreferencesCaptureConfigurationStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `R_950 CF11's live Rig link row strips the prefix and names transport and address`() {
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(
                mode = CaptureMode.BLUETOOTH_RADIO,
                selectedInputId = null,
                rigParams = mapOf(DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS to "D8:3A:DD:41:0C:7F"),
            ),
        )
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.modeScreen(context)
        assert(state.rigLink.subLine == "TH-D75A · Bluetooth SPP · D8:3A:DD:41:0C:7F · connected") {
            "expected the board's own name/transport/address/state shape, got ${state.rigLink.subLine}"
        }
    }

    @Test
    fun `R_950 CF11's live Rig link row omits the address clause when none is known`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.modeScreen(context)
        assert(state.rigLink.subLine == "TH-D75A · Bluetooth SPP · connected") {
            "expected no address clause when unknown, got ${state.rigLink.subLine}"
        }
    }

    @Test
    fun `R_871 CF11's stale Rig-link row reads a real clock time and duration, never a raw epoch millisecond`() {
        RigStatus.connected(descriptor = "Kenwood TH-D75A", bands = emptyList())
        val sinceMillis = SystemClock.wallMillis() - 3 * 60_000L
        RigStatus.stale(lastKnown = RigStatus.state as RigStatus.State.Connected, sinceMillis = sinceMillis)
        val state = SettingsPolling.modeScreen(context)
        assert(!state.rigLink.subLine.contains(sinceMillis.toString())) {
            "expected no raw epoch millisecond value, got ${state.rigLink.subLine}"
        }
        assert(state.rigLink.subLine.contains(org.ort.app.ui.failures.FailureMapper.clockLabel(sinceMillis))) {
            "expected the real clock time, got ${state.rigLink.subLine}"
        }
    }

    @Test
    fun `R_871 CF06's staleSinceLabel reads a real clock time and duration, never a raw epoch millisecond`() {
        RigStatus.connected(descriptor = "Kenwood TH-D75A", bands = emptyList())
        val sinceMillis = SystemClock.wallMillis() - 3 * 60_000L
        RigStatus.stale(lastKnown = RigStatus.state as RigStatus.State.Connected, sinceMillis = sinceMillis)
        val state = SettingsPolling.rig(context)
        assert(state.staleSinceLabel?.contains(sinceMillis.toString()) == false) {
            "expected no raw epoch millisecond value, got ${state.staleSinceLabel}"
        }
        assert(state.staleSinceLabel?.contains(org.ort.app.ui.failures.FailureMapper.clockLabel(sinceMillis)) == true) {
            "expected the real clock time, got ${state.staleSinceLabel}"
        }
    }

    @Test
    fun `R_845 the title strips the real bundled Kenwood descriptor's own leading manufacturer word`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.descriptorLabel == "TH-D75A") {
            "expected the manufacturer word dropped, got ${state.descriptorLabel}"
        }
    }

    @Test
    fun `R_845 a descriptor with no digit-bearing word is left unchanged, nothing to strip`() {
        RigStatus.connected(descriptor = "Generic ASCII CAT", bands = emptyList())
        val state = SettingsPolling.rig(context)
        assert(state.descriptorLabel == "Generic ASCII CAT") {
            "expected no change (no manufacturer prefix to drop), got ${state.descriptorLabel}"
        }
    }

    @Test
    fun `R_845 the pollingClause is unpolled for the real Kenwood descriptor, which declares push`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.pollingClause == "reading both bands unpolled") {
            "expected the real unsolicited-push clause, got ${state.pollingClause}"
        }
    }

    @Test
    fun `R_845 the pollingClause is a real interval for the generic descriptor, which has no push`() {
        RigStatus.connected(
            descriptor = "Generic ASCII CAT",
            bands = emptyList(),
            transportKind = RigTransportKind.USB_SERIAL,
            descriptorId = BundledDescriptors.genericAsciiCat().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.pollingClause == "polled every 0.5 s") {
            "expected the real 500ms poll interval, got ${state.pollingClause}"
        }
    }

    @Test
    fun `R_835 an unmatched descriptorId falls back to the honest not-reported label, never invented data`() {
        RigStatus.connected(descriptor = "Imported Radio", bands = emptyList(), descriptorId = "some-imported-id")
        val state = SettingsPolling.rig(context)
        assert(state.rigModuleLabel == NOT_REPORTED_BY_RIG_MODULE)
        assert(state.pollingClause == NOT_REPORTED_BY_RIG_MODULE)
        assert(state.autoInformation == null)
    }

    @Test
    fun `R_835_R_923 the Rig-module row renders the real descriptor's own capabilities as CAT mnemonics`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        // R-923 (Reviewer C2, run 3): `docs/reference/th-d75a-cat.md`'s own verified command
        // table — FREQUENCY -> FQ, SQUELCH_STATE -> BY, SUB_BAND -> BC — the screen's own
        // established shorthand, never the raw `RigCapability` enum names, and never the board
        // mockup's own broader eight-command example (`FQ BY FO BC MR ME AI BL`, illustrative of a
        // command set no accessible source in this build actually declares).
        assert(state.rigModuleLabel == "kenwood-thd75a · built in · verified command set FQ BY BC") {
            "expected the real CAT mnemonics, got ${state.rigModuleLabel}"
        }
        assert(!state.rigModuleLabel.contains("FQ BY FO BC MR ME AI BL"))
        assert(!state.rigModuleLabel.contains("FREQUENCY"))
    }

    @Test
    fun `R_835_reopened otherTransportLabel reads vid-pid not yet verified H1, never the board mockup ids`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.otherTransportLabel == "USB serial also supported, vid/pid not yet verified (H1)") {
            "expected the honest H1 qualifier, got ${state.otherTransportLabel}"
        }
        assert(state.otherTransportLabel?.contains("0x0451") != true)
    }

    @Test
    fun `R_835 Auto-information is real for the Kenwood descriptor, using its own real poll interval`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        val ai = state.autoInformation
        assert(ai != null) { "expected a real Auto-information row for the Kenwood descriptor" }
        assert(ai!!.label == "Auto-information, AI 1") {
            "expected the descriptor's own real enable string, got ${ai.label}"
        }
        assert(ai.subLine.contains("fallback poll every 2 s if it stops")) {
            "expected the descriptor's own real 2s poll fallback, got ${ai.subLine}"
        }
    }

    @Test
    fun `R_835 no Auto-information for the generic descriptor, which declares no push block`() {
        RigStatus.connected(
            descriptor = "Generic ASCII CAT",
            bands = emptyList(),
            transportKind = RigTransportKind.USB_SERIAL,
            descriptorId = BundledDescriptors.genericAsciiCat().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.autoInformation == null) { "generic ASCII CAT declares no unsolicited push at all" }
    }
}
