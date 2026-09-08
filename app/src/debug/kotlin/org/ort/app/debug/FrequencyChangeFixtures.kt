package org.ort.app.debug

import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.StationEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * `frequency-change` (register R-216/R-074): a busier-than-usual night on [FREQ_A] so
 * `Frequency-Change.dc.html` (FQ03, reached from `Frequencies`'/`Frequency`'s own amber "busier
 * than usual" affordance) is actually reachable on the emulator — V5 @f8430b8 found no scenario
 * seeded one, so FQ03 was unverifiable. Ten quiet prior nights at ~2 overs each on [FREQ_A] set
 * [org.ort.app.ui.data.NightlyDeparture]'s "usual" average low; tonight gets twenty, more than
 * `2 x usual`, which is exactly [org.ort.app.ui.data.NightlyDeparture.isBusierThanUsual]'s own
 * threshold. Tonight also carries a station heard for the first time (a real "first time heard"
 * cause) and a few `UNKNOWN`-attribution overs (a real "unidentified voices" cause), so
 * `FrequencyPolling.frequencyChange`'s `causes` list is not empty either.
 */
internal object FrequencyChangeFixtures {

    private const val FREQ_A = 145_230_000L
    private const val FREQ_B = 146_960_000L
    private const val NEW_STATION = "KE7QRS"

    suspend fun frequencyChange(db: OrtDatabase): Scenarios.LoadResult {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val regulars = listOf("W7NPC", "KJ7ABC")
        val firstHeard = mutableMapOf<String, Long>()
        val lastHeard = mutableMapOf<String, Long>()
        val heardCount = mutableMapOf<String, Int>()

        var sessionCount = 0
        var transmissionCount = 0
        var primarySessionId: String? = null

        fun recordStation(station: String, at: Long) {
            firstHeard[station] = minOf(firstHeard[station] ?: at, at)
            lastHeard[station] = maxOf(lastHeard[station] ?: at, at)
            heardCount[station] = (heardCount[station] ?: 0) + 1
        }

        // Ten quiet prior nights — a real "usual" for NightlyDeparture to compare against.
        for (dayOffset in 10 downTo 1) {
            val date = today.minusDays(dayOffset.toLong())
            val startMillis = date.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
            val endMillis = startMillis + 2 * 3_600_000L
            val sessionId = ScenarioFixtures.sessionId("freqchange", "night$dayOffset")
            db.sessionDao().insert(ScenarioFixtures.session(sessionId, startedAt = startMillis, endedAt = endMillis))
            sessionCount++

            repeat(2) { i ->
                val station = regulars[i % regulars.size]
                val t = startMillis + (i + 1) * 20 * 60_000L
                val id = "$sessionId-tx${i + 1}"
                db.transmissionDao().insert(
                    ScenarioFixtures.transmission(
                        id = id,
                        sessionId = sessionId,
                        startedAtUtc = t,
                        samplePosition = (i + 1).toLong(),
                        frequencyHz = FREQ_A,
                        attributionState = AttributionState.CONFIRMED,
                        stationId = station,
                        attributionConfidence = 0.9,
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
                recordStation(station, t)
            }
        }

        // Tonight — the busy, most recent session: twenty overs on FREQ_A (more than double the
        // ~2/night usual), a station heard for the first time, and unidentified weak activity.
        val tonightStart = today.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val tonightSessionId = ScenarioFixtures.sessionId("freqchange", "tonight")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                tonightSessionId,
                startedAt = tonightStart,
                endedAt = tonightStart + 2 * 3_600_000L,
            ),
        )
        sessionCount++
        primarySessionId = tonightSessionId

        repeat(17) { i ->
            val station = regulars[i % regulars.size]
            val t = tonightStart + (i + 1) * 5 * 60_000L
            val id = "$tonightSessionId-tx${i + 1}"
            db.transmissionDao().insert(
                ScenarioFixtures.transmission(
                    id = id,
                    sessionId = tonightSessionId,
                    startedAtUtc = t,
                    samplePosition = (i + 1).toLong(),
                    frequencyHz = FREQ_A,
                    attributionState = AttributionState.CONFIRMED,
                    stationId = station,
                    attributionConfidence = 0.9,
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
            recordStation(station, t)
        }

        // A station heard for the first time tonight — a real "first time heard" cause.
        run {
            val t = tonightStart + 18 * 5 * 60_000L
            val id = "$tonightSessionId-tx-new"
            db.transmissionDao().insert(
                ScenarioFixtures.transmission(
                    id = id,
                    sessionId = tonightSessionId,
                    startedAtUtc = t,
                    samplePosition = 18L,
                    frequencyHz = FREQ_A,
                    attributionState = AttributionState.CONFIRMED,
                    stationId = NEW_STATION,
                    attributionConfidence = 0.85,
                ),
            )
            db.transcriptDao().insert(
                ScenarioFixtures.transcript(
                    "$id-t1",
                    id,
                    "this is $NEW_STATION, first time on this repeater",
                    isCurrent = true,
                    createdAt = t + 500L,
                ),
            )
            transmissionCount++
            recordStation(NEW_STATION, t)
        }

        // Weak, unidentified activity tonight — a real "unidentified voices" cause.
        repeat(2) { i ->
            val t = tonightStart + (19 + i) * 5 * 60_000L
            val id = "$tonightSessionId-tx-unk${i + 1}"
            db.transmissionDao().insert(
                ScenarioFixtures.transmission(
                    id = id,
                    sessionId = tonightSessionId,
                    startedAtUtc = t,
                    samplePosition = (19 + i).toLong(),
                    frequencyHz = FREQ_A,
                    attributionState = AttributionState.UNKNOWN,
                    signalStrength = 1.5,
                ),
            )
            db.transcriptDao().insert(
                ScenarioFixtures.transcript(
                    "$id-t1",
                    id,
                    "[unintelligible]",
                    isCurrent = true,
                    createdAt = t + 500L,
                ),
            )
            transmissionCount++
        }

        // A quiet second frequency exists too, so this scenario also exercises the ordinary
        // (not busier-than-usual) Frequencies/Frequency read path, not just FQ03.
        run {
            val t = tonightStart + 25 * 5 * 60_000L
            val id = "$tonightSessionId-tx-freqb"
            db.transmissionDao().insert(
                ScenarioFixtures.transmission(
                    id = id,
                    sessionId = tonightSessionId,
                    startedAtUtc = t,
                    samplePosition = 25L,
                    frequencyHz = FREQ_B,
                    attributionState = AttributionState.CONFIRMED,
                    stationId = regulars[0],
                    attributionConfidence = 0.9,
                ),
            )
            transmissionCount++
            recordStation(regulars[0], t)
        }

        (regulars + NEW_STATION).distinct().forEach { station ->
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
