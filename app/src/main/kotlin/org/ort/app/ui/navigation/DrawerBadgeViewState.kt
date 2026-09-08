package org.ort.app.ui.navigation

/**
 * The drawer's live badges (FR-UI-7, audit F-020) — everything shown here MUST be a real,
 * measured fact, never a fabricated stand-in for one the product has not built yet.
 *
 * [threadsCount] is always `null`: no prompt has ever populated `TransmissionEntity.threadId`
 * (every insert path writes it as `null`), so a numeric count derived from it would silently claim
 * a grouping that does not exist — precisely the kind of confident-but-wrong number constitution I
 * forbids. The drawer renders it as "—", never as `0`-as-if-grouped and never as a missing badge
 * that could be mistaken for "no threads yet" (a fact) rather than "not built" (the truth).
 *
 * [logCount] and [captureElapsedLabel] are `null` when there is no active session (or, for
 * [captureElapsedLabel], when a session exists but nothing is actually capturing right now) — the
 * drawer then shows no badge at all for that row, rather than a stale or zeroed one.
 */
public data class DrawerBadgeViewState(
    public val logCount: Int?,
    public val threadsCount: Int?,
    /** The running session's elapsed time (e.g. `"6:42"`), or `null` when nothing is capturing. */
    public val captureElapsedLabel: String?,
) {
    public companion object {
        public val NONE: DrawerBadgeViewState = DrawerBadgeViewState(
            logCount = null,
            threadsCount = null,
            captureElapsedLabel = null,
        )
    }
}
