package org.ort.app.debug

import android.content.Context
import org.ort.app.ui.failures.AssetSwapOption
import org.ort.app.ui.failures.AssetSwapViewState
import org.ort.app.ui.failures.CalibrationViewState
import org.ort.app.ui.failures.ClockLogRow
import org.ort.app.ui.failures.ClockViewState
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.failures.FailurePresentation
import org.ort.app.ui.failures.FailureSignalsPolling
import org.ort.app.ui.failures.InterruptedOverRow
import org.ort.app.ui.failures.InterruptedViewState
import org.ort.app.ui.failures.MigrationStep
import org.ort.app.ui.failures.MigrationViewState
import org.ort.app.ui.failures.ReconcileFile
import org.ort.app.ui.failures.ReconcileRecord
import org.ort.app.ui.failures.ReconcileViewState
import org.ort.app.ui.failures.UsbViewState
import org.ort.app.ui.setup.RadioChoice
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.dao.CorrectionDao
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import java.io.File

/**
 * spec/ui-conformance-plan.md WP0, register R-110 — the debug scenario simulator's fixture
 * registry. [load] deletes every row a previous scenario wrote (see [clearPriorScenarioData]),
 * resets the process-wide capture-facet singletons ([resetProcessWideFacets]) and inserts one
 * named scenario's fixtures through `:data`'s real DAOs and entities, exactly as the app's own
 * read path ([org.ort.app.ui.data.ReaderPolling]) reads them back — no fake, no shortcut schema.
 *
 * **Fictional data only** (this package's brief, verbatim): every callsign is drawn from
 * [ScenarioFixtures.CALLSIGNS] (the set the design canvas artboards themselves use), nothing is
 * read from `corpus/` or the `eval` fold, and no voiceprint, real name or precise location is ever
 * written (constitution V — those four categories never leave the device, and a debug scenario is
 * not an exception to that).
 *
 * **Clearing prior scenario data.** None of `:data`'s DAOs expose a delete query (build-plan P5
 * never needed one), so [clearPriorScenarioData] reaches through [OrtDatabase.openHelper] — public
 * Room API, not a new dependency — and runs plain `DELETE` statements scoped to the
 * `scenario-`-prefixed session/transmission ids every scenario here writes
 * ([ScenarioFixtures.SESSION_PREFIX]). `station`/`voiceprint` rows are cleared unconditionally
 * rather than by prefix: [org.ort.data.entity.TransmissionEntity.stationId] *is* the callsign
 * shown on screen (confirmed against `ReaderPollingTest`'s own fixtures — there is no separate
 * numeric station id to prefix without also changing what the artboards' rows would show), and
 * nothing in this codebase writes the `station` table outside this simulator today, so wiping it
 * on every scenario load is the same "start from a known, empty state" guarantee the plan asks
 * for, not a narrower one.
 */
public object Scenarios {

    public data class LoadResult(val transmissionCount: Int, val sessionCount: Int, val primarySessionId: String?)

    /** R-143: how many overs [fieldTier1] seeds — see that function's own doc comment. */
    private const val FIELD_TIER1_OVER_COUNT: Int = 12

    /**
     * Every scenario name `spec/ui-conformance-plan.md` §E and `design/design-intent.md` need
     * reachable. WP11a (register R-104/R-105/R-106) closed the three gaps this list used to note
     * as unreachable from `:app` — `thermal`/`rig-lost`/`os-stopped` are new; `storage-warn` now
     * uses [StorageForecast] instead of reusing F6's exhaustion string. WP11c (register R-112/
     * R-113) adds `level-low`/`level-clip`/`input-verified`/`input-mismatch`. WP11b (register
     * R-100) adds `clock-dst`/`usb-permission`/`interrupted-pass`/`reconcile`/`migration-failed`/
     * `asset-swap`/`calibration` — the seven ids with no runtime signal today, driven through
     * `org.ort.app.ui.failures.DebugFailureOverride` (that object's own kdoc says exactly why for
     * each). `setup-verified` (register R-227, validator pass 2) adds the one scenario this
     * registry seeds outside `:data` and the process-wide capture facets — see [setupVerified]'s
     * own doc comment for why (S05's `SetupStore` gates, not a runtime signal, are what block S07/
     * S12 from ever being reached on an emulator with no real signal to hear).
     */
    public val NAMES: List<String> = listOf(
        "empty",
        "first-session",
        "overnight",
        "overnight-live",
        "unclean-end",
        "os-stopped",
        "gap-call",
        "pass-a-partial",
        "pass-failed",
        "corrected",
        "no-audio",
        "revisions",
        "stations-14-nights",
        "field-tier1",
        "search-corpus",
        "backlog",
        "model-missing",
        "storage-warn",
        "thermal",
        "rig-lost",
        "level-low",
        "level-clip",
        "input-verified",
        "input-mismatch",
        "setup-verified",
        "clock-dst",
        "usb-permission",
        "interrupted-pass",
        "reconcile",
        "migration-failed",
        "asset-swap",
        "calibration",
    )

