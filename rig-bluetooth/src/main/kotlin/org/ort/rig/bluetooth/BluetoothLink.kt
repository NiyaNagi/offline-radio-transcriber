package org.ort.rig.bluetooth

import kotlinx.coroutines.flow.Flow

/** Whether a bonded device advertises the SPP UUID. [UNKNOWN] is deliberate, not a fallback for
 * `NO`: some Android stacks return no UUIDs at all for a bonded device until it is connected at
 * least once, and reporting that as `NO` would be a guess (constitution I — never assert what is
 * not known). */
public enum class SppSupport { YES, NO, UNKNOWN }

/** One bonded (paired) device, as [BluetoothSppTransport.Companion.pairedDevices] surfaces it to
 * the onboarding picker (FR-RIG-14). */
public data class PairedBluetoothDevice(
    public val address: String,
    public val name: String?,
    public val advertisesSpp: SppSupport,
)

/** Something the wire pushed at [BluetoothSppTransport] with no call in progress. */
public sealed interface BluetoothLinkEvent {
    public data class Dropped(public val address: String, public val reason: String) : BluetoothLinkEvent
}

public class BluetoothLinkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The seam between [BluetoothSppTransport]'s state machine and Android's Bluetooth Classic APIs
 * (FR-RIG-14: RFCOMM to the SPP UUID `00001101-0000-1000-8000-00805F9B34FB`). The real
 * implementation ([AndroidBluetoothLink]) is the only class in this module that imports
 * `android.bluetooth.*`; [org.ort.rig.bluetooth.fakes.FakeBluetoothLink] scripts every state a
 * real link can be in (constitution II) so [BluetoothSppTransport] itself has no Android
 * dependency and its tests need no Robolectric.
 */
public interface BluetoothLink {
    /** Bonded devices, each marked whether it advertises SPP — never assumed (see
     * [SppSupport.UNKNOWN]'s doc comment). */
    public fun pairedDevices(): List<PairedBluetoothDevice>

    /** `BLUETOOTH_CONNECT` on API >= 31 (or the legacy `BLUETOOTH` permission pair below it).
     * Checked before every connect attempt so an absence becomes [BluetoothSppTransport.Reason
     * .NO_PERMISSION], never a `SecurityException` thrown into capture. */
    public fun hasConnectPermission(): Boolean

    /** Opens an RFCOMM socket to [address] on the SPP UUID. Throws [BluetoothLinkException] on
     * failure (device not reachable, socket refused, …) — permission absence is reported
     * structurally by [hasConnectPermission] instead, never as an exception from here. */
    public fun connect(address: String)

    public fun close()

    public fun write(bytes: ByteArray)

    /** Waits up to [timeoutMs] for more bytes; a `suspend` fun so a real implementation can park
     * the calling coroutine around the socket's blocking `InputStream.read`, and a fake can
     * honour the timeout against virtual test time. Returns an empty array on timeout. Throws
     * [BluetoothLinkException] if the socket has dropped. */
    public suspend fun read(timeoutMs: Long): ByteArray

    /** Unsolicited drop notifications — the only way this seam pushes news that [
     * BluetoothSppTransport] did not itself trigger by calling [connect] or [read]. */
    public val events: Flow<BluetoothLinkEvent>
}
