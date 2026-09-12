package org.ort.app.fieldreport.recorder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.ort.app.BuildConfig
import org.ort.core.Clock
import org.ort.core.SystemClock
import java.io.File
import java.time.Instant

/**
 * FR-OBS-6: the debug-build session recorder. A bounded ring buffer of [RecorderEvent] — the
 * closed vocabulary that file's own doc comment specifies — kept entirely off the audio frame
 * path, absent from release builds, and rendered to the one file FR-OBS-8 names as part of the
 * field-report bundle's ungated set.
 *
 * **Never on the audio frame path (FR-OBS-6, FR-RUN-1).** [record] only builds a [RecorderEvent]
 * (already built by the caller — this function does no work of its own beyond that) and offers it
 * to an unbounded [Channel] via the non-suspending, non-blocking [Channel.trySend]; the one
 * consumer coroutine [configure] launches on [Dispatchers.IO] does every bit of buffer maintenance
 * and file I/O off whatever thread called [record] — the identical shape
 * `pipeline/src/main/kotlin/org/ort/pipeline/diagnostics/DiagnosticsLog.kt` already uses and for
 * the same reason (that file's own doc comment: several of *its* call sites are on the audio frame
 * path, so blocking there would itself be the "capture must never block" violation FR-RUN-1
 * forbids). No destination-change call site this recorder is wired from is on that path either,
 * but the discipline is the same discipline regardless of which call site happens to use it today.
 *
 * **Absent from release builds (FR-OBS-6).** [isDebugBuild] gates both [configure] and [record] —
 * a release build that somehow still called either does no work at all, the same seam
 * `org.ort.app.ui.failures.DebugFailureOverride` already uses and for the same reason: Robolectric
 * only ever compiles this module's **debug** variant, so `BuildConfig.DEBUG` is `true` in every
 * unit test regardless of what this object does — proving "ignored when not debug" needs a seam a
 * test can flip.
 *
 * **Bounded ring buffer (FR-OBS-6, tested by exceeding it, not by asserting the constant —
 * `FieldReportRecorderTest`'s `FR_OBS_6_...` bound test).** [events] never returns more than
 * [maxEvents] entries; once that many are held, recording one more drops the oldest, never the
 * newest, and never silently grows past the bound.
 */
public object FieldReportRecorder {

    /** FR-OBS-6's ring-buffer capacity. Debug-only diagnostics for a single field session — this
     * is generous headroom over what a session's worth of navigation, permission and capture-state
     * transitions produces, not a number tuned to a measured ceiling. */
    public const val DEFAULT_MAX_EVENTS: Int = 500

    private const val LOG_DIR_NAME = "field-report"

    /** FR-OBS-8: the exact file name the bundle's ungated set includes alongside
     * `DiagnosticsBundleSpec`'s seven scrubbed files — this package produces it, it does not
     * package it (see this package's own report for the contract). */
    public const val LOG_FILE_NAME: String = "session-recorder.log"

    /** Test seam (mirrors [org.ort.app.ui.failures.DebugFailureOverride.isDebugBuild] exactly, same
     * reason): production code never assigns this. */
    internal var isDebugBuild: () -> Boolean = { BuildConfig.DEBUG }

    private sealed interface Message {
        data class Record(val event: RecorderEvent) : Message
        data class Barrier(val ack: CompletableDeferred<Unit>) : Message
    }

    @Volatile private var logDir: File? = null

    @Volatile private var clock: Clock = SystemClock

    @Volatile private var maxEvents: Int = DEFAULT_MAX_EVENTS

    @Volatile private var frameCapturer: ScreenFrameCapturer? = null

    @Volatile private var frameStore: FrameStore? = null

    private var channel: Channel<Message>? = null
    private var consumerJob: Job? = null
    private val consumerScope = CoroutineScope(Dispatchers.IO)
    private val captureScope = CoroutineScope(Dispatchers.IO)

    private val buffer = ArrayDeque<RecorderEvent>()

    /**
     * Configures (or reconfigures) where this session's recorder log and frames live, and starts
     * the single consumer coroutine. A no-op in a release build ([isDebugBuild] false) — no
     * directory is created, no consumer starts, [record] will do nothing either.
     *
     * [frameCapturer], when non-null, is what [onDestinationChanged] uses to satisfy FR-OBS-7 —
     * left `null` by every call this change makes (no in-scope call site constructs a real
     * `Window`-backed capturer yet; see this package's report). Idempotent-safe to call again: the
     * previous consumer is cancelled first, matching `DiagnosticsLog.configure`'s own contract.
     */
    public fun configure(
        filesDir: File,
        clock: Clock = SystemClock,
        maxEvents: Int = DEFAULT_MAX_EVENTS,
        frameCapturer: ScreenFrameCapturer? = null,
        maxFrames: Int = FrameStore.DEFAULT_MAX_FRAMES,
        maxFrameBytes: Long = FrameStore.DEFAULT_MAX_TOTAL_BYTES,
    ) {
        consumerJob?.cancel()
        if (!isDebugBuild()) {
            logDir = null
            channel = null
            frameStore = null
            this.frameCapturer = null
            return
        }
        val dir = File(filesDir, LOG_DIR_NAME)
        dir.mkdirs()
        this.logDir = dir
        this.clock = clock
        this.maxEvents = maxEvents
        this.frameCapturer = frameCapturer
        this.frameStore = FrameStore(File(dir, "frames"), maxFrames = maxFrames, maxTotalBytes = maxFrameBytes)
        synchronized(buffer) { buffer.clear() }
        val ch = Channel<Message>(Channel.UNLIMITED)
        this.channel = ch
        consumerJob = consumerScope.launch { drain(ch) }
    }

