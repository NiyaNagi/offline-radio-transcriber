package org.ort.pipeline.rig

import org.ort.rig.RigTransport
import org.ort.rig.RigTransportKind

/**
 * Production wiring for [RigTransportFactory]. `:rig-usb`'s `UsbSerialTransport` and
 * `:rig-bluetooth`'s `BluetoothSppTransport` (WPB) are not on `main` yet — see the `TODO(WPB)`
 * below for the one place this class changes once they merge. Until then every request fails
 * loudly with [UnsupportedRigTransportException] rather than returning a transport that could be
 * mistaken for a working link (constitution I) — [org.ort.pipeline.rig.RigSupervisor] already
 * treats a failed [org.ort.rig.RigModule.connect] as "fall back to the null module, stated",
 * exactly like an invalid descriptor (FR-RIG-11), so this failing loudly here degrades safely
 * rather than blocking capture.
 */
public class DefaultRigTransportFactory : RigTransportFactory {
    override fun create(kind: RigTransportKind, params: Map<String, String>): RigTransport = when (kind) {
        // TODO(WPB): RigTransportKind.USB_SERIAL -> org.ort.rig.usb.UsbSerialTransport(...)
        // TODO(WPB): RigTransportKind.BLUETOOTH_SPP -> org.ort.rig.bluetooth.BluetoothSppTransport(...)
        else -> throw UnsupportedRigTransportException(kind)
    }
}
