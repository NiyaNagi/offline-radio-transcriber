package org.ort.capture.android.service

/**
 * The one persistent notification's content (FR-SVC-1, FR-PLT-3 → AC-61; register R-102;
 * `design/canvas/Capture-Notification.dc.html`, `design-guide.md` §6.19). Every field is a typed,
 * narrowly-named fact — a state enum, counts, elapsed millis, a callsign, a device name, an
 * already-formatted label — the same closed set this type has always kept (constitution VII: a
 * guarantee expressed as a type, not a reviewer's promise); AC-61's own test asserts the field set
 * directly. Nothing here is, or is derived from, a transcript.
 *
 * `RealCaptureService`'s old free-text state strings (`"Interrupted"`, `"Failed: …"`,
 * `"ASR unavailable"`) become [State] values, not strings a caller composes by hand.
 */
public data class CaptureNotificationContent(
    val state: State,
    val elapsedMillis: Long,
    val transmissionCount: Int,
    val secondLine: SecondLine,
    /** The most recent over's callsign — never its transcript — or `null` if none has resolved yet. */
    val lastOverCallsign: String?,
    val lastOverAtWallMillis: Long?,
    val inputDeviceName: String?,
    val inputVerified: Boolean,
    /** Already formatted (`"38.2 of 60 GB · 16 nights left"`, or the honest `"… · no budget set"`). */
    val storageLabel: String,
) {

    public enum class State(internal val titleWord: String) {
        CAPTURING("Capturing"),
        INTERRUPTED("Interrupted"),
        FAILED("Failed"),

        /** Capture is genuinely running; only the ASR engine is not — the design still reads "Capturing". */
        ASR_UNAVAILABLE("Capturing"),
    }

    /**
     * The line beneath the title: ordinary frequencies·tier, or a degraded reason that replaces
     * it — the same notification, never a second one.
     */
    public sealed interface SecondLine {
        public val text: String

        public data class Normal(val frequenciesLabel: String, val tier: Int?) : SecondLine {
            override val text: String
                get() = if (tier != null) "$frequenciesLabel · tier $tier" else frequenciesLabel
        }

        public data class Degraded(val reason: String) : SecondLine {
            override val text: String get() = reason
        }
    }

    /** `"Capturing · 6:42 · 412 overs"` — state · elapsed (h:mm) · count. Computed, not stored (AC-61's test). */
    public val title: String
        get() = "${state.titleWord} · ${elapsedHoursMinutes(elapsedMillis)} · $transmissionCount overs"

    public val hasLastOver: Boolean get() = lastOverCallsign != null || lastOverAtWallMillis != null

    /**
     * `CaptureService` (`:capture-android`'s own, distinct from `RealCaptureService`) predates
     * R-102 and reads `.stateLabel`/`.elapsedLabel`/`.text` directly — that file is outside
     * WP11a's file ownership (only `CaptureNotificationBuilder.kt`/`CaptureNotificationContent`
     * themselves are), so rather than touch it, these three stay as computed aliases (no backing
     * field — AC-61's own test still asserts the exact stored-field set below) reproducing
     * exactly what that caller already expects: the old `HH:MM:SS` elapsed format and
     * `"$stateLabel · $elapsedLabel · N transmissions"`. New callers (`RealCaptureService`) use
     * [title]/[secondLine] instead.
     */
    public val stateLabel: String get() = state.titleWord
    public val elapsedLabel: String get() = elapsedHoursMinutesSeconds(elapsedMillis)
    public val text: String get() = "$stateLabel · $elapsedLabel · $transmissionCount transmissions"
}

/**
 * Everything [CaptureNotificationBuilder.build] needs beyond state/elapsed/count, grouped so the
 * function stays under the parameter-count lint threshold without hiding any field — the same
 * approach `PassBResolutionChain` (`:pipeline`) uses for the same reason.
 */
