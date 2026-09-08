package org.ort.app.debug

import android.content.Context
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.dao.CorrectionDao
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity

/**
 * `overnight`, `gap-call` and `overnight-live` (spec/ui-conformance-plan.md §E's V3 set; register
 * R-110, R-171) — the one scenario `design/canvas/Rows.dc.html` needs every variant from:
 * CONFIRMED, INFERRED (linked to its confirming over), AMBIGUOUS, UNKNOWN, a corrected row, a
 * revised row, a rejected row, a first-heard ("NEW") station, a QSO thread, a capture gap, mixed
 * signal strength and mixed retained audio — spread across a real 6h42m, two-frequency session
 * with roughly forty overs. `overnight-live` (R-171) is the same fixture with the session still
 * open and [CaptureState] actually capturing, so the populated `Main`/`Capture-Status` running
 * shape is reachable — see [overnightLive]'s own doc comment.
 */
internal object OvernightScenario {

    private const val FREQ_A = 145_230_000L // MHz label 145.230
    private const val FREQ_B = 146_960_000L // MHz label 146.960
    private const val SESSION_DURATION_MILLIS = 6L * 3_600_000L + 42L * 60_000L // 6h42m

    suspend fun overnight(context: Context, db: OrtDatabase): Scenarios.LoadResult =
        build(context, db, scenarioName = "overnight", extraGap = false)

    /** `gap-call` — `overnight` plus a second gap (this package's report notes the "incoming call" caveat). */
    suspend fun gapCall(context: Context, db: OrtDatabase): Scenarios.LoadResult =
        build(context, db, scenarioName = "gap-call", extraGap = true)

    /**
     * `overnight-live` (R-171) — the same populated ~40-over overnight fixture, but
     * [SessionEntity.endedAt] `null` and [CaptureState] actually `capturing` on it (via
     * [ScenarioFixtures.markCapturing], the same primitive `backlog`/`thermal`/`rig-lost` already
     * use), so `Main.dc.html` (N01) and `Capture-Status.dc.html` (N04) are reachable in their
     * *populated, running* shape — every other overnight-shaped scenario is either populated and
     * ended (`overnight`, `gap-call`) or running and empty (`first-session`); nothing before this
     * was both at once.
     */
    suspend fun overnightLive(context: Context, db: OrtDatabase): Scenarios.LoadResult =
        build(context, db, scenarioName = "overnight-live", extraGap = false, live = true)

