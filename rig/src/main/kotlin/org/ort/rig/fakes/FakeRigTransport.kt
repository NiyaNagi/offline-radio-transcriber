package org.ort.rig.fakes

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.ort.rig.RigTransport
import org.ort.rig.RigTransportException
import org.ort.rig.TransportState

/**
 * A scripted [RigTransport] (constitution II — every model-bearing interface ships with a
 * behavioural fake that can be told to fail, hang or return a hallucination). Every failure mode
 * a real serial/Bluetooth link can exhibit is a named, callable method:
 *
 * - [failToOpen] — [open] throws and the state flow reports [TransportState.Lost].
 * - [hangOnNextRead] — the next [readLine] never returns on its own; a caller that applies no
 *   watchdog of its own hangs forever, which is exactly the point (FR-RIG-1's `health()`).
 * - [dropMidStream] — the link goes [TransportState.Lost] while nominally "open".
 * - [scriptGarbage] — a queued reply that will not match any descriptor pattern.
 * - [pushUnsolicited] — a line delivered with no matching outbound command, modelling the
 *   TH-D75A's `AI` auto-information push (`docs/reference/th-d75a-cat.md`).
 *
 * All incoming lines — scripted replies and pushes alike — share one FIFO, exactly like a real
 * serial port: nothing on the wire distinguishes "the reply I asked for" from "the radio talking
 * on its own". That is precisely the design pressure `DescriptorRigModule` is built to survive.
 */
public class FakeRigTransport : RigTransport {

    private val incoming = Channel<String>(capacity = Channel.UNLIMITED)
    private val scripted = mutableMapOf<String, MutableList<String>>()
    private val sent = mutableListOf<String>()
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Closed)

    private var openShouldFail = false
    private var hangNextRead = false
    private var isOpen = false

    /** Every line handed to [write], in order — so a test can assert what a module actually sent. */
    public val commandsSent: List<String> get() = sent.toList()

    override val state: Flow<TransportState> = stateFlow.asStateFlow()

    /** Queues [replies] to be delivered, in order, the moment [command] is [write]-ed. */
    public fun scriptReply(command: String, vararg replies: String) {
        scripted.getOrPut(command) { mutableListOf() }.addAll(replies)
    }

    /** Sugar over [scriptReply] for a reply that will not match any `expect` pattern. */
    public fun scriptGarbage(command: String, raw: String = "@@GARBAGE@@") {
        scriptReply(command, raw)
    }

    /** The next [open] call throws instead of succeeding. */
    public fun failToOpen() {
        openShouldFail = true
    }

    /** The next [readLine] call suspends forever, ignoring its own [timeoutMs][RigTransport.readLine]. */
    public fun hangOnNextRead() {
        hangNextRead = true
    }

    /** Delivers [line] with no corresponding outbound command — an unsolicited push. */
    public fun pushUnsolicited(line: String) {
        incoming.trySend(line)
    }

    /** The link goes [TransportState.Lost] while still nominally "open" — a real disconnection. */
    public fun dropMidStream(reason: String = "connection dropped") {
        isOpen = false
        stateFlow.value = TransportState.Lost(reason)
    }

    override fun open() {
        stateFlow.value = TransportState.Connecting
        if (openShouldFail) {
            stateFlow.value = TransportState.Lost("failed to open")
            throw RigTransportException("failed to open")
        }
        isOpen = true
        stateFlow.value = TransportState.Open
    }

    override fun close() {
        isOpen = false
        stateFlow.value = TransportState.Closed
    }

    override fun write(line: String) {
        check(isOpen) { "transport is not open" }
        sent += line
        scripted[line]?.forEach { incoming.trySend(it) }
    }

    override suspend fun readLine(timeoutMs: Long): String? {
        if (hangNextRead) {
            hangNextRead = false
            awaitCancellation()
        }
        if (!isOpen) return null
        return withTimeoutOrNull(timeoutMs) { incoming.receive() }
    }
}