public data class CaptureNotificationExpandedFacts(
    val frequenciesLabel: String,
    val tier: Int?,
    /** Non-null replaces the ordinary frequencies·tier second line — see [CaptureNotificationContent.SecondLine]. */
    val degradedReason: String?,
    val lastOverCallsign: String?,
    val lastOverAtWallMillis: Long?,
    val inputDeviceName: String?,
    val inputVerified: Boolean,
    /** Already formatted — see [CaptureNotificationContent.storageLabel]. */
    val storageLabel: String,
) {
    public companion object {
        /** "Just started, nothing measured yet" — [CaptureNotificationBuilder]'s legacy overload uses this. */
        public val EMPTY: CaptureNotificationExpandedFacts = CaptureNotificationExpandedFacts(
            frequenciesLabel = "",
            tier = null,
            degradedReason = null,
            lastOverCallsign = null,
            lastOverAtWallMillis = null,
            inputDeviceName = null,
            inputVerified = false,
            storageLabel = "",
        )
    }
}

/** The pre-R-102 `HH:MM:SS` format — [CaptureNotificationContent.elapsedLabel]'s shape, kept for `CaptureService`. */
private fun elapsedHoursMinutesSeconds(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** `"6:42"` — hours:minutes, hour unpadded, matching the board exactly (not the old HH:MM:SS). */
internal fun elapsedHoursMinutes(elapsedMillis: Long): String {
    val totalMinutes = (elapsedMillis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "$hours:${minutes.toString().padStart(2, '0')}"
}

public object CaptureNotificationBuilder {

    /**
     * Builds the notification's content from typed facts only (AC-61). [facts.degradedReason]
     * (see [CaptureNotificationExpandedFacts]) — when non-null — replaces the ordinary
     * frequencies·tier [CaptureNotificationContent.SecondLine] with a
     * [CaptureNotificationContent.SecondLine.Degraded] one; the caller (`RealCaptureService`,
     * which alone can read `ThermalStatus`/`RigStatus`/`StorageForecast`/`CaptureState`) decides
     * whether one applies and composes its text — this stays a small, Android- and holder-free
     * function so it is testable without Robolectric, same as before.
     */
    public fun build(
        state: CaptureNotificationContent.State,
        elapsedMillis: Long,
        transmissionCount: Int,
        facts: CaptureNotificationExpandedFacts,
    ): CaptureNotificationContent = CaptureNotificationContent(
        state = state,
        elapsedMillis = elapsedMillis,
        transmissionCount = transmissionCount,
        secondLine = if (facts.degradedReason != null) {
            CaptureNotificationContent.SecondLine.Degraded(facts.degradedReason)
        } else {
            CaptureNotificationContent.SecondLine.Normal(facts.frequenciesLabel, facts.tier)
        },
        lastOverCallsign = facts.lastOverCallsign,
        lastOverAtWallMillis = facts.lastOverAtWallMillis,
        inputDeviceName = facts.inputDeviceName,
        inputVerified = facts.inputVerified,
        storageLabel = facts.storageLabel,
    )

    /**
     * The pre-R-102 signature, kept for `CaptureService` (see [CaptureNotificationContent.text]'s
     * kdoc for why — outside this ownership row's file). [state] is matched case-insensitively
     * against a small closed vocabulary; anything unrecognised reads as
     * [CaptureNotificationContent.State.CAPTURING] rather than throwing, since a free-text caller
     * may pass anything.
     */
    public fun build(state: String, elapsedMillis: Long, transmissionCount: Int): CaptureNotificationContent =
        build(stateFor(state), elapsedMillis, transmissionCount, CaptureNotificationExpandedFacts.EMPTY)

    private fun stateFor(raw: String): CaptureNotificationContent.State = when {
        raw.contains("fail", ignoreCase = true) -> CaptureNotificationContent.State.FAILED
        raw.contains("interrupt", ignoreCase = true) -> CaptureNotificationContent.State.INTERRUPTED
        else -> CaptureNotificationContent.State.CAPTURING
    }
}
