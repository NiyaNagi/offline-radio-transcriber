package org.ort.pipeline.reprocess

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.ort.core.AssetRef
import org.ort.core.Clock
import org.ort.core.PassId
import org.ort.core.SystemClock
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.PassRunOutcome
import org.ort.data.WorkQueue
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.ort.pipeline.CaptureProcessingLoop
import org.ort.pipeline.Pass
import org.ort.pipeline.PassDrainRunner
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.diagnostics.DiagnosticsLog
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.passb.PassBFactory
import org.ort.pipeline.passb.RealAsrEngineProvider
import org.ort.pipeline.passb.UnavailableAsrEngine
import java.io.File

/** One emission per transmission id processed, in order — mirrors `org.ort.app.ui.improve.ImproveRunProgress`'s
 * shape plus [currentId], which that interface's minimal return type does not carry (read it from
 * [ReprocessStatus] instead, or from this richer type directly against [ReprocessRunner]). */
public data class ReprocessProgress(public val done: Int, public val total: Int, public val currentId: String?)

/**
 * The tuning knobs [ReprocessRunner] itself does not need to reason about, folded out of its own
 * constructor (detekt's `LongParameterList`) — every default here is the exact value the
 * constructor used inline before this existed; only the grouping changed.
 */
public data class ReprocessTuning(
    /** How often [ReprocessRunner] re-checks capture priority while yielding. */
    public val yieldPollIntervalMillis: Long = 500L,
    /** Per-lease deadline handed to [PassDrainRunner.drainBatch] (FR-RUN-10a). */
    public val deadlineMillis: Long = CaptureProcessingLoop.DEFAULT_DEADLINE_MILLIS,
    /** Batch size handed to [PassDrainRunner.drainBatch]. */
    public val drainBatchLimit: Int = CaptureProcessingLoop.DEFAULT_BATCH_SIZE,
    /** Defensive bound on how many drain calls one transmission's pass waits through before this
     * class gives up on it as failed rather than looping forever. */
    public val maxDrainIterationsPerItem: Int = 1_000,
)

