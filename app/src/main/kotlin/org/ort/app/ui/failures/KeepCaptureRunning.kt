package org.ort.app.ui.failures

import android.content.Context
import org.ort.pipeline.capture.FileHeartbeatTrailStore
import org.ort.pipeline.capture.HeartbeatTrailEntry
import org.ort.pipeline.capture.HeartbeatTrailStore
import java.io.File

/**
 * **R-1161, D58, AC-189 (as amended): the battery-exemption ask's new home.**
 *
 * The old `SetupStep.OVERNIGHT` refused to let capture start until overnight survival was proven,
 * and the only thing that can prove it is a capture it would not start — a deadlock by
 * construction, with no exit but uninstalling. D58 removes the step; this is where it lands
 * instead: a *Keep capture running* prompt raised by **the first missed heartbeat**, which is
 * evidence that can only exist after a capture has actually run.
 *
 * **The trigger is the heartbeat and nothing else.** Constitution IV and NFR-8 are emphatic that
 * `PowerManager.isIgnoringBatteryOptimizations()` lies on the reference device, so it appears
 * nowhere in this file — not as the trigger, not as a guard, not as a suppressor. What is read is
 * [HeartbeatTrailStore]: one row per 30-second beat, written to a file that survives exactly the
 * process death this is meant to detect, so a gap between two consecutive rows is the app's own
 * record of a stretch in which it was not running.
 *
 * This is deliberately a *different and stronger* signal than `OvernightSurvivalChecker`'s
 * (`ui/setup/OvernightSurvival.kt`), which infers survival from a session row that ended cleanly
 * after fifteen minutes and documents itself as a heuristic proxy. A trail gap is not a proxy: the
 * beats stopped, and here is when.
 */
public data class MissedHeartbeatEvidence(
    /** How long the app went without writing a beat. */
    public val gapMillis: Long,
    /** The last beat before the gap — when the app was last known alive. */
    public val stoppedAtWallMillis: Long,
    /** The first beat after it. Doubles as the gap's identity for the dismiss policy below: it is
     * monotonically increasing across gaps, so "dismissed through T" suppresses exactly the gaps
     * the operator has already been shown and no later one. */
    public val resumedAtWallMillis: Long,
    /** `true` when the two beats belong to different sessions — the app was restarted across the
     * gap, i.e. the OS ended the process rather than merely starving it of frames. */
    public val crossedProcessRestart: Boolean,
)

/**
 * **R-1184: the copy is gone.** This file used to declare its own `HEARTBEAT_INTERVAL_MILLIS =
 * 30_000L`, because `RealCaptureService`'s was `private` — two numbers describing one fact, whose
 * failure mode is invisible by construction: raise the real cadence, leave this behind, and the gap
 * threshold below silently widens until the prompt stops firing, which looks exactly like a phone
 * that never misses a beat. [HeartbeatTrailStore.HEARTBEAT_INTERVAL_MILLIS] is now the single
 * declaration, in the module that owns the trail this file reads, and both the service's ticker and
 * this trigger read it from there.
 *
 * The same slack `ProveItAnalyzer` (`:capture-android`, the class D8's prove-it run and the 8-hour
 * gate both read) allows before it calls a late beat a gap. **Still a restatement**, and
 * deliberately left as one this round: that class's own default is `private` in a different module,
 * `:capture-android` is outside this brief's ownership, and R-1184 is about the cadence. A beat that
 * lands 31 seconds late is a busy phone, not a kill.
 */
public const val HEARTBEAT_GAP_TOLERANCE_MILLIS: Long = 5_000L

/**
 * The newest real gap in [trail], or `null` when the app has never missed a beat — the ordinary
 * case, and the one in which this prompt must never appear.
 *
 * Pure, so the whole trigger is testable with no file, no Android and no device. The rule is
 * `ProveItAnalyzer`'s exactly (`delta > expected + tolerance`); what this adds is *which* gap and
 * *when*, which that class does not report and the dismiss policy needs.
 */
public fun newestMissedHeartbeat(
    trail: List<HeartbeatTrailEntry>,
    expectedIntervalMillis: Long = HeartbeatTrailStore.HEARTBEAT_INTERVAL_MILLIS,
    toleranceMillis: Long = HEARTBEAT_GAP_TOLERANCE_MILLIS,
): MissedHeartbeatEvidence? {
    var newest: MissedHeartbeatEvidence? = null
    for (index in 1 until trail.size) {
        val previous = trail[index - 1]
        val current = trail[index]
        val delta = current.wallMillis - previous.wallMillis
        if (delta <= expectedIntervalMillis + toleranceMillis) continue
        val candidate = MissedHeartbeatEvidence(
            gapMillis = delta,
            stoppedAtWallMillis = previous.wallMillis,
            resumedAtWallMillis = current.wallMillis,
            crossedProcessRestart = previous.sessionId != current.sessionId,
        )
        if (newest == null || candidate.resumedAtWallMillis >= newest.resumedAtWallMillis) newest = candidate
    }
    return newest
}

