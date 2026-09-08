package org.ort.app.debug

import android.content.Context
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.dao.CorrectionDao
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.TranscriptPass
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
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

    /**
     * Every scenario name `spec/ui-conformance-plan.md` §E and `design/design-intent.md` need
     * reachable. WP11a (register R-104/R-105/R-106) closed the three gaps this list used to note
     * as unreachable from `:app` — `thermal`/`rig-lost`/`os-stopped` are new; `storage-warn` now
     * uses [StorageForecast] instead of reusing F6's exhaustion string.
     */
    public val NAMES: List<String> = listOf(
        "empty",
        "first-session",
        "overnight",
        "unclean-end",
        "os-stopped",
        "gap-call",
        "pass-a-partial",
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
            "gap-call" -> OvernightScenario.gapCall(context, db)
            "unclean-end" -> uncleanEnd(context, db)
            "os-stopped" -> osStopped(context, db)
            "pass-a-partial" -> passAPartial(db)
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
     * R-104/R-105 — [ThermalStatus]/[RigStatus]/[StorageForecast]) reset to their own honest "not
     * started" default before a scenario applies its own facts — otherwise a facet a previous
     * scenario set (e.g. `model-missing`'s [AsrAvailability.unavailable]) would leak into the next
     * scenario loaded in the same process, which is exactly the kind of silent cross-contamination
     * this simulator exists to prevent.
     */
    private fun resetProcessWideFacets() {
        CaptureState.idle(clearSession = true)
        AsrAvailability.reset()
        VadAvailability.reset()
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
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
     * is the only tier field `:data` carries, and it is a plain, freely-settable `String?`).
     * **Nothing in the built reader renders it yet** (`Settings-Tier`/CF05 is a placeholder,
     * register R-090) — representable in data, not yet wired to any screen; reported as such.
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
        val startedAt = SystemClock.wallMillis() - 400_000L
        val txId = "$sessionId-tx1"
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = txId,
                sessionId = sessionId,
                startedAtUtc = startedAt,
                samplePosition = 1L,
                frequencyHz = 146_960_000L,
                attributionState = AttributionState.INFERRED,
                stationId = "K7LWH",
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
        return LoadResult(1, 1, sessionId)
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
        ThermalStatus.update(osThermalStatus = ThermalStatus.THERMAL_STATUS_MODERATE, realTimeFactor = 0.9)
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
}