/**
 * register R-091 (FR-REP-1, FR-REP-5, FR-REP-8, FR-REP-9, FR-REP-11, P12): the reprocessing engine
 * `:pipeline` never had. `org.ort.app.ui.improve.ImproveRunner`'s own kdoc found "no reprocess/
 * Pass B/C scheduling mechanism exists anywhere in `:pipeline`" and shipped [org.ort.app.ui.improve.FakeImproveRunner]
 * as the honest stand-in until this existed — this is that mechanism, and `RealImproveRunner`
 * (this package's `app`-side sibling file) is the adapter satisfying that interface over it.
 *
 * **No `RealCaptureService` refactor was needed.** Every collaborator a real Pass B run requires —
 * [RealAsrEngineProvider], [PassBFactory], [WorkQueue], [PassDrainRunner] — was already an
 * independently constructible class outside that service; [RealCaptureService.startProcessingLoop]
 * merely composes them the same way [realPassBFor] does here. The one genuinely new capability
 * needed was `PassBFactory.create`'s existing `tier` parameter, already present and unused by the
 * live path (which always runs at `Tier.T0`, the device's live capture tier) — reprocessing is the
 * first caller to pass a *different* one.
 *
 * **Idempotent by construction (FR-RUN-8), never by convention**: [run] drives transmissions
 * through the exact same [WorkQueue]/[PassDrainRunner] machinery live capture uses, which already
 * treats `COMPLETE`/`REJECTED` → `PROCESSING` as a legal transition reserved for exactly this
 * ("reprocess requested / a higher tier is available", `org.ort.core.TransmissionLifecycle`'s own
 * comment) and whose `completePass`/`failPass` paths are the same ones a live run commits through.
 * Re-running this class twice over the same ids is safe: a transmission with an already-active
 * queue row for the requested pass is never double-enqueued (the partial unique index over active
 * states would reject it; [enqueueOrReuseActive] checks first and waits on the existing row
 * instead), and Pass B's own `DataPassBResultSink` only ever *supersedes* — old transcript/lattice/
 * candidate rows stay reachable (constitution III), never deleted.
 *
 * **Never worse than before a run started (FR-REP-11)**: a transmission a user already marked
 * [org.ort.data.entity.TransmissionEntity.corrected] is still reprocessed — a better transcript is
 * still worth having — but never has its attribution overwritten: `TransmissionDao.updateAttribution`
 * already conditions its write on `AND corrected = 0` (build-plan P16, FR-SPK-7), a structural
 * `:data`-layer guard this class relies on rather than re-implementing (a second, independent skip
 * check here could silently drift from that one and either double-protect or, worse, under-protect
 * it). A pass that errors or times out leaves the transmission's *existing* current transcript/
 * attribution untouched — nothing here writes on [org.ort.data.PassRunOutcome.Errored]; that
 * guarantee comes from [org.ort.pipeline.passb.DataPassBResultSink] itself, unchanged.
 *
 * **Capture-priority-safe**: [isCaptureBusy] (default: [CaptureState.isCapturing] *and* the shed
 * level is at or past [BUSY_SHED_LEVEL_THRESHOLD] — the same level technical design §7.3 already
 * downgrades the live Pass B model at) is checked before every item, not just once at the start,
 * so a long run started while the device is idle still backs off the moment capture needs the one
 * inference slot more. While yielding, [ReprocessStatus.Paused] is published and this class does
 * nothing but poll — it never contends with a live pass for CPU/the inference slot meanwhile.
 *
 * **Interruptible and resumable (FR-REP-11), with no extra state of its own**: cancelling the
 * coroutine collecting [run]'s `Flow` (navigating away, `ImproveRunner`'s own documented "Cancel"
 * mechanism) stops this class immediately — cooperative cancellation at every `delay`/suspend DB
 * call, nothing to catch. Whatever was already enqueued but not yet drained simply sits in the
 * durable `work_queue_item` table, exactly where a live capture session's own restart, or a later
 * reprocess run, picks it up — resumability is a property of the durable queue this class reuses,
 * not a mechanism it had to build.
 *
 * **A missing/deleted retained-audio file must never take the process down (register R-290)**:
 * `FlacSegmentAudioProvider.forItem` throws a bare `IllegalStateException` when the file a
 * transmission's `audioPath()` names is gone (retention deletion, or a synthetic fixture that
 * never wrote one) — a real condition on a real device, not a test-only edge case. [run] wraps
 * whatever [passFor] returns in [SafePass] specifically so that exception (or any other a [Pass]
 * throws) is caught and converted to [org.ort.data.PassRunOutcome.Errored] *before* it can escape
 * [org.ort.data.WorkQueue.runLeased]'s `withTimeoutOrNull` — which does not catch ordinary
 * exceptions, only its own timeout — and reach the collecting coroutine (the app's main thread,
 * via `RealImproveRunner`) uncaught. Once converted, [WorkQueue.failPass]'s existing, already-
 * tested bounded-retry path takes over exactly as it does for any other Pass B error (FR-RUN-9):
 * the transmission reaches terminal `FAILED` with the real message recorded as
 * [org.ort.data.entity.WorkQueueItemEntity.lastError] — the same shape the `pass-failed` scenario
 * (register R-153) already renders — and this run keeps going with the next id.
 * [ReprocessStatus.Summary.failureReasons] carries that message forward so `Improve-Done` can say
 * *why*, not just *how many*, without a caller needing a separate `:data` query.
 * [CancellationException] is deliberately never caught by [SafePass] — swallowing it would break
 * `run`'s own documented "a collector that stops collecting stops the run" contract.
 *
 * **What this does not do yet**: only [PassId.B_OFFLINE] is supported — [PassId.C_SPOT] exists as
 * an id (technical design §9.7, M4) but no runner for it exists anywhere in `:pipeline` (grepped
 * the tree before writing this); requesting it throws rather than silently no-opping. "Current
 * tier" is threaded into [org.ort.core.PassFingerprint.tier] honestly, but nothing in `:data` today
 * persists a queryable per-transmission tier (FR-REP-2 asks for one; no column exists yet) — see
 * this package's report for why `app/.../improve/ImprovePolling`'s own `SessionEntity.deviceTier`
 * grouping is consequently not cleared by a successful reprocess, a gap this class cannot close
 * without a `:data` change outside its granted ownership.
 */
