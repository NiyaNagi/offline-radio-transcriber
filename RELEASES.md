# Releases

This is the human-readable release history — what changed, in plain language, for someone
installing the app. It is **not** [`CHANGELOG.md`](CHANGELOG.md): that file is the project's
engineering build log, one dense entry per commit (scope, requirement ids, what changed, how it
was verified, what was left open), and it stays that way — it is written for the next person
working on the code, not the next person installing it. This file is written for the second
person. If you want the requirement id, the commit hash, or how a fix was verified, follow this
file's "Engineering detail" link down into `CHANGELOG.md`; if you just want to know what's new,
stay here.

Newest first. `## Unreleased` at the top collects what has landed since the last numbered
version — it is what the rolling "latest build" prerelease on GitHub shows, and it is kept
current as work lands, not written all at once when a release is cut (see
[`RELEASING.md`](RELEASING.md)). Each `## vX.Y.Z — date` section below it is a numbered
milestone. Within a version, only the headings that have something to say appear: `New`,
`Improved`, `Fixed`, `Known issues`, `Install`.

**Version policy:** this project stays on `0.1.x` and does not move to `0.2` until basic
transcribing works with actual models built in. See `RELEASING.md`.

---

## Unreleased

Everything in v0.1.1 below, plus the items here. This is what the rolling "latest build"
prerelease contains.

### Changed

- **Setting the app up is now four screens instead of twelve.** A first run asks what it genuinely
  cannot start without and nothing else: what the app is and two notes to acknowledge, how the radio
  is connected, then one screen where you pick the input, watch the app prove it really is the radio,
  and set the level. That's it — capture starts.

  Everything the old flow asked for is still asked, just at the moment it matters instead of up
  front. Notifications are requested when capture first starts, where the notification is about to
  appear. The battery-optimisation prompt appears if the app is actually stopped in the background,
  because that is the first moment there is anything to prove. The frequency moved into the log's own
  header, where you can see what it is labelling and change it in place. The radio-control steps
  appear only once there is a radio module that can actually talk to a rig — there isn't one yet, so
  they no longer appear at all, which is the honest version of what the old flow implied.

- **The wording throughout is plainer.** *"a handheld near the phone · room audio · frequency by
  hand"* is now *"the phone listens to a handheld or speaker nearby"*. The privacy statement appears
  once, on the first screen, instead of on four screens in slightly different words — two of which
  were saying something that had stopped being true.

### Fixed

- **Setup could trap you on the "Running overnight" screen with no way out.** After finishing
  setup you were sent straight back to it, and both of its buttons returned you there again — on a
  fresh install there was no escape but uninstalling. The cause was circular: the app would not
  start capturing until it had proof it survives running in the background, and the only thing that
  can produce that proof is a capture it would not start. The battery-optimisation prompt now
  appears once per launch and never blocks capture, and the screen has a **Back to the app** action
  whenever it appears after setup is already done.
- **The welcome screen made a privacy claim this app cannot honestly make.** It said there was no
  upload, *ever*. That is not true of an app that offers to share audio, a field report, or a
  contribution at your choice — and it offered one of those a few screens later. It now states the
  one accurate promise: *your audio is processed only on your phone and is never uploaded unless
  you choose to share it.*
- **Two screens still said voiceprints never leave the device.** They can, by one route you control
  — a field report where you switch that category on, or a transfer you make to your own device.
  Names you give stations, what the phone has learned about who is around when, and your location
  still leave in no channel and no tier, and those screens now say exactly that instead of an
  absolute that had stopped being true.

### New

- **Input gain, on setup's Level step.** A slider beside the live meter, 0 to 12 dB, which takes
  effect immediately on the audio being captured rather than waiting for a restart. It is honest
  about what it is: a software multiply applied after the audio has been digitised, so it raises
  the noise floor by exactly as much as the speech and cannot rescue an input already clipping.
  Set the level on the radio first; use this only when an adapter is so quiet that speech wastes
  most of its range.
- **The app now asks Android for unprocessed audio where the device offers it**, instead of always
  taking the voice-recognition input, which on many phones applies its own automatic gain and noise
  suppression underneath you. Which one it actually got is shown on the setup step that verifies
  your input.

### Improved

- **Continue is no longer greyed out while setup waits for something.** It stays available and, if
  you press it before the step is satisfied, tells you what is missing rather than leaving you to
  guess. A greyed-out button also cannot be reached by a screen reader at all, which meant the one
  control blocking you was the one control you could not find. Checks that genuinely must pass —
  proving your chosen input really is the radio, and a verified rig link — still refuse to proceed;
  they now say so instead of going quiet.

### Correction

v0.1.1's notes below overclaimed two things that did not actually work as described in that
build. Neither has shipped code changed yet — this correction is to the record, so the wording in
the app catches up in a following release:

- **"Groups related transmissions into threads automatically"** — conversations were not, in
  fact, grouped into threads automatically in that build. Automatic thread grouping is specified
  and is planned, but it wasn't built as of v0.1.1.
- **"Apply that correction to every other past transmission recognized as the same voice"** —
  there is no voice-recognition library in the app yet. What the correction actually applies to
  is every other transmission with the **same callsign**, not the same voice. Real voice-based
  correction is planned for after the app's first full release.

---

## v0.1.1 — 2026-09-09

The first numbered build since the interface itself was designed. Every screen in the app —
95 design artboards' worth — was built, then audited screen-by-screen against its design and
fixed until it matched. There is still no real transcription in this build (see Known issues
below); what this release proves is the reader you will use once there is.

