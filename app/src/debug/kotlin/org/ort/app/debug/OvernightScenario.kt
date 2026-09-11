package org.ort.app.debug

import android.content.Context
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionId
import org.ort.core.TransmissionState
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind
import org.ort.data.OrtDatabase
import org.ort.data.dao.CorrectionDao
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.LatticeSlotEntity
import org.ort.data.entity.LatticeSource
import org.ort.data.entity.StationEntity
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.rig.descriptor.BundledDescriptors

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

    /**
     * `gap-call` — `overnight` plus a second gap. R-410 (register, WP12's own tour finding): F15
     * (`Fail-Call.dc.html`)'s banner ([org.ort.app.ui.failures.FailureMapper.isRecentCallGap])
     * needs the session's own *newest* gap to be CALL-caused, closed within the last
     * [org.ort.app.ui.failures.FailureMapper.RECENT_GAP_WINDOW_MILLIS] (5 minutes) of real
     * wall-clock time, **and** the session still genuinely capturing — three real facts a scenario
     * built to have already *ended* (this fixture's own base shape, `overnight`) can never satisfy
     * at once, no matter how the extra gap is timed. `live = true` now, same primitive
     * [overnightLive] already established; the extra gap itself is anchored to real "now" instead
     * of the session's own start offset (every other over/gap in this fixture is), specifically so
     * it is still inside that 5-minute window whenever a validator or the tour actually loads this
     * scenario and looks. The Log row itself (`not listening · 52 s · incoming call`) renders from
     * this same [org.ort.data.entity.CaptureGapEntity] regardless of the session's own end state —
     * unaffected by this change (register: "the ended-session gap row is fine").
     */
    suspend fun gapCall(context: Context, db: OrtDatabase): Scenarios.LoadResult =
        build(context, db, scenarioName = "gap-call", extraGap = true, live = true)

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
            ScenarioFixtures.session(
                sessionId,
                startedAt = start,
                endedAt = if (live) null else end,
                // R-914 (register, reviewer B2 on run 3): DG04's Mode/Input/Rig-link rows read "not
                // tracked per session in this build" for a v10 session with only the v10 columns
                // seeded — a session row's own null v7 columns honestly mean "not recorded for this
                // session", never "not tracked in this build", so a scenario claiming a real USB
                // session (this fixture's own long-standing shape — `seedConfiguredDeviceState`
                // configures `CaptureConfigurationStore`/`InputStatus` to the identical usb-1 USB
                // route for `overnight`/`overnight-live`/`stations-14-nights`) must seed the v7
                // columns too, not just v10's.
                captureMode = CaptureMode.USB_RADIO.name,
                audioRouteKind = AudioRouteKind.USB.name,
                audioRouteLabel = "USB Audio Device",
                rigTransport = RigTransportKind.USB_SERIAL.name,
                // E2-A07 (schema v10): every variant here (`overnight`/`overnight-live`/`gap-call`)
                // is the same real TH-D75A overnight session, captured over a verified route.
                rigDescriptorId = BundledDescriptors.kenwoodThD75a().id,
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
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
        // R-421: TEXT_DERIVED, not the default ACOUSTIC — this lattice's own slots below carry a
        // real char span into the transcript (LatticeSlotEntity's own doc comment: an acoustic
        // lattice's slots never do), so D01's transcript highlight has a real span to render
        // instead of falling back to the literal-substring check the phonetic spelling defeats.
        db.catalogDao().insert(
            ScenarioFixtures.lattice("$tx1-lat", tx1, source = LatticeSource.TEXT_DERIVED, createdAt = t1 + 500L),
        )
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
        ScenarioFixtures.latticeSlots(
            transmissionId = tx1,
            candidateId = "$tx1-c1",
            transcriptText = "this is whiskey seven november papa charlie, monitoring",
            unitsAndWords = listOf(
                "W" to "whiskey",
                "7" to "seven",
                "N" to "november",
                "P" to "papa",
                "C" to "charlie",
            ),
        ).forEach { db.catalogDao().insert(it) }

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
        // R-771 (register, confirmation sweep): unlike tx1/tx3/tx6 below, tx2's own transcript
        // ("roger that, good copy on the repeater this morning") never spells K7LWH phonetically —
        // this over's attribution comes from a voice match against the station's own voiceprint,
        // not from what was said, which is the whole point of the INFERRED case D02 exists to show.
        // `ScenarioFixtures.latticeSlots(...)` cannot seed this: it *requires* each unit's spoken
        // word to be found as a real substring of the transcript (its own doc comment's invariant,
        // enforced by a `check()`), which is exactly the ACOUSTIC-not-TEXT_DERIVED shape this
        // lattice already correctly has (the `lattice(...)` call two lines above never passes
        // `source = TEXT_DERIVED`, unlike tx1/tx3/tx6's). So these rows are built directly instead,
        // `charStart`/`charEnd` both `null` — [org.ort.data.entity.LatticeSlotEntity]'s own doc
        // comment: exactly the state an acoustic lattice's slots carry, never a span into text that
        // was never derived from. Scores are real but deliberately weaker than tx1's confirmed
        // 0.98..0.90 run (matched by ear, on a weaker `signalStrength = 5.0` over, not confirmed by
        // what was heard) — still a real, ordered per-unit lattice for D02's grid to render, not a
        // copy of tx1's own stronger one.
        listOf("K" to "kilo", "7" to "seven", "L" to "lima", "W" to "whiskey", "H" to "hotel")
            .mapIndexed { index, (unit, _) ->
                LatticeSlotEntity(
                    id = "$tx2-c1-slot$index",
                    transmissionId = tx2,
                    candidateId = "$tx2-c1",
                    index = index,
                    unit = unit,
                    score = (0.74 - index * 0.03).coerceAtLeast(0.5),
                    keptAlternate = null,
                    charStart = null,
                    charEnd = null,
                )
            }
            .forEach { db.catalogDao().insert(it) }

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
        // R-421: TEXT_DERIVED — see tx1's own comment above for why. `selected` stays `false` on
        // every AMBIGUOUS candidate (unchanged — `AmbiguousCandidatesFixtureTest`'s own
        // `no candidate should be pre-selected on an AMBIGUOUS over` already establishes why): these
        // slots still give D05's `slotDetailsFor` (rank-then-index over *every* candidate,
        // register R-320) a real per-slot breakdown for c1, even though
        // `winningCandidateCharSpan`'s `cc.selected = 1` join honestly stays empty here — an
        // AMBIGUOUS over has no winner to highlight inline, which is the correct, not a fixture gap.
        db.catalogDao().insert(
            ScenarioFixtures.lattice("$tx3-lat", tx3, source = LatticeSource.TEXT_DERIVED, createdAt = t3 + 500L),
        )
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
        // R-421: D03's transcript highlight (CorrectionPolling.winningCharSpan) reads the
        // `selected` candidate's own slots — see c1's own comment above.
        ScenarioFixtures.latticeSlots(
            transmissionId = tx3,
            candidateId = "$tx3-c1",
            transcriptText = "kilo echo seven quebec romeo sierra, portable",
            unitsAndWords = listOf(
                "K" to "kilo",
                "E" to "echo",
                "7" to "seven",
                "Q" to "quebec",
                "R" to "romeo",
                "S" to "sierra",
            ),
        ).forEach { db.catalogDao().insert(it) }

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
        // R-421: tx6's own text ("kilo juliet seven alpha bravo charlie, back to you") already
        // phonetically self-identifies as KJ7ABC — the real INFERRED case D01/D03's highlight needs
        // (this over's own lattice+candidate rows never existed before this; every other field
        // above stays untouched, so the over is still INFERRED/corrected exactly as before).
        db.catalogDao().insert(
            ScenarioFixtures.lattice("$tx6-lat", tx6, source = LatticeSource.TEXT_DERIVED, createdAt = t6 + 500L),
        )
        db.catalogDao().insert(
            ScenarioFixtures.candidate("$tx6-c1", tx6, "KJ7ABC", rank = 0, score = 7.6, selected = true),
        )
        ScenarioFixtures.latticeSlots(
            transmissionId = tx6,
            candidateId = "$tx6-c1",
            transcriptText = "kilo juliet seven alpha bravo charlie, back to you",
            unitsAndWords = listOf(
                "K" to "kilo",
                "J" to "juliet",
                "7" to "seven",
                "A" to "alpha",
                "B" to "bravo",
                "C" to "charlie",
            ),
        ).forEach { db.catalogDao().insert(it) }

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
            // "not listening · 52 s · incoming call" (Fail-Call.dc.html's own wording). R-410: this
            // is now the session's own *newest* gap, anchored to real "now" (not the session's own
            // start offset — see this scenario's own kdoc), closed 90 s ago, comfortably inside
            // FailureMapper's own 5-minute recency window for the F15 banner.
            val recentCallGapEndedAt = SystemClock.wallMillis() - 90_000L
            db.captureGapDao().insert(
                CaptureGapEntity(
                    id = "$sessionId-gap2",
                    sessionId = sessionId,
                    startedAt = recentCallGapEndedAt - 52_000L,
                    endedAt = recentCallGapEndedAt,
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