public class ReprocessRunner(
    private val db: OrtDatabase,
    private val filesDir: File,
    private val clock: Clock = SystemClock,
    private val runId: String = Ulid.generate().value,
    /** Overridable so a test can pin a tier without depending on a real [ShedStatus] reading. */
    private val currentTier: () -> Tier = { currentTierFromShedLevel() },
    /** Overridable so a test can inject a fake [Pass] instead of a real ASR engine + audio file. */
    private val passFor: (Tier) -> Pass = { tier -> realPassBFor(db, filesDir, tier) },
    private val isCaptureBusy: () -> Boolean = {
        CaptureState.isCapturing && ShedStatus.currentLevel >= BUSY_SHED_LEVEL_THRESHOLD
    },
    private val tuning: ReprocessTuning = ReprocessTuning(),
) {

    /**
     * Re-runs [passes] (default: Pass B alone) for every id in [transmissionIds], at the current
     * tier, one emission per id in order — matching `ImproveRunner.run`'s own contract exactly
     * ("the last emission has `done == total`", "a collector that stops collecting stops the
     * run"), so `RealImproveRunner` can map this directly. [ReprocessStatus] is republished on
     * every state change as a side effect, for a status surface that is not itself the collector.
     */
    public fun run(
        transmissionIds: List<String>,
        passes: Set<PassId> = setOf(PassId.B_OFFLINE),
    ): Flow<ReprocessProgress> = flow {
        require(passes.isNotEmpty()) { "at least one pass must be requested" }
        val unsupported = passes - SUPPORTED_PASSES
        require(unsupported.isEmpty()) {
            "unsupported pass(es) $unsupported -- only $SUPPORTED_PASSES have a runner in :pipeline today"
        }

        val total = transmissionIds.size
        if (total == 0) {
            ReprocessStatus.done(ReprocessStatus.Summary(total = 0))
            emit(ReprocessProgress(0, 0, null))
            return@flow
        }

        val tier = currentTier()
        // R-290: never the raw Pass returned by passFor() -- see the class kdoc.
        val pass = SafePass(passFor(tier))
        val queue = WorkQueue(db, clock)
        val drainRunner = PassDrainRunner(queue, runId)

        var done = 0
        var transcriptsChanged = 0
        var attributionsChanged = 0
        var rejected = 0
        var failed = 0
        var correctedCount = 0
        val failureReasons = LinkedHashSet<String>()

        ReprocessStatus.running(done, total, null)

        for (id in transmissionIds) {
            awaitCaptureNotBusy(done, total)
            ReprocessStatus.running(done, total, id)

            val transmission = db.transmissionDao().getById(id)
            if (transmission == null) {
                done++
                emit(ReprocessProgress(done, total, id))
                continue
            }
            if (transmission.corrected) correctedCount++

            val beforeTranscript = db.transcriptDao().getCurrent(id)?.text
            val beforeState = transmission.attributionState
            val beforeStation = transmission.stationId

            val outcome = runOnePass(queue, drainRunner, pass, id, passes.first(), done, total)

            when (outcome) {
                is PassAttemptOutcome.Failed -> {
                    failed++
                    outcome.reason?.let { failureReasons.add(it) }
                }
                PassAttemptOutcome.Completed, PassAttemptOutcome.Rejected -> {
                    db.transmissionDao().setReprocessCandidate(id, false)
                    if (outcome == PassAttemptOutcome.Rejected) {
                        rejected++
                    } else {
                        val after = db.transmissionDao().getById(id)
                        val afterTranscript = db.transcriptDao().getCurrent(id)?.text
                        if (afterTranscript != beforeTranscript) transcriptsChanged++
                        // Never true for a corrected transmission -- TransmissionDao
                        // .updateAttribution's own "AND corrected = 0" guard means `after`
                        // is unchanged from `before` here, structurally (see class kdoc).
                        if (after != null &&
                            (after.attributionState != beforeState || after.stationId != beforeStation)
                        ) {
                            attributionsChanged++
                        }
                    }
                }
            }

            done++
            ReprocessStatus.running(done, total, id)
            emit(ReprocessProgress(done, total, id))
        }

        val summary = ReprocessStatus.Summary(
            total = total,
            transcriptsChanged = transcriptsChanged,
            attributionsChanged = attributionsChanged,
            rejected = rejected,
            failed = failed,
            correctedCount = correctedCount,
            failureReasons = failureReasons.toList(),
        )
        ReprocessStatus.done(summary)
    }

    /** Capture-priority yield (see the class kdoc) -- polls, publishing [ReprocessStatus.Paused], until clear. */
    private suspend fun awaitCaptureNotBusy(done: Int, total: Int) {
        while (isCaptureBusy()) {
            ReprocessStatus.paused(done, total)
            delay(tuning.yieldPollIntervalMillis)
        }
    }

    /**
     * Reuses an already-active queue row for `(transmissionId, passId)` if one exists (another
     * reprocess run, or — vanishingly unlikely for an already-`COMPLETE`/`REJECTED` candidate, but
     * not impossible — a live one) instead of enqueueing a second one, which the partial unique
     * index over active states would otherwise reject outright.
     */
    private suspend fun enqueueOrReuseActive(queue: WorkQueue, transmissionId: String, passId: PassId): Long {
        val active = db.workQueueDao().findByTransmissionAndPass(transmissionId, passId.name)
            .firstOrNull { it.state != WorkQueueState.FAILED }
        return active?.id ?: queue.enqueue(transmissionId, passId, priority = REPROCESS_QUEUE_PRIORITY)
    }

    /**
     * Enqueues (or reuses) [transmissionId]'s [passId] item and drains the queue, yielding for
     * capture priority between every drain call, until that specific row either completes (row
     * deleted — [org.ort.data.WorkQueue.completePass]) or terminally fails (row left `FAILED` —
     * [org.ort.data.WorkQueue.failPass] at the attempt bound). A drain call may lease and run a
     * *different* ready item first (live capture's own backlog, or another transmission's earlier
     * in this same run's queue) — that is correct, not wasted work: the queue is shared and durable
     * by design (technical design §7.1), and this call converges regardless of lease order.
     */
    private suspend fun runOnePass(
        queue: WorkQueue,
        drainRunner: PassDrainRunner,
        pass: Pass,
        transmissionId: String,
        passId: PassId,
        done: Int,
        total: Int,
    ): PassAttemptOutcome {
        val rowId = enqueueOrReuseActive(queue, transmissionId, passId)
        var iterations = 0
        while (iterations < tuning.maxDrainIterationsPerItem) {
            val row = db.workQueueDao().getById(rowId)
            if (row == null) {
                val state = db.transmissionDao().getById(transmissionId)?.processingState
                return if (state == TransmissionState.REJECTED) {
                    PassAttemptOutcome.Rejected
                } else {
                    PassAttemptOutcome.Completed
                }
            }
            // R-290: lastError is the real message SafePass converted the pass's exception (or an
            // ordinary PassRunOutcome.Errored) into -- WorkQueue.failPass's own field, read back
            // rather than duplicated, so this is never out of step with what a later query of the
            // same row would show.
            if (row.state == WorkQueueState.FAILED) return PassAttemptOutcome.Failed(row.lastError)

            awaitCaptureNotBusy(done, total)
            drainRunner.drainBatch(limit = tuning.drainBatchLimit, deadlineMillis = tuning.deadlineMillis, pass = pass)
            iterations++
        }
        // Defensive only -- a queue row that never resolves after this many drain calls would be a
        // bug elsewhere (e.g. a starved lease); reported as a failure rather than hanging forever.
        return PassAttemptOutcome.Failed("gave up after ${tuning.maxDrainIterationsPerItem} drain attempts")
    }

    private sealed interface PassAttemptOutcome {
        data object Completed : PassAttemptOutcome
        data object Rejected : PassAttemptOutcome
        data class Failed(val reason: String?) : PassAttemptOutcome
    }

    public companion object {
        /** Only pass runner this class can actually invoke today -- see the class kdoc. */
        public val SUPPORTED_PASSES: Set<PassId> = setOf(PassId.B_OFFLINE)

        /** technical design §7.3: the shed level that already downgrades the live Pass B model. */
        public const val BUSY_SHED_LEVEL_THRESHOLD: Int = 3

        /** Below live capture's own (default 0) `WorkQueue.enqueue` priority -- "live traffic > reprocessing". */
        public const val REPROCESS_QUEUE_PRIORITY: Int = -1

        private const val MAX_TIER_ORDINAL: Int = 3

        /** The same `(MAX_TIER - ShedStatus.currentLevel)` formula `RealCaptureService.tierFromShedLevel()`
         * and `ImprovePolling.currentTierOrdinal()` each already compute independently -- :pipeline
         * cannot import the `app`-side one (module boundary) and duplicates it exactly, as
         * `RealCaptureService` itself already does for the same reason. */
        public fun currentTierFromShedLevel(): Tier {
            val ordinal = (MAX_TIER_ORDINAL - ShedStatus.currentLevel).coerceIn(0, MAX_TIER_ORDINAL)
            return Tier.entries[ordinal]
        }
    }
}

