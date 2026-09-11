package org.ort.rig.descriptor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.rig.Connection
import org.ort.rig.RigBand
import org.ort.rig.RigCapability
import org.ort.rig.RigConfigField
import org.ort.rig.RigConfigSchema
import org.ort.rig.RigHealth
import org.ort.rig.RigHealthIssue
import org.ort.rig.RigModule
import org.ort.rig.RigState
import org.ort.rig.RigStateConfidence
import org.ort.rig.RigTransport
import org.ort.rig.RigTransportKind
import org.ort.rig.TransportState
import java.util.concurrent.ConcurrentHashMap

/** FR-RIG-6: the state at a transmission's start, and whether it changed again before the
 * transmission ended — a mid-transmission rig change is flagged, never silently overwritten. */
public data class TransmissionRigState(public val atStart: RigState, public val changedDuringTransmission: Boolean)

/**
 * FR-RIG-4's engine: turns a validated [RigDescriptor] plus a [RigTransport] into a working
 * [RigModule], with no radio-specific code.
 *
 * Polls per the descriptor and applies `AI`-style unsolicited push lines the instant they arrive
 * (`docs/reference/th-d75a-cat.md`, finding 1). The two are handled by **one** read loop that
 * matches every incoming line against every known pattern — poll replies and unsolicited
 * patterns alike — rather than assuming a reply follows its request in order: a line-oriented
 * ASCII link gives no way to tell "the reply I asked for" from "the radio talking on its own"
 * apart on the wire itself, so pattern matching is not a simplification, it is the only design
 * that is actually correct. It also means push support falls out for free once poll support
 * exists — no separate code path is needed.
 */
