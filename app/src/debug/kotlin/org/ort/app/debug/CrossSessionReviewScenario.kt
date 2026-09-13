package org.ort.app.debug

import android.content.Context
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase

/**
 * Register R-1055 (spec, coordinator device-evidence ask): reproduces the validator's own real
 * shape on a device — a live, over-less capture session running *while* the operator reviews an
 * earlier, already-ended night's overs (the normal overnight shape: capture runs while the
 * operator improves an earlier night). Built from two existing, already-tested primitives rather
 * than a third, parallel one: [OvernightScenario.overnight] seeds the real ~40-over
 * `scenario-overnight` session completely unchanged (so `logFilterTransmissionIds` in `tour.json`
 * can name its own real, literal ids — `scenario-overnight-tx02` etc., the same ones
 * `overnight/L01-log-filtered-overs` already uses), and this scenario's own live session is the
 * identical "running, genuinely empty" shape `Scenarios.firstSession` already establishes for
 * `first-session` (`ScenarioFixtures.markCapturing`, zero transmissions) — under this scenario's
 * own id, so [Scenarios.LoadResult.primarySessionId] (the id `LogContent`'s own `sessionId`
 * parameter receives) names the *live* session, never the earlier one the curated filter actually
 * points at.
 */
internal object CrossSessionReviewScenario {

    suspend fun load(context: Context, db: OrtDatabase): Scenarios.LoadResult {
        val earlier = OvernightScenario.overnight(context, db)

        val liveSessionId = ScenarioFixtures.sessionId("cross-session-review")
        val startedAt = SystemClock.wallMillis() - 5 * 60_000L
        db.sessionDao().insert(ScenarioFixtures.session(liveSessionId, startedAt = startedAt, endedAt = null))
        ScenarioFixtures.markCapturing(context, liveSessionId)

        return Scenarios.LoadResult(
            transmissionCount = earlier.transmissionCount,
            sessionCount = earlier.sessionCount + 1,
            primarySessionId = liveSessionId,
        )
    }
}