    public suspend fun load(context: Context, name: String): LoadResult {
        require(name in NAMES) { "unknown scenario '$name' — known scenarios: $NAMES" }
        val db = OrtDatabase.create(context.applicationContext)
        clearPriorScenarioData(context, db)
        resetProcessWideFacets()
        return when (name) {
            "empty" -> empty(db)
            "first-session" -> firstSession(context, db)
            "overnight" -> OvernightScenario.overnight(context, db)
            "overnight-live" -> OvernightScenario.overnightLive(context, db)
            "gap-call" -> OvernightScenario.gapCall(context, db)
            "unclean-end" -> uncleanEnd(context, db)
            "os-stopped" -> osStopped(context, db)
            "pass-a-partial" -> passAPartial(db)
            "pass-failed" -> passFailed(context, db)
            "corrected" -> corrected(db)
            "no-audio" -> noAudio(db)
            "revisions" -> revisions(context, db)
            "stations-14-nights" -> StationsFixtures.stations14Nights(db)
            "field-tier1" -> fieldTier1(db)
            "search-corpus" -> searchCorpus(db)
            "backlog" -> backlog(context, db)
            "model-missing" -> modelMissing(context, db)
            "storage-warn" -> storageWarn(context, db)
            "thermal" -> thermal(context, db)
            "rig-lost" -> rigLost(context, db)
            "level-low" -> levelLow(context, db)
            "level-clip" -> levelClip(context, db)
            "input-verified" -> inputVerified(context, db)
            "input-mismatch" -> inputMismatch(db)
            "setup-verified" -> setupVerified(context)
            "clock-dst" -> clockDst(context, db)
            "usb-permission" -> usbPermission(context, db)
            "interrupted-pass" -> interruptedPass(context, db)
            "reconcile" -> reconcile(context, db)
            "migration-failed" -> migrationFailed(context, db)
            "asset-swap" -> assetSwap(context, db)
            "calibration" -> calibration(context, db)
            else -> error("unreachable — guarded by the require() above")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Clearing
    // ---------------------------------------------------------------------------------------

    private fun clearPriorScenarioData(context: Context, db: OrtDatabase) {
        val sql = db.openHelper.writableDatabase
        val likeScenario = arrayOf<Any>("${ScenarioFixtures.SESSION_PREFIX}%")
        sql.execSQL(
            "DELETE FROM correction WHERE transmissionId IN " +
                "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
            likeScenario,
        )
        sql.execSQL(
            "DELETE FROM callsign_candidate WHERE transmissionId IN " +
                "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
            likeScenario,
        )
        sql.execSQL(
            "DELETE FROM phonetic_lattice WHERE transmissionId IN " +
                "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
            likeScenario,
        )
        sql.execSQL(
            "DELETE FROM transcript WHERE transmissionId IN " +
                "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
            likeScenario,
        )
        // R-153: `pass-failed` is the first scenario to write a work_queue_item row — cleared by
        // the same transmissionId-through-sessionId join every other per-transmission table uses.
        sql.execSQL(
            "DELETE FROM work_queue_item WHERE transmissionId IN " +
                "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
            likeScenario,
        )
        sql.execSQL("DELETE FROM thread WHERE sessionId LIKE ?", likeScenario)
        sql.execSQL("DELETE FROM capture_gap WHERE sessionId LIKE ?", likeScenario)
        sql.execSQL("DELETE FROM transmission WHERE sessionId LIKE ?", likeScenario)
        sql.execSQL("DELETE FROM session WHERE id LIKE ?", likeScenario)
        // Unconditional — see this object's own doc comment for why station/voiceprint rows
        // cannot be tagged by the same session-id-prefix convention.
        sql.execSQL("DELETE FROM station")
        sql.execSQL("DELETE FROM voiceprint")

        File(context.filesDir, "audio").listFiles { f -> f.name.startsWith(ScenarioFixtures.SESSION_PREFIX) }
            ?.forEach { it.deleteRecursively() }
        File(context.filesDir, "heartbeat.txt").delete()
    }

    /**
     * Every process-wide capture-facet singleton [org.ort.app.ui.data.ReaderPolling] reads
     * ([CaptureState], [AsrAvailability], [VadAvailability], [ShedStatus], and — WP11a, register
     * R-104/R-105 — [ThermalStatus]/[RigStatus]/[StorageForecast], and — WP11c, register R-112/
     * R-113 — [LevelStatus]/[InputStatus]) reset to their own honest "not started" default before a
     * scenario applies its own facts — otherwise a facet a previous scenario set (e.g.
     * `model-missing`'s [AsrAvailability.unavailable]) would leak into the next scenario loaded in
     * the same process, which is exactly the kind of silent cross-contamination this simulator
     * exists to prevent.
     */
    private fun resetProcessWideFacets() {
        CaptureState.idle(clearSession = true)
        AsrAvailability.reset()
        VadAvailability.reset()
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        DebugFailureOverride.clear()
        FailureSignalsPolling.reset()
    }

    // ---------------------------------------------------------------------------------------
    // Scenarios
    // ---------------------------------------------------------------------------------------

    /** `empty` — no sessions at all: the true first-launch / freshly-reset state. */
    private fun empty(db: OrtDatabase): LoadResult = LoadResult(0, 0, null)

    /** `first-session` — one session started "now" minus 3 minutes, no transmissions yet (`Now-First`). */
    private suspend fun firstSession(context: Context, db: OrtDatabase): LoadResult {
        val id = ScenarioFixtures.sessionId("first-session")
        val startedAt = SystemClock.wallMillis() - 3 * 60_000L
        db.sessionDao().insert(ScenarioFixtures.session(id, startedAt = startedAt, endedAt = null))
        ScenarioFixtures.markCapturing(context, id)
        // R-174: `Now-First.dc.html`'s subtitle reads "listening on 145.230 and 146.960" —
        // `NowViewStateMapper.active` only ever says that when `RigStatus.state` is actually
        // `Connected` (`ReaderPolling.activeNowViewState`'s own `listeningOnLabel`), so a scenario
        // that never sets it renders an honestly-blank subtitle instead, not this artboard's text.
        RigStatus.connected(
            descriptor = "TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = false),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
        )
        return LoadResult(0, 1, id)
    }

    /**
     * `unclean-end` — a prior session whose heartbeat stopped without a clean shutdown
     * ([org.ort.capture.android.heartbeat.FileHeartbeatStore.markCleanShutdown] is deliberately
     * never called — that omission alone is what
     * [org.ort.capture.android.heartbeat.FileHeartbeatStore.hadUncleanEnd] reads as unclean, per
     * that store's own `write()` always stamping the "clean" marker `false`). This process is
     * *not* capturing — the finding is about the *previous* launch.
     */
    private suspend fun uncleanEnd(context: Context, db: OrtDatabase): LoadResult {
        val id = ScenarioFixtures.sessionId("unclean-end")
        val staleWallMillis = SystemClock.wallMillis() - 6 * 3_600_000L
        db.sessionDao().insert(
            ScenarioFixtures.session(
                id,
                startedAt = staleWallMillis - 3_600_000L,
                endedAt = null,
                terminationReason = TerminationReason.UNKNOWN,
            ),
        )
        FileHeartbeatStore(File(context.filesDir, "heartbeat.txt")).write(
            HeartbeatRecord(
                sessionId = id,
                monotonicNanos = 0L,
                wallMillis = staleWallMillis,
                samplePosition = 118_000L,
            ),
        )
        return LoadResult(0, 1, id)
    }

    /**
     * `os-stopped` — F5, register R-106: the same unclean-end heartbeat [uncleanEnd] writes, plus
     * the [CaptureGapCause.OS_STOPPED] gap [RealCaptureService][org.ort.pipeline.capture.RealCaptureService]
     * itself now persists on relaunch (from the last heartbeat to the moment it is detected — never
     * "reopening" the previous session, a policy the lead has not decided; see this package's
     * report). Written directly, the same way `overnight`'s own gap rows are, rather than driving
     * the real service end to end — this scenario proves the *rendering* path (`Fail-Killed`'s gap
     * list, the log's gap row) is fed real data shaped exactly like the real fix produces.
     */
    private suspend fun osStopped(context: Context, db: OrtDatabase): LoadResult {
        val id = ScenarioFixtures.sessionId("os-stopped")
        val staleWallMillis = SystemClock.wallMillis() - 6 * 3_600_000L
        val detectedAtWallMillis = SystemClock.wallMillis()
        db.sessionDao().insert(
            ScenarioFixtures.session(
                id,
                startedAt = staleWallMillis - 3_600_000L,
                endedAt = null,
                terminationReason = TerminationReason.UNKNOWN,
            ),
        )
        FileHeartbeatStore(File(context.filesDir, "heartbeat.txt")).write(
            HeartbeatRecord(
                sessionId = id,
                monotonicNanos = 0L,
                wallMillis = staleWallMillis,
                samplePosition = 118_000L,
            ),
        )
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "$id-gap-os-stopped",
                sessionId = id,
                startedAt = staleWallMillis,
                endedAt = detectedAtWallMillis,
                cause = CaptureGapCause.OS_STOPPED,
                recoveredAutomatically = false,
            ),
        )
        return LoadResult(0, 1, id)
    }

