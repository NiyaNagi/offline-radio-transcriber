package org.ort.app.ui.setup

import org.ort.app.BuildConfig
import org.ort.data.dao.SessionDao
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason

/**
 * P22 (AC-189, constitution IV): "liveness is proven by heartbeat, never by
 * `isIgnoringBatteryOptimizations()`" applies exactly as much to [OvernightScreen]'s own gate as
 * it does to the capture-status surface (`StatusViewState.kt`'s "Alive (heartbeat current)" line,
 * built the identical way from `FileHeartbeatStore`/`UncleanEndDetector`, `:capture-android`).
 * This is the setup-side equivalent question — "has this device, at least once, actually run long
 * enough for backgrounding to plausibly have happened, and come back clean?" — answered from real
 * session history in `:data`, never from the OS's own exemption flag.
 *
 * [hasProvenSurvival] deliberately does not require the app to have been observed *literally*
 * backgrounded — this module cannot see `ActivityLifecycleCallbacks`/process-visibility history for
 * a past session, only what `:data`'s `session` table recorded. A session that ran to a *clean*,
 * *operator-initiated* end ([TerminationReason.USER]) and lasted at least
 * [OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS] is treated as evidence the process was not killed by the
 * OS somewhere in the middle of it — the same reasoning [RealCaptureService]'s own
 * `UncleanEndDetector` uses in the other direction (an *unclean* end is the thing that must never
 * be missed). This is a heuristic proxy, not a certainty, and is documented as such rather than
 * silently asserted (constitution I): a session that happened to stay foregrounded the whole time
 * would pass this check too. It is still strictly more honest than the OS's own exemption flag,
 * which [OvernightScreen]'s own doc comment already establishes lies on the reference device.
 */
public interface OvernightSurvivalChecker {
    public suspend fun hasProvenSurvival(): Boolean
}

/**
 * Fifteen minutes — long enough that a session merely opened and closed again in the same breath
 * (the operator immediately backing out of `Start capture`) cannot count as survival evidence, but
 * short of asking for anything like a full overnight run before the nagging step ever clears. A
 * heuristic threshold, not a spec-mandated number — the same kind of judgement call
 * [ReadyScreen.kt]'s own `LARGE_FONT_SCALE_THRESHOLD` documents itself as.
 */
public const val OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS: Long = 15 * 60 * 1000L

/** The real, `:data`-backed [OvernightSurvivalChecker] — reads every session this device has ever
 * recorded and asks whether any one of them is [isSurvivalEvidence]. */
public class RealOvernightSurvivalChecker(private val sessionDao: SessionDao) : OvernightSurvivalChecker {
    override suspend fun hasProvenSurvival(): Boolean = sessionDao.listAll().any(::isSurvivalEvidence)
}

/** The pure decision [RealOvernightSurvivalChecker] delegates to — see this file's own class doc
 * comment on [OvernightSurvivalChecker] for what "evidence" means and why. `internal` so
 * [OvernightSurvivalTest] can exercise it directly against a plain [SessionEntity] fixture, with no
 * `SessionDao`/database involved at all. */
internal fun isSurvivalEvidence(session: SessionEntity): Boolean {
    val endedAt = session.endedAt ?: return false
    if (session.terminationReason != TerminationReason.USER) return false
    return (endedAt - session.startedAt) >= OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS
}

/** The behavioural fake (constitution II) — scriptable to whatever [proven] state a test needs,
 * with every call recorded so a test can assert how many times [SetupActivity] actually consulted
 * it. */
public class FakeOvernightSurvivalChecker(@Volatile public var proven: Boolean = false) : OvernightSurvivalChecker {
    public var callCount: Int = 0
        private set

    override suspend fun hasProvenSurvival(): Boolean {
        callCount += 1
        return proven
    }
}

/**
 * **R-1161** — whether the overnight prompt has already been put in front of the operator **in this
 * process**. A process-wide holder in exactly the shape `CaptureState`/`LevelStatus` already
 * establish for this codebase (`:pipeline`), and for the same reason: the fact is true of the
 * running process, not of the device, so it deliberately does not belong in [SetupStore] —
 * persisting it would silence AC-189's "reappears on every relevant subsequent launch".
 *
 * **P39 (D58, AC-199): nothing reads this today, and that is the point.** It existed to bound the
 * router's detour to once per process, because the detour itself could otherwise loop. The detour is
 * gone — `MainActivity` no longer diverts a launch for overnight survival at all, which is the
 * structural half of R-1161's fix — so there is nothing left to bound. It is kept rather than deleted
 * because the *Keep capture running* prompt D58 names as the ask's new home (first missed heartbeat,
 * a capture-status surface outside this change's ownership) needs exactly this "have we already asked
 * this process?" fact, and rebuilding it there from scratch would be rebuilding the reasoning above
 * too. If that surface lands and does not use it, delete it then and say so.
 *
 * [reset] is a test seam and nothing else: a leaked static between tests is its own defect.
 */
internal object OvernightNagState {

    @Volatile
    var askedThisProcess: Boolean = false
        private set

    fun markAsked() {
        askedThisProcess = true
    }

    fun reset() {
        askedThisProcess = false
    }
}

/**
 * The same debug-override seam [DebugRigLinkPortOverride]/`DebugLexiconImportOverride` already
 * establish for this package (see either's own class kdoc for the full rationale): a real device
 * or a Robolectric-hosted [SetupActivity] test needs a way to hand [SetupActivity.onCreate] a
 * scripted [OvernightSurvivalChecker] before it runs, since [SetupActivity] is launched through
 * [androidx.test.core.app.ActivityScenario] in `SetupActivityTest` — there is no instance to set a
 * field on before `onCreate` itself already ran the real check.
 */
public object DebugOvernightSurvivalOverride {

    @Volatile
    public var current: OvernightSurvivalChecker? = null
        private set

    /** Test seam (see class kdoc) — production code never assigns this. */
    @Volatile
    internal var isDebugBuild: () -> Boolean = { BuildConfig.DEBUG }

    public fun show(checker: OvernightSurvivalChecker) {
        current = checker
    }

    public fun clear() {
        current = null
    }

    /** [SetupActivity.onCreate]'s own read — gated on [isDebugBuild], `null` in any non-debug
     * build no matter what [current] holds. */
    public val activeOverride: OvernightSurvivalChecker?
        get() = if (isDebugBuild()) current else null
}

/**
 * [ReadyScreen]'s own two overnight facts, bundled (AC-189): [batteryExemptDiagnostic] is the
 * live, diagnostic-only `PowerManager.isIgnoringBatteryOptimizations()` reading, shown for wording
 * only; [survivalProven] is the real, heartbeat/session-derived fact that alone decides
 * [ReadyRow.ok] for the Overnight row — the OS flag must never satisfy that gate by itself, even
 * when it reports the exemption already granted.
 */
public data class OvernightSurvivalState(
    public val batteryExemptDiagnostic: Boolean,
    public val survivalProven: Boolean,
)
