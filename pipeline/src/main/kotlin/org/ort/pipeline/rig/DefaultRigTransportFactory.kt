package org.ort.pipeline.rig

import android.content.Context
import org.ort.rig.RigTransport
import org.ort.rig.RigTransportKind
import org.ort.rig.bluetooth.AndroidBluetoothLink
import org.ort.rig.bluetooth.BluetoothSppTransport
import org.ort.rig.descriptor.TransportSpec
import org.ort.rig.usb.AndroidUsbSerialLink
import org.ort.rig.usb.UsbSerialLineConfig
import org.ort.rig.usb.UsbSerialParity
import org.ort.rig.usb.UsbSerialTransport

/**
 * Production wiring for [RigTransportFactory] (WPB merged): [RigTransportKind.USB_SERIAL] maps to
 * [UsbSerialTransport] over a fresh [AndroidUsbSerialLink]; [RigTransportKind.BLUETOOTH_SPP] to
 * [BluetoothSppTransport] over a fresh [AndroidBluetoothLink]. Every other kind still throws
 * [UnsupportedRigTransportException] — never returns a transport that could be mistaken for a
 * working link (constitution I) — [RigSupervisor] already treats a failed
 * [org.ort.rig.RigModule.connect] (this factory throwing included, per [RigSupervisor.connect]'s
 * own `runCatching`) as "fall back to the null module, stated", exactly like an invalid descriptor
 * (FR-RIG-11), so this failing loudly degrades safely rather than blocking capture.
 *
 * **Connection parameters, and where each one comes from (WPC3, FR-RIG-3):** VID/PID and the line
 * terminator are hardware/protocol facts a [TransportSpec] can now declare directly — see that
 * class's own kdoc — so [transportSpec], when non-null, is read **first**; [CaptureConfiguration.rigParams]
 * (by the key names in [ParamKeys]) is the fallback for exactly what [transportSpec] does not
 * declare (e.g. the bundled TH-D75A descriptor today, which leaves VID/PID absent pending H1 — see
 * [org.ort.rig.descriptor.BundledDescriptors.kenwoodThD75a]'s own kdoc). The Bluetooth address is
 * always a configuration value — no descriptor field carries it, since it names *which* paired
 * device to use, not a fact about the rig model. A required value missing from both sources fails
 * this call loudly (see class kdoc) rather than substituting an invented one. `baud`/`dataBits`/
 * `stopBits`/`parity` fall back to the RS-232 defaults both bundled descriptors already declare
 * (9600 8N1) — not a new assumption, the same one already written into the JSON files under
 * `rig/src/main/resources/descriptors/`.
 *
 * One instance is created per session ([RealCaptureService][org.ort.pipeline.capture.RealCaptureService]'s
 * `startCapture()`) and torn down via [dispose] when [RigSupervisor.disconnect] runs — real
 * transports self-reconnect on their own internal backoff ladder once opened (FR-RIG-7/15;
 * `UsbSerialTransport`/`BluetoothSppTransport`'s own `runSupervisor` loops), so [create] is called
 * at most once per [RigTransportKind] per session, never repeatedly to drive a reconnect.
 */
public class DefaultRigTransportFactory(private val context: Context) : RigTransportFactory {

    /** The [CaptureConfiguration.rigParams] keys this factory reads. Documented here, in one
     * place, so a caller assembling `rigParams` (WPD's setup UI, WPE's settings screen) has a
     * single source of truth for the exact strings expected. */
    public object ParamKeys {
        public const val USB_VENDOR_ID: String = "usbVendorId"
        public const val USB_PRODUCT_ID: String = "usbProductId"
        public const val LINE_TERMINATOR: String = "lineTerminator"
        public const val BLUETOOTH_ADDRESS: String = "bluetoothAddress"
        public const val BAUD: String = "baud"
        public const val DATA_BITS: String = "dataBits"
        public const val STOP_BITS: String = "stopBits"
        public const val PARITY: String = "parity"
    }

    private val createdUsbLinks = mutableListOf<AndroidUsbSerialLink>()

