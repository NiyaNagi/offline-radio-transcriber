package org.ort.app.ui.setup

import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.DefaultRigTransportFactory
import org.ort.rig.NullRigModule

/**
 * WPC2's `CaptureConfigurationStore` (`:pipeline`) is what `RealCaptureService` actually reads at
 * session start (FR-CAP-12/13, AC-131) — [SetupStore] is `:app`-local UI state, adapted *to* that
 * contract rather than the other way around (that package's own doc comment). This is the pure
 * half of the adapter: [toCaptureConfiguration] reads exactly the facts [SetupStore] already
 * carries and returns `null` only when there is nothing yet to configure (S00 not yet walked —
 * [SetupStore.captureMode] is `null`), never a fabricated default. [SetupActivity] calls this after
 * every mutation to the mode/route/rig axes and pushes the result through
 * `CaptureConfigurationStore.update`.
 *
 * [CaptureConfiguration.rigParams] carries only what is genuinely known here: the Bluetooth
 * address S10b's checklist verified ([DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS] — no
 * `RigDescriptor` field names *which* paired device to use, only what the rig model can do over a
 * transport). USB's `usbVendorId`/`usbProductId` are, since WPC3, a `TransportSpec` field a
 * descriptor CAN declare directly — the bundled TH-D75A descriptor still leaves them absent
 * pending H1's own verification, so this adapter has nothing real to add for them either; left out
 * of [rigParams] rather than guessed (constitution I). `DefaultRigTransportFactory` fails that
 * connect loudly when neither the descriptor nor `rigParams` supplies a required value, and
 * `RigSupervisor` degrades it to the null module, exactly like an invalid descriptor (FR-RIG-11).
 */
public object SetupCaptureConfigurationAdapter {
    public fun toCaptureConfiguration(store: SetupStore): CaptureConfiguration? {
        val mode = store.captureMode ?: return null
        val rigParams = buildMap {
            store.rigBluetoothAddress?.let { put(DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS, it) }
        }
        return CaptureConfiguration(
            mode = mode,
            selectedInputId = store.selectedInputId,
            rigId = store.rigId ?: NullRigModule.ID,
            // SetupStore.rigTransport is :core's preset-shaped kind (USB_SERIAL/BLUETOOTH_SPP
            // only); CaptureConfiguration wants :rig's own (wider) RigTransportKind -- the two are
            // deliberately separate types (RigLinkPort.kt's own doc comment has the full account),
            // converted here at the one seam that needs both.
            rigTransportKind = store.rigTransport?.let(RigPickerCatalogue::fromPresetKind),
            rigParams = rigParams,
        )
    }
}
