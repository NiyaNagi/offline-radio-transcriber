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
 * R-1173 is the same finding again, three screens further on, and it is why every privacy claim
 * the app makes now lives here rather than beside the composable that draws it: `README.md`,
 * `StationIdentityScreen`, `SettingsExportScreen`, a `SettingsViewData` kdoc and a `:data` entity
 * kdoc had each kept a copy of the superseded wording, and one of them ([EXPORT_NEVER_INCLUDED])
 * flatly contradicted `SettingsBackupScreen` on the same device. [VOICEPRINT_ROUTES] exists so a
 * surface cannot state *some* of FR-SPK-20's exception — naming the field report and forgetting
 * the operator's own device-to-device transfer would ship a third false claim, not a fix.
 *
 * Every constant here is checked against the spec by `OfflinePromiseCopyTest`, which sweeps them
 * by reflection so a constant added later cannot escape the check by not being listed:
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
     * FR-SPK-20's amended voiceprint exception, as one clause, so no surface can state a different
     * number of routes from another. It completes the sentence *"…can leave only "*.
     *
     * Both routes are the operator's own action and neither is a background path: a backup they
     * transfer to their own device (FR-SPK-20's *"Export MAY include them only for the user's own
     * device-to-device transfer, and SHALL say so"*), and the field-report channel, per-category
     * and defaulting off (FR-OBS-9, FR-OBS-10, D38). Stating only one of the two is how R-1173's
     * own warning gets ignored: a screen reading *"only by field report"* would contradict
     * `SettingsBackupScreen`, which already tells the operator about the other one.
     */
    const val VOICEPRINT_ROUTES: String =
        "by your own hand — a transfer you make to your own device, or a field report where you " +
            "switch that one category on"

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
            "backup. They can leave only $VOICEPRINT_ROUTES. It is off on every report " +
            "until you do, each file and its real size is named before anything is sent, and a " +
            "public destination is refused unless you have explicitly turned off the guard in Settings.",
        "Nothing is deleted quietly. Every attribution carries its confidence. A weaker phone knows " +
            "less; it is never more wrong.",
    )

    /**
     * `Station-Identity.dc.html`'s subtitle (ST04), under *How this station is known*.
     *
     * R-1173: this read *"Three things, kept separately, none of which leave this phone"*. Two of
     * the three do leave — the callsign is public by nature and is in every export, and a
     * voiceprint has [VOICEPRINT_ROUTES] — so the screen now points at the card that says which is
     * which instead of flattening all three into one absolute that was never true of all of them.
     */
    const val STATION_IDENTITY_SUBTITLE: String =
        "Three things, kept separately, each with its own rule about leaving this phone"

    /**
     * `Station-Identity.dc.html`'s lock card (ST04), beneath the three facts.
     *
     * R-1173: the *"or a backup"* clause was the false half — `SettingsBackupScreen` already tells
     * the operator that voiceprints travel in a backup, because FR-SPK-20 permits exactly that for
     * their own device-to-device transfer. The name and the note keep their absolute; the
     * voiceprint gets [VOICEPRINT_ROUTES], neither larger nor smaller than FR-SPK-20 makes it.
     */
    const val STATION_IDENTITY_CARD: String =
        "The name and the note you add here leave this phone in no channel and no tier. A " +
            "voiceprint is biometric data: never in a contribution, a diagnostic bundle or a " +
            "cloud backup, and it can leave only $VOICEPRINT_ROUTES. The callsign itself is " +
            "public by nature and is the only part of this record an export file carries."

    /**
     * `Settings-Export.dc.html`'s lock card (CF07).
     *
     * R-1173: *"Never exported, by any option"* is true of the four flat export formats and false
     * of the word *export* as FR-STO-6 and FR-SPK-20 use it — a backup is an export, and it
     * carries voiceprints. Binding the absolute to the **file** keeps it true, and the second half
     * names the two routes a voiceprint does have rather than leaving Export and Backup
     * contradicting each other on the same device.
     */
    const val EXPORT_NEVER_INCLUDED: String =
        "No export file carries any of these, whichever options you choose: voiceprints, names " +
            "you gave stations, notes, your location, the level of any signal that would locate " +
            "you. Names, notes and location leave this phone in no channel and no tier. A " +
            "voiceprint can leave only $VOICEPRINT_ROUTES."
}