    @Suppress("LongMethod")
    private suspend fun build(
        context: Context,
        db: OrtDatabase,
        scenarioName: String,
        extraGap: Boolean,
        live: Boolean = false,
    ): Scenarios.LoadResult {
        val sessionId = ScenarioFixtures.sessionId(scenarioName)
        val end = SystemClock.wallMillis() - 8 * 60_000L
        val start = end - SESSION_DURATION_MILLIS
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = start, endedAt = if (live) null else end),
        )

        var sample = 0L
        fun nextSample(): Long {
            sample += 1
            return sample
        }
        fun offset(minutes: Double): Long = start + (minutes * 60_000L).toLong()

        // -- 1. CONFIRMED, the QSO's opener, audio retained, positive priors only. ------------------
        // R-241 (V3 pass 2 @de56368): a real ULID, not the readable "$sessionId-tx01" every other
        // id in this file uses — `attributionSourceTransmissionId` is round-tripped through
        // `TransmissionId.parse` (`ReaderPolling.sourceId`) before an INFERRED reasoning line can
        // link to its source, and a non-ULID string fails that parse silently (caught by
        // `runCatching`), which is exactly why the several INFERRED overs below that cite `tx1` as
        // their source rendered "source transmission not recorded" with no link. Every other id in
        // this scenario stays the readable form — only the ids actually used as a source need to
        // survive `TransmissionId.parse`.
        val tx1 = TransmissionId.new().toString()
        val t1 = offset(0.2)
        insertTx(
            db,
            context,
            ScenarioFixtures.transmission(
                id = tx1, sessionId = sessionId, startedAtUtc = t1, samplePosition = nextSample(),
                frequencyHz = FREQ_A, signalStrength = 7.0,
                attributionState = AttributionState.CONFIRMED, stationId = "W7NPC", attributionConfidence = 0.94,
            ),
            text = "this is whiskey seven november papa charlie, monitoring",
            writeAudio = true,
        )
        db.catalogDao().insert(ScenarioFixtures.lattice("$tx1-lat", tx1, createdAt = t1 + 500L))
        db.catalogDao().insert(
            ScenarioFixtures.candidate(
                "$tx1-c1",
                tx1,
                "W7NPC",
                rank = 0,
                score = 9.4,
                selected = true,
                priorBreakdown = mapOf("callsign-history" to 1.2, "database" to 0.8),
            ),
        )

        // -- 2. INFERRED, linked back to tx1's confirming over; carries a cold-start prior and a --
        //       negative ("argued against") prior side by side (FR-LEX-31, register R-051). --------
        val tx2 = "$sessionId-tx02"
        val t2 = offset(1.0)
        insertTx(
            db,
            context,
            ScenarioFixtures.transmission(
                id = tx2, sessionId = sessionId, startedAtUtc = t2, samplePosition = nextSample(),
                frequencyHz = FREQ_A, signalStrength = 5.0,
                attributionState = AttributionState.INFERRED, stationId = "K7LWH", attributionConfidence = 0.82,
                attributionSourceTransmissionId = tx1,
            ),
            text = "roger that, good copy on the repeater this morning",
            writeAudio = false,
        )
        db.catalogDao().insert(ScenarioFixtures.lattice("$tx2-lat", tx2, createdAt = t2 + 500L))
        db.catalogDao().insert(
            ScenarioFixtures.candidate(
                "$tx2-c1",
                tx2,
                "K7LWH",
                rank = 0,
                score = 8.2,
                selected = true,
                priorBreakdown = mapOf("callsign-history" to 0.0, "propagation" to -0.42, "database" to 0.8),
            ),
        )

        // -- 3. AMBIGUOUS: two close candidates, neither chosen (attributionFrom() always maps ----
        //       AMBIGUOUS to Attribution.ambiguous() — no station on the transmission row itself). -
        val tx3 = "$sessionId-tx03"
        val t3 = offset(2.0)
        insertTx(
            db,
            context,
            ScenarioFixtures.transmission(
                id = tx3,
                sessionId = sessionId,
                startedAtUtc = t3,
                samplePosition = nextSample(),
                frequencyHz = FREQ_A,
                signalStrength = 3.0,
                attributionState = AttributionState.AMBIGUOUS,
            ),
            text = "kilo echo seven quebec romeo sierra, portable",
            writeAudio = true,
        )
        db.catalogDao().insert(ScenarioFixtures.lattice("$tx3-lat", tx3, createdAt = t3 + 500L))
        db.catalogDao().insert(
            ScenarioFixtures.candidate("$tx3-c1", tx3, "KE7QRS", rank = 0, score = 8.20, selected = false),
        )
        db.catalogDao().insert(
            ScenarioFixtures.candidate("$tx3-c2", tx3, "KE7QRF", rank = 1, score = 8.05, selected = false),
        )
        // R-184: a third, distinctly-scored candidate — `Detail-Correct-A.dc.html`'s Tier A list
        // ("the resolver's other candidates") renders every ranked candidate whose callsign is not
        // the over's own current one; for an AMBIGUOUS over that is `null`, so the whole ranked list
        // shows. WP6 verified the derivation by test (a9c24c6); the zero-rows-on-device symptom was
        // this fixture never carrying more than two, not the screen — see this scenario's own report.
        db.catalogDao().insert(
            ScenarioFixtures.candidate("$tx3-c3", tx3, "KE7QRZ", rank = 2, score = 7.90, selected = false),
        )

        // -- 4. UNKNOWN: nothing claimed. ----------------------------------------------------------
        val tx4 = "$sessionId-tx04"
        val t4 = offset(3.0)
        insertTx(
            db,
            context,
            ScenarioFixtures.transmission(
                id = tx4,
                sessionId = sessionId,
                startedAtUtc = t4,
                samplePosition = nextSample(),
                frequencyHz = FREQ_A,
                signalStrength = 2.0,
                attributionState = AttributionState.UNKNOWN,
            ),
            text = "…any station on frequency, this is",
            writeAudio = false,
        )

        // -- 5. First-heard station (the "NEW" badge is a display concern over this fact — the ------
        //       station row's own firstHeardAt is what makes it checkable). ------------------------
        val tx5 = "$sessionId-tx05"
        val t5 = offset(4.0)
        insertTx(
            db,
            context,
            ScenarioFixtures.transmission(
                id = tx5, sessionId = sessionId, startedAtUtc = t5, samplePosition = nextSample(),
                frequencyHz = FREQ_B, signalStrength = 9.0,
                attributionState = AttributionState.CONFIRMED, stationId = "WA7HJR", attributionConfidence = 0.9,
            ),
            text = "whiskey alpha seven hotel juliet romeo at park kilo dash four four one two",
            writeAudio = true,
        )
        db.catalogDao().insert(
            StationEntity(
                id = "WA7HJR", callsign = "WA7HJR", firstHeardAt = t5, lastHeardAt = t5, transmissionCount = 1,
                isUserPinned = false, notes = null, userName = null, frequenciesHeard = listOf(FREQ_B),
                activityByHourDow = null, potaRefs = listOf("K-4412"), spokenGrids = null,
                ituRegionFromPrefix = null, overCountsByAttributionState = null,
            ),
        )

        // -- 6. Corrected: a human overrode the machine's INFERRED guess. --------------------------
        val tx6 = "$sessionId-tx06"
        val t6 = offset(5.0)
        insertTx(
            db,
            context,
            ScenarioFixtures.transmission(
                id = tx6, sessionId = sessionId, startedAtUtc = t6, samplePosition = nextSample(),
                frequencyHz = FREQ_B, signalStrength = 6.0,
                attributionState = AttributionState.INFERRED, stationId = "KJ7ABC", attributionConfidence = null,
                corrected = true,
            ),
            text = "kilo juliet seven alpha bravo charlie, back to you",
            writeAudio = true,
        )
        db.catalogDao().insert(
            CorrectionEntity(
                id = "$tx6-correction1",
                transmissionId = tx6,
                field = CorrectionDao.FIELD_STATION,
                previousValue = "K7LWH",
                newValue = "KJ7ABC",
                correctedAt = t6 + 30_000L,
                propagatedToCount = 1,
            ),
        )

        // -- 7. Revised: Pass A partial superseded by Pass B's final. ------------------------------
        val tx7 = "$sessionId-tx07"
        val t7 = offset(6.0)
        val tx7entity = ScenarioFixtures.transmission(
            id = tx7, sessionId = sessionId, startedAtUtc = t7, samplePosition = nextSample(),
            frequencyHz = FREQ_B, signalStrength = 8.0,
            attributionState = AttributionState.CONFIRMED, stationId = "W7NPC", attributionConfidence = 0.93,
        )
        db.transmissionDao().insert(tx7entity)
        ScenarioFixtures.writeAudioFixture(context, tx7entity)
        db.transcriptDao().supersede(
            ScenarioFixtures.transcript(
                "$tx7-t1",
                tx7,
                "and we're clear on the repeater, seven th",
                pass = TranscriptPass.A,
                isCurrent = true,
                createdAt = t7 + 500L,
                confidence = null,
            ),
        )
        db.transcriptDao().supersede(
            ScenarioFixtures.transcript(
                "$tx7-t2",
                tx7,
                "and we're clear on the repeater, seven three",
                isCurrent = true,
                createdAt =
                t7 + 3_000L,
            ),
        )

        // -- 8. Rejected: retained, not hidden (P9). ------------------------------------------------
        // R-242 (V3 pass 2): the real write path (`DataPassBResultSink`) always writes
        // `"$rule: $detail"` — a bare "squelch tail" has no `": "` separator, so
        // `LogItemsMapper.whyFor` (honestly) never invents a why-line for it. Real rule token, so
        // the dedicated Rejected view's second line actually renders.
        val tx8 = "$sessionId-tx08"
        val t8 = offset(6.6)
        insertTx(
            db,
            context,
            ScenarioFixtures.transmission(
                id = tx8, sessionId = sessionId, startedAtUtc = t8, samplePosition = nextSample(),
                durationMs = 400L,
                frequencyHz = FREQ_B, signalStrength = null, attributionState = AttributionState.UNKNOWN,
                processingState = TransmissionState.REJECTED,
                rejectionReason = "VAD_NO_SPEECH: squelch tail, 0.4 s",
            ),
            text = null,
            writeAudio = true,
        )

        // -- 9. A capture gap: 38 seconds. ----------------------------------------------------------
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "$sessionId-gap1",
                sessionId = sessionId,
                startedAt = offset(7.0),
                endedAt = offset(7.0) + 38_000L,
                cause = CaptureGapCause.INTERRUPTION,
                recoveredAutomatically = true,
            ),
        )
        if (extraGap) {
            // `gap-call` — WP11a (register R-106) added CaptureGapCause.CALL for exactly this:
            // "not listening · 38 s · incoming call" (Fail-Call.dc.html's own wording).
            db.captureGapDao().insert(
                CaptureGapEntity(
                    id = "$sessionId-gap2",
                    sessionId = sessionId,
                    startedAt = offset(45.0),
                    endedAt = offset(45.0) + 52_000L,
                    cause = CaptureGapCause.CALL,
                    recoveredAutomatically = true,
                ),
            )
        }

        // -- 10. A QSO thread: four overs, two stations, one shared threadId. ----------------------
        val threadId = "$sessionId-thread1"
        val qsoStations = listOf("W7NPC", "K7LWH")
        val qsoTimes = listOf(offset(10.0), offset(10.5), offset(11.0), offset(11.5))
        qsoTimes.forEachIndexed { i, t ->
            val id = "$sessionId-qso${i + 1}"
            val station = qsoStations[i % 2]
            val state = if (i % 2 == 0) AttributionState.CONFIRMED else AttributionState.INFERRED
            insertTx(
                db,
                context,
                ScenarioFixtures.transmission(
                    id = id, sessionId = sessionId, threadId = threadId,
                    startedAtUtc = t, samplePosition = nextSample(),
                    frequencyHz = FREQ_A, signalStrength = 6.0 + i,
                    attributionState = state, stationId = station, attributionConfidence = 0.85,
                    attributionSourceTransmissionId = if (state == AttributionState.INFERRED) tx1 else null,
                ),
                text = "over $i on the QSO — copy?",
                writeAudio = i % 2 == 0,
            )
        }
        db.catalogDao().insert(
            ThreadEntity(
                id = threadId, sessionId = sessionId, startedAt = qsoTimes.first(), endedAt = qsoTimes.last() + 4_200L,
                frequencyHz = FREQ_A, transmissionCount = qsoTimes.size, participantStationIds = qsoStations,
                digestText = null, kind = ThreadKind.QSO, kindSource = ThreadKindSource.DETECTED,
                participantOrder = qsoStations,
            ),
        )

        // -- Filler: spread the rest of the ~40 overs across the whole 6h42m window. ---------------
        val fillerCount = 30
        val fillerStartMinutes = 15.0
        val fillerEndMinutes = SESSION_DURATION_MILLIS / 60_000.0 - 5.0
        val step = (fillerEndMinutes - fillerStartMinutes) / fillerCount
        val phrases = listOf(
            "QRZ, this is",
            "copy, back to the repeater",
            "seven three, good signal tonight",
            "any traffic for the net",
            "monitoring on this frequency",
            "thanks for the contact",
        )
        repeat(fillerCount) { i ->
            val minutes = fillerStartMinutes + step * i
            val t = offset(minutes)
            val station = ScenarioFixtures.CALLSIGNS[i % ScenarioFixtures.CALLSIGNS.size]
            val freq = if (i % 2 == 0) FREQ_A else FREQ_B
            val isConfirmed = i % 3 != 0
            val id = "$sessionId-fill${i + 1}"
            insertTx(
                db,
                context,
                ScenarioFixtures.transmission(
                    id = id, sessionId = sessionId, startedAtUtc = t, samplePosition = nextSample(),
                    frequencyHz = freq, signalStrength = (2 + (i % 8)).toDouble(),
                    attributionState = if (isConfirmed) AttributionState.CONFIRMED else AttributionState.INFERRED,
                    stationId = station, attributionConfidence = if (isConfirmed) 0.9 else 0.7,
                    attributionSourceTransmissionId = if (!isConfirmed) tx1 else null,
                ),
                text = "${phrases[i % phrases.size]} $station",
                writeAudio = i % 2 == 0,
            )
        }

        if (live) {
            // Marked *after* every insert above, not before: `markCapturing` also writes a fresh
            // heartbeat (liveness proven by heartbeat, never by battery-exemption APIs — see
            // AGENTS.md), and `sample` is by then the real high-water mark this "session" reached,
            // not zero.
            ScenarioFixtures.markCapturing(context, sessionId, samplePosition = sample)
        }

        val txCount = db.transmissionDao().listBySession(sessionId).size
        return Scenarios.LoadResult(transmissionCount = txCount, sessionCount = 1, primarySessionId = sessionId)
    }

    /** Inserts [entity], its (optional) current transcript and (optional) audio fixture — the common shape every over here shares. */
    private suspend fun insertTx(
        db: OrtDatabase,
        context: Context,
        entity: TransmissionEntity,
        text: String?,
        writeAudio: Boolean,
    ) {
        db.transmissionDao().insert(entity)
        if (text != null) {
            db.transcriptDao().insert(
                ScenarioFixtures.transcript(
                    "${entity.id}-t1",
                    entity.id,
                    text,
                    isCurrent = true,
                    createdAt =
                    entity.startedAtUtc + 500L,
                ),
            )
        }
        if (writeAudio) ScenarioFixtures.writeAudioFixture(context, entity)
    }
}
