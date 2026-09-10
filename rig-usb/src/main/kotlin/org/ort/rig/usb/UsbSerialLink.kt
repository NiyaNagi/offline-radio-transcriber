package org.ort.rig.usb

import kotlinx.coroutines.flow.Flow

/**
 * One USB device as it is enumerated (a thin proxy for `android.hardware.usb.UsbDevice`, kept out
 * of this file's imports so the state machine below never touches Android types).
 */
public data class UsbDeviceHandle(public val vendorId: Int, public val productId: Int, public val deviceName: String)

public enum class UsbSerialParity { NONE, ODD, EVEN }

/** The serial line parameters a descriptor's USB transport spec declares (functional spec §9.2). */
public data class UsbSerialLineConfig(
    public val baudRate: Int,
    public val dataBits: Int,
    public val stopBits: Int,
    public val parity: UsbSerialParity,
    /** Kenwood convention is `;` (unconfirmed for the TH-D75A — docs/reference/th-d75a-cat.md
     * "still to verify"), so this is never defaulted: a wrong assumption baked into a default is
     * exactly the kind of silent failure constitution I exists to forbid. */
    public val lineTerminator: Char,
)

/** Something the wire pushed at [UsbSerialTransport] with no read in progress. */
public sealed interface UsbSerialLinkEvent {
    public data class PermissionResult(public val device: UsbDeviceHandle, public val granted: Boolean) :
        UsbSerialLinkEvent

    public data class Detached(public val device: UsbDeviceHandle) : UsbSerialLinkEvent
}

public class UsbSerialIoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The seam between [UsbSerialTransport]'s state machine and Android's `UsbManager` +
 * usb-serial-for-android's CDC-ACM driver (FR-RIG-3). The real implementation
 * ([AndroidUsbSerialLink]) is the only class in this module that imports `android.hardware.usb.*`
 * or touches a `PendingIntent`; [org.ort.rig.usb.fakes.FakeUsbSerialPort] scripts every state a
 * real link can be in (constitution II) so [UsbSerialTransport] itself has no Android dependency
 * and its tests need no Robolectric.
 */
public interface UsbSerialLink {
    /** Currently attached devices matching the configured VID/PID (FR-RIG-3). Empty when none. */
    public fun attachedDevices(vendorId: Int, productId: Int): List<UsbDeviceHandle>

    /** True if the app already holds permission for [device] — USB permission is granted per
     * attachment and is not persistent by default (FR-PLT-2), so this can flip to false across a
     * detach/re-attach even when it was true a moment ago. */
    public fun hasPermission(device: UsbDeviceHandle): Boolean

    /** Fires the platform's permission request (a `PendingIntent` broadcast for real hardware).
     * Never throws; the result arrives on [events] as [UsbSerialLinkEvent.PermissionResult]. */
    public fun requestPermission(device: UsbDeviceHandle)

    /** Claims the interface and opens the CDC-ACM port with [config]. Throws
     * [UsbSerialIoException] if the claim or open fails. */
    public fun open(device: UsbDeviceHandle, config: UsbSerialLineConfig)

    public fun close()

    public fun write(bytes: ByteArray)

    /** Waits up to [timeoutMs] for more bytes. Returns an empty array on timeout (never blocks
     * past it — the same contract as [org.ort.rig.RigTransport.readLine]); a `suspend` fun so a
     * real implementation can park the calling coroutine (`withContext(Dispatchers.IO)` around
     * usb-serial-for-android's blocking `read(buffer, timeoutMillis)`) and a fake can honour the
     * timeout against virtual test time exactly as [org.ort.rig.fakes.FakeRigTransport] does.
     * Throws [UsbSerialIoException] if the device is gone (F16: a re-attach that loses
     * permission, or an outright detach mid-read, must never throw into capture —
     * [UsbSerialTransport] is the layer that catches this and turns it into a
     * [org.ort.rig.TransportState]). */
    public suspend fun read(timeoutMs: Long): ByteArray

    /** Permission results and detach notifications — the only way this seam pushes news that did
     * not originate from a call [UsbSerialTransport] made itself. */
    public val events: Flow<UsbSerialLinkEvent>
}
