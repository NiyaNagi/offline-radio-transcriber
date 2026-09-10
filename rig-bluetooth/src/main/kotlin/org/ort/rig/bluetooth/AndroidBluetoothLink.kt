package org.ort.rig.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

private val SPP_SERVICE_UUID: UUID = UUID.fromString(SPP_UUID)

/**
 * The real [BluetoothLink]: `BluetoothSocket` RFCOMM to the SPP UUID (FR-RIG-14), with
 * `BLUETOOTH_CONNECT` checked structurally before every attempt rather than caught as a
 * `SecurityException` (FR-PLT-2's Bluetooth equivalent). Exercised only by hardware rows H2/H3
 * (`results/e2e-audit/hardware-checklist.md`) — the emulator has no Bluetooth radio at all, so
 * every behavioural test in this module runs [BluetoothSppTransport] against
 * [org.ort.rig.bluetooth.fakes.FakeBluetoothLink] instead.
 */
public class AndroidBluetoothLink(private val context: Context) : BluetoothLink {

    private val eventsChannel = Channel<BluetoothLinkEvent>(capacity = Channel.UNLIMITED)
    private val incoming = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    override val events: Flow<BluetoothLinkEvent> = eventsChannel.receiveAsFlow()

    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var socket: BluetoothSocket? = null

    @Volatile
    private var connectedAddress: String? = null

    /**
     * FR-PLT-2's Bluetooth equivalent, checked structurally before every stack call below
     * (constitution IV: a permission race is a transport state, never a thrown
     * `SecurityException`). Below API 31 `BLUETOOTH_CONNECT` does not exist as a runtime concept
     * — the legacy `BLUETOOTH` permission (`AndroidManifest.xml`, `maxSdkVersion="30"`) covers
     * those platforms at install time, so this is unconditionally true there.
     *
     * This exact expression — not a call to some other wrapping function — is repeated inline at
     * every call site below (rather than shared through this one method) because Android Lint's
     * `MissingPermission` check only recognises a `checkSelfPermission` guard in the same method
     * body as the call it protects; routing it through a helper leaves the real platform calls
     * unguarded as far as lint's flow analysis is concerned.
     */
    override fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    override fun pairedDevices(): List<PairedBluetoothDevice> {
        val bonded = if (
            Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        } else {
            emptySet()
        }
        return bonded.map { device -> device.toPairedDevice() }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun BluetoothDevice.toPairedDevice(): PairedBluetoothDevice {
        val deviceName = if (
            Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { name }.getOrNull()
        } else {
            null
        }
        return PairedBluetoothDevice(address = address, name = deviceName, advertisesSpp = sppSupport())
    }

    /** Never assume `NO` from an absent answer — some stacks report no UUIDs at all for a
     * bonded device until it has been connected once (constitution I). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun BluetoothDevice.sppSupport(): SppSupport {
        val uuids = if (
            Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { uuids }.getOrNull()
        } else {
            null
        }
        if (uuids.isNullOrEmpty()) return SppSupport.UNKNOWN
        return if (uuids.any { it.uuid == SPP_SERVICE_UUID }) SppSupport.YES else SppSupport.NO
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun connect(address: String) {
        if (
            Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw BluetoothLinkException("BLUETOOTH_CONNECT not granted")
        }
        val device = adapter?.getRemoteDevice(address)
            ?: throw BluetoothLinkException("no Bluetooth adapter available")
        val newSocket = try {
            device.createRfcommSocketToServiceRecord(SPP_SERVICE_UUID).also { it.connect() }
        } catch (e: IOException) {
            throw BluetoothLinkException("connect failed to $address", e)
        } catch (e: SecurityException) {
            // The structural guard above is the primary path; this catch exists only for the
            // platform's own race (permission revoked between the check and the call) so it
            // never becomes an uncaught SecurityException in capture (FR-PLT-2's equivalent).
            throw BluetoothLinkException("BLUETOOTH_CONNECT denied by the platform", e)
        }
        socket = newSocket
        connectedAddress = address
        startReader(newSocket, address)
    }

    private fun startReader(activeSocket: BluetoothSocket, address: String) {
        Thread({
            val buffer = ByteArray(READ_BUFFER_SIZE)
            val input = activeSocket.inputStream
            try {
                while (connectedAddress == address) {
                    val readLength = input.read(buffer)
                    if (readLength < 0) break
                    if (readLength > 0) incoming.trySend(buffer.copyOf(readLength))
                }
            } catch (e: IOException) {
                // Falls through to the drop notification below — a closed/reset socket surfaces
                // as a read failure on most platforms.
            }
            if (connectedAddress == address) {
                connectedAddress = null
                eventsChannel.trySend(BluetoothLinkEvent.Dropped(address, "socket closed"))
            }
        }, "bluetooth-spp-reader").start()
    }

    override fun close() {
        connectedAddress = null
        runCatching { socket?.close() }
        socket = null
    }

    override fun write(bytes: ByteArray) {
        val current = socket ?: throw BluetoothLinkException("socket is not connected")
        try {
            current.outputStream.write(bytes)
        } catch (e: IOException) {
            throw BluetoothLinkException("write failed", e)
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        withTimeoutOrNull(timeoutMs) { incoming.receive() } ?: ByteArray(0)

    private companion object {
        const val READ_BUFFER_SIZE = 1024
    }
}
