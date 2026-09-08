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
 * own tap target alike.
 */
public enum class ReaderDestination(public val label: String, public val hasScreen: Boolean) {
    NOW("Now", hasScreen = true),
    LOG("Log", hasScreen = true),
    SEARCH("Search", hasScreen = true),
    THREADS("Threads", hasScreen = true),
    STATIONS("Stations", hasScreen = true),
    FREQUENCIES("Frequencies", hasScreen = true),
    EARLIER_NIGHTS("Earlier nights", hasScreen = false),
    CAPTURE("Capture", hasScreen = true),
    IMPROVE_RECORDS("Improve records", hasScreen = false),
    SETTINGS("Settings", hasScreen = true),
    ;

    public companion object {
        /** A divider in `Menu.dc.html` separates the log/reference group from these three. */
        public val trailingGroup: Set<ReaderDestination> = setOf(CAPTURE, IMPROVE_RECORDS, SETTINGS)
    }
}
