package org.ort.rig.bluetooth.fakes

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.ort.rig.bluetooth.BluetoothLink
import org.ort.rig.bluetooth.BluetoothLinkEvent
import org.ort.rig.bluetooth.BluetoothLinkException
import org.ort.rig.bluetooth.PairedBluetoothDevice
import org.ort.rig.bluetooth.SppSupport

/**
 * A scripted [BluetoothLink] (constitution II — every model-bearing interface ships with a
 * behavioural fake that can be told to fail, hang or drop). Every failure mode a real RFCOMM
 * link can exhibit is a named, callable method:
 *
 * - [pair] — adds a bonded device to [pairedDevices], its SPP support declared honestly
 *   ([SppSupport.UNKNOWN] when a real stack would report no UUIDs at all — never guessed as
 *   [SppSupport.NO]).
 * - [denyConnectPermission] / [grantConnectPermission] — script whether `BLUETOOTH_CONNECT` is
 *   held; `BluetoothSppTransport` never sees a `SecurityException` either way.
 * - [failNextConnect] — the next [connect] throws [BluetoothLinkException].
 * - [scriptReply] — queues bytes to hand back from [read] once a matching line is [write]-ed.
 * - [drop] — the socket drops mid-session, pushing [BluetoothLinkEvent.Dropped] — the shared
 *   FR-RIG-15 scenario this fake and `FakeUsbSerialPort.detach` both drive.
 * - [dropDuringRead] — the next (or every, if [persistent]) [read] call throws, with no prior
 *   [drop] event — "socket gone during read".
 */
public class FakeBluetoothLink : BluetoothLink {

    private val eventsChannel = Channel<BluetoothLinkEvent>(capacity = Channel.UNLIMITED)
    override val events: Flow<BluetoothLinkEvent> = eventsChannel.receiveAsFlow()

    private val paired = mutableListOf<PairedBluetoothDevice>()
    private val incoming = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val scripted = mutableMapOf<String, MutableList<ByteArray>>()
    private val sent = mutableListOf<ByteArray>()

    private var connectPermission = true
    private var connectShouldFail = false
    private var readShouldFail = false
    private var readFailsPersistently = false
    private var connectedAddress: String? = null

    /** Every line [write] has sent, as raw bytes, in order. */
    public val bytesSent: List<ByteArray> get() = sent.toList()

    /** Adds [address] to the bonded list returned by [pairedDevices]. */
    public fun pair(address: String, name: String?, advertisesSpp: SppSupport) {
        paired += PairedBluetoothDevice(address, name, advertisesSpp)
    }

    /** `BLUETOOTH_CONNECT` is held from now on (the default). */
    public fun grantConnectPermission() {
        connectPermission = true
    }

    /** `BLUETOOTH_CONNECT` is absent — [BluetoothSppTransport] must surface
     * [org.ort.rig.bluetooth.BluetoothSppTransport.Reason.NO_PERMISSION], never throw. */
    public fun denyConnectPermission() {
        connectPermission = false
    }

    /** The next [connect] call throws [BluetoothLinkException] instead of succeeding. */
    public fun failNextConnect() {
        connectShouldFail = true
    }

    /** Queues [replies] to be handed back from [read], in order, the moment a line ending in
     * [terminator] equal to [command] is [write]-ed. */
    public fun scriptReply(command: String, vararg replies: String, terminator: Char) {
        val key = command + terminator
        scripted.getOrPut(key) { mutableListOf() }.addAll(replies.map { (it + terminator).toByteArray() })
    }

    /** The socket drops while nominally connected — a real disconnection (FR-RIG-15). */
    public fun drop(reason: String = "connection dropped") {
        val current = connectedAddress ?: return
        connectedAddress = null
        eventsChannel.trySend(BluetoothLinkEvent.Dropped(current, reason))
    }

    /** [read] throws on its next call ([persistent] = false, default) or every call from now on
     * ([persistent] = true) — "socket gone during read", with no prior [drop]. */
    public fun dropDuringRead(persistent: Boolean = false) {
        readShouldFail = true
        readFailsPersistently = persistent
    }

    override fun pairedDevices(): List<PairedBluetoothDevice> = paired.toList()

    override fun hasConnectPermission(): Boolean = connectPermission

    override fun connect(address: String) {
        if (connectShouldFail) {
            connectShouldFail = false
            throw BluetoothLinkException("connect failed")
        }
        connectedAddress = address
    }

    override fun close() {
        connectedAddress = null
    }

    override fun write(bytes: ByteArray) {
        check(connectedAddress != null) { "socket is not connected" }
        sent += bytes
        scripted[String(bytes)]?.forEach { incoming.trySend(it) }
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        if (readShouldFail) {
            if (!readFailsPersistently) readShouldFail = false
            throw BluetoothLinkException("socket gone")
        }
        if (connectedAddress == null) return ByteArray(0)
        return withTimeoutOrNull(timeoutMs) { incoming.receive() } ?: ByteArray(0)
    }
}
