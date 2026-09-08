package org.ort.app.ui.navigation

/**
 * The drawer's destinations, in the order `design/canvas/Menu.dc.html` lists them (build-plan
 * P13). `Now` and `Log` are backed by real screens in this prompt — the existing status and
 * transmission-list surfaces, ported to Compose (see `ui/screens/`). The rest are exposed in the
 * drawer, as the canvas requires, but render [ReaderDestination.hasScreen] `false`'s
 * [org.ort.app.ui.screens.PlaceholderScreen] until their own build-plan prompt (P15-P17) builds
 * them — reachable and honest about not existing yet, rather than missing from the drawer or
 * silently faked. `Stations` and `Frequencies` are real screens as of build-plan P17.
 */
public enum class ReaderDestination(public val label: String, public val hasScreen: Boolean) {
    NOW("Now", hasScreen = true),
    LOG("Log", hasScreen = true),
    THREADS("Threads", hasScreen = false),
    STATIONS("Stations", hasScreen = true),
    FREQUENCIES("Frequencies", hasScreen = true),
    EARLIER_NIGHTS("Earlier nights", hasScreen = false),
    CAPTURE("Capture", hasScreen = false),
    IMPROVE_RECORDS("Improve records", hasScreen = false),
    SETTINGS("Settings", hasScreen = false),
    ;

    public companion object {
        /** A divider in `Menu.dc.html` separates the log/reference group from these three. */
        public val trailingGroup: Set<ReaderDestination> = setOf(CAPTURE, IMPROVE_RECORDS, SETTINGS)
    }
}
