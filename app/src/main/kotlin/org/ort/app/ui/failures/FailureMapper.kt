package org.ort.app.ui.failures

import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every process-wide failure signal [FailureHost] reads, snapshotted once per poll tick so
 * [FailureMapper.map] is a pure function of one consistent read — never a live re-read mid-mapping
 * (which could otherwise see [org.ort.pipeline.capture.InputStatus] change halfway through a
 * decision). No Android dependency: every field here is a plain `:pipeline`/`:data` type, so
 * [FailureMapper] is unit-testable without Robolectric.
 */
public data class FailureSignals(
    public val captureState: CaptureState.State,
    public val inputStatus: InputStatus.State,
    public val levelStatus: LevelStatus.State,
    public val thermalStatus: ThermalStatus.State,
    public val rigStatus: RigStatus.State,
    public val storageForecast: StorageForecast.State,
    public val shedLevel: Int,
    public val shedBacklog: Int,
    public val newestGap: CaptureGapEntity?,
    public val nowMillis: Long,
    public val debugOverride: FailurePresentation?,
    /** Register R-126: the current session's own start time, for F1's "after H:MM:SS" subtitle —
     * `null` when there is no session to measure against (never fabricated). */
    public val sessionStartedAtMillis: Long? = null,
    /** Register R-126: the current session's transmission count, for F1's "N overs kept". */
    public val sessionTransmissionCount: Int = 0,
)

/**
 * The mapper's one result: at most one failure to show, typed by exactly which of this package's
 * composables renders it — [FailureHost]'s `when` is therefore exhaustive and cast-free. [None] is
 * a genuine, common result: most of the time nothing is wrong.
 */
public sealed interface FailurePresentation {
    public data class Route(public val state: RouteViewState) : FailurePresentation
    public data class Disconnect(public val state: DisconnectViewState) : FailurePresentation
    public data class Level(public val state: LevelViewState) : FailurePresentation
    public data class Killed(public val state: KilledViewState) : FailurePresentation
    public data class StorageWarning(public val state: StorageWarningViewState) : FailurePresentation
    public data class StorageHalt(public val state: StorageHaltViewState) : FailurePresentation
    public data class StorageAudioPaused(public val state: StorageAudioPausedViewState) : FailurePresentation
    public data class Thermal(public val state: ThermalViewState) : FailurePresentation
    public data class Backlog(public val state: BacklogViewState) : FailurePresentation
    public data class Rig(public val state: RigViewState) : FailurePresentation
    public data class Clock(public val state: ClockViewState) : FailurePresentation
    public data class Call(public val state: CallViewState) : FailurePresentation
    public data class Usb(public val state: UsbViewState) : FailurePresentation
    public data class Interrupted(public val state: InterruptedViewState) : FailurePresentation
    public data class Reconcile(public val state: ReconcileViewState) : FailurePresentation
    public data class Migration(public val state: MigrationViewState) : FailurePresentation
    public data class AssetSwap(public val state: AssetSwapViewState) : FailurePresentation
    public data class Calibration(public val state: CalibrationViewState) : FailurePresentation
    public data object None : FailurePresentation
}

/**
 * WP11b, register R-100/R-101/R-104/R-105/R-106/R-112/R-113: [map] is the one place every real
 * signal this package's boards need is read and turned into at most one [FailurePresentation] —
 * "never continue silently" (constitution IV) means at most one *takeover* can win, checked first
 * and in a fixed order; below that, at most one *banner*, in `Flow-Degrade.dc.html`'s own story
 * order (a route/USB problem outranks a stale rig, which outranks a informational recovery notice).
 * [FailureSignals.debugOverride], when set, wins outright — see [DebugFailureOverride]'s kdoc for
 * why that is the *only* way six of this package's seventeen ids can be exercised at all today.
 */
public object FailureMapper {

    /** `Fail-Backlog.dc.html`'s own story: a handful waiting is normal; F8 is for a queue that is
     * actually behind (the `backlog` scenario's 112, not a passing blip of one or two). */
    public const val BACKLOG_GROWING_THRESHOLD: Int = 10

    /** `Fail-Level.dc.html`: "-34 dBFS" is clearly too quiet; "-14" (labelled "was") is fine. */
    public const val QUIET_PEAK_THRESHOLD_DBFS: Float = -30f

    /** A gap counts as "just happened" for F5/F15's one-time informational banners for this long. */
    public const val RECENT_GAP_WINDOW_MILLIS: Long = 5 * 60_000L

    public fun map(signals: FailureSignals): FailurePresentation {
        signals.debugOverride?.let { return it }
        return mapTakeover(signals) ?: mapBanner(signals)
    }

    /** "Never continue silently" (constitution IV): at most one takeover, checked before any banner. */
    private fun mapTakeover(signals: FailureSignals): FailurePresentation? {
        val input = signals.inputStatus
        if (input is InputStatus.State.Mismatch) {
            val startedAt = signals.sessionStartedAtMillis
            return FailurePresentation.Route(
                RouteViewState(
                    expectedLabel = input.expected.label,
                    actualLabel = input.actual?.label ?: "an unrecognised device",
                    sinceLabel = clockLabel(signals.nowMillis),
                    elapsedLabel = if (startedAt != null) {
                        hoursMinutesSecondsLabel(signals.nowMillis - startedAt)
                    } else {
                        "0:00:00"
                    },
                    oversKeptCount = signals.sessionTransmissionCount,
                    sessionElapsedKnown = startedAt != null,
                ),
            )
        }
        val storage = signals.storageForecast
        if (storage is StorageForecast.State.AtFloor && signals.captureState is CaptureState.State.Failed) {
            return FailurePresentation.StorageHalt(
                StorageHaltViewState(freeLabel = bytesLabel(storage.freeBytes), floorLabel = "100 MB"),
            )
        }
        return null
    }

