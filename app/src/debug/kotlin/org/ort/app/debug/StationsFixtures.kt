package org.ort.app.debug

import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.LatticeSource
import org.ort.data.entity.StationEntity
import org.ort.data.entity.VoiceprintEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * `stations-14-nights` (spec/ui-conformance-plan.md §E's V5 set) — fourteen nights over two
 * calendar weeks, in the **device's own local zone** (matching `ActivityPatternMapper`'s own
 * `ZoneId.systemDefault()` bucketing, audit F-019/F-001), with a realistic hour-of-day/day-of-week
 * spread across [STATIONS], a within-night "hatched" (not-listening) gap on most nights, and one
 * whole calendar day with **no session at all** — real absence, not a fabricated quiet night, so
 * `Station-Pattern.dc.html`'s and `Frequencies.dc.html`'s not-listening hatch and sparklines have
 * something honest to draw.
 */
internal object StationsFixtures {

    private const val FREQ_A = 145_230_000L
    private const val FREQ_B = 146_960_000L

    /** The whole-day skip — 7 nights back from "today" is never captured at all. */
    private const val SKIPPED_DAY_OFFSET = 7

    /** Comfortably past the largest per-night `txPerNight` (4-6) any session's own overs use, so
     * the R-184 fixture's `samplePosition` never collides with one of them. */
    private const val AMBIGUOUS_SAMPLE_POSITION = 100L

    /** R-272: the one station this scenario gives two voiceprint clusters, so `Station-Identity`'s
     * Split branch (`StationPolling.voiceSplitCandidates`) has something real to split — see the
     * loop below for how its overs are actually divided between them. */
    private const val SPLIT_STATION = "WA7HJR"
    private const val SPLIT_VOICEPRINT_A = "voiceprint-wa7hjr-a"
    private const val SPLIT_VOICEPRINT_B = "voiceprint-wa7hjr-b"