/**
 * register R-290: wraps [delegate] so a leased pass's own exception —
 * `org.ort.pipeline.passb.FlacSegmentAudioProvider`'s `IllegalStateException` when a transmission's
 * retained audio is missing being the reproduced case, but this is deliberately general, not a
 * special case for that one class — is converted to
 * [PassRunOutcome.Errored] instead of escaping [org.ort.data.WorkQueue.runLeased]'s
 * `withTimeoutOrNull` uncaught. [CancellationException] is re-thrown, never swallowed: catching it
 * here would silently defeat `run`'s own cooperative-cancellation contract (a collector that stops
 * collecting must actually stop the run, not have this class convert the resulting cancellation
 * into a fake "failed" item and carry on).
 *
 * **`internal`, not `private` (R-290, live-capture round):** the exact same gap exists in the live
 * path — `RealCaptureService.startProcessingLoop` hands a real `Pass` to `CaptureProcessingLoop`/
 * `PassDrainRunner` through the identical `WorkQueue.runLeased` machinery this class already
 * protects, and `RealCaptureService`'s own `scope` is a plain `CoroutineScope(Dispatchers.IO +
 * Job())` — **not** a `SupervisorJob`, confirmed by reading it — so an uncaught exception in the
 * processing-loop coroutine cancels the whole `Job`, including the sibling coroutine actually
 * recording audio. A missing retained-audio file must never take live capture down any more than
 * it may take a reprocess run down; `RealCaptureService.startProcessingLoop` wraps its own real
 * pass in this same class rather than duplicating the catch/convert logic.
 */
