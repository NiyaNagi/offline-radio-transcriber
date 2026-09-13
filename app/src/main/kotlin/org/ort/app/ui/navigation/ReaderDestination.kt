package org.ort.app.ui.navigation

/**
 * The drawer's destinations, in the order `design/canvas/Menu.dc.html` lists them (build-plan
 * P13). `Now` and `Log` are P14's real screens; `Search` and `Threads` are build-plan P15's
 * (FR-UI-3, FR-UI-2 — the `SEARCH` entry itself is a deliberate divergence from the canvas, which
 * puts search behind a magnifying-glass icon on `Log.dc.html`'s own top bar rather than a drawer
 * row — see `SearchScreen`'s own doc comment). The rest are exposed in the drawer, as the canvas
 * requires, but render [ReaderDestination.hasScreen] `false`'s
 * [org.ort.app.ui.screens.PlaceholderScreen] until their own build-plan prompt (P16-P17) builds
 * them — reachable and honest about not existing yet, rather than missing from the drawer or
 * silently faked. `Stations` and `Frequencies` are real screens as of build-plan P17. `Settings`
 * is a real screen as of audit F-008: [org.ort.app.ui.screens.ModelsScreen], the declared,
 * user-initiated channel through which an ASR/VAD model reaches the device (constitution V) — put
 * here rather than under `Improve records`, which P16 already gave a per-transmission
 * correction/labelling meaning; asset management reads as a `Settings` concern instead. `Capture`
 * is a real screen as of ui-conformance-plan WP4:
 * [org.ort.app.ui.screens.CaptureStatusContent], reached from the drawer row and the live bar's
 * own tap target alike. `Improve records` is a real screen as of WP10 (register R-091, P12):
 * [org.ort.app.ui.improve.ImproveContent].
 *
 * **WPREC (design-intent row RC01, IA-1): `EARLIER_NIGHTS` is `Recordings.dc.html`'s (RC01) own
 * destination now, not `Sessions.dc.html`'s (DG03) — for an ordinary reach.** The drawer row shows
 * [drawerLabel] ("Recordings"), and an ordinary tap lands on
 * [org.ort.app.ui.recordings.RecordingsContent]; `Settings-Storage`'s "Next deletion … Review"
 * link still seeds [org.ort.app.ui.digest.SessionsContent] on this exact same destination via
 * `NavSeed.pendingReviewSessionId` (`OrtNavHost.kt`'s own `DestinationContent` picks between the
 * two on exactly that fact) — the one path `Session.dc.html` (DG04) and the Digest (DG01/DG05)
 * stay reachable through, per this session's own brief.
 *
 * **[label] itself is deliberately left as "Earlier nights"**, unrenamed: it is what every drill-in
 * opened from this destination shows as its own "Back to <label>" origin
 * ([org.ort.app.ui.navigation.ReaderDestination]'s consumers — `openedFrom.label` throughout
 * `OrtNavHost.kt`), including a drill-in reached from the *old* `SessionsContent` path (DG04, the
 * Digest) — content that still genuinely is "Earlier nights" in exactly the sense those headers
 * already mean. Overloading the one property both the drawer row and every back-header read would
 * have made every such header read "Back to Recordings" regardless of which of the two real
 * screens the drill-in was actually opened from, which is not more correct, only differently
 * imprecise, for a change whose only exceeded a small, safely-diffable label — [drawerLabel] keeps
 * the drawer-visible fact separate from the back-navigation one instead of picking a single
 * compromise string for both.
 */
public enum class ReaderDestination(
    public val label: String,
    public val hasScreen: Boolean,
    public val drawerLabel: String = label,
) {
    NOW("Now", hasScreen = true),
    LOG("Log", hasScreen = true),
    SEARCH("Search", hasScreen = true),
    THREADS("Threads", hasScreen = true),
    STATIONS("Stations", hasScreen = true),
    FREQUENCIES("Frequencies", hasScreen = true),
    EARLIER_NIGHTS("Earlier nights", hasScreen = true, drawerLabel = "Recordings"),
    CAPTURE("Capture", hasScreen = true),
    IMPROVE_RECORDS("Improve records", hasScreen = true),
    SETTINGS("Settings", hasScreen = true),
    ;

    public companion object {
        /** A divider in `Menu.dc.html` separates the log/reference group from these three. */
        public val trailingGroup: Set<ReaderDestination> = setOf(CAPTURE, IMPROVE_RECORDS, SETTINGS)
    }
}
