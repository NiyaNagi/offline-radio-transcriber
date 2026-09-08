package org.ort.app.diagnostics

/**
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`'s own example: a line reads `resolved
 * [callsign] at 0.94`, never the callsign itself; FR-OBS-3, constitution V). `:lexicon`'s public
 * API (`CallsignGrammar`) parses a phonetic lattice into candidates — it has no entry point that
 * finds a callsign substring inside plain log text, so this is the brief's documented fallback: a
 * conservative pattern, a pure function, its own tests (see `CallsignScrubberTest`).
 *
 * The pattern mirrors `:lexicon`'s own `ParsedCallsign` shape (an optional secondary prefix, a
 * `letters+digit+letters` core, optional `/`-separated modifiers) closely enough to catch what a
 * real log line would contain, while staying deliberately narrow: a token needs at least one
 * letter, exactly the structural digit position a callsign core has, and a letter suffix — a bare
 * rule code like `VAD_NO_SPEECH` or a plain number like `48000` never matches, because neither
 * carries a letter immediately adjacent to a digit in this shape.
 */
public object CallsignScrubber {

    private const val REPLACEMENT = "[callsign]"

    /**
     * `(secondary-prefix/)? letters{1,2} digit letters{1,4} (/modifier){0,}` — word-bounded on
     * both ends so a match never spans into surrounding punctuation or a longer token that merely
     * contains this shape.
     */
    private val CALLSIGN_TOKEN = Regex(
        """\b(?:[A-Za-z0-9]{1,3}/)?[A-Za-z]{1,2}[0-9][A-Za-z]{1,4}(?:/[A-Za-z0-9]{1,4})*\b""",
    )

    /** Replaces every callsign-shaped token in [text] with the literal `[callsign]`. Safe to call
     * on text that has already been scrubbed — `[callsign]` itself never matches the pattern
     * again (no digit in it), so this is idempotent. */
    public fun scrub(text: String): String = CALLSIGN_TOKEN.replace(text) { REPLACEMENT }
}