/** Reads the app's own heartbeat trail for [newestMissedHeartbeat]. An interface so the trigger can
 * be driven from a test without writing a real trail file, and so nothing in the banner path has to
 * know where that file lives. */
public interface MissedHeartbeatReader {
    public fun newest(): MissedHeartbeatEvidence?
}

/** The real reader, over the same `heartbeat-trail.log` `RealCaptureService` appends to. */
public class FileMissedHeartbeatReader(private val store: HeartbeatTrailStore) : MissedHeartbeatReader {
    public constructor(context: Context) : this(
        FileHeartbeatTrailStore(File(context.applicationContext.filesDir, TRAIL_FILE_NAME)),
    )

    override fun newest(): MissedHeartbeatEvidence? = newestMissedHeartbeat(store.recent())

    public companion object {
        /** `RealCaptureService.onCreate`'s own file name, verbatim. */
        public const val TRAIL_FILE_NAME: String = "heartbeat-trail.log"
    }
}

/** The behavioural fake (constitution II) — scriptable to any evidence, including none. */
public class FakeMissedHeartbeatReader(@Volatile public var evidence: MissedHeartbeatEvidence? = null) :
    MissedHeartbeatReader {
    override fun newest(): MissedHeartbeatEvidence? = evidence
}

/**
 * **The dismiss policy, stated once, here.**
 *
 * *Dismissal is keyed to the gap it was shown for, and it persists across process restarts. The
 * prompt returns only when a strictly newer missed heartbeat is observed.*
 *
 * Both halves are deliberate. **Persisting** it is what stops the nag: re-raising the same gap on
 * every launch after the operator has said no tells them nothing they have not already decided
 * about, and R-1161 is a row about a prompt that would not take no for an answer. **Keying it to
 * the gap** is what keeps AC-189's "reappears on every relevant subsequent launch until the
 * evidence exists" true rather than defeated: a phone that keeps killing the app keeps producing
 * new gaps, so the prompt keeps coming back — on new evidence each time — and a phone that has
 * stopped killing it produces none, which is exactly the state AC-189 wanted the prompt to stop in.
 *
 * Stored as a wall-clock watermark rather than a boolean for the same reason: a boolean could only
 * express "never ask again", which is not what the operator was asked.
 */
public interface KeepCaptureRunningDismissStore {
    /** `null` until the operator has dismissed the prompt at least once. */
    public fun dismissedThroughWallMillis(): Long?

    public fun dismiss(throughWallMillis: Long)
}

/** The real store. Its own small preferences file, not [org.ort.app.ui.settings.SettingsStore]'s —
 * this is not a setting the operator manages, it is a record of one answer they gave. */
public class SharedPreferencesKeepCaptureRunningDismissStore(private val prefs: android.content.SharedPreferences) :
    KeepCaptureRunningDismissStore {
    public constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    override fun dismissedThroughWallMillis(): Long? =
        if (prefs.contains(KEY_DISMISSED_THROUGH)) prefs.getLong(KEY_DISMISSED_THROUGH, 0L) else null

    override fun dismiss(throughWallMillis: Long) {
        // Never moves backwards: a stale, out-of-order write must not un-dismiss a newer gap the
        // operator has already answered.
        val existing = dismissedThroughWallMillis() ?: Long.MIN_VALUE
        if (throughWallMillis <= existing) return
        prefs.edit().putLong(KEY_DISMISSED_THROUGH, throughWallMillis).apply()
    }

    public companion object {
        public const val PREFS_NAME: String = "org.ort.app.keep_capture_running"
        private const val KEY_DISMISSED_THROUGH = "dismissed_through_wall_millis"
    }
}

/** The behavioural fake (constitution II). */
public class InMemoryKeepCaptureRunningDismissStore(@Volatile private var dismissedThrough: Long? = null) :
    KeepCaptureRunningDismissStore {
    override fun dismissedThroughWallMillis(): Long? = dismissedThrough

    override fun dismiss(throughWallMillis: Long) {
        if (throughWallMillis > (dismissedThrough ?: Long.MIN_VALUE)) dismissedThrough = throughWallMillis
    }
}