### New

- Every screen the app needs now exists and is reachable: guided first-run setup, a live
  capture status screen, the transmission log, conversation threads, a full transmission detail
  and inspection view, search, station and frequency pages, settings, a digest, and an "improve"
  flow for reprocessing older recordings at a better tier.
- A guided setup sequence walks through microphone and notification permissions, checking your
  audio input route and level, and connecting a rig, before your first recording session.
- A live status bar — signal level, elapsed session time, and a "Live" indicator — now stays
  visible at the bottom of every screen while a session is recording.
- The log shows a plain-language marker for how sure the app is about each callsign
  (confirmed, inferred, ambiguous, or unknown) instead of just a bare guess, and groups related
  transmissions into threads automatically.
- Tapping a transmission opens a detail view: the phonetic breakdown behind a callsign guess,
  the recorded waveform with scrubbing, and the full history of any corrections made to it.
- You can correct a callsign by hand, and choose to apply that correction to every other past
  transmission recognized as the same voice.
- Search finds transmissions by callsign, frequency, or transcript text, with filters and the
  matching words highlighted in results.
- Station and frequency pages show activity-by-hour-and-day patterns, and let you give a
  station a name and a note.
- Settings gained real pages for storage usage (with a "what gets deleted next" preview),
  a diagnostics export, model management, and an About screen with the offline-privacy promise
  stated plainly and the real installed version and component versions.
- A digest view summarizes recent activity.
- Every failure case — lost microphone route, disconnected rig, storage running out, a corrupt
  lexicon file, a failed model update, an interrupted session, and others — now has its own
  screen that explains what happened and what to do about it, instead of the app just going
  quiet or showing a generic error.
- Debug builds carry a hidden scenario simulator that can jump the app straight into any of 19
  fixture states (empty log, overnight session, low storage, rig lost, and so on), used to test
  every screen without needing hours of real radio traffic.

### Improved

- The whole app is readable at large text sizes now — every screen was checked at double the
  normal font size and fixed so nothing overlaps, clips, or gets crushed.
- Every tappable control is at least a comfortably-sized tap target, and nothing depends on
  color alone to tell you its state — screen-reader labels were added throughout.
- The dark theme is now applied consistently everywhere, including the status bar and
  navigation bar icons, which used to render wrong depending on the phone's own dark-mode
  setting.
- Screens now share one consistent set of building blocks (buttons, chips, banners, charts,
  markers) instead of each screen hand-rolling its own look, so styling is consistent from
  screen to screen.
- The navigation drawer shows real counts and a live capture indicator instead of placeholder
  dashes.
- Storage usage in the drawer and in Settings is now shown honestly — it no longer implies a
  storage budget the app doesn't actually have yet.

### Fixed

- A full screen-by-screen audit catalogued 338 differences between the built app and its
  intended design, and 305 of them are fixed and confirmed against a screenshot of the built
  screen. The rest are listed under Known issues or are waiting on a check we can only do on
  real hardware. Fixed along the way: duplicate headers, missing icons, broken "back"
  navigation that used to lose your search filters, text colliding at large font sizes, and the
  live-capture status bar overlapping content it should have made room for, among many others.
- Fixed a bug where reopening the app during a running recording session could show a stale or
  wrong session's status instead of the one actually recording.
- The storage bar in Settings drew as almost full when nothing was stored yet — the opposite of
  the truth. It had two separate causes: an empty store was not recognised as empty, and the
  check that decided this compared a number formatted for your phone's language, so on any
  language that writes decimals with a comma it never matched at all.
- The model list in Settings showed three rows per transcription model instead of one.
- The interface no longer shrinks on large phones. Every screen was designed at a fixed width,
  so on a wider display everything used to occupy less of the screen than intended; the app now
  lays out at the design's own width and scales up to fit, while your system text-size setting
  still applies on top.

### Known issues

- Transcription models are not bundled with the app yet, so it cannot transcribe real audio in
  this build — the reader interface works, but there is nothing for it to show from a real
  recording session yet.
- Radio/rig support has not been built yet. Setup's rig-connection step falls back to entering
  your frequency by hand instead of reading it from a connected radio.
- On emulators, two text-rendering oddities show up on some Setup screens: a faint duplicate of
  the secondary button below the status bar, and one line of body text rendering distorted after
  scrolling. Both were traced to how the emulator draws text in software, and neither has been
  confirmed on a real phone yet.
- The search box's own accessibility label doesn't reach screen readers correctly — a separate,
  correctly-labelled element next to it does carry the label, so a screen reader still works,
  but the field itself reports an empty description.

### Install

This is a debug build, so Android will warn it's from an unknown source — that's expected for a
sideload, not a sign anything is wrong.

1. Download `app-debug.apk` from this release's Assets, on the phone you want to install it on
   (or copy it over after downloading elsewhere).
2. Allow your browser or file manager to install unknown apps, if asked: **Settings → Apps →
   [that app] → Install unknown apps → Allow**.
3. Open the downloaded file and confirm the install. Requires Android 8.0 (minSdk 26) or newer.
4. On first launch, the app walks you through microphone and notification permissions and an
   audio input check before it lets you start a session.

This debug build also carries a hidden scenario simulator (used for testing, not part of the
normal app) that can force the app into any of 19 fixture states — you won't need it for normal
use, but if you see a screen that looks like a fixed test state rather than your own data,
that's what it is.
