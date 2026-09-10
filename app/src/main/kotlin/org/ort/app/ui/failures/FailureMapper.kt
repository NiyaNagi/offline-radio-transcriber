package org.ort.app.ui.failures

import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.StagedActivation
import org.ort.capture.android.AudioDeviceKind
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
 * [FailureMapper] is unit-testable without Robolectric — [stagedActivation] and
 * [stagedActivationActiveLabel] (`:app`'s own [StagedActivation], a plain data class) are the one
 * exception to "pipeline/data type", added by coordinator direction once FR-AST-4's real guard
 * landed (`ModelsController`, WP10) — see [FailureMapper.map]'s own kdoc for why this package reads
 * across into `ui/data` for exactly this one signal.
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
    /** Register R-149: every `StorageForecast` transition `FailureSignalsPolling` has actually
     * observed this process's lifetime, oldest first — never a fabricated multi-day history. */
    public val storageForecastHistory: List<StorageForecastSample> = emptyList(),
    /** Register R-149: the real, in-process-observed `ShedStatus.backlog` depth for up to the
     * last 30 minutes, oldest first. */
    public val backlogHistory: List<BacklogSample> = emptyList(),
    /** FR-AST-4 (register R-448 follow-up): [ModelsController.stagedActivation]'s own real fact —
     * the one lexicon swap or model install waiting for the live session to end, or `null` when
     * nothing is staged. `null` on every real poll tick until WP10's own guard actually stages
     * something — never fabricated when it has not. */
    public val stagedActivation: StagedActivation? = null,
    /** The real, currently-*active* counterpart's label — `RoomActiveLexiconStore.current()`'s
     * version for a staged lexicon swap, or the currently-installed checksum prefix for a staged
     * model — computed only when [stagedActivation] is non-null (Android/file I/O, so it lives in
     * [FailureSignalsPolling], never derived here). `null` exactly when [stagedActivation] is, or
     * when nothing real is installed yet to compare against. */
    public val stagedActivationActiveLabel: String? = null,
)

/** One observed [org.ort.pipeline.capture.StorageForecast.State] transition, timestamped. */
public data class StorageForecastSample(public val state: StorageForecast.State, public val atMillis: Long)

/** One observed [org.ort.pipeline.capture.ShedStatus.backlog] reading, timestamped — alongside the
 * same tick's own [transmissionCount] (register R-254: the session's total captured-transmission
 * count at that moment, the same number F1's "N overs kept" reads — see
 * [FailureMapper.backlogRateLabel]'s own kdoc for why pairing the two lets the Rate row's "Band"
 * half be a real measurement rather than a guess). */
public data class BacklogSample(
    public val count: Int,
    public val atMillis: Long,
    public val transmissionCount: Int = 0,
)

/**
 * The mapper's one result: at most one failure to show, typed by exactly which of this package's
 * composables renders it — [FailureHost]'s `when` is therefore exhaustive and cast-free. [None] is
 * a genuine, common result: most of the time nothing is wrong.
 */