    override fun create(
        kind: RigTransportKind,
        transportSpec: TransportSpec?,
        params: Map<String, String>,
    ): RigTransport = when (kind) {
        RigTransportKind.USB_SERIAL -> createUsb(transportSpec, params)
        RigTransportKind.BLUETOOTH_SPP -> createBluetooth(transportSpec, params)
        else -> throw UnsupportedRigTransportException(kind)
    }

    /** [AndroidUsbSerialLink] registers broadcast receivers for its whole lifetime, independent of
     * a port being open ([AndroidUsbSerialLink.dispose]'s own kdoc); [BluetoothLink] has no
     * equivalent per-instance OS resource to release, so only the USB links are tracked here. */
    override fun dispose() {
        createdUsbLinks.forEach { it.dispose() }
        createdUsbLinks.clear()
    }

    private fun createUsb(spec: TransportSpec?, params: Map<String, String>): RigTransport {
        val vendorId = spec?.usbVendorId ?: requireIntParam(params, ParamKeys.USB_VENDOR_ID)
        val productId = spec?.usbProductId ?: requireIntParam(params, ParamKeys.USB_PRODUCT_ID)
        val lineTerminator = resolveLineTerminator(spec, params)
        val lineConfig = UsbSerialLineConfig(
            baudRate = params[ParamKeys.BAUD]?.toIntOrNull() ?: DEFAULT_BAUD,
            dataBits = params[ParamKeys.DATA_BITS]?.toIntOrNull() ?: DEFAULT_DATA_BITS,
            stopBits = params[ParamKeys.STOP_BITS]?.toIntOrNull() ?: DEFAULT_STOP_BITS,
            parity = parseParity(params[ParamKeys.PARITY]),
            lineTerminator = lineTerminator,
        )
        val link = AndroidUsbSerialLink(context)
        createdUsbLinks += link
        return UsbSerialTransport(vendorId, productId, lineConfig, link)
    }

    private fun createBluetooth(spec: TransportSpec?, params: Map<String, String>): RigTransport {
        // The Bluetooth address is always a configuration value (WPC3): no descriptor field names
        // *which* paired device to use, only what a rig model over this transport can do.
        val address = requireParam(params, ParamKeys.BLUETOOTH_ADDRESS)
        val lineTerminator = resolveLineTerminator(spec, params)
        return BluetoothSppTransport(address, AndroidBluetoothLink(context), lineTerminator)
    }

    /** WPC3 (FR-RIG-3): the descriptor's own [TransportSpec.lineTerminator] first, falling back to
     * [CaptureConfiguration.rigParams] only when the descriptor does not declare one. */
    private fun resolveLineTerminator(spec: TransportSpec?, params: Map<String, String>): Char {
        val raw = spec?.lineTerminator ?: requireParam(params, ParamKeys.LINE_TERMINATOR)
        require(raw.length == 1) { "${ParamKeys.LINE_TERMINATOR} must be exactly one character, was '$raw'" }
        return raw[0]
    }

    // Deliberately isNotEmpty, not isNotBlank: a legitimate lineTerminator is very often
    // whitespace ('\n', '\r', ' '), which isBlank() would wrongly reject as "missing".
    private fun requireParam(params: Map<String, String>, key: String): String {
        val raw = params[key]
        if (raw.isNullOrEmpty()) {
            error("rig connect params missing required '$key' (see DefaultRigTransportFactory.ParamKeys)")
        }
        return raw
    }

    private fun requireIntParam(params: Map<String, String>, key: String): Int {
        val raw = requireParam(params, key)
        return raw.toIntOrNull() ?: error("rig connect params '$key' must be an integer, was '$raw'")
    }

    private fun parseParity(raw: String?): UsbSerialParity = when (raw?.lowercase()) {
        null, "none" -> UsbSerialParity.NONE
        "odd" -> UsbSerialParity.ODD
        "even" -> UsbSerialParity.EVEN
        else -> error("unknown parity '$raw' (expected none/odd/even)")
    }

    private companion object {
        // The RS-232 defaults both bundled descriptors already declare (kenwood-thd75a.json,
        // generic-ascii-cat.json) -- not a new assumption introduced here.
        const val DEFAULT_BAUD = 9_600
        const val DEFAULT_DATA_BITS = 8
        const val DEFAULT_STOP_BITS = 1
    }
}
