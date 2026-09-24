package org.ort.app.ui.setup

import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.DefaultRigTransportFactory
import org.ort.rig.NullRigModule

/**
 * WPC2's `CaptureConfigurationStore` (`:pipeline`) is what `RealCaptureService` actually reads at
 * session start (FR-CAP-12/13, AC-131) — [SetupStore] is `:app`-local UI state, adapted *to* that
 * contract rather than the other way around (that package's own doc comment). This is the pure half
 * of the adapter: [toCaptureConfiguration] reads exactly the facts [SetupStore] already carries and
 * returns `null` only when there is nothing yet to configure ([SetupStore.captureMode] is `null`),
 * never a fabricated default. [SetupActivity] calls this after every mutation to the mode/route/rig
 * axes and pushes the result through `CaptureConfigurationStore.update`.
 *
 * **P39 (D58, AC-202, R-1167): setup no longer has an opinion about the manual frequency, and this
 * is where that had to be made true.** The prompt moved to the log's own header, so nothing writes
 * `SetupStore.manualFrequencyHz` on a first run any more — and because `CaptureConfigurationStore
 * .update` replaces the configuration wholesale, an adapter still sourcing that field from
 * [SetupStore] would have overwritten a frequency the operator typed into the log header **with
 * `null`**, on any later trip through setup (Settings › Input's own re-entry, say). Silent data
 * loss, no error, and the only symptom would be every subsequent over logged without a frequency.
 *
 * The field is therefore **carried through** from whatever the configuration store already holds
 * ([manualFrequencyHz], passed by the caller) rather than read from [SetupStore] at all. Setup can
 * still *set* it — `SetupActivity.onEnterFrequency`, the rig branch's own manual-entry screen, which
 * no first run reaches — by passing the new value here explicitly, which is the one place an
 * intentional change is distinguishable from a sync that should preserve what is there.
 *
 * [CaptureConfiguration.rigParams] carries only what is genuinely known here: the Bluetooth address
 * the rig-Bluetooth checklist verified ([DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS] —
 * no `RigDescriptor` field names *which* paired device to use, only what the rig model can do over a
 * transport). USB's `usbVendorId`/`usbProductId` are a `TransportSpec` field a descriptor CAN declare
 * directly — the bundled TH-D75A descriptor still leaves them absent pending H1's own verification,
 * so this adapter has nothing real to add for them either; left out rather than guessed
 * (constitution I).
 */
public object SetupCaptureConfigurationAdapter {

    /**
     * [manualFrequencyHz] is **what the configuration store already holds**, not what [store] holds —
     * see this object's own doc comment. It has no default on purpose: a caller that forgets it would
     * otherwise silently reintroduce exactly the data loss this parameter exists to prevent.
     */
    public fun toCaptureConfiguration(store: SetupStore, manualFrequencyHz: Long?): CaptureConfiguration? {
        val mode = store.captureMode ?: return null
        val rigParams = buildMap {
            store.rigBluetoothAddress?.let { put(DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS, it) }
        }
        return CaptureConfiguration(
            mode = mode,
            selectedInputId = store.selectedInputId,
            rigId = store.rigId ?: NullRigModule.ID,
            // SetupStore.rigTransport is :core's preset-shaped kind (USB_SERIAL/BLUETOOTH_SPP only);
            // CaptureConfiguration wants :rig's own (wider) RigTransportKind -- the two are
            // deliberately separate types (RigLinkPort.kt's own doc comment has the full account),
            // converted here at the one seam that needs both.
            rigTransportKind = store.rigTransport?.let(RigPickerCatalogue::fromPresetKind),
            rigParams = rigParams,
            // R-1030/R-1167: carried through exactly as it stands, independent of the rig axes above
            // (the override applies with or without a rig configured; see CaptureConfiguration's own
            // doc comment).
            manualFrequencyHz = manualFrequencyHz,
        )
    }
}
