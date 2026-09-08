package org.ort.pipeline.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.ort.asrapi.rules.RejectionRuleId
import org.ort.capture.android.AudioDeviceKind
import org.ort.core.Clock
import org.ort.core.PassId
import org.ort.core.SystemClock
import org.ort.core.Tier
import org.ort.data.entity.TerminationReason
import java.io.File
import java.time.Instant

/**
 * FR-OBS-1 (register R-133 follow-up): the diagnostics-log writer WP11e's `DiagnosticsBundleBuilder`
 * (`:app`) has been reading an honest placeholder from ever since it landed — before this existed,
 * no writer anywhere in the tree ever put a real line at `lifecycle.log`/`capture.log`/
 * `pipeline.log`/`rig.log`.
 *
 * **The path contract, and why this file does not import `DiagnosticsLogPaths`.** WP11e's
 * `org.ort.app.diagnostics.DiagnosticsLogPaths.logFile(context, id)` resolves
 * `context.filesDir/diagnostics-logs/<fileName>` — but that object lives in `:app`, and the
 * dependency graph is `:app -> :pipeline`, never the reverse (`dependencyRules` enforces this
 * structurally); `:pipeline` cannot import an `:app` class without breaking the module boundary.
 * [configure] therefore takes a plain `filesDir: File` (the same shape every other real path in
 * this package already uses — [org.ort.pipeline.capture.StorageAccounting], `RealCaptureService`'s
 * own `audio`/`models` directories) and independently derives `filesDir/diagnostics-logs/<fileName>`
 * — the identical relative path, by convention rather than by a shared type, exactly the same
 * resolution [DiagnosticsLogPaths.logDir]/[DiagnosticsLogPaths.logFile] compute. If that convention
 * ever drifts the two files diverge silently; this is flagged in this package's report as the one
 * thing to watch, not fixed by adding the forbidden dependency edge.
 *
 * **Structural privacy (AC-109): the public API has no free-text parameter.** Every logging
 * function below takes only closed types — `Int`/`Long`/`Double`/`Boolean`, a project enum
 * ([PassId], [Tier], [TerminationReason], [RejectionRuleId], [AudioDeviceKind]), or a ULID
 * session/transmission id (an opaque identifier, never itself a callsign, transcript, name, note,
 * location or voiceprint). There is no `message: String`/`detail: String`/`note: String` parameter
 * anywhere in this file, on purpose — a caller physically cannot pass free text through this API,
 * so a callsign or transcript fragment cannot reach a log line no matter what the caller has in
 * scope. `AC_109_no_private_field_ever_logged` proves this empirically: a seeded callsign is placed
 * in the same state real call sites read from, the real hooks are driven, and the written files are
 * scanned for it.
 *
 * **A single [Dispatchers.IO] channel consumer, no new thread per event.** Every `log*` call below
 * is synchronous and non-blocking from the caller's point of view — it only builds a line and
 * offers it to an unbounded [Channel]; the one consumer coroutine launched by [configure] does all
 * file I/O (including rotation) off whatever thread called `log*`. This matters because several
 * real call sites are on the audio frame path (`AudioRecordSource`'s read loop, by way of
 * `RealCaptureService.publishLevelStatus`) where blocking on disk I/O would itself be exactly the
 * "capture must never block" violation this file must not introduce while fixing FR-OBS-1.
 *
 * **Rotation**: [ROTATE_AT_BYTES] (2 MiB) per category file, two generations — the active file plus
 * one `.1` backup, matching the coordinator's brief exactly ("2 MB × 2 generations").
 */
public object DiagnosticsLog {

    public const val ROTATE_AT_BYTES: Long = 2L * 1024 * 1024

    public enum class Category(public val fileName: String) {
        LIFECYCLE("lifecycle.log"),
        CAPTURE("capture.log"),
        PIPELINE("pipeline.log"),
        RIG("rig.log"),
    }

    public enum class Level { INFO, WARN, ERROR }