internal class SafePass(private val delegate: Pass) : Pass {
    override suspend fun run(item: WorkQueueItemEntity): PassRunOutcome = try {
        delegate.run(item)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // FR-OBS-1 "SafePass failures": the exception's own class name only, never e.message --
        // a message string is free text the failing library chose, exactly what DiagnosticsLog's
        // structural no-free-text guarantee exists to keep out of pipeline.log.
        DiagnosticsLog.logSafePassFailure(e::class.simpleName ?: "unknown")
        PassRunOutcome.Errored(e.message ?: e::class.simpleName ?: "unknown reprocess error")
    }
}

/**
 * The real Pass B composition (mirrors `RealCaptureService.startProcessingLoop`'s own, for
 * reprocessing instead of live capture) — a top-level function, not a [ReprocessRunner] member, so
 * it can be referenced from that class's own constructor-parameter default (`passFor`), which runs
 * before `this` exists and therefore cannot call an instance method.
 */
private fun realPassBFor(db: OrtDatabase, filesDir: File, tier: Tier): Pass {
    val availability = RealAsrEngineProvider(filesDir).provide()
    val (engine, modelRef, provider) = when (availability) {
        is AsrEngineAvailability.Available ->
            Triple(availability.engine, availability.modelRef, availability.provider)
        is AsrEngineAvailability.Unavailable ->
            // F-013: the fingerprint's provider must say so honestly ("none"), never repeat the
            // real engine's "cpu" -- the same discipline RealCaptureService's own
            // startProcessingLoop already applies for the live path.
            Triple(UnavailableAsrEngine(availability.reason), AssetRef("asr-unavailable", "0"), "none")
    }
    return PassBFactory.create(filesDir, db, engine, modelRef, provider, tier = tier)
}
