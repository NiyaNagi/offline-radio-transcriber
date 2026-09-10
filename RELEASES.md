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

Nothing user-facing yet. Changes since v0.1.1 are internal: the project's binding principles and
test plan now describe how the interface is checked against its designs, and there is a written
procedure for continuing that work from real device testing.

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
