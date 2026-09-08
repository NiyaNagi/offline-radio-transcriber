package org.ort.app.ui.failures

import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus

/** One recovery toast, [id] stable per kind so [FailureHost] can key a Compose list on it. */
public data class RecoveryToast(public val id: String, public val message: String)

/**
 * R-103, `Flow-Degrade.dc.html`'s own closing note: "Recovery is announced too... because silence
 * after a warning reads as 'still broken'." [diff] is a pure function of two consecutive
 * [FailureSignals] snapshots — no polling, no `Context`, no Compose — so it is tested the same way
 * [FailureMapper.map] is, and [FailureHost] is the only caller that ever feeds it consecutive
 * reads. One toast per transition, never a stacked "everything is fine now" summary, matching
 * guide §Feedback's "every propagating action gets one" for a *recovery* rather than a correction.
 */
public object RecoveryAnnouncer {

    public fun diff(
        previous: FailureSignals,
        current: FailureSignals,
        improvableCount: Int? = null,
    ): List<RecoveryToast> {
        val toasts = mutableListOf<RecoveryToast>()

        val wasDegradedTier = previous.shedLevel > 0 || previous.thermalStatus !is ThermalStatus.State.Nominal
        val isNominalTier = current.shedLevel == 0 && current.thermalStatus is ThermalStatus.State.Nominal
        if (wasDegradedTier && isNominalTier) {
            val suffix = if (improvableCount != null && improvableCount > 0) {
                " · $improvableCount overs can be improved"
            } else {
                ""
            }
            toasts += RecoveryToast("tier", "Back to tier 3$suffix")
        }

        if (previous.rigStatus is RigStatus.State.Stale && current.rigStatus is RigStatus.State.Connected) {
            toasts += RecoveryToast("rig", "Radio reconnected")
        }

        val wasInputTrouble =
            previous.inputStatus is InputStatus.State.Lost || previous.inputStatus is InputStatus.State.Mismatch
        if (wasInputTrouble && current.inputStatus is InputStatus.State.Opened) {
            toasts += RecoveryToast("input", "Input back")
        }

        val wasStorageLow = previous.storageForecast is StorageForecast.State.OneNightLeft ||
            previous.storageForecast is StorageForecast.State.ThreeNightsLeft ||
            previous.storageForecast is StorageForecast.State.AtFloor
        if (wasStorageLow && current.storageForecast is StorageForecast.State.Fine) {
            toasts += RecoveryToast("storage", "Storage back above the floor")
        }

        return toasts
    }
}
