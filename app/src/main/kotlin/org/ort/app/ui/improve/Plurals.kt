package org.ort.app.ui.improve

/**
 * R-141 (register, round 4 System validator): one pluralization decision for every operator-facing
 * count this package (and [org.ort.app.ui.digest], [org.ort.app.ui.settings]) renders — "1 overs",
 * "1 session(s)", "N gap(s)" were each a separate ad hoc `"$n thing(s)"`/string-template call
 * before this, and each got the singular case wrong somewhere. Lives here (not a new top-level
 * package) because Improve was the finding's first cited screen and this round's file ownership
 * covers the settings, improve and digest packages together — all three already import across each
 * other (e.g. `SessionsContent` composes WP5's `LogContent`), so this is not a new kind of edge.
 */
public object Plurals {
    /** `"1 over"` / `"64 overs"` — the regular case, an `s` appended for anything but exactly 1.
     * [irregularPlural] overrides the suffix for a noun that does not simply take `s`. */
    public fun count(n: Int, singular: String, irregularPlural: String = "${singular}s"): String =
        "$n " + if (n == 1) singular else irregularPlural
}
