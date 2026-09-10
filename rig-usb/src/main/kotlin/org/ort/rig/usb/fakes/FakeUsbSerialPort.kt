package org.ort.rig.usb.fakes

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.ort.rig.usb.UsbDeviceHandle
import org.ort.rig.usb.UsbSerialIoException
import org.ort.rig.usb.UsbSerialLineConfig
import org.ort.rig.usb.UsbSerialLink
import org.ort.rig.usb.UsbSerialLinkEvent

/**
 * A scripted [UsbSerialLink] (constitution II — every model-bearing interface ships with a
 * behavioural fake that can be told to fail, hang or drop). Everything [UsbSerialTransport][
 * org.ort.rig.usb.UsbSerialTransport]'s state machine needs to be driven through is a named,
 * callable method here:
 *
 * - [attach] — makes a device with the given VID/PID visible to [attachedDevices].
 * - [detach] — removes it and, if a port is open on it, delivers [UsbSerialLinkEvent.Detached].
 * - [grantPermission] / [denyPermission] — script the result [UsbSerialTransport] gets back
 *   after calling [requestPermission]; call [denyPermission] more than once in a row to model
 *   "permission denied twice" without ever having granted it.
 * - [failNextOpen] — the next [open] call throws [UsbSerialIoException].
 * - [scriptReply] — queues bytes to hand back from [read] the moment [write] sends a matching
 *   line (matched on the exact bytes written, including the configured terminator).
 * - [dropDuringRead] — the next (or every, if [persistent]) [read] call throws
 *   [UsbSerialIoException], modelling "device gone during read" without a prior [detach].
 */
public class FakeUsbSerialPort : UsbSerialLink {

    private val eventsChannel = Channel<UsbSerialLinkEvent>(capacity = Channel.UNLIMITED)
    private var attachedDevice: UsbDeviceHandle? = null
    private val permittedDevices = mutableSetOf<UsbDeviceHandle>()
    private val incoming = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val scripted = mutableMapOf<String, MutableList<ByteArray>>()
    private val sent = mutableListOf<ByteArray>()

    private var openShouldFail = false
    private var readShouldFail = false
    private var readFailsPersistently = false
    private var isOpen = false
    private var openedDevice: UsbDeviceHandle? = null

    override val events: Flow<UsbSerialLinkEvent> = eventsChannel.receiveAsFlow()

    /** Every line [write] has sent, as raw bytes, in order. */
    public val bytesSent: List<ByteArray> get() = sent.toList()

    /** Makes [device] visible to [attachedDevices]. Permission is unaffected — call
     * [grantPermission] separately, matching a real re-attach that needs it granted again. */
    public fun attach(device: UsbDeviceHandle) {
        attachedDevice = device
    }

    /** Removes [device] from [attachedDevices] and, if it is the currently open device, pushes
     * [UsbSerialLinkEvent.Detached]. */
    public fun detach(device: UsbDeviceHandle) {
        if (attachedDevice == device) attachedDevice = null
        if (openedDevice == device) {
            eventsChannel.trySend(UsbSerialLinkEvent.Detached(device))
        }
    }

    /** The next [requestPermission] call for [device] resolves granted. */
    public fun grantPermission(device: UsbDeviceHandle) {
        permittedDevices += device
        eventsChannel.trySend(UsbSerialLinkEvent.PermissionResult(device, granted = true))
    }

    /** The next [requestPermission] call for [device] resolves denied — call it again for a
     * second denial in a row ("permission denied twice"). */
    public fun denyPermission(device: UsbDeviceHandle) {
        permittedDevices -= device
        eventsChannel.trySend(UsbSerialLinkEvent.PermissionResult(device, granted = false))
    }

    /** The next [open] call throws [UsbSerialIoException] instead of succeeding. */
    public fun failNextOpen() {
        openShouldFail = true
    }

    /** Queues [replies] to be handed back from [read], in order, the moment a line ending in
     * [lineConfig]'s terminator equal to [command] is [write]-ed. */
    public fun scriptReply(command: String, vararg replies: String, lineConfig: UsbSerialLineConfig) {
        val key = command + lineConfig.lineTerminator
        val encoded = replies.map { (it + lineConfig.lineTerminator).toByteArray() }
        scripted.getOrPut(key) { mutableListOf() }.addAll(encoded)
    }

    /** [read] throws [UsbSerialIoException] on its next call ([persistent] = false, default) or
     * every call from now on ([persistent] = true) — "device gone during read", with no prior
     * [detach]. */
    public fun dropDuringRead(persistent: Boolean = false) {
        readShouldFail = true
        readFailsPersistently = persistent
    }

    override fun attachedDevices(vendorId: Int, productId: Int): List<UsbDeviceHandle> =
        listOfNotNull(attachedDevice?.takeIf { it.vendorId == vendorId && it.productId == productId })

    override fun hasPermission(device: UsbDeviceHandle): Boolean = device in permittedDevices

    override fun requestPermission(device: UsbDeviceHandle) {
        // Real hardware answers asynchronously via a PendingIntent broadcast; this fake only
        // resolves when the test calls grantPermission/denyPermission, exactly like the real
        // link only resolves when the user answers the system dialog.
    }

    override fun open(device: UsbDeviceHandle, config: UsbSerialLineConfig) {
        if (openShouldFail) {
            openShouldFail = false
            throw UsbSerialIoException("failed to claim interface")
        }
        isOpen = true
        openedDevice = device
    }

    override fun close() {
        isOpen = false
        openedDevice = null
    }

    override fun write(bytes: ByteArray) {
        check(isOpen) { "port is not open" }
        sent += bytes
        scripted[String(bytes)]?.forEach { incoming.trySend(it) }
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        if (readShouldFail) {
            if (!readFailsPersistently) readShouldFail = false
            throw UsbSerialIoException("device gone")
        }
        if (!isOpen) return ByteArray(0)
        return withTimeoutOrNull(timeoutMs) { incoming.receive() } ?: ByteArray(0)
    }
}