    /** Test/shutdown-only: stops the consumer and forgets the configured directory. */
    public fun shutdown() {
        consumerJob?.cancel()
        consumerJob = null
        channel = null
        logDir = null
        frameStore = null
        frameCapturer = null
        synchronized(buffer) { buffer.clear() }
    }

    /** Test-only: blocks until every event recorded before this call has actually been applied to
     * the buffer and the log file. */
    public suspend fun flush() {
        val ack = CompletableDeferred<Unit>()
        val sent = channel?.trySend(Message.Barrier(ack))
        if (sent == null || sent.isFailure) {
            ack.complete(Unit)
        }
        ack.await()
    }

    /**
     * FR-OBS-6's one recording entry point. Takes only [RecorderEvent] — see that type's own doc
     * comment for the structural guarantee this makes possible. A no-op in a release build or
     * before [configure] has run.
     */
    public fun record(event: RecorderEvent) {
        if (!isDebugBuild()) return
        channel?.trySend(Message.Record(event))
    }

    /**
     * The convenience form [OrtNavHost]'s single hook calls: records a
     * [RecorderEvent.DestinationChanged] and, when a [ScreenFrameCapturer] was configured,
     * fire-and-forgets an FR-OBS-7 frame capture on [captureScope] — never on the caller's own
     * coroutine, so a slow or hung capturer (see [FakeScreenFrameCapturer]) cannot delay
     * navigation. A capture failure (`null` bytes) is recorded nowhere else; [FrameStore] simply
     * gains one fewer frame than it might have.
     */
    public fun onDestinationChanged(destination: RecorderDestination) {
        record(RecorderEvent.DestinationChanged(destination))
        if (!isDebugBuild()) return
        val capturer = frameCapturer ?: return
        val store = frameStore ?: return
        lastFrameCaptureJob = captureScope.launch {
            val bytes = capturer.capture() ?: return@launch
            store.store(bytes)
        }
    }

    @Volatile private var lastFrameCaptureJob: Job? = null

    /** Test-only: blocks until the most recently requested FR-OBS-7 frame capture (if any) has
     * finished. [onDestinationChanged]'s capture runs on [captureScope], a separate scope from the
     * channel [flush] drains, so [flush] alone does not wait for it. */
    public suspend fun awaitFrameCapture() {
        lastFrameCaptureJob?.join()
    }

    /** Test/inspection-only: a snapshot of the current ring buffer, oldest first. Never more than
     * [maxEvents] entries — see [drain]'s own doc comment for the eviction this enforces. */
    public fun events(): List<RecorderEvent> = synchronized(buffer) { buffer.toList() }

    private suspend fun drain(ch: Channel<Message>) {
        for (message in ch) {
            when (message) {
                is Message.Barrier -> message.ack.complete(Unit)
                is Message.Record -> applyBounded(message.event)
            }
        }
    }

    /** The ring buffer's own bound (FR-OBS-6): drops the oldest entry before adding a new one once
     * [maxEvents] is already held, so the buffer never grows past it and the newest event always
     * survives. Also appends the rendered line to the log file — see [render]. */
    private fun applyBounded(event: RecorderEvent) {
        synchronized(buffer) {
            while (buffer.size >= maxEvents) buffer.removeFirst()
            buffer.addLast(event)
        }
        writeLine(event)
    }

    private fun writeLine(event: RecorderEvent) {
        val dir = logDir ?: return
        val file = File(dir, LOG_FILE_NAME)
        val timestamp = Instant.ofEpochMilli(clock.wallMillis()).toString()
        file.appendText("$timestamp ${render(event)}\n")
    }

    /** Renders one [RecorderEvent] as a single well-formed line — every token comes from a closed
     * type (an enum's `.name`, a `Boolean`/`Int`/`Long`, a `List` of one of those), so this
     * function has the same "no free text in the output" property [RecorderEvent] has by
     * construction. */
    private fun render(event: RecorderEvent): String = when (event) {
        is RecorderEvent.DestinationChanged -> "destination_changed destination=${event.destination.name}"
        is RecorderEvent.ControlTapped -> "control_tapped control=${event.control.name}"
        is RecorderEvent.PermissionResultRecorded ->
            "permission_result permission=${event.permission.name} result=${event.result.name}"
        is RecorderEvent.CaptureStateChanged -> "capture_state_changed kind=${event.kind.name}"
        is RecorderEvent.SetupStepChanged -> "setup_step_changed step=${event.step.name}"
        is RecorderEvent.AudioDevicesEnumerated ->
            "audio_devices_enumerated count=${event.devices.size} " +
                event.devices.joinToString(" ") { device ->
                    "device[id=${device.id},type=${device.type},productName=${device.productName}," +
                        "address=${device.address},channelCounts=${device.channelCounts}," +
                        "sampleRates=${device.sampleRates},isSource=${device.isSource}]"
                }
    }
}