    /** At most one banner, in `Flow-Degrade.dc.html`'s own story order. */
    private fun mapBanner(signals: FailureSignals): FailurePresentation {
        mapInputOrLevelBanner(signals)?.let { return it }
        mapKilledOrStorageWarningBanner(signals)?.let { return it }
        mapThermalOrBacklogOrRigBanner(signals)?.let { return it }
        if (isRecentCallGap(signals)) {
            val gap = requireNotNull(signals.newestGap)
            return FailurePresentation.Call(CallViewState(durationLabel(gap.endedAt!! - gap.startedAt)))
        }
        return FailurePresentation.None
    }

    private fun mapInputOrLevelBanner(signals: FailureSignals): FailurePresentation? {
        val input = signals.inputStatus
        if (input is InputStatus.State.Lost) {
            return FailurePresentation.Disconnect(
                DisconnectViewState(
                    deviceLabel = input.lastKnown.descriptor.label,
                    sinceLabel = clockLabel(input.sinceMillis),
                ),
            )
        }
        val level = signals.levelStatus
        if (level is LevelStatus.State.Measured) {
            val problem = when {
                level.clipped -> LevelProblem.CLIPPING
                level.peakDbfs <= QUIET_PEAK_THRESHOLD_DBFS -> LevelProblem.QUIET
                else -> null
            }
            if (problem != null) {
                val sinceLabel = clockLabel(level.updatedAtMillis)
                return FailurePresentation.Level(LevelViewState(problem, level.peakDbfs, sinceLabel))
            }
        }
        return null
    }

    private fun mapKilledOrStorageWarningBanner(signals: FailureSignals): FailurePresentation? {
        val gap = signals.newestGap
        if (gap != null && gap.cause == CaptureGapCause.OS_STOPPED && isRecentlyClosed(gap, signals.nowMillis)) {
            return FailurePresentation.Killed(
                KilledViewState(
                    stoppedAtLabel = clockLabel(gap.startedAt),
                    gapDurationLabel = durationLabel(gap.endedAt!! - gap.startedAt),
                ),
            )
        }
        return when (val storage = signals.storageForecast) {
            is StorageForecast.State.OneNightLeft ->
                FailurePresentation.StorageWarning(
                    StorageWarningViewState(nightsLeftLabel = "1", freeLabel = bytesLabel(storage.freeBytes)),
                )
            is StorageForecast.State.ThreeNightsLeft ->
                FailurePresentation.StorageWarning(
                    StorageWarningViewState(
                        nightsLeftLabel = nightsLabel(storage.nightsLeft),
                        freeLabel = bytesLabel(storage.freeBytes),
                    ),
                )
            else -> null
        }
    }

    private fun mapThermalOrBacklogOrRigBanner(signals: FailureSignals): FailurePresentation? {
        val thermal = signals.thermalStatus
        if (thermal is ThermalStatus.State.Warm || thermal is ThermalStatus.State.Hot) {
            val tier = (MAX_TIER - signals.shedLevel).coerceIn(0, MAX_TIER)
            val label = clockLabel(signals.nowMillis)
            return FailurePresentation.Thermal(ThermalViewState(tier, label, thermal.realTimeFactor))
        }
        if (signals.shedBacklog >= BACKLOG_GROWING_THRESHOLD) {
            return FailurePresentation.Backlog(BacklogViewState(signals.shedBacklog))
        }
        val rig = signals.rigStatus
        if (rig is RigStatus.State.Stale) {
            return FailurePresentation.Rig(
                RigViewState(deviceLabel = rig.lastKnown.descriptor, sinceLabel = clockLabel(rig.sinceMillis)),
            )
        }
        return null
    }

    private fun isRecentCallGap(signals: FailureSignals): Boolean {
        val gap = signals.newestGap ?: return false
        if (gap.cause != CaptureGapCause.CALL) return false
        if (!isRecentlyClosed(gap, signals.nowMillis)) return false
        return signals.captureState is CaptureState.State.Capturing
    }

    private fun isRecentlyClosed(gap: CaptureGapEntity, nowMillis: Long): Boolean {
        val endedAt = gap.endedAt ?: return false
        return nowMillis - endedAt in 0..RECENT_GAP_WINDOW_MILLIS
    }

    private const val MAX_TIER = 3

    private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
        .withZone(ZoneId.systemDefault())

    internal fun clockLabel(millis: Long): String = clockFormat.format(Instant.ofEpochMilli(millis))

    internal fun durationLabel(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "$h h $m m"
            m > 0 -> "$m m $s s"
            else -> "$s s"
        }
    }

    /** `Fail-Route.dc.html`'s subtitle: "H:MM:SS" (hours unpadded, minutes/seconds zero-padded) —
     * `"6:42"` below an hour is never produced here on purpose, register R-126's cited pattern
     * always carries all three components, unlike [LiveBarPolling]'s shorter elapsed label. */
    internal fun hoursMinutesSecondsLabel(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%d:%02d:%02d".format(Locale.ROOT, h, m, s)
    }

    internal fun bytesLabel(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1.0) {
            "%.1f GB free".format(Locale.ROOT, gb)
        } else {
            "${bytes / 1_048_576} MB free"
        }
    }

    private fun nightsLabel(nightsLeft: Double): String = if (nightsLeft == Math.floor(nightsLeft)) {
        nightsLeft.toInt().toString()
    } else {
        "%.1f".format(Locale.ROOT, nightsLeft)
    }
}
