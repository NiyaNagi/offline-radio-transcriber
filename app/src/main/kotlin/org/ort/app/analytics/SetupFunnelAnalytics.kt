package org.ort.app.analytics

import org.ort.telemetry.AnalyticsEventFactory
import org.ort.telemetry.AnalyticsTier1Payload

/**
 * FR-ANL-2's "the setup funnel, including model-download outcomes (FR-AST-11)" tier-1 stat. A
 * thin call-site helper so `SetupActivity` (`ui/setup`, restricted by this change's own file
 * ownership to "adding a submit call" and nothing else) needs exactly one line per step
 * transition, never any step-tracking logic of its own — the throttling, if any were needed,
 * and the event construction both live here instead.
 *
 * [step] is always a [org.ort.app.ui.setup.SetupStep]'s own `name` — the closed enum FR-ANL-2
 * already requires ("never their content"), never a free-text description of what the operator
 * did on that screen.
 */
public object SetupFunnelAnalytics {

    /** A step was newly shown to the operator. */
    public fun reached(step: String) {
        submit(step, OUTCOME_REACHED)
    }

    /** The operator moved past a step by completing whatever it asked for. */
    public fun completed(step: String) {
        submit(step, OUTCOME_COMPLETED)
    }

    /** The operator moved past a step without completing what it offered (declined, skipped, or
     * chose the "not now" branch). */
    public fun skipped(step: String) {
        submit(step, OUTCOME_SKIPPED)
    }

    /** FR-AST-11: the outcome of one model download attempted from the `MODELS` step — reported
     * from wherever the download itself actually resolves ([org.ort.app.work.ModelDownloadWorker],
     * not `SetupActivity`, since the step is not required to still be on screen when a background
     * download finishes). */
    public fun modelDownloadOutcome(succeeded: Boolean, retrying: Boolean = false) {
        val outcome = when {
            succeeded -> OUTCOME_DOWNLOAD_SUCCEEDED
            retrying -> OUTCOME_DOWNLOAD_RETRY
            else -> OUTCOME_DOWNLOAD_FAILED
        }
        submit(STEP_MODELS, outcome)
    }

    private fun submit(step: String, outcome: String) {
        AnalyticsAppWiring.submitSafely {
            AnalyticsEventFactory.tier1(
                AnalyticsAppWiring.baseProvenance(),
                AnalyticsTier1Payload.SetupFunnel(step = step, outcome = outcome),
            )
        }
    }

    private const val STEP_MODELS = "MODELS"
    private const val OUTCOME_REACHED = "reached"
    private const val OUTCOME_COMPLETED = "completed"
    private const val OUTCOME_SKIPPED = "skipped"
    private const val OUTCOME_DOWNLOAD_SUCCEEDED = "download_succeeded"
    private const val OUTCOME_DOWNLOAD_FAILED = "download_failed"
    private const val OUTCOME_DOWNLOAD_RETRY = "download_retry"
}