    private sealed interface Message {
        data class Write(val category: Category, val level: Level, val event: String, val fields: String) : Message
        data class Barrier(val ack: CompletableDeferred<Unit>) : Message
    }

    @Volatile private var logDir: File? = null

    @Volatile private var clock: Clock = SystemClock
    private var channel: Channel<Message>? = null
    private var consumerJob: Job? = null
    private val consumerScope = CoroutineScope(Dispatchers.IO)

    /**
     * Sets (or resets) where this session's four log files live and starts the single consumer
     * coroutine. Idempotent-safe to call again (a test reconfiguring between cases, a service
     * restarting): the previous consumer is cancelled first so exactly one is ever draining
     * [channel] at a time.
     */
    public fun configure(filesDir: File, clock: Clock = SystemClock) {
        consumerJob?.cancel()
        val dir = File(filesDir, LOG_DIR_NAME)
        dir.mkdirs()
        this.logDir = dir
        this.clock = clock
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
    }

    /** Test-only: blocks until every line enqueued before this call has actually been written. */
    public suspend fun flush() {
        val ack = CompletableDeferred<Unit>()
        val sent = channel?.trySend(Message.Barrier(ack))
        if (sent == null || sent.isFailure) {
            ack.complete(Unit)
        }
        ack.await()
    }

    private suspend fun drain(ch: Channel<Message>) {
        for (message in ch) {
            when (message) {
                is Message.Barrier -> message.ack.complete(Unit)
                is Message.Write -> writeLine(message)
            }
        }
    }

    private fun writeLine(message: Message.Write) {
        val dir = logDir ?: return
        val file = File(dir, message.category.fileName)
        rotateIfNeeded(file)
        val timestamp = Instant.ofEpochMilli(clock.wallMillis()).toString()
        file.appendText("$timestamp ${message.level.name} ${message.event} ${message.fields}\n".trimEnd() + "\n")
    }

    /** Two generations: the active file rolls to `<name>.1` (overwriting any older `.1`) once it
     * reaches [ROTATE_AT_BYTES]; a fresh, empty active file starts immediately after. */
    private fun rotateIfNeeded(file: File) {
        if (!file.isFile || file.length() < ROTATE_AT_BYTES) return
        val backup = File(file.parentFile, "${file.name}.1")
        if (backup.isFile) backup.delete()
        file.renameTo(backup)
    }

    private fun enqueue(category: Category, level: Level, event: String, fields: List<Pair<String, String>>) {
        val rendered = fields.joinToString(" ") { (key, value) -> "$key=$value" }
        channel?.trySend(Message.Write(category, level, event, rendered))
    }

    // --- lifecycle.log (FR-OBS-1: "service lifecycle") -----------------------------------------

    public fun logServiceStarted(sessionId: String) {
        enqueue(Category.LIFECYCLE, Level.INFO, "service_started", listOf("sessionId" to sessionId))
    }

    public fun logServiceStopped(sessionId: String, reason: TerminationReason?, clean: Boolean) {
        enqueue(
            Category.LIFECYCLE,
            Level.INFO,
            "service_stopped",
            listOf("sessionId" to sessionId, "reason" to (reason?.name ?: "NONE"), "clean" to clean.toString()),
        )
    }

    public fun logUncleanRestart(previousSessionId: String, gapMillis: Long) {
        enqueue(
            Category.LIFECYCLE,
            Level.WARN,
            "unclean_restart",
            listOf("previousSessionId" to previousSessionId, "gapMillis" to gapMillis.toString()),
        )
    }

    public fun logHeartbeatGap(sessionId: String, gapMillis: Long) {
        enqueue(
            Category.LIFECYCLE,
            Level.WARN,
            "heartbeat_gap",
            listOf("sessionId" to sessionId, "gapMillis" to gapMillis.toString()),
        )
    }

    // --- capture.log (FR-OBS-1: "audio route changes ... VAD statistics ... overruns") ---------

