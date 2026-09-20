package org.ort.app.analytics

import android.content.Context
import org.ort.data.OrtDatabase
import org.ort.telemetry.AnalyticsEventFactory

/**
 * FR-ANL-2's aggregate transcript-quality statistics, computed fresh from `:data` and submitted as
 * one tier-1 `QualityStats` event per run — driven by [org.ort.app.analytics.AnalyticsUploadWorker]'s
 * own periodic chain (FR-ANL-7's 6-hour cycle), never on the audio frame path and never blocking
 * capture: a database read on a background worker, the same place
 * [org.ort.pipeline.digest.ProseDigestRunner] already does comparable `:data` aggregation work.
 * [QualityStatsAggregator] does the actual arithmetic and is tested independently of this Android
 * wiring.
 */
public object QualityStatsReporter {

    public suspend fun reportOnce(context: Context) {
        val db = OrtDatabase.create(context.applicationContext)
        val transmissions = db.transmissionDao().listAll()
        val correctionFields = db.correctionDao().listAllFields()
        val stats = QualityStatsAggregator.aggregate(transmissions, correctionFields) ?: return
        AnalyticsAppWiring.submitSafely { AnalyticsEventFactory.tier1(AnalyticsAppWiring.baseProvenance(), stats) }
    }
}