public sealed interface FailurePresentation {
    public data class Route(public val state: RouteViewState) : FailurePresentation
    public data class Disconnect(public val state: DisconnectViewState) : FailurePresentation
    public data class BluetoothAudioDropped(public val state: BluetoothAudioDroppedViewState) : FailurePresentation
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
 *
 * F21/`AssetSwap` is no longer one of the six (WP10's FR-AST-4 `ModelsController.stagedActivation`
 * guard landed on main): [assetSwapViewState] reads `:app/ui/data`'s own [StagedActivation] type
 * directly, a coordinator-directed exception to this package's usual "no Android/`ui.data`
 * dependency" rule (matching R-448's own precedent — [org.ort.app.ui.ReaderActivity]'s
 * `onOpenModels`/`onOpenEarlierNights` wiring is the same kind of directed cross-package addendum),
 * because `ui/failures`'s own [FailureMapperTest] previously proved (`R_448_asset_swap_signal`,
 * before WP10's guard existed) that no real signal existed to read at all.
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
        // FR-AST-4 (register R-448 follow-up): checked last among takeovers — real, but the least
        // urgent of the three; an input mismatch or a hard storage floor both outrank a swap that is
        // simply waiting.
        signals.stagedActivation?.let { staged ->
            return FailurePresentation.AssetSwap(assetSwapViewState(staged, signals.stagedActivationActiveLabel))
        }
        return null
    }

    /** FR-AST-4: [staged]'s own real facts (asset, version, staged-at time, and the real reason
     * [ModelsController] itself recorded) become F21's board — never a placeholder. [activeLabel]
     * reads "not measured" only when [FailureSignalsPolling] genuinely could not read one (never
     * fabricated). The two options are the two real actions available today: wait (the default —
     * [staged]'s own [StagedActivation.reason]), or activate now — safe to offer unconditionally
     * since [ModelsController.activateStaged] itself refuses, with no effect, while a session is
     * still live (`FailureHost`'s own `onActivateStagedAsset` wiring calls it directly). */
    private fun assetSwapViewState(staged: StagedActivation, activeLabel: String?): AssetSwapViewState {
        val assetName = if (staged.assetId == ModelsController.CALLSIGN_LEXICON_ASSET_ID) {
            "callsign lexicon"
        } else {
            ModelId.entries.firstOrNull { it.name == staged.assetId }?.label ?: staged.assetId
        }
        return AssetSwapViewState(
            activeLabel = activeLabel ?: "not measured",
            stagedLabel = "$assetName ${staged.version} · staged ${clockLabel(staged.stagedAtMillis)}",
            options = listOf(
                AssetSwapOption("Wait for the session to end", staged.reason),
                AssetSwapOption(
                    "Activate now",
                    "takes effect only once this session has actually ended — the same real " +
                        "activation Settings › Assets already runs automatically whenever it opens",
                ),
            ),
            selectedOption = 0,
        )
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
            // E2-G05 (F23, FR-CAP-5, D34): the real, live signal is the lost device's own
            // descriptor kind (WPC1's `AudioDeviceDescriptor.kind`) — not a session column, so
            // this reads correctly the instant the drop happens, before any session-level read
            // could catch up. [BluetoothAudioDroppedViewState.retryAttempt]/[retryTotal]/
            // [nextRetrySeconds] stay `null` — no reconnect-with-backoff counter is published by
            // `:pipeline` yet (see that type's own kdoc); reported, not fabricated.
            if (input.lastKnown.descriptor.kind == AudioDeviceKind.BLUETOOTH) {
                return FailurePresentation.BluetoothAudioDropped(
                    BluetoothAudioDroppedViewState(
                        deviceLabel = input.lastKnown.descriptor.label,
                        droppedAtLabel = clockLabel(input.sinceMillis),
                        rigLinkStillUp = signals.rigStatus !is RigStatus.State.Stale,
                    ),
                )
            }
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
        val timeline = storageTimeline(signals.storageForecastHistory)
        return when (val storage = signals.storageForecast) {
            is StorageForecast.State.OneNightLeft ->
                FailurePresentation.StorageWarning(
                    StorageWarningViewState(
                        nightsLeftLabel = "1",
                        freeLabel = bytesLabel(storage.freeBytes),
                        timeline = timeline,
                    ),
                )
            is StorageForecast.State.ThreeNightsLeft ->
                FailurePresentation.StorageWarning(
                    StorageWarningViewState(
                        nightsLeftLabel = nightsLabel(storage.nightsLeft),
                        freeLabel = bytesLabel(storage.freeBytes),
                        timeline = timeline,
                    ),
                )
            else -> null
        }
    }

    /** Register R-149: `Fail-Storage.dc.html`'s own "How this unfolded" stage copy, keyed to
     * exactly the transitions [signals.storageForecastHistory] recorded — a stage never appears
     * unless `FailureSignalsPolling` actually observed it. The hard-floor row is always appended
     * "not reached" here, since reaching it takes the takeover path (`StorageHalt`), never this
     * banner. */
    private fun storageTimeline(history: List<StorageForecastSample>): List<StorageTimelineStage> {
        val stages = history.mapNotNull { sample ->
            val (headline, detail) = when (sample.state) {
                is StorageForecast.State.ThreeNightsLeft ->
                    "warned at 3 nights left" to "notification and status surface · retention set to 30 nights"
                is StorageForecast.State.OneNightLeft -> "warned at 1 night left" to "same channels, louder"
                is StorageForecast.State.AtFloor ->
                    "audio stopped, text continues" to
                        "the order is fixed: audio goes before transcripts, transcripts before capture"
                else -> null
            } ?: return@mapNotNull null
            StorageTimelineStage(label = "${clockLabel(sample.atMillis)} · $headline", detail = detail, reached = true)
        }
        val floorStage = StorageTimelineStage(
            label = "Not reached",
            detail = "500 MB hard floor — capture would halt with the red banner, never quietly",
            reached = false,
        )
        return stages + floorStage
    }

    private fun mapThermalOrBacklogOrRigBanner(signals: FailureSignals): FailurePresentation? {
        val thermal = signals.thermalStatus
        if (thermal is ThermalStatus.State.Warm || thermal is ThermalStatus.State.Hot) {
            val tier = (MAX_TIER - signals.shedLevel).coerceIn(0, MAX_TIER)
            // Register R-177: the real transition moment ThermalStatus recorded, never `now` —
            // a mapper polled repeatedly must render the moment the tier actually dropped, not a
            // clock that creeps forward on every 2s tick. `sinceMillis` lives on Warm/Hot, not the
            // shared `State` interface, so it is read per-branch here rather than through `thermal`.
            val sinceMillis = when (thermal) {
                is ThermalStatus.State.Warm -> thermal.sinceMillis
                is ThermalStatus.State.Hot -> thermal.sinceMillis
                is ThermalStatus.State.Nominal -> signals.nowMillis
            }
            val label = clockLabel(sinceMillis)
            return FailurePresentation.Thermal(ThermalViewState(tier, label, thermal.realTimeFactor))
        }
        if (signals.shedBacklog >= BACKLOG_GROWING_THRESHOLD) {
            return FailurePresentation.Backlog(backlogViewState(signals))
        }
        val rig = signals.rigStatus
        if (rig is RigStatus.State.Stale) {
            return FailurePresentation.Rig(
                RigViewState(
                    deviceLabel = rig.lastKnown.descriptor,
                    sinceLabel = clockLabel(rig.sinceMillis),
                    // E2-G06 (F9, FR-RIG-15): WPC2's own live `RigStatus.transportKind` — `null`
                    // for a caller that predates it (a debug scenario), never fabricated.
                    transportLabel = rigTransportLabel(rig.lastKnown.transportKind),
                ),
            )
        }
        return null
    }

    /** E2-G06: the transport named in F9's own banner copy — "the Bluetooth SPP transport" /
     * "the USB serial transport" — `null` when [transport] is `null` or [org.ort.rig.RigTransportKind.NONE]. */
    private fun rigTransportLabel(transport: org.ort.rig.RigTransportKind?): String? = when (transport) {
        org.ort.rig.RigTransportKind.USB_SERIAL -> "the USB serial transport"
        org.ort.rig.RigTransportKind.BLUETOOTH_SPP -> "the Bluetooth SPP transport"
        org.ort.rig.RigTransportKind.BLE -> "the Bluetooth LE transport"
        org.ort.rig.RigTransportKind.NETWORK -> "the network transport"
        org.ort.rig.RigTransportKind.NONE, null -> null
    }

    /** Register R-149/R-254: `Fail-Backlog.dc.html`'s Waiting/Rate/Capture/In-the-log rows and its
     * "Queue, last 30 minutes" chart, built only from real signals — `queueHistory` from
     * `FailureSignalsPolling`'s own rolling backlog samples, its two real clock-time endpoints
     * ([BacklogViewState.queueHistoryOldestLabel]/[queueHistoryNewestLabel]) rather than the
     * board's own relative "-30m"/"now" (this app has the real timestamps; using them is strictly
     * more honest), [growthRateLabel] from [backlogRateLabel] (see that function's own kdoc for
     * exactly how "Band"/"Pass B" are derived without fabricating either), [rateSubLabel] from the
     * same tier/RTF pairing [FailThermalBanner] already reads, and `captureLabel` from
     * `CaptureState` alone — never a dropped-sample or ring-buffer figure nothing publishes. */
    private fun backlogViewState(signals: FailureSignals): BacklogViewState {
        val history = signals.backlogHistory
        val maxDepth = (history.maxOfOrNull { it.count } ?: signals.shedBacklog).coerceAtLeast(1)
        val queueHistory = history.map { it.count.toFloat() / maxDepth }
        val captureLabel = if (signals.captureState is CaptureState.State.Capturing) {
            "Nominal — every over is on disk"
        } else {
            "Not capturing"
        }
        return BacklogViewState(
            waitingCount = signals.shedBacklog,
            queueHistory = queueHistory,
            queueHistoryOldestLabel = history.firstOrNull()?.let { clockLabel(it.atMillis) },
            queueHistoryNewestLabel = history.lastOrNull()?.let { clockLabel(it.atMillis) },
            growthRateLabel = backlogRateLabel(history),
            rateSubLabel = backlogRateSubLabel(signals),
            captureLabel = captureLabel,
        )
    }

    /** Register R-254: `Fail-Backlog.dc.html`'s Rate row reads "Band N overs/min · Pass B M/min" —
     * two *separate* rates this codebase has never published a direct signal for
     * ([org.ort.pipeline.capture.ShedStatus] only ever exposes the net queue depth). Both halves
     * are nonetheless real, not guessed: [BacklogSample.transmissionCount] is the session's own
     * total *captured* count at that tick (the same number F1's "N overs kept" reads) — every
     * capture is a "Band" arrival by definition, so its own delta over the window *is* the arrival
     * rate, directly measured. The net backlog delta is *also* directly measured
     * (`arrivals − completions`, by definition of what a backlog is), so "Pass B" — the completion
     * rate — falls out algebraically (`arrivals − netDelta`) rather than needing its own signal:
     * exact given two real measurements, not an estimate. `null` (rendered "Not measured") with
     * fewer than two samples, or a non-positive time delta — never a fabricated single-sample rate. */
    internal fun backlogRateLabel(history: List<BacklogSample>): String? {
        if (history.size < 2) return null
        val first = history.first()
        val last = history.last()
        val minutes = (last.atMillis - first.atMillis) / 60_000.0
        if (minutes <= 0.0) return null
        val bandRate = (last.transmissionCount - first.transmissionCount) / minutes
        val netRate = (last.count - first.count) / minutes
        val passBRate = bandRate - netRate
        return "Band %.1f overs/min · Pass B %.1f/min".format(Locale.ROOT, bandRate, passBRate)
    }

    /** Register R-254: `Fail-Backlog.dc.html`'s own Rate sub-line — "tier N, RTF X.XX while the
     * net runs", from the same two facts [FailThermalBanner] already surfaces (never invented just
     * for this row). `null` when the real-time factor has not been measured yet this session. */
    private fun backlogRateSubLabel(signals: FailureSignals): String? {
        val realTimeFactor = signals.thermalStatus.realTimeFactor ?: return null
        val tier = (MAX_TIER - signals.shedLevel).coerceIn(0, MAX_TIER)
        return "tier $tier, RTF %.2f while the net runs".format(Locale.ROOT, realTimeFactor)
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