    suspend fun stations14Nights(db: OrtDatabase): Scenarios.LoadResult {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val stations = ScenarioFixtures.CALLSIGNS
        val firstHeard = mutableMapOf<String, Long>()
        val lastHeard = mutableMapOf<String, Long>()
        val heardCount = mutableMapOf<String, Int>()

        var sessionCount = 0
        var transmissionCount = 0
        var primarySessionId: String? = null
        var primarySessionStart: Long? = null
        // R-272: every third `SPLIT_STATION` over goes to the smaller of two voiceprint clusters,
        // the rest to the larger — real counts, tallied as they are actually assigned below, not
        // guessed from the round-robin station spread in advance.
        var splitStationOverIndex = 0
        var splitVoiceprintACount = 0
        var splitVoiceprintBCount = 0

        // 1..15 minus the one skipped day yields exactly the fourteen sessions this scenario
        // promises by name, spanning a fifteen-day window - "two weeks and the missed night".
        for (dayOffset in 1..15) {
            if (dayOffset == SKIPPED_DAY_OFFSET) continue // the whole day never listened
            val date = today.minusDays(dayOffset.toLong())
            val eveningHour = 19 + (dayOffset % 4) // spreads sessions across 19:00-22:00 local
            val startMinute = (dayOffset * 7) % 60
            val startMillis = date.atTime(eveningHour, startMinute).atZone(zone).toInstant().toEpochMilli()
            val durationHours = 2 + (dayOffset % 3) // 2-4 hours, a realistic evening watch
            val endMillis = startMillis + durationHours * 3_600_000L

            val sessionId = ScenarioFixtures.sessionId("stations14", "night$dayOffset")
            db.sessionDao().insert(ScenarioFixtures.session(sessionId, startedAt = startMillis, endedAt = endMillis))
            sessionCount++
            if (primarySessionId == null) {
                primarySessionId = sessionId
                primarySessionStart = startMillis
            }

            // A within-night hatch: a real gap most nights, so a single evening is not read as
            // wall-to-wall listening either (FR-UI-12: "not heard" vs "not listening").
            if (dayOffset % 4 != 0) {
                val gapStart = startMillis + 40 * 60_000L
                db.captureGapDao().insert(
                    CaptureGapEntity(
                        id = "$sessionId-gap1",
                        sessionId = sessionId,
                        startedAt = gapStart,
                        endedAt = gapStart + 22 * 60_000L,
                        cause = CaptureGapCause.ROUTE_CHANGE,
                        recoveredAutomatically = true,
                    ),
                )
            }

            val txPerNight = 4 + (dayOffset % 3) // 4-6 overs/night
            var sample = 0L
            repeat(txPerNight) { i ->
                val station = stations[(dayOffset + i) % stations.size]
                val spacing = (durationHours * 3_600_000L) / (txPerNight + 1)
                val t = startMillis + spacing * (i + 1)
                val id = "$sessionId-tx${i + 1}"
                sample += 1
                val voiceprintId = if (station == SPLIT_STATION) {
                    val isMinorityCluster = splitStationOverIndex % 3 == 2
                    splitStationOverIndex++
                    if (isMinorityCluster) {
                        splitVoiceprintBCount++
                        SPLIT_VOICEPRINT_B
                    } else {
                        splitVoiceprintACount++
                        SPLIT_VOICEPRINT_A
                    }
                } else {
                    null
                }
                db.transmissionDao().insert(
                    ScenarioFixtures.transmission(
                        id = id, sessionId = sessionId, startedAtUtc = t, samplePosition = sample,
                        frequencyHz = if (i % 2 == 0) FREQ_A else FREQ_B, signalStrength = (2 + (i % 8)).toDouble(),
                        attributionState = AttributionState.CONFIRMED, stationId = station, attributionConfidence = 0.9,
                        voiceprintId = voiceprintId,
                    ),
                )
                db.transcriptDao().insert(
                    ScenarioFixtures.transcript(
                        "$id-t1",
                        id,
                        "this is $station, checking in",
                        isCurrent = true,
                        createdAt = t + 500L,
                    ),
                )
                transmissionCount++
                firstHeard[station] = minOf(firstHeard[station] ?: t, t)
                lastHeard[station] = maxOf(lastHeard[station] ?: t, t)
                heardCount[station] = (heardCount[station] ?: 0) + 1
            }
        }

        // R-184: at least one over whose resolver result carries three ranked, distinctly-scored
        // Tier A candidates — `overnight/D08-correct-a.png`'s own repro (WP6's derivation was
        // already verified by test; the zero-rows-on-device symptom was the fixture, per WP6's own
        // finding a9c24c6) needs more than one real fixture to prove `CorrectionSheet`'s Tier A row
        // is not a one-scenario fluke. Same trio, same shape as `OvernightScenario`'s own AMBIGUOUS
        // over (KE7QRS/KE7QRF, which R-240 already relies on, plus KE7QRZ) — added onto the primary
        // session rather than a fresh one, so it is reachable from this scenario's own default
        // session without adding a fifteenth night.
        val ambiguousSessionId = requireNotNull(primarySessionId)
        val ambiguousStart = requireNotNull(primarySessionStart) + 15 * 60_000L
        val ambiguousTxId = "$ambiguousSessionId-tx-ambiguous"
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = ambiguousTxId,
                sessionId = ambiguousSessionId,
                startedAtUtc = ambiguousStart,
                samplePosition = AMBIGUOUS_SAMPLE_POSITION,
                frequencyHz = FREQ_A,
                signalStrength = 3.0,
                attributionState = AttributionState.AMBIGUOUS,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                "$ambiguousTxId-t1",
                ambiguousTxId,
                "kilo echo seven quebec romeo sierra, portable",
                isCurrent = true,
                createdAt = ambiguousStart + 500L,
            ),
        )
        // R-421: TEXT_DERIVED, mirroring `OvernightScenario`'s own identical AMBIGUOUS over — see
        // that scenario's own comment for why `selected` stays `false` (an AMBIGUOUS over has no
        // winner to highlight inline; `AmbiguousCandidatesFixtureTest` already establishes this).
        db.catalogDao().insert(
            ScenarioFixtures.lattice(
                "$ambiguousTxId-lat",
                ambiguousTxId,
                source = LatticeSource.TEXT_DERIVED,
                createdAt =
                ambiguousStart + 500L,
            ),
        )
        db.catalogDao().insert(
            ScenarioFixtures.candidate(
                "$ambiguousTxId-c1",
                ambiguousTxId,
                "KE7QRS",
                rank = 0,
                score = 8.20,
                selected = false,
            ),
        )
        db.catalogDao().insert(
            ScenarioFixtures.candidate(
                "$ambiguousTxId-c2",
                ambiguousTxId,
                "KE7QRF",
                rank = 1,
                score = 8.05,
                selected = false,
            ),
        )
        db.catalogDao().insert(
            ScenarioFixtures.candidate(
                "$ambiguousTxId-c3",
                ambiguousTxId,
                "KE7QRZ",
                rank = 2,
                score = 7.90,
                selected = false,
            ),
        )
        // R-421: D03's transcript highlight reads the `selected` candidate's own slots.
        ScenarioFixtures.latticeSlots(
            transmissionId = ambiguousTxId,
            candidateId = "$ambiguousTxId-c1",
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
        transmissionCount++

        // R-272: two real voiceprint clusters bound to SPLIT_STATION, each carrying the exact
        // count of overs actually assigned to it above — never a fabricated member count. WP8's
        // `voiceSplitCandidates` picks the larger cluster (`maxByOrNull { it.memberCount }`) as the
        // one Split shows overs from; both are seeded here regardless, so `Station-Identity`'s own
        // multi-cluster summary (`Fail-Cluster.dc.html`) has two real rows to render.
        check(splitVoiceprintACount > 0 && splitVoiceprintBCount > 0) {
            "expected $SPLIT_STATION to have overs in both voiceprint clusters; got " +
                "A=$splitVoiceprintACount, B=$splitVoiceprintBCount"
        }
        db.catalogDao().insert(
            VoiceprintEntity(
                id = SPLIT_VOICEPRINT_A,
                embedding = ByteArray(0),
                memberCount = splitVoiceprintACount,
                centroidUpdatedAt = lastHeard[SPLIT_STATION],
                boundStationId = SPLIT_STATION,
                bindingConfidence = 0.91,
                lastConfirmedAt = lastHeard[SPLIT_STATION],
                isEnrolled = false,
                enrolmentObservationCount = 0,
                enrolmentSessionIds = null,
                enrolledAt = null,
                lastMatchedAt = lastHeard[SPLIT_STATION],
                bindingSource = null,
                embeddingModelId = null,
                embeddingModelVersion = null,
            ),
        )
        db.catalogDao().insert(
            VoiceprintEntity(
                id = SPLIT_VOICEPRINT_B,
                embedding = ByteArray(0),
                memberCount = splitVoiceprintBCount,
                centroidUpdatedAt = lastHeard[SPLIT_STATION],
                boundStationId = SPLIT_STATION,
                bindingConfidence = 0.78,
                lastConfirmedAt = lastHeard[SPLIT_STATION],
                isEnrolled = false,
                enrolmentObservationCount = 0,
                enrolmentSessionIds = null,
                enrolledAt = null,
                lastMatchedAt = lastHeard[SPLIT_STATION],
                bindingSource = null,
                embeddingModelId = null,
                embeddingModelVersion = null,
            ),
        )

        stations.forEach { station ->
            val first = firstHeard[station] ?: return@forEach
            db.catalogDao().insert(
                StationEntity(
                    id = station, callsign = station, firstHeardAt = first, lastHeardAt = lastHeard[station],
                    transmissionCount = heardCount[station] ?: 0, isUserPinned = false, notes = null, userName = null,
                    frequenciesHeard = listOf(FREQ_A, FREQ_B), activityByHourDow = null, potaRefs = null,
                    spokenGrids = null, ituRegionFromPrefix = null, overCountsByAttributionState = null,
                ),
            )
        }

        return Scenarios.LoadResult(transmissionCount, sessionCount, primarySessionId)
    }
}