public class DescriptorRigModule(
    private val descriptor: RigDescriptor,
    /**
     * WPC3 (FR-RIG-3): the matching [TransportSpec] for the [RigTransportKind] being connected —
     * `null` only when the descriptor declares no spec for that kind at all, which
     * [DescriptorValidator]'s [DescriptorError.NoTransports]/[DescriptorError.UnknownTransportKind]
     * already guard against for anything this class was actually built from. Passed through
     * unchanged from [connect] so a real factory
     * ([org.ort.pipeline.rig.DefaultRigTransportFactory]) can read `usbVendorId`/`usbProductId`/
     * `lineTerminator` from the descriptor itself, falling back to its own `params` only for
     * whatever the descriptor did not declare — see [TransportSpec]'s own kdoc.
     */
    private val transportFactory: (RigTransportKind, TransportSpec?, Map<String, String>) -> RigTransport,
    private val clock: Clock = SystemClock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val readTimeoutMs: Long = 1_000,
) : RigModule {

    override val id: String = descriptor.id
    override val displayName: String = descriptor.displayName
    override val transports: Set<RigTransportKind> =
        descriptor.transports.mapNotNull { wireTransportKind(it.kind) }.toSet()
    override val configSchema: RigConfigSchema = RigConfigSchema(
        descriptor.transports.firstNotNullOfOrNull { it.serial }?.let { serial ->
            listOf(
                RigConfigField("baud", "Baud rate", defaultValue = serial.baud.toString()),
                RigConfigField("dataBits", "Data bits", defaultValue = serial.dataBits.toString()),
                RigConfigField("stopBits", "Stop bits", defaultValue = serial.stopBits.toString()),
                RigConfigField("parity", "Parity", defaultValue = serial.parity),
            )
        } ?: emptyList(),
    )

    private val stateFlow = MutableSharedFlow<RigState>(replay = 1, extraBufferCapacity = 64)
    private val healthFlow = MutableSharedFlow<RigHealth>(replay = 1, extraBufferCapacity = 64)

    // Per-band (null = unscoped) last-known reading, patched as partial updates arrive.
    // `ConcurrentHashMap` refuses a null key, and `null` (unscoped) is exactly the key an
    // unbanded rig uses, so a plain synchronized map is used here instead.
    private val lastKnown = java.util.Collections.synchronizedMap(HashMap<RigBand?, RigState>())
    private val history = java.util.Collections.synchronizedList(mutableListOf<RigState>())
    private val compiledPatterns = ConcurrentHashMap<String, Regex>()

    private val allPatterns: List<PatternSpec> =
        descriptor.poll?.commands.orEmpty().map { PatternSpec(it.expect, it.map, it.lookup) } +
            descriptor.poll?.perBand.orEmpty().map { PatternSpec(it.expect, it.map, it.lookup) } +
            descriptor.unsolicited?.patterns.orEmpty()

    private var transport: RigTransport? = null
    private var pollJob: Job? = null
    private var readJob: Job? = null
    private var stateWatchJob: Job? = null

    override fun capabilities(transport: RigTransportKind): Set<RigCapability> {
        val spec = transportSpecFor(transport) ?: return emptySet()
        return spec.capabilities.mapNotNull { runCatching { RigCapability.valueOf(it) }.getOrNull() }.toSet()
    }

    private fun transportSpecFor(transport: RigTransportKind): TransportSpec? =
        descriptor.transports.firstOrNull { wireTransportKind(it.kind) == transport }

    override fun connect(transport: RigTransportKind, params: Map<String, String>): Result<Connection> {
        val opened = transportFactory(transport, transportSpecFor(transport), params)
        return try {
            opened.open()
            this.transport = opened
            startLoops(opened)
            Result.success(Connection(transport, id))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun disconnect() {
        pollJob?.cancel()
        readJob?.cancel()
        stateWatchJob?.cancel()
        transport?.close()
        transport = null
    }

    override fun observe(): Flow<RigState> = stateFlow

    override fun health(): Flow<RigHealth> = healthFlow

    /**
     * FR-RIG-6: the reading in force at [startNanos] for [band], and whether a later reading in
     * the same band landed before [endNanos] — a mid-transmission change. Returns `null` when
     * nothing has ever been recorded for [band].
     */
    public fun stateForTransmission(band: RigBand?, startNanos: Long, endNanos: Long): TransmissionRigState? {
        val snapshot = synchronized(history) { history.toList() }.filter { it.band == band }
        val atStart = snapshot.lastOrNull { it.timestampNanos <= startNanos }
            ?: snapshot.firstOrNull { it.timestampNanos > startNanos }
            ?: return null
        val changedDuring = snapshot.any { it.timestampNanos in (startNanos + 1)..endNanos && it !== atStart }
        return TransmissionRigState(atStart, changedDuring)
    }

    /**
     * D23: the timestamp at which [band]'s squelch most recently, continuously, transitioned to
     * open, looking no later than [atOrBeforeNanos]. `null` when [band]'s squelch is not open at
     * that time, or nothing about [band] has ever been recorded — a caller
     * ([org.ort.pipeline.rig.RigSupervisor.bandAtTransmissionStart]) never guesses which band a
     * transmission belongs to from this alone (constitution I); it uses this to compare bands, not
     * to assert one.
     */
    public fun squelchOpenedAtNanos(band: RigBand, atOrBeforeNanos: Long): Long? {
        val snapshot = synchronized(history) { history.toList() }
            .filter { it.band == band && it.timestampNanos <= atOrBeforeNanos }
        val latest = snapshot.lastOrNull() ?: return null
        if (latest.squelchOpen != true) return null
        var openSinceNanos = latest.timestampNanos
        for (index in snapshot.size - 2 downTo 0) {
            if (snapshot[index].squelchOpen == true) {
                openSinceNanos = snapshot[index].timestampNanos
            } else {
                break
            }
        }
        return openSinceNanos
    }

    private fun startLoops(opened: RigTransport) {
        descriptor.unsolicited?.let { unsolicited -> runCatching { opened.write(unsolicited.enable) } }

        readJob = scope.launch {
            while (true) {
                val line = withTimeoutOrNull(readTimeoutMs) { opened.readLine(readTimeoutMs) }
                if (line == null) {
                    healthFlow.tryEmit(RigHealth.Degraded(clock.monotonicNanos(), RigHealthIssue.TIMEOUT))
                    // WPC3 finding: a RigTransport that is not Open at all (still Connecting, or
                    // Lost between reconnect attempts -- both real states for UsbSerialTransport/
                    // BluetoothSppTransport, whose own connect runs asynchronously after open()
                    // returns) returns null from readLine() SYNCHRONOUSLY, with no suspension at
                    // all. Without a genuine suspension point here, this loop would busy-spin one
                    // CPU core at 100% for as long as the transport stays not-Open, AND -- since
                    // Kotlin coroutine cancellation is cooperative -- would make disconnect()'s
                    // readJob.cancel() silently ineffective for as long as that spin continued,
                    // permanently starving any other coroutine sharing this scope's dispatcher
                    // (reproduced deterministically: WPC3's RigLinkBridgeTest against the real
                    // Bluetooth/USB transports). delay() is itself a cancellation point, so this
                    // both stops the spin and makes disconnect() take effect promptly.
                    delay(READ_RETRY_BACKOFF_MS)
                } else {
                    handleLine(line)
                }
            }
        }

        val poll = descriptor.poll
        if (poll != null) {
            pollJob = scope.launch {
                while (true) {
                    poll.commands.forEach { runCatching { opened.write(it.send) } }
                    for (band in descriptor.bands) {
                        poll.perBand.forEach { cmd ->
                            runCatching { opened.write(cmd.send.replace("{band}", band.toString())) }
                        }
                    }
                    delay(poll.intervalMs)
                }
            }
        }

        stateWatchJob = scope.launch {
            opened.state.collect { transportState ->
                if (transportState is TransportState.Lost) {
                    healthFlow.tryEmit(
                        RigHealth.Degraded(
                            clock.monotonicNanos(),
                            RigHealthIssue.TRANSPORT_LOST,
                            transportState.reason,
                        ),
                    )
                    markAllStale()
                }
            }
        }
    }

    private fun handleLine(line: String) {
        for (pattern in allPatterns) {
            val regex = compiledPatterns.getOrPut(pattern.expect) { Regex(pattern.expect) }
            val match = regex.find(line) ?: continue
            applyMatch(pattern, match)
            healthFlow.tryEmit(RigHealth.Healthy(clock.monotonicNanos()))
            return
        }
        healthFlow.tryEmit(
            RigHealth.Degraded(
                clock.monotonicNanos(),
                RigHealthIssue.UNPARSEABLE_RESPONSE,
                line.take(LINE_DETAIL_LIMIT),
            ),
        )
    }

    private fun applyMatch(pattern: PatternSpec, match: MatchResult) {
        val raw = mutableMapOf<String, String>()
        pattern.map.forEach { (field, template) ->
            val groupIndex = template.removePrefix("$").toIntOrNull() ?: return@forEach
            val value = match.groupValues.getOrNull(groupIndex) ?: return@forEach
            raw[field] = pattern.lookup[field]?.get(value) ?: value
        }

        val band = raw["band"]?.toIntOrNull()?.let(::bandFromWireIndex)
        val previous = lastKnown[band]
        val next = RigState(
            timestampNanos = clock.monotonicNanos(),
            band = band ?: previous?.band,
            frequencyHz = raw["frequencyHz"]?.toLongOrNull() ?: previous?.frequencyHz,
            mode = raw["mode"] ?: previous?.mode,
            squelchOpen = raw["squelchOpen"]?.let { it == "1" || it.equals("true", ignoreCase = true) }
                ?: previous?.squelchOpen,
            signalStrength = raw["signalStrength"]?.toIntOrNull() ?: previous?.signalStrength,
            memoryChannel = raw["memoryChannel"] ?: previous?.memoryChannel,
            channelName = raw["channelName"] ?: previous?.channelName,
            position = previous?.position,
            sourceConfidence = RigStateConfidence.FRESH,
        )
        lastKnown[band] = next
        recordHistory(next)
        stateFlow.tryEmit(next)
    }

    private fun markAllStale() {
        val bands = synchronized(lastKnown) { lastKnown.keys.toList() }
        bands.forEach { band ->
            val current = lastKnown[band] ?: return@forEach
            val stale = current.copy(sourceConfidence = RigStateConfidence.STALE)
            lastKnown[band] = stale
            recordHistory(stale)
            stateFlow.tryEmit(stale)
        }
    }

    private fun recordHistory(state: RigState) {
        synchronized(history) {
            history.add(state)
            if (history.size > HISTORY_LIMIT) history.removeAt(0)
        }
    }

    public companion object {
        private const val HISTORY_LIMIT = 512
        private const val LINE_DETAIL_LIMIT = 64

        /** WPC3: the read loop's backoff after a null [RigTransport.readLine] result -- see that
         * call site's own kdoc for why this exists at all (a not-Open transport returns null with
         * no suspension, which would otherwise busy-spin and defeat cooperative cancellation). */
        private const val READ_RETRY_BACKOFF_MS = 50L

        internal fun wireTransportKind(raw: String): RigTransportKind? = when (raw.lowercase()) {
            "usb_serial" -> RigTransportKind.USB_SERIAL
            "bluetooth_spp" -> RigTransportKind.BLUETOOTH_SPP
            "ble" -> RigTransportKind.BLE
            "network" -> RigTransportKind.NETWORK
            "none" -> RigTransportKind.NONE
            else -> null
        }

        internal fun bandFromWireIndex(index: Int): RigBand? = when (index) {
            0 -> RigBand.A
            1 -> RigBand.B
            else -> null
        }
    }
}
