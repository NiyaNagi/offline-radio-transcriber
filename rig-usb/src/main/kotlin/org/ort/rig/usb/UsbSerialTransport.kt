package org.ort.rig.usb

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

/**
 * [RigTransport] over `usb-serial-for-android`'s CDC-ACM driver (FR-RIG-3). Everything that
 * touches `UsbManager`, a `PendingIntent` or the actual serial port lives behind [UsbSerialLink]
 * ([AndroidUsbSerialLink] for real hardware); this class is the state machine alone — open, wait
 * for permission, open the port, read, react to a detach, back off, try again (FR-RIG-7) — and
 * has no Android dependency, so its tests run as plain JVM unit tests against
 * [org.ort.rig.usb.fakes.FakeUsbSerialPort].
 *
 * `TransportState` (`:rig`) is a closed, four-case sealed interface this module may not extend,
 * so USB-specific conditions are carried as named [Reason] strings inside
 * [TransportState.Lost] — never raw prose (constitution II: assertions must not depend on
 * message text) — plus, additionally, on [permissionState] for a caller (WPD's setup UI) that
 * wants the richer distinction directly rather than parsing a reason string.
 */
public class UsbSerialTransport(
    private val vendorId: Int,
    private val productId: Int,
    private val lineConfig: UsbSerialLineConfig,
    private val link: UsbSerialLink,
    private val backoffMillisFor: (attempt: Int) -> Long = UsbReconnectBackoff::delayMillisFor,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RigTransport {

    /** Named, stable reasons for [TransportState.Lost] — asserted on in tests, never the prose a
     * human-facing screen might show next to it. */
    public object Reason {
        public const val DEVICE_NOT_FOUND: String = "usb-device-not-found"
        public const val PERMISSION_DENIED: String = "usb-permission-denied"
        public const val PERMISSION_LOST: String = "usb-permission-lost"
        public const val OPEN_FAILED: String = "usb-open-failed"
        public const val DETACHED: String = "usb-detached"
        public const val DEVICE_GONE_DURING_READ: String = "usb-device-gone-during-read"
        public const val WRITE_FAILED: String = "usb-write-failed"
    }

    /** FR-PLT-2's distinction, for a caller that wants it structurally rather than by parsing
     * [TransportState.Lost]'s reason string. */
    public enum class UsbPermissionState { UNKNOWN, GRANTED, DENIED, LOST }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Closed)
    private val permissionFlow = MutableStateFlow(UsbPermissionState.UNKNOWN)
    private val lineBuffer = StringBuilder()

    private var supervisorJob: Job? = null
    private var everGranted = false
    private var currentDevice: UsbDeviceHandle? = null

    override val state: Flow<TransportState> = stateFlow.asStateFlow()

    /** [UsbPermissionState] over time — see the class doc comment. */
    public val permissionState: Flow<UsbPermissionState> = permissionFlow.asStateFlow()

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
            link.write((line + lineConfig.lineTerminator).toByteArray(Charsets.US_ASCII))
        } catch (e: UsbSerialIoException) {
            stateFlow.value = TransportState.Lost(Reason.WRITE_FAILED)
        }
    }

    override suspend fun readLine(timeoutMs: Long): String? {
        if (stateFlow.value !is TransportState.Open) return null
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val terminatorIndex = lineBuffer.indexOf(lineConfig.lineTerminator.toString())
                if (terminatorIndex >= 0) {
                    val line = lineBuffer.substring(0, terminatorIndex)
                    lineBuffer.delete(0, terminatorIndex + 1)
                    return@withTimeoutOrNull line
                }
                val bytes = try {
                    link.read(timeoutMs)
                } catch (e: UsbSerialIoException) {
                    stateFlow.value = TransportState.Lost(Reason.DEVICE_GONE_DURING_READ)
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

    private suspend fun connectOnce(): ConnectOutcome {
        val device = link.attachedDevices(vendorId, productId).firstOrNull()
            ?: return lost(Reason.DEVICE_NOT_FOUND)

        if (!link.hasPermission(device) && !awaitPermission(device)) {
            val reason = if (everGranted) Reason.PERMISSION_LOST else Reason.PERMISSION_DENIED
            permissionFlow.value = if (everGranted) UsbPermissionState.LOST else UsbPermissionState.DENIED
            return lost(reason)
        }
        permissionFlow.value = UsbPermissionState.GRANTED

        return try {
            link.open(device, lineConfig)
            everGranted = true
            currentDevice = device
            lineBuffer.setLength(0)
            stateFlow.value = TransportState.Open
            ConnectOutcome.Connected
        } catch (e: UsbSerialIoException) {
            lost(Reason.OPEN_FAILED)
        }
    }

    /** Requests permission for [device] and suspends for the platform's answer. */
    private suspend fun awaitPermission(device: UsbDeviceHandle): Boolean {
        permissionFlow.value = UsbPermissionState.UNKNOWN
        link.requestPermission(device)
        return link.events
            .filterIsInstance<UsbSerialLinkEvent.PermissionResult>()
            .first { it.device == device }
            .granted
    }

    /** Suspends until the open connection ends, whether by detach ([Reason.DETACHED], watched
     * concurrently here) or by any other path setting [stateFlow] away from [TransportState.Open]
     * (e.g. [readLine]'s [Reason.DEVICE_GONE_DURING_READ]). */
    private suspend fun awaitOpenEnds() {
        val device = currentDevice ?: return
        coroutineScope {
            val detachWatcher = launch {
                link.events.filterIsInstance<UsbSerialLinkEvent.Detached>().first { it.device == device }
                stateFlow.value = TransportState.Lost(Reason.DETACHED)
            }
            stateFlow.first { it !is TransportState.Open }
            detachWatcher.cancel()
        }
    }
}
