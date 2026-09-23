package org.ort.app.ui

/**
 * The privacy copy that more than one screen states, held once so the surfaces cannot drift.
 *
 * `WelcomeScreen`'s own `OFFLINE_PROMISE_POINTS` used to carry [POINTS] privately while
 * `SettingsAboutScreen` re-typed the same paragraphs as literals, with a doc comment claiming the
 * two were "verbatim, not a paraphrase". They were not: the first point already differed (`:net`
 * against `only one module`), and when D37/D38 made the voiceprint guarantee false, **both**
 * screens went on repeating the superseded promise for the same reason — nothing tied them
 * together. R-1165's own finding is that the prose quoting a principle was never swept when the
 * principle was amended, so the prose now lives in one place and a test asserts both screens
 * render exactly it.
 *
 * Both constants are checked against the spec by `OfflinePromiseCopyTest`:
 *
 * - [WELCOME_PROMISE] must carry **FR-ANL-14**'s one permitted single-sentence claim verbatim, and
 *   no copy here may make the bare "no upload, ever" claim that requirement forbids (R-1164). The
 *   claim is false the moment contribution, a field report, or analytics tier 3 is turned on — and
 *   setup offers tier 3 a few screens after Welcome.
 * - [POINTS] must keep **FR-SPK-25 / FR-DIG-13 / FR-LEX-24**'s absolute guarantee (names, station
 *   knowledge and location leave in no channel and no tier) while stating **FR-SPK-20**'s amended
 *   voiceprint rule honestly: voiceprints may leave by the field-report channel (per-category, off
 *   by default, every file and its real size named before each upload, refused against a public
 *   destination unless the FR-OBS-10 switch was explicitly turned off) and by the operator's own
 *   device-to-device transfer, which `SettingsBackupScreen` already tells them about.
 *
 * Asserting on this wording is the one place the constitution's "never assert on prose" rule does
 * not apply: FR-ANL-14 makes the wording itself the requirement.
 */
internal object OfflinePromiseCopy {

    /**
     * `Setup-Welcome.dc.html`'s lead paragraph (S01), under the screen title.
     *
     * R-1164: this read *"No account, no upload, no network in the capture path — ever"* in
     * `v0.1.1`. FR-ANL-14's permitted sentence replaces the absolute; *no network call from the
     * capture path* is the half that was true, is enforced by `dependencyRules` rather than
     * promised, and stays.
     */
    const val WELCOME_PROMISE: String =
        "Your audio is processed only on your phone and is never uploaded unless you choose to " +
            "share it. Transcription, callsign resolution and the log all run here, with no " +
            "account and no network call from the capture path."

    /**
     * `Settings-About.dc.html`'s "the offline promise" section — rendered as paragraphs there and
     * as the bullets of Welcome's "What is captured" sheet, from this one list in this one order.
     *
     * R-1165 split the second point in two. It used to lead with voiceprints inside an absolute
     * that covered four categories; three of those are still absolute and say so, and voiceprints
     * now carry their own point naming the exception and every condition D38 attached to it,
     * neither larger nor smaller than it is.
     */
    val POINTS: List<String> = listOf(
        "No part of capture, transcription, resolution or the reader can reach the network. The " +
            "build enforces it: only one module may link an HTTP client, and none of these is that module.",
        "The names you give stations, what this phone has learned about who is around when, and " +
            "your location never leave this phone — not in an export, a contribution, a diagnostic " +
            "bundle, a backup, or any analytics tier.",
        "Voiceprints are biometric data: never in a contribution, a diagnostic bundle or a cloud " +
            "backup. They can leave only by your own hand — a transfer you make to your own device, " +
            "or a field report where you switch that one category on. It is off on every report " +
            "until you do, each file and its real size is named before anything is sent, and a " +
            "public destination is refused unless you have explicitly turned off the guard in Settings.",
        "Nothing is deleted quietly. Every attribution carries its confidence. A weaker phone knows " +
            "less; it is never more wrong.",
    )
}
