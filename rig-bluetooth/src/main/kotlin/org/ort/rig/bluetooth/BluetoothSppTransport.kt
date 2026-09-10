package org.ort.rig.bluetooth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ort.rig.RigTransport
import org.ort.rig.TransportState

/** The Serial Port Profile UUID every Bluetooth Classic SPP link uses (FR-RIG-14). */
public const val SPP_UUID: String = "00001101-0000-1000-8000-00805F9B34FB"

/**
 * [RigTransport] over Bluetooth Classic SPP (FR-RIG-14). A drop is treated exactly like a USB
 * disconnection — [Reason.DROPPED] plays the same role as `UsbSerialTransport.Reason.DETACHED`,
 * the same backoff shape, the same "never throw into capture" discipline (FR-RIG-15: "a
 * transport that drops more often SHALL NOT become a transport that drops capture"). Everything
 * that touches `BluetoothAdapter`/`BluetoothSocket` lives behind [BluetoothLink]
 * ([AndroidBluetoothLink] for real hardware); this class is the state machine alone, with no
 * Android dependency, so its tests run as plain JVM unit tests against
 * [org.ort.rig.bluetooth.fakes.FakeBluetoothLink].
 */
public class BluetoothSppTransport(
    private val address: String,
    private val link: BluetoothLink,
    private val lineTerminator: Char,
    private val backoffMillisFor: (attempt: Int) -> Long = BluetoothReconnectBackoff::delayMillisFor,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RigTransport {

    /** Named, stable reasons for [TransportState.Lost] — asserted on in tests, never the prose a
     * human-facing screen might show next to it (constitution II). */
    public object Reason {
        public const val NO_PERMISSION: String = "bluetooth-connect-permission-missing"
        public const val CONNECT_FAILED: String = "bluetooth-connect-failed"
        public const val DROPPED: String = "bluetooth-dropped"
        public const val WRITE_FAILED: String = "bluetooth-write-failed"
        public const val READ_FAILED: String = "bluetooth-device-gone-during-read"
    }

    public companion object {
        /** FR-RIG-14: bonded devices, each marked whether it advertises SPP (FR-RIG-16's
         * onboarding picker reads this directly; it needs no live transport instance). */
        public fun pairedDevices(link: BluetoothLink): List<PairedBluetoothDevice> = link.pairedDevices()
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Closed)
    private val lineBuffer = StringBuilder()
    private var supervisorJob: Job? = null

    override val state: Flow<TransportState> = stateFlow.asStateFlow()

    override fun open() {
        if (supervisorJob?.isActive == true) return
        stateFlow.value = TransportState.Connecting
        supervisorJob = scope.launch { runSupervisor() }
    }

    override fun close() {
        supervisorJob?.cancel()
        supervisorJob = null
        runCatching { link.close() }
        lineBuffer.setLength(0)
        stateFlow.value = TransportState.Closed
    }

    override fun write(line: String) {
        check(stateFlow.value is TransportState.Open) { "transport is not open" }
        try {
            link.write((line + lineTerminator).toByteArray(Charsets.US_ASCII))
        } catch (e: BluetoothLinkException) {
            stateFlow.value = TransportState.Lost(Reason.WRITE_FAILED)
        }
    }

    override suspend fun readLine(timeoutMs: Long): String? {
        if (stateFlow.value !is TransportState.Open) return null
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val terminatorIndex = lineBuffer.indexOf(lineTerminator.toString())
                if (terminatorIndex >= 0) {
                    val line = lineBuffer.substring(0, terminatorIndex)
                    lineBuffer.delete(0, terminatorIndex + 1)
                    return@withTimeoutOrNull line
                }
                val bytes = try {
                    link.read(timeoutMs)
                } catch (e: BluetoothLinkException) {
                    stateFlow.value = TransportState.Lost(Reason.READ_FAILED)
                    return@withTimeoutOrNull null
                }
                if (stateFlow.value !is TransportState.Open) return@withTimeoutOrNull null
                if (bytes.isNotEmpty()) lineBuffer.append(String(bytes, Charsets.US_ASCII))
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    /** The outcome of one connection attempt — kept to two cases so [runSupervisor] never needs
     * more than one branch per iteration (detekt: nesting depth / jump statements). */
    private sealed interface ConnectOutcome {
        data object Connected : ConnectOutcome
        data object Retry : ConnectOutcome
    }

    private suspend fun runSupervisor() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            when (connectOnce()) {
                ConnectOutcome.Connected -> {
                    awaitOpenEnds()
                    if (stateFlow.value is TransportState.Closed) return
                    attempt = 1
                }
                ConnectOutcome.Retry -> attempt++
            }
            delay(backoffMillisFor(attempt))
            stateFlow.value = TransportState.Connecting
        }
    }

    private fun lost(reason: String): ConnectOutcome.Retry {
        stateFlow.value = TransportState.Lost(reason)
        return ConnectOutcome.Retry
    }

    private fun connectOnce(): ConnectOutcome {
        if (!link.hasConnectPermission()) return lost(Reason.NO_PERMISSION)

        return try {
            link.connect(address)
            lineBuffer.setLength(0)
            stateFlow.value = TransportState.Open
            ConnectOutcome.Connected
        } catch (e: BluetoothLinkException) {
            lost(Reason.CONNECT_FAILED)
        }
    }

    /** Suspends until the open connection ends, whether by a drop ([Reason.DROPPED], watched
     * concurrently here) or by any other path setting [stateFlow] away from [TransportState.Open]
     * (e.g. [readLine]'s [Reason.READ_FAILED]). */
    private suspend fun awaitOpenEnds() {
        coroutineScope {
            val dropWatcher = launch {
                link.events.filterIsInstance<BluetoothLinkEvent.Dropped>().first { it.address == address }
                stateFlow.value = TransportState.Lost(Reason.DROPPED)
            }
            stateFlow.first { it !is TransportState.Open }
            dropWatcher.cancel()
        }
    }
}