    public fun logRouteVerified(deviceKind: AudioDeviceKind, sampleRateHz: Int, routedDeviceMatches: Boolean) {
        enqueue(
            Category.CAPTURE,
            Level.INFO,
            "route_verified",
            listOf(
                "deviceKind" to deviceKind.name,
                "sampleRateHz" to sampleRateHz.toString(),
                "matches" to routedDeviceMatches.toString(),
            ),
        )
    }

    public fun logRouteMismatch(expectedKind: AudioDeviceKind, actualKind: AudioDeviceKind?) {
        enqueue(
            Category.CAPTURE,
            Level.ERROR,
            "route_mismatch",
            listOf("expected" to expectedKind.name, "actual" to (actualKind?.name ?: "NONE")),
        )
    }

    public fun logInputLost(sinceMillis: Long) {
        enqueue(Category.CAPTURE, Level.WARN, "input_lost", listOf("sinceMillis" to sinceMillis.toString()))
    }

    public fun logLevelClip(clipCountLastSecond: Int, peakDbfs: Double) {
        enqueue(
            Category.CAPTURE,
            Level.WARN,
            "level_clip",
            listOf("clipCountLastSecond" to clipCountLastSecond.toString(), "peakDbfs" to peakDbfs.toString()),
        )
    }

    public fun logOverrun(droppedMillis: Long) {
        enqueue(Category.CAPTURE, Level.WARN, "overrun", listOf("droppedMillis" to droppedMillis.toString()))
    }

    // --- pipeline.log (FR-OBS-1: "per-pass latency ... rejection reasons ... tier changes") -----

    public fun logPassLatency(pass: PassId, tier: Tier, elapsedMillis: Long) {
        enqueue(
            Category.PIPELINE,
            Level.INFO,
            "pass_latency",
            listOf("pass" to pass.name, "tier" to tier.name, "elapsedMillis" to elapsedMillis.toString()),
        )
    }

    public fun logRejection(rule: RejectionRuleId) {
        enqueue(Category.PIPELINE, Level.INFO, "rejection", listOf("rule" to rule.name))
    }

    public fun logTierChange(fromTier: Tier, toTier: Tier, shedLevel: Int) {
        enqueue(
            Category.PIPELINE,
            Level.INFO,
            "tier_change",
            listOf("from" to fromTier.name, "to" to toTier.name, "shedLevel" to shedLevel.toString()),
        )
    }

    /**
     * [errorClass] must be an exception's own class simple name (`e::class.simpleName`), never
     * [Throwable.message] — a message string is free text an engine or the runtime chose, exactly
     * the kind of value this file's structural guarantee exists to keep out.
     */
    public fun logSafePassFailure(errorClass: String) {
        enqueue(Category.PIPELINE, Level.ERROR, "safe_pass_failure", listOf("errorClass" to errorClass))
    }

    // --- rig.log (FR-OBS-1: "rig connection events"; frequencies are explicitly not private) ----

    public fun logRigConnected(bandCount: Int) {
        enqueue(Category.RIG, Level.INFO, "rig_connected", listOf("bandCount" to bandCount.toString()))
    }

    public fun logRigStale(sinceMillis: Long) {
        enqueue(Category.RIG, Level.WARN, "rig_stale", listOf("sinceMillis" to sinceMillis.toString()))
    }

    public fun logRigAbsent() {
        enqueue(Category.RIG, Level.INFO, "rig_absent", emptyList())
    }

    public fun logRigBand(band: String, frequencyHz: Long?, squelchOpen: Boolean) {
        enqueue(
            Category.RIG,
            Level.INFO,
            "rig_band",
            listOf(
                "band" to band,
                "frequencyHz" to (frequencyHz?.toString() ?: "NONE"),
                "squelchOpen" to squelchOpen.toString(),
            ),
        )
    }

    private const val LOG_DIR_NAME = "diagnostics-logs"
}
