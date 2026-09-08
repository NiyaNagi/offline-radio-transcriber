# Design canvas — implemented vs artboard

This is the register of what each `.dc.html` artboard in this directory specifies against what
the Compose screens under `app/src/main/kotlin/org/ort/app/ui/` actually build. It exists because
`results/audit-2026-09-07.md` found two divergences (F-012, F-015) that had never been named
anywhere — an unnamed gap between a mockup and the shipped screen looks, from the outside,
identical to an oversight. Recording a divergence here (or in the CHANGELOG entry that introduced
it) turns it from an unrecorded defect into a named deferral: audit class F, not class D.

Read alongside `results/audit-2026-09-07.md`'s "Design fidelity" finding (its Wave summary, item
5) and its "Unbuilt milestones" section (class H) — this table does not re-itemize every artboard
belonging wholly to an unbuilt milestone (M6 identity/threading, M7 rig, M8 streaming, M9
digest/contribution/export, M10 tiers/reprocessing, M11 reference levers); those are scoped there,
per-milestone, not per-artboard, because no code exists yet to diverge from the mockup.

## Screens with a real Compose build, and what they leave out

| Artboard | Element not built | Deferred to |
|---|---|---|
| `Main.dc.html` (Now) | Per-hour activity-bar chart | M9 — a digest depends on the M4 fork decision the build plan leaves open; see `NowScreen.kt`'s own KDoc |
| `Main.dc.html` (Now) | "Worth knowing" digest section | M9 (same reason; `NowScreen` renders an honest "Nothing to report yet" instead of a look-alike) |
| `Log.dc.html` | QSO-header thread grouping | M6 — FR-UI-2, threading (P15 built search and the FTS5-backed thread *view*; grouping consecutive overs into a QSO header on the Log row itself is M6's identity/threading work) |
| `Log.dc.html` | Inline "not listening" gap row | P17 follow-up — `ActivityPatternChart` (Station/Frequency detail) already renders the three-state gap distinction (FR-UI-12); wiring an inline gap row into `LogScreen` itself is unbuilt |
| `Log.dc.html` | Rejected-row dimming | F-015 (this audit) — no visual treatment distinguishes a rejected transmission's row in `LogScreen.kt` |
| `Log.dc.html` | "New" badge | F-015 (this audit) — no "unseen since last open" concept exists in `TransmissionListEntryViewState` yet |
| `Log.dc.html` | Filter chips (band / attribution state / rejected-accepted) on the Log screen itself | F-015 (this audit) — FR-UI-3's filters were built (F-017, fixed) but live in `SearchScreen`, not as chips on `Log.dc.html` |
| `Detail.dc.html` | "Why this callsign" phonetic-lattice / per-prior panel | Built, but as `InspectionSection`'s text list (P16, FR-UI-8) rather than the artboard's visual panel — recorded in CHANGELOG at the time (search "lattice/per-prior panel") |
| `Detail.dc.html` | Playback scrubber (position/seek) | F-015 (this audit) — see "Playback scrubber" below; not deferred to a P-number because it cannot be built without a player API change |
| `Menu.dc.html` (drawer) | Live badge counts (Log's count, Threads' count, Capture's running timer) | F-020 (open) |
| `Menu.dc.html` (drawer) | Per-category storage footer (D26) | F-020 (open) — current footer is whole-device `StatFs`, flagged `isPlaceholder = true`; needs F-007's storage signal |
| *(all screens)* | Light theme | No artboard exists to derive one from — every artboard in this directory is dark-first. Not deferred to a milestone; deferred to whenever a light-theme artboard is drawn |

## Screens that match

| Artboard | Status |
|---|---|
| `States.dc.html` | Matches `AttributionMarker` exactly — all four attribution states, distinct content descriptions, colour-independent (FR-A11Y-1) |
| `Timeline.dc.html` | Built as the reusable `ActivityPatternChart` (P17) and wired into `Station.dc.html`/`Frequency.dc.html`'s detail screens — same three-state, texture-not-just-colour rendering the artboard specifies |
| `Search.dc.html` / `Threads.dc.html` | Built (P15); one deliberate wiring divergence recorded at the time — Search is reachable from the drawer rather than a magnifying-glass icon on Log's top bar, because the owning prompt's scope was drawer wiring |

## Superseded artboards

- `Editorial.dc.html` — an earlier exploratory digest/reader mockup. The digest direction actually
  being tracked is `Main.dc.html` (Now) and `Digest.dc.html`, both still M9 work. `Editorial.dc.html`
  has no open build target of its own and is not expected to get one.

## Playback scrubber (F-015) — why it is recorded, not built

`Detail.dc.html` shows a waveform and a scrubber; `TransmissionDetailScreen.kt`'s `PlaybackSection`
shows a text `▶ Play` control only. Before recording this as a pure deferral, this session checked
whether even the cheap half — a progress/position text indicator, no scrubbing — was reachable
without extending the player:

- `TransmissionAudioPlayer` (`app/src/main/kotlin/org/ort/app/ui/audio/TransmissionAudioPlayer.kt`)
  exposes exactly `suspend fun play(transmissionId): PlaybackOutcome` (a closed `Played` /
  `Unavailable(reason)` result) and `fun stop()`. Neither the interface, `RealTransmissionAudioPlayer`,
  nor `FakeTransmissionAudioPlayer` exposes playback position, duration, or an in-progress state.
- `RealTransmissionAudioPlayer.play()` decodes the whole transmission, writes it to a static
  `AudioTrack` with `MODE_STATIC`, and calls `play()` — it returns as soon as playback *starts*, not
  as it progresses, and keeps no position state a caller could poll.

Per this fix's brief: since the player does not expose position or duration, the player is **not**
extended and no progress/position text was added to `PlaybackSection`. Building either the scrubber
or even the cheap progress text would mean adding a position-reporting API to
`TransmissionAudioPlayer` (and its real and fake implementations) — a second, larger piece of work
than this finding's fix, and one that belongs to whichever prompt next touches playback. This
divergence is recorded here and in `CHANGELOG.md`, with no P-number, until that prompt exists.
