package org.ort.app.debug

import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.StationEntity
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
            if (primarySessionId == null) primarySessionId = sessionId

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
                db.transmissionDao().insert(
                    ScenarioFixtures.transmission(
                        id = id, sessionId = sessionId, startedAtUtc = t, samplePosition = sample,
                        frequencyHz = if (i % 2 == 0) FREQ_A else FREQ_B, signalStrength = (2 + (i % 8)).toDouble(),
                        attributionState = AttributionState.CONFIRMED, stationId = station, attributionConfidence = 0.9,
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
