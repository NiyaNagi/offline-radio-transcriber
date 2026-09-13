package org.ort.rig.fakes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ort.rig.RigTransport
import org.ort.rig.RigTransportException
import org.ort.rig.TransportState
import kotlin.random.Random

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
 * - [replyLatencyMillis] / [replyJitterMillis] — R-1062 round 3: a scripted reply is delivered
 *   this long after [write], not synchronously — modelling the real round-trip a serial or
 *   Bluetooth SPP link actually has, which `RigSupervisor`'s squelch staleness watchdog must
 *   survive without misreading a single poll cycle's ordinary latency as a lost link (see that
 *   class's own `squelchStalenessBoundMillis` kdoc for the timing analysis this fake exists to
 *   drive). Both default to `0`, so every pre-existing caller keeps its exact synchronous
 *   behaviour unchanged.
 *
 * All incoming lines — scripted replies and pushes alike — share one FIFO, exactly like a real
 * serial port: nothing on the wire distinguishes "the reply I asked for" from "the radio talking
 * on its own". That is precisely the design pressure `DescriptorRigModule` is built to survive.
 *
 * [scope] is where a delayed reply's own `delay()` runs (see [replyLatencyMillis]) — pass a test's
 * own `TestScope`/`UnconfinedTestDispatcher` so that delay resolves against virtual time exactly
 * like every other coroutine in the test, rather than a real wall-clock second. Defaults to a real
 * background dispatcher, which is never reached by a caller that leaves [replyLatencyMillis] and
 * [replyJitterMillis] at their default `0` (the synchronous path never launches a coroutine at all).
 */
public class FakeRigTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val random: Random = Random.Default,
) : RigTransport {

    private val incoming = Channel<String>(capacity = Channel.UNLIMITED)
    private val scripted = mutableMapOf<String, MutableList<String>>()
    private val sent = mutableListOf<String>()
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Closed)

    private var openShouldFail = false
    private var hangNextRead = false
    private var isOpen = false

    /**
     * R-1062 round 3: fixed delay, in milliseconds, before a scripted reply is delivered after
     * the [write] that triggers it — `0` (the default) delivers synchronously, exactly as before
     * this field existed. Models the base round-trip time a real CAT link has.
     */
    public var replyLatencyMillis: Long = 0

    /**
     * R-1062 round 3: an additional `0..replyJitterMillis` chosen uniformly per reply (via
     * [random]), on top of [replyLatencyMillis] — models the occasional slow reply a real link
     * (Bluetooth SPP especially) produces. `0` (the default) adds no jitter.
     */
    public var replyJitterMillis: Long = 0

    /** Every line handed to [write], in order — so a test can assert what a module actually sent. */
    public val commandsSent: List<String> get() = sent.toList()

    override val state: Flow<TransportState> = stateFlow.asStateFlow()

    /** Queues [replies] to be delivered, in order, the moment [command] is [write]-ed — after
     * [replyLatencyMillis]/[replyJitterMillis] if either is set, synchronously otherwise. */
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
        val replies = scripted[line] ?: return
        if (replyLatencyMillis <= 0L && replyJitterMillis <= 0L) {
            replies.forEach { incoming.trySend(it) }
            return
        }
        // R-1062 round 3: each reply gets its own independently-jittered delay, launched on
        // [scope] -- write() itself stays a plain, non-suspending function (the RigTransport
        // contract), exactly matching how a real transport's write returns immediately while the
        // radio's own reply arrives later, asynchronously, on the read side.
        replies.forEach { reply ->
            val jitter = if (replyJitterMillis > 0) random.nextLong(replyJitterMillis + 1) else 0L
            val delayMillis = replyLatencyMillis + jitter
            scope.launch {
                delay(delayMillis)
                incoming.trySend(reply)
            }
        }
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