    /**
     * `pass-a-partial` — a transmission whose only transcript row is a Pass A partial
     * ([TranscriptPass.A], `isCurrent = true`, `processingState = PROCESSING`). **Representable**:
     * `:data`'s schema has no separate "is a partial" flag, but a pass=A current transcript with no
     * pass=B row yet *is* exactly what a real in-flight Pass A produces — see this package's report
     * for what is and is not built to *render* that state (register R-041 — the rendering, not the
     * data, is what is missing; `spec/ui-conformance-plan.md`'s WP0 brief asked this to be checked
     * and reported, not fixed here).
     */
    private suspend fun passAPartial(db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("pass-a-partial")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 5 * 60_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 20_000L
        val tx = ScenarioFixtures.transmission(
            id = txId,
            sessionId = sessionId,
            startedAtUtc = startedAt,
            samplePosition = 1L,
            frequencyHz = 146_960_000L,
            attributionState = AttributionState.UNKNOWN,
            processingState = TransmissionState.PROCESSING,
        )
        db.transmissionDao().insert(tx)
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "and we're clear on the repeater, seven th",
                pass = TranscriptPass.A,
                isCurrent = true,
                createdAt = startedAt + 1_000L,
                confidence = null,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /**
     * `pass-failed` — R-153, F18 `Fail-Pass.dc.html`, FR-RUN-9: a transmission whose Pass B
     * ([PassId.B_OFFLINE]) errored out under [org.ort.data.WorkQueue.failPass]'s bound and is now
     * terminally [WorkQueueState.FAILED] (3 attempts, the real `lastError` text), while its Pass A
     * partial ([TranscriptPass.A], `isCurrent = true`) is the honest text
     * [org.ort.app.ui.data.ReaderTransmissionViewStateMapper.transcriptLabel] shows in place of a
     * final transcript — this scenario writes the [WorkQueueItemEntity] row directly (the same
     * shape [org.ort.data.WorkQueue.failPass] itself writes via
     * [org.ort.data.dao.WorkQueueDao.markFailed]) rather than driving a real pass through failure,
     * the same "prove the rendering path, not the pipeline" approach [osStopped] already uses for
     * its gap row. Retained audio is written so `Retry this pass`/the waveform have a real over to
     * act on, matching `Fail-Pass.dc.html`'s own "the audio is here" reading.
     */
    private suspend fun passFailed(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("pass-failed")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 90 * 60_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 5 * 60_000L
        val tx = ScenarioFixtures.transmission(
            id = txId,
            sessionId = sessionId,
            startedAtUtc = startedAt,
            durationMs = 28_400L,
            samplePosition = 1L,
            frequencyHz = 145_230_000L,
            signalStrength = 8.0,
            attributionState = AttributionState.UNKNOWN,
            processingState = TransmissionState.FAILED,
        )
        db.transmissionDao().insert(tx)
        ScenarioFixtures.writeAudioFixture(context, tx)
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "okay so for the net tonight we've got the following announcements first the club " +
                    "meeting has moved to the second thursday and second the",
                pass = TranscriptPass.A,
                isCurrent = true,
                createdAt = startedAt + 1_000L,
                confidence = null,
            ),
        )
        db.workQueueDao().insert(
            WorkQueueItemEntity(
                transmissionId = txId,
                pass = PassId.B_OFFLINE,
                state = WorkQueueState.FAILED,
                priority = 0,
                attemptCount = 3,
                lastError = "out of memory in the decoder",
                enqueuedAt = startedAt,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /**
     * `corrected` — a single transmission carrying the exact shape a one-tap correction produces
     * ([org.ort.core.Attribution.withCorrection]: `INFERRED`, no confidence, no propagation
     * source, `corrected = true`), plus the [CorrectionEntity] audit row
     * [CorrectionDao.recordCorrection] would have written, for the detail screen's `corrected`
     * variant.
     */
    private suspend fun corrected(db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("corrected")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 600_000L
        val tx = ScenarioFixtures.transmission(
            id = txId,
            sessionId = sessionId,
            startedAtUtc = startedAt,
            samplePosition = 1L,
            frequencyHz = 146_960_000L,
            attributionState = AttributionState.INFERRED,
            stationId = "KJ7ABC",
            attributionConfidence = null,
            corrected = true,
        )
        db.transmissionDao().insert(tx)
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "kilo juliet seven alpha bravo charlie, back to you",
                isCurrent = true,
                createdAt = startedAt + 1_000L,
            ),
        )
        db.catalogDao().insert(
            CorrectionEntity(
                id = "$txId-correction1",
                transmissionId = txId,
                field = CorrectionDao.FIELD_STATION,
                previousValue = "K7LWH",
                newValue = "KJ7ABC",
                correctedAt = startedAt + 30_000L,
                propagatedToCount = 3,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /** `no-audio` — a confirmed transmission with no retained-audio file at all (`Detail-Playback`'s no-audio variant). */
    private suspend fun noAudio(db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("no-audio")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 500_000L
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = txId,
                sessionId = sessionId,
                startedAtUtc = startedAt,
                samplePosition = 1L,
                frequencyHz = 145_230_000L,
                attributionState = AttributionState.CONFIRMED,
                stationId = "W7NPC",
                attributionConfidence = 0.95,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "this is whiskey seven november papa charlie, monitoring",
                isCurrent = true,
                createdAt = startedAt + 1_000L,
            ),
        )
        // Deliberately no ScenarioFixtures.writeAudioFixture(...) call — hasAudio must read false.
        return LoadResult(1, 1, sessionId)
    }

    /** `revisions` — a single transmission with two transcript versions, the older superseded (`Detail-Revisions`). */
    private suspend fun revisions(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("revisions")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 700_000L
        val tx = ScenarioFixtures.transmission(
            id = txId,
            sessionId = sessionId,
            startedAtUtc = startedAt,
            samplePosition = 1L,
            frequencyHz = 146_960_000L,
            attributionState = AttributionState.CONFIRMED,
            stationId = "W7NPC",
            attributionConfidence = 0.9,
        )
        db.transmissionDao().insert(tx)
        ScenarioFixtures.writeAudioFixture(context, tx)
        db.transcriptDao().supersede(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "and we're clear on the repeater, seven th",
                pass = TranscriptPass.A,
                isCurrent = true,
                createdAt = startedAt + 1_000L,
                confidence = null,
            ),
        )
        db.transcriptDao().supersede(
            ScenarioFixtures.transcript(
                id = "$txId-t2",
                transmissionId = txId,
                text = "and we're clear on the repeater, seven three",
                isCurrent = true,
                createdAt = startedAt + 4_000L,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /**
     * `field-tier1` — a session flagged as captured at tier 1 ([org.ort.data.entity.SessionEntity.deviceTier]
     * is the only tier field `:data` carries, and it is a plain, freely-settable `String?`). Now
     * wired end to end by WP10 (register R-090/R-091, round 3): `Settings-Tier`/CF05 and
     * `Improve`/`Improve-Select`/`Improve-Running` all read it.
     *
     * R-143 (round 4, System validator): a single over made `Improve-Running` (R03) unreachable on
     * the emulator — [FakeImproveRunner]'s per-item delay times the *whole* run, so one over
     * finished before a screenshot script's own settle wait could ever catch it running. Twelve
     * overs (`FIELD_TIER1_OVER_COUNT`), still a real, honest number [ImprovePolling] counts for
     * real, gives the run a visible multi-second span at the runner's default per-item delay.
     */
    private suspend fun fieldTier1(db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("field-tier1")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = SystemClock.wallMillis() - 3_600_000L,
                endedAt = null,
                deviceTier = org.ort.core.Tier.T1.name,
            ),
        )
        val baseStartedAt = SystemClock.wallMillis() - 400_000L
        repeat(FIELD_TIER1_OVER_COUNT) { i ->
            val txId = "$sessionId-tx${i + 1}"
            val startedAt = baseStartedAt + i * 20_000L
            db.transmissionDao().insert(
                ScenarioFixtures.transmission(
                    id = txId,
                    sessionId = sessionId,
                    startedAtUtc = startedAt,
                    samplePosition = (i + 1).toLong(),
                    frequencyHz = 146_960_000L,
                    attributionState = AttributionState.INFERRED,
                    stationId = ScenarioFixtures.CALLSIGNS[i % ScenarioFixtures.CALLSIGNS.size],
                    attributionConfidence = 0.68,
                ),
            )
            db.transcriptDao().insert(
                ScenarioFixtures.transcript(
                    id = "$txId-t1",
                    transmissionId = txId,
                    text = "roger that, good copy on the repeater this morning",
                    isCurrent = true,
                    createdAt = startedAt + 1_000L,
                ),
            )
        }
        return LoadResult(FIELD_TIER1_OVER_COUNT, 1, sessionId)
    }

    /** `search-corpus` — enough transcripts containing "park activation" for ~14 hits across 3 nights. */
    private suspend fun searchCorpus(db: OrtDatabase): LoadResult {
        var transmissionCount = 0
        val nights = 3
        val perNight = listOf(5, 5, 4) // sums to 14, matching this package's brief
        var primary: String? = null
        for (night in 0 until nights) {
            val sessionId = ScenarioFixtures.sessionId("search-corpus", "night$night")
            val nightStart = SystemClock.wallMillis() - (nights - night) * 24 * 3_600_000L
            db.sessionDao().insert(
                ScenarioFixtures.session(
                    sessionId,
                    startedAt = nightStart,
                    endedAt =
                    nightStart + 3_600_000L,
                ),
            )
            if (primary == null) primary = sessionId
            repeat(perNight[night]) { i ->
                val txId = "$sessionId-tx$i"
                val startedAt = nightStart + i * 60_000L
                val callsign = ScenarioFixtures.CALLSIGNS[i % ScenarioFixtures.CALLSIGNS.size]
                db.transmissionDao().insert(
                    ScenarioFixtures.transmission(
                        id = txId,
                        sessionId = sessionId,
                        startedAtUtc = startedAt,
                        samplePosition = i.toLong() + 1L,
                        frequencyHz = if (i % 2 == 0) 146_960_000L else 145_230_000L,
                        attributionState = AttributionState.CONFIRMED,
                        stationId = callsign,
                        attributionConfidence = 0.9,
                    ),
                )
                db.transcriptDao().insert(
                    ScenarioFixtures.transcript(
                        id = "$txId-t1",
                        transmissionId = txId,
                        text = "this is $callsign, doing a park activation at K-${4400 + i}, any hunters listening",
                        isCurrent = true,
                        createdAt = startedAt + 1_000L,
                    ),
                )
                transmissionCount++
            }
        }
        return LoadResult(transmissionCount, nights, primary)
    }

    /**
     * `backlog` — F8, the shed level 3 the plan's own artboard reading maps to (register R-038:
     * "Backlog (3 overs waiting · not growing)"). [ShedStatus] is settable from `:app` today
     * ([org.ort.app.ui.data.ReaderPolling] already reads it directly) — no `:pipeline` change
     * needed.
     */
    private suspend fun backlog(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("backlog")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 40 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        ShedStatus.update(level = 3, backlog = 112)
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `model-missing` — F13, no ASR model installed. [AsrAvailability] is settable from `:app`
     * today for the same reason [ShedStatus] is. Transmissions are captured but never transcribed
     * (`processingState = CAPTURED`, no transcript row) — the honest state a real device with no
     * model shows.
     */
    private suspend fun modelMissing(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("model-missing")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 20 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        AsrAvailability.unavailable("no ASR model installed — see Settings › Models")
        val startedAt = SystemClock.wallMillis() - 60_000L
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = "$sessionId-tx1",
                sessionId = sessionId,
                startedAtUtc = startedAt,
                samplePosition = 1L,
                frequencyHz = 146_960_000L,
                attributionState = AttributionState.UNKNOWN,
                processingState = TransmissionState.CAPTURED,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /**
     * `storage-warn` — F6/FR-STO-3, register R-105. **Replaces this scenario's earlier misuse of
     * F6's exhaustion failure string** (`CaptureState.failed(...)`) — capture is genuinely still
     * running (never `Failed`); the early warning is now [StorageForecast.ThreeNightsLeft], the
     * real "getting low" state WP11a added, set directly the way [ShedStatus]/[AsrAvailability]
     * are for the same reason a scenario asserts an exact state rather than reverse-engineering the
     * byte/time inputs that would produce it.
     */
    private suspend fun storageWarn(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("storage-warn")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 90 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        StorageForecast.set(
            StorageForecast.State.ThreeNightsLeft(
                freeBytes = 6L * 1024 * 1024 * 1024, // ~6 GB free
                audioDirectoryBytes = 38L * 1024 * 1024 * 1024 + (200L * 1024 * 1024), // ~38.2 GB, matching the board
                nightsLeft = 2.4,
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `thermal` — F7, register R-104. [ThermalStatus] warm with a measured RTF of 0.9
     * (`Fail-Thermal.dc.html`'s own figure), the same shed level/backlog `backlog` already sets
     * (F8's board and this one show the same degraded tier together in practice).
     */
    private suspend fun thermal(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("thermal")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 70 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        ShedStatus.update(level = 3, backlog = 112)
        // R-177: a real, fixed "minutes ago" transition moment -- not "now" -- so the banner's
        // "dropped to tier N at HH:MM:SS" reads the same real clock time on every poll instead of
        // advancing each time the mapper re-renders it.
        ThermalStatus.update(
            osThermalStatus = ThermalStatus.THERMAL_STATUS_MODERATE,
            realTimeFactor = 0.9,
            sinceMillis = SystemClock.wallMillis() - 12 * 60_000L,
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `rig-lost` — F9, register R-104. [RigStatus] stale since 30 minutes ago, last known on
     * 145.230 (`Fail-Rig.dc.html`'s own figures) — the rig module itself (FR-RIG) is unbuilt
     * (register R-084), so this scenario is the only producer of a `Connected`/`Stale` [RigStatus]
     * today; [org.ort.pipeline.capture.RealCaptureService]'s own real production call is
     * [RigStatus.absent] (see that class's kdoc).
     */
    private suspend fun rigLost(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("rig-lost")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 5 * 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        val lastKnown = RigStatus.State.Connected(
            descriptor = "TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = true),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
        )
        RigStatus.stale(lastKnown, sinceMillis = SystemClock.wallMillis() - 30 * 60_000L)
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `level-low` — F3, register R-112. Peak −38 dBFS, floor −60 dBFS, no clipping
     * (`Fail-Level.dc.html`'s own figures) — the radio's volume has drifted quiet, but capture is
     * still genuinely running.
     */
    private suspend fun levelLow(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("level-low")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 90 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = -38f,
                rmsDbfs = -44f,
                noiseFloorDbfs = -60f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 48_000,
                updatedAtMillis = SystemClock.wallMillis(),
            ),
            peakHistoryDbfs = List(60) { -40f + (it % 5) },
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `level-clip` — F3, register R-112. Peak 0 dBFS, clipped, 12 clips in the last second
     * (`Level-Meter.dc.html`'s own clip-count row) — the radio's volume is too hot.
     */
    private suspend fun levelClip(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("level-clip")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 20 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = 0f,
                rmsDbfs = -6f,
                noiseFloorDbfs = -55f,
                clipped = true,
                clipCountLastSecond = 12,
                sampleRateHz = 48_000,
                updatedAtMillis = SystemClock.wallMillis(),
            ),
            peakHistoryDbfs = List(60) { if (it % 4 == 0) 0f else -8f },
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `input-verified` — R-113. [InputStatus.State.Opened] with a USB descriptor, native 48 kHz, a
     * recorded resampler identity, and a verified matching route (`Setup-Verify.dc.html`'s four
     * checks all satisfied).
     */
    private suspend fun inputVerified(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("input-verified")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 10 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis(),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `input-mismatch` — R-113, F1. [InputStatus.State.Mismatch]: the built-in mic routed instead
     * of the chosen USB device (`Setup-Route-Mismatch.dc.html`/`Fail-Route.dc.html`'s own pairing).
     * Capture is deliberately **not** marked running — a mismatch halts capture in the same second
     * (`Fail-Route.dc.html`: "Capture stopped in the same second"; see this package's report for
     * exactly where `RealCaptureService`/`AudioRecordSource` already do that), so a scenario naming
     * this state must not also claim capture is still live.
     */
    private suspend fun inputMismatch(db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("input-mismatch")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 5 * 60_000L, endedAt = null),
        )
        InputStatus.mismatch(
            expected = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            actual = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone"),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `setup-verified` — R-227 (validator pass 2). Before this, S07 (`Setup-Level.dc.html`) and
     * S12 (`Setup-Done.dc.html`) had no sanctioned path on an emulator: [SetupStateMachine.stepFor]
     * resumes at [SetupStep.INPUT] until [SetupStore.inputVerified] is real, and that can only
     * become real through S05's own 30 s raw-signal listen ([RealRouteCheck]) actually hearing
     * something — which a silent emulator mic never does. The validator's own workaround (editing
     * `SharedPreferences` via `run-as`) is exactly what a debug scenario exists to make unnecessary
     * (register R-110's own brief) — this seeds the *same* `org.ort.app.setup` preferences file
     * [SharedPreferencesSetupStore] itself reads and writes, through that real class (never
     * duplicated key names), landing the natural resume point at [SetupStep.READY] (S12) with the
     * Level row already green — S07 itself is then one real, sanctioned tap away, S12's own
     * `Fix`/`Install` rows, or `SetupActivity.EXTRA_STEP=LEVEL` (WP9 round 3): with
     * [SetupSnapshot.setupComplete] left `false`, the natural resume point stays `READY`, and
     * `LEVEL`'s ordinal sits before it, so the extra is honored (see that constant's own doc
     * comment for the ordinal-gate rule this relies on).
     *
     * **Not a substitute for granting the two OS permissions.** `RECORD_AUDIO`/`POST_NOTIFICATIONS`
     * are live [android.content.pm.PackageManager] state, not a preference this scenario can seed —
     * confirmed by reading [SetupStateMachine.stepFor] before writing this: both gates are read from
     * a live `PermissionsState`, never from [SetupSnapshot]. The validator (or `results/ui-audit/README.md`,
     * which documents this) must still grant both separately, e.g.
     * `adb shell pm grant org.ort.app android.permission.RECORD_AUDIO` and the `POST_NOTIFICATIONS`
     * equivalent, exactly as every other scenario that exercises a real screen already assumes.
     */
    private fun setupVerified(context: Context): LoadResult {
        val prefs = context.applicationContext.getSharedPreferences(
            SharedPreferencesSetupStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val store = SharedPreferencesSetupStore(prefs)
        store.welcomeSeen = true
        store.notificationsSkipped = true
        store.selectedInputId = "usb-1"
        store.selectedInputLabel = "USB Audio Device"
        store.inputVerified = true
        store.verifiedNativeRateHz = 48_000
        store.verifiedResamplerIdentity = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)"
        store.levelInBand = true
        store.levelPeakDbfs = -14.0
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.NONE
        store.manualFrequencyHz = 145_230_000L
        store.setupComplete = false
        return LoadResult(0, 0, null)
    }

    // ---------------------------------------------------------------------------------------
    // WP11b (register R-100): the seven ids with no runtime signal today —
    // `DebugFailureOverride.kt`'s kdoc says exactly why for each. Each scenario below sets
    // [DebugFailureOverride.show] with the exact [FailurePresentation] its board needs, on top of
    // a minimal session so the reader beneath the takeover is not a bare crash surface.
    // ---------------------------------------------------------------------------------------

    /** `clock-dst` — F14, `Fail-Clock.dc.html`. */
    private suspend fun clockDst(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("clock-dst")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 8 * 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.Clock(
                ClockViewState(
                    offsetChangeLabel = "PDT → PST",
                    ranForLabel = "8 h 30 m",
                    startedLabel = "23:10",
                    endedLabel = "06:40",
                    nightLabel = "Overnight, Sat 31 Oct",
                    windowLabel = "23:10 – 06:40 · 8 h 30 m · the clock went back at 02:00",
                    logRows = listOf(
                        ClockLogRow("01:58:40", "before", "W7NPC", "copy on the repeater, seven three"),
                        ClockLogRow("01:01:12", "after (was 02:01:12)", "KJ7ABC", "back to you, seven three"),
                        ClockLogRow("01:04:03", "after (was 02:04:03)", null, "break, break — anyone on frequency"),
                    ),
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `usb-permission` — F16, `Fail-Usb.dc.html`. */
    private suspend fun usbPermission(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("usb-permission")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3 * 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.Usb(
                UsbViewState(detachedAtLabel = "03:44", reattachedAtLabel = "03:47", staleOversCount = 4),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `interrupted-pass` — F17, `Fail-Interrupted.dc.html`. */
    private suspend fun interruptedPass(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("interrupted-pass")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 10 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.Interrupted(
                InterruptedViewState(
                    overCount = 3,
                    gapLabel = "03:12 – 06:48",
                    backlogLabel = "3 overs waiting",
                    overs = listOf(
                        InterruptedOverRow("03:12:31", "audio only", "no transcript — the pass never started"),
                        InterruptedOverRow("03:12:08", "partial", "a Pass A partial, superseded by nothing yet"),
                        InterruptedOverRow("03:11:40", "audio only", "no transcript — the pass never started"),
                    ),
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `reconcile` — F19, `Fail-Reconcile.dc.html`. */
    private suspend fun reconcile(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("reconcile")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 8 * 3_600_000L, endedAt = null),
        )
        DebugFailureOverride.show(
            FailurePresentation.Reconcile(
                ReconcileViewState(
                    recordsNoFile = listOf(
                        ReconcileRecord(
                            "W7NPC",
                            "Fri 4 Sep 01:22 · 28.4 s",
                            "transcript, attribution and lattice intact",
                        ),
                        ReconcileRecord(
                            "KJ7ABC",
                            "Fri 4 Sep 01:23 · 4.1 s",
                            "same session, same minute — likely the same cause",
                        ),
                        ReconcileRecord("unknown station", "Fri 4 Sep 01:23 · 2.0 s", null),
                    ),
                    filesNoRecord = listOf(
                        ReconcileFile(
                            "a/2026-09-04/03-12-31.flac",
                            "6.2 s",
                            "written while the app was stopped mid-write",
                        ),
                        ReconcileFile("a/2026-09-04/03-12-40.flac", "1.8 s", "same"),
                    ),
                    causeText = "The OS stopped the app at 03:12 on Fri 4 Sep with writes in flight. The three " +
                        "records lost their files to an interrupted retention pass the next morning; the two " +
                        "files are overs whose records never landed.",
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `migration-failed` — F20, `Fail-Migration.dc.html`. */
    private suspend fun migrationFailed(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("migration-failed")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 60_000L, endedAt = null),
        )
        DebugFailureOverride.show(
            FailurePresentation.Migration(
                MigrationViewState(
                    versionLabel = "Updated to 1.1.0",
                    headline = "The records did not fully carry over",
                    steps = listOf(
                        MigrationStep("Audio untouched", "38.2 GB · 4,318 files · checksums match", ok = true),
                        MigrationStep(
                            "Transcripts, all versions, untouched",
                            "current and superseded · 6,904 rows",
                            ok = true,
                        ),
                        MigrationStep(
                            "Attributions, corrections, stations untouched",
                            "every state, every correction",
                            ok = true,
                        ),
                        MigrationStep(
                            "Activity patterns need rebuilding",
                            "the hour-bucket table changed shape · 4,318 overs marked · runs in the background",
                            ok = false,
                        ),
                    ),
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `asset-swap` — F21, `Fail-Asset-Swap.dc.html`. */
    private suspend fun assetSwap(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("asset-swap")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.AssetSwap(
                AssetSwapViewState(
                    activeLabel = "2026.08 · active · this session · 1,104,208 records",
                    stagedLabel = "2026.09 · staged · next session · 1,122,410 records",
                    options = listOf(
                        AssetSwapOption("Wait for the session to end", "the default · nothing else to do"),
                        AssetSwapOption(
                            "Stop capture, swap, start a new session",
                            "tonight's log ends here · a second session begins on 2026.09 · both stay in " +
                                "Earlier nights",
                        ),
                        AssetSwapOption(
                            "Afterwards, re-run tonight on 2026.09",
                            "Improve records will offer it · 12 ambiguous overs might resolve with the new " +
                                "prefixes",
                        ),
                    ),
                    selectedOption = 0,
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `calibration` — F22, `Fail-Calibration.dc.html`. */
    private suspend fun calibration(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("calibration")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.Calibration(
                CalibrationViewState(
                    sinceLabel = "1 Sep",
                    scoreLabel = "0.90",
                    accuracyLabel = "78%",
                    points = listOf(0.30f to 0.22f, 0.50f to 0.38f, 0.70f to 0.52f, 0.86f to 0.66f, 0.94f to 0.74f),
                    calibrationVersion = "2026.09-a",
                    correctionsCount = 22,
                    correctionsNeeded = 100,
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }
}
