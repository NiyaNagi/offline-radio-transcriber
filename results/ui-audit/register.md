# UI conformance register

Phase C of [`spec/ui-conformance-plan.md`](../../spec/ui-conformance-plan.md). Every finding is
one row; every row is assigned to exactly one work package. This is the only place findings live.
Builders close rows; validators add rows; the lead confirms or rejects.

**Evidence.** Emulator screenshots taken 2026-09-07 on a Pixel 6 / API 34 AVD (`ort_audit`,
port 5554) of the app at `6aaa608`, and the source under `app/src/main/kotlin/org/ort/app/` at
`bff688b` (which includes audit fixes F-002, F-004, F-017, F-018, F-019 merged during phase A).
Each row names the artboard it was compared against; the artboards are under
[`design/canvas/`](../../design/canvas/) and the tokens in [`design/design-guide.md`](../../design/design-guide.md).

**Severity.** `halt` — wrong or misleading to the operator (constitution I) · `spec` — an
FR / P / F not met · `design` — the artboard not matched · `polish`.

**Status.** `open` · `building` (a builder owns it) · `fixed` (builder reports it closed, lead
not yet confirmed) · `closed` (confirmed on the emulator) · `rejected` (lead disagrees; reason
in the row).

---

## Theme, manifest, scaffold — WP1

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-001 | every | any phone board | No `android:theme` in the manifest, so every Activity renders a platform `DeviceDefault` ActionBar titled "Offline Radio Transcriber" *above* the Compose top bar. Every screen shows two headers. Screenshot `03-now.png`. | halt | closed |
| R-002 | launch | `Setup-Mic` | `MainActivity` is an unthemed `TextView` reading "scaffold running… Requesting microphone access…" on the platform ground, then fires bare OS prompts. Screenshot `01-launch.png`. | design | closed |
| R-003 | every drawer destination | `Main`, `Log` headers | The Compose `TopAppBar` carries the destination label as a title, and the screen body repeats it 27px below. "Now" / "Log" / "Search" each appear twice. The artboard header is icons only — drawer, live dot + elapsed, search — with no title text. | design | fixed |
| R-004 | every | `Icons` | The drawer control is the text glyph `=` in body type. The artboard is the 21px three-line stroke icon at `text/icon` in a 44px target. Same for search: absent. | design | fixed |
| R-005 | every | `Type` | `OrtType.typography` maps M3 `bodyMedium` to the 12.5px caption and `bodyLarge` to 15px, so every screen that asks for `MaterialTheme.typography.bodyMedium` gets caption size for body copy. The ramp has no 14 / 14.5 / 11.5 / 17px roles, which the boards use for controls, row titles, sub-lines and figures. Platform fallback faces are correct per constitution V; weights and tracking must still match §4 of the guide. | design | fixed |
| R-006 | every | `Tokens` | `OrtTheme` sets a colour scheme but no root `Surface`; the window ground is whatever `DeviceDefault` gives. Screens must sit on `bg/screen` `oklch(0.155 0.008 250)` (≈ `#202329`) from the theme, not from the OS. | design | closed |
| R-007 | every | — | `ReaderActivity` is `exported="false"` and `MainActivity` `finish()`es after launching it; the back stack after a cold start is `Reader` alone, which is right — but a process death mid-session relaunches into `MainActivity`'s permission flow rather than the reader. Verify and route. | polish | fixed |
| R-008 | every | guide §12 | Found on the emulator after WP1 merged (`8ad7cc1`): status-bar clock and icons render dark on the dark ground on both activities — `enableEdgeToEdge()` with no arguments chooses icon colour from the OS night-mode setting, and this app is dark-only. Both activities must pass `SystemBarStyle.dark(TRANSPARENT)` for status and navigation bars, and `themes.xml` must set `windowLightStatusBar`/`windowLightNavigationBar` false. Screenshot `wp1-02-now.png`. | design | closed |

## Drawer and navigation — WP3

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-010 | drawer | `Menu` | No icons, no session header ("Repeater watch / TH-D75A · both bands"), no divider before Capture/Improve/Settings (`ReaderDestination.trailingGroup` exists and is never read), no selected-row pill on `bg/selected`. Counts: F-020 added a Log count and Capture elapsed badge and renders Threads as "—" (deliberate — nothing populates `threadId`); Stations and Frequencies counts are still absent and the badge is plain `bodySmall` text, not the mono 11.5px `text/figure` the board specifies. Screenshot `05-drawer.png` predates F-020. | design | fixed |
| R-011 | drawer | `Grid` | Rows measure ≈31dp tall (8dp padding + one text line). Guide §5 and FR-A11Y-2: 44dp minimum, whole row is the target. | halt | fixed |
| R-012 | drawer footer | `Menu` footer, D26 | **Partly fixed by F-020** (merged during phase A): the footer now reads the audio directory's real usage and says "no budget set" visibly, so the constitution-I misrepresentation is gone. Remaining: the layout. The board is one baseline row — "Audio 38.2 GB" in `text/dim` 12px left, "of 60" mono 11px `text/figure` right — over a 4px `line/default` track with a `oklch(0.60 0.10 150)` fill, above a `line/default` top border. Built is two stacked `bodyMedium` lines and no bar. When a budget exists (WP10, FR-STO), "of N" is the budget; until then the row reads "Audio 0.8 GB · no budget set" and the bar is omitted, not drawn against device total. | design | fixed |
| R-013 | drawer | `Menu` | The three unbuilt destinations (Earlier nights, Capture, Improve records — Settings became real with F-008) look identical to the seven built ones; `hasScreen` is never rendered. Until WP10/WP11 land, an unbuilt row must say so in the row (`text/disabled`, "not built"), not only after a tap. | design | fixed |
| R-014 | drawer | — | `DrawerRow` uses `selectable` with no `Role` and overrides the merged description with "Open Now", so TalkBack never announces which destination is current. FR-A11Y-2. | spec | fixed |
| R-015 | every drawer destination | `Main` header | No search icon in any header; search is reachable only as a drawer row. `Flow-Search`: the magnifier is top-right on every destination. | design | fixed |
| R-016 | drill-ins | `Detail` header | `TransmissionDetailContent` / `StationDetailContent` / `FrequencyDetailContent` replace the whole scaffold and each screen draws its own "‹ Back" text row. The artboard header is a 20px chevron + the parent destination's label in `accent/green`, 44px target. One header component, not three text rows. | design | fixed |
| R-017 | drill-ins | `Search-Results` → `Detail` | Back from a detail opened from Search returns to the Log destination, not to Search with its filters intact (the `openTransmissionId` model has no origin). `Flow-Search`: back label reads "Search" and restores the results. | spec | fixed |

## Shared components — WP2

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-020 | every attribution | `States`, `Rows`, guide §6.1–6.2 | `AttributionMarker` draws the shape, then a text glyph (`✓` `~` `?` `—`), then the confidence for every state that has one. The artboard is shape + mono callsign, with a score chip **only** on INFERRED and the `or QRF` alternate on AMBIGUOUS; CONFIRMED shows no number. Callers then append the callsign *outside* the marker with a literal `"  "` spacer. The marker must own the callsign and the chip. | design | fixed |
| R-021 | station, frequency | `Charts`, guide §8 | `ActivityPatternChart`: bars are a single `accentGreen` with no intensity ramp (guide: five green steps, neutral for low); 34dp tall with 1dp side padding (board: 38px, 1.5px gap); not-listening is drawn as 2dp `accentAmber` diagonal lines on 5dp (guide: `hatch/bar` `oklch(0.40 0.055 60)` stripe, 2 on 5); no axis labels; the legend is the font glyph `▨` (guide §7: never a font glyph — a 9×7 hatch swatch + "not listening · 38 s"); title is a hardcoded uppercase string "ACTIVITY BY HOUR (UTC)" instead of the section-label style with the guide's copy. | design | fixed |
| R-022 | every running screen | `Main` footer, guide §6.6 | No live bar component exists. P4 and P5 both depend on it: level meter, italic partial, `Live` — pinned to the bottom of every screen while a session runs, and its degraded (amber) and halted (red) variants. | spec | fixed |
| R-023 | many | `Controls`, `Feedback`, guide §6 | No `FilterChip`, `Banner` (amber / red), `Toast` with undo, `Sheet`, `StepIndicator`, `Badge` (NEW / REVISED / CORRECTED / tier), `ScoreChip`, `Tile`, `WaveformCard`, `Radio` / `Checkbox` / `Toggle` rows, `SectionHeader` with trailing action, `EmptyState`, `FailedState`. Every screen hand-rolls text where one of these belongs. | design | fixed |
| R-024 | every text action | `Controls`, guide §6.7 | Actions are `Text` + `clickable` with no pressed state and the text's own height as the target ("Filter", "Search", "▶ Play", "‹ Back", "Save"). Guide: 44px hit area, pressed state on `bg/pressed`, `accent/green` 13px/500. | halt | fixed |
| R-025 | every | guide §7 | Icons: none of the guide's stroke set exists as composables; screens use `=`, `▶`, `‹`, `▸`/`▾`, `▨` glyphs. | design | fixed |

## Now and capture status — WP4

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-030 | Now | `Main` | "Worth knowing" and everything after `StatusScreen` is unreachable: `StatusScreen` is `fillMaxSize()` inside `NowScreen`'s `Column`, which does not scroll. Swiping does nothing. Screenshot `04-now-scrolled.png`. | halt | fixed |
| R-031 | Now | `Capture-Status` | "ASR: ASR: unavailable — …" and "VAD: VAD: energy fallback (…)": `StatusScreen.LabeledLine("ASR", …)` prefixes a value that `StatusViewStateMapper.asrLabel` already prefixes. Screenshot `03-now.png`. | halt | fixed |
| R-032 | Now | `Capture-Status`, FR-UI-7 | Of FR-UI-7's nine required facts, six are absent: input device **and verified route**, rig connection state, current tier (as a tier number, not an ASR sentence), storage used, battery. Present: state, elapsed, transmissions, backlog (as "Not measured" / "N queued"). | spec | fixed |
| R-033 | Now | `Main` | The home is a status field dump. The artboard is: header (drawer · live dot + elapsed · search), session title (`Overnight`, not "Now") + `412 overs · 19 stations · 2 bands`, the hour activity chart with the not-listening hatch and legend, `WORTH KNOWING` digest items with state dots, `STATIONS HEARD` rows with `All 19`, and the live bar. One of six elements is present. The field dump belongs on `Capture-Status`, reached from the drawer's Capture row and from the live bar. | design | fixed |
| R-034 | Now, status | `Feedback` failed state, `Fail-Model`, guide §9 | The ASR/VAD rows print the raw availability reason: an absolute file path, expected filenames, `EnergyVadModel`, "see asr-sherpa/README.md for the fetch URL". Operator surfaces use the *failed* pattern: "No transcription model installed — audio is being captured and kept; every over is transcribed once one is installed — Install a model". The path goes to Diagnostics. | halt | fixed |
| R-035 | drawer › Capture | `Capture-Status` | The Capture destination is a `PlaceholderScreen`. FR-UI-7 says the status surface is always one tap away; today it is reachable only as the body of Now. | spec | fixed |
| R-036 | Now | `Now-Idle`, `Now-First` | No idle state (no session: the artboard is "Not capturing · Last session ended …", a Start capture button, Earlier nights, Can get better) and no first-session state (chart baseline, "Nothing yet — the digest builds as overs arrive", "Listening since 23:32"). Today idle is the word "Idle" inside the field dump. | design | fixed |
| R-037 | Now | `Main` | `NowScreen` hardcodes "Worth knowing / Nothing to report yet." with a content description pointing at `spec/build-plan.md`. Until digest exists (WP10), render the first-session empty state from `Now-First`; never a developer note as operator copy. | design | fixed |
| R-038 | status | `Capture-Status` | Shed level is labelled "Shed level: Nominal / Level N — …". The artboard has no "shed level" — the operator sees Tier (`3 of 3`), Thermal (`Nominal · RTF 0.31`), Backlog (`3 overs waiting · not growing`). Map the shed levels onto those three rows. | design | fixed |
| R-039 | status | `Level-Meter` | No level surface at all (FR-CAP-3, F3). | spec | fixed |

## Log and threads — WP5

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-040 | Log | `Log`, `Rows`, `Grid` | Rows are two free-width columns of captions. The artboard row is `[time 52px mono] [freq 56px mono] [marker + callsign / transcript] [sig mono right]` at `8px 20px 9px` with a `line/row` divider — plus a mono uppercase column-header row above. None of: `Filter` action, filter chips, QSO group header (`bg/group`, "QSO · 4 overs · 2 stations"), gap row (`bg/row-gap`, "not listening · 38 s · incoming call"), rejected row (45% opacity, "rejected · squelch tail"), ambiguous row tint, `NEW` badge, `or QRF` alternate. Screenshot `06-log.png` (empty) + `Rows`. | design | building |
| R-041 | Log | `Log-Partial`, `Rows`, FR-UI-1, P5 | No Pass A partial rendering. FR-UI-1: "Pass A partials appear and are visibly replaced by Pass B finals". The artboard has three stages — `hearing…` (mini meter, italic, no marker), `resolving…` (ring, italic), then the marker with a `REVISED` badge if the text changed. `revisionNote` exists in the view state but there is no partial state at all. | spec | building |
| R-042 | Log | `Log-Filter`, FR-UI-3 | No filter on the Log. The artboard is a sheet: frequency chips with counts, attribution checkboxes with counts, "also show" rejected/gaps, a time range, `Show N overs`. | spec | building |
| R-043 | Log | `Log-Rejected`, P9 | Rejected segments are not reachable from the Log. `TransmissionDetail.rejectionReason` exists; nothing renders it as a row or offers the `Rejected` chip. | spec | building |
| R-044 | Threads | `Threads`, `Thread-Detail`, `Threads-Ungrouped`, FR-UI-2 | `ThreadScreen` is a flat list with a section-label per group and a reasoning caption per row. The artboard is: thread cards (kind · frequency · count, participants in mono, meta line), a `Thread-Detail` with the "How these were attributed" card and per-over reasoning lines linking to the source over, and a `Threads-Ungrouped` state that explains *why* (tier) and offers by-frequency meanwhile. | design | building |
| R-045 | Log, Threads | `Log-Empty` | Empty states are a bare "No transmissions yet". The artboard keeps the header, chips (dimmed) and column headers, and says "Listening since 23:32 on 145.230 and 146.960. The first one appears here the moment squelch opens." | design | building |

## Transmission detail — WP6

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-050 | detail | `Detail`, `Detail-Confirmed` | Layout does not match: built is "‹ Back" row, marker + text label, caption meta, "▶ Play" text, "Transcript" label, disclosure-arrow text rows ("▸ Correct attribution", "▸ Record labelled sample") with raw `TextField`s beneath. The artboard is chevron header with parent label, marker + 27px mono callsign, one-sentence explanation ("Heard in this over. Resolved from the phonetics at 0.94."), mono meta row, waveform card, transcript with the callsign span highlighted, inline "Why this callsign" (lattice slots, prior bars, runner-up), and a bottom action bar `Not right?` / `Confirm`. | design | building |
| R-051 | detail › why | `Detail-Why`, FR-UI-8 | The inspection surface prints candidates and priors as text lines ("K7LWH — score 8.60", "  prior: +3.10 (supported)"). The artboard is lattice slots (unit, score, kept alternate; amber border below threshold), a grammar line, a ranked candidate list with the chosen one marked, and prior *bars* — with a prior that argued against filling leftward in amber and a cold-start prior reading `cold start`, no bar. The mapper already distinguishes cold start from negative; the rendering does not. | spec | building |
| R-052 | detail › correct | `Detail-Correct-A/B/C`, `Detail-Propagated`, P3 | Correction is an inline disclosure with a lexicon `TextField` and a free-text `TextField`. The artboard is a sheet with three tiers in order (candidates → lexicon search with match highlighting → typed callsign with the *unverified* warning and a scope choice), and after applying, a `Detail-Propagated` screen: what changed (N overs, voiceprint, priors, 0 deleted), `Undo all`, the affected rows with the old callsign struck through. Today `onCorrect` runs and the detail silently `refresh()`es. | spec | building |
| R-053 | detail | `Detail` header, FR-UI-4 | An INFERRED attribution must link to the transmission that confirmed it. `Attribution.inferred(…, sourceId)` carries it; the header renders no link. The artboard sentence is "Matched by voice to **02:14:07**, where the callsign was heard clearly", tappable. | spec | building |
| R-054 | detail › playback | `Detail-Playback`, FR-UI-5 | "▶ Play" is a text action; no waveform, no position, no scrub, no speed; "No retained audio" and playback-unavailable are plain sentences. The artboard is the waveform card (§6.16) with playing / no-audio / unavailable variants and the spoken word outlined in the transcript. | design | building |
| R-055 | detail › revisions | `Detail-Revisions`, P9 | Superseded transcripts are a bare list under "Earlier versions (superseded)". The artboard is version cards (current / superseded, pass, model, time, who), a word-level diff against current, and `Restore`. | design | building |
| R-056 | detail › label | `Controls` §6.11 | The labelled-sample form uses tap-to-cycle labels ("Outcome: speech", "Certainty: certain") and four unlabelled `TextField`s whose purpose is only in the content description. Closed sets are visible lists; every field has a visible label. | design | building |
| R-057 | detail | `Detail-Ambiguous`, `Detail-Unknown` | No state-specific layout. AMBIGUOUS is the chooser (both candidates with their evidence, `Neither`, the lattice slot that split, `Leave ambiguous`). UNKNOWN is "what was tried" (grammar, voice match with nearest, thread context, kept as unidentified) and `I know who this is`. | design | building |
| R-058 | detail | `Detail` | The `Confirm` action does not exist (records that a human agreed; feeds calibration; not a fifth state). | spec | building |

## Search — WP7

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-060 | search | `Search` | Three different things read "Search": the top bar, the screen title, and the run action — and the action is bare body text indistinguishable from the filter labels above it. Screenshot `07-search.png`. | halt | fixed |
| R-061 | search | `Search-Filters`, `Controls` §6.11 | Band, attribution state and accepted/rejected are tap-to-cycle text labels: the operator cannot see the options, cannot go back (80M → all bands is ~10 taps), and cannot tell they are interactive. The artboard is a filter sheet: chips for frequency/band, checkboxes with counts for state, checkboxes for include, a time control. Screenshot `08-search-filters.png`. | halt | fixed |
| R-062 | search | `Search-Filters`, FR-UI-3 | FR-UI-3 requires a time **range**; only a single `Date (YYYY-MM-DD)` field exists. | spec | fixed |
| R-063 | search | `Search`, `Search-Results`, `Search-Empty`, `Search-Unavailable` | Initial state is four empty text fields; the artboard is a focused query field, recent searches with counts, "Try" hints, Tonight / All nights chips. Results are a flat list; the artboard groups by night, highlights matched words, shows applied filters as dismissable chips and a count line. No-results is "No results"; the artboard names the narrowing filters and offers to widen each with its count. Unavailable is a sentence; the artboard is the amber banner naming what was and was not applied, with `Retry`. | design | fixed |
| R-064 | search | guide §9 | Enum names surface raw: "Attribution state filter: CONFIRMED". Operator strings are prose: `Confirmed`. | design | fixed |
| R-065 | search | `Search` | Search results rows omit time/frequency columns the log row has. Same row component as the Log. | design | fixed |

## Stations and frequencies — WP8

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-070 | stations | `Stations` | List rows are callsign + "N transmissions · last heard …". The artboard: chips (Tonight / All time / Named / Unidentified), a column-header row, per row a state marker, mono callsign, tonight's count with context ("net control", "12 by voice match · 3 heard"), `NEW` / `CORRECTED` badges, a given name beside the callsign, mono last-heard, and a trailing "4 unidentified voices · 36 overs" row. | design | building |
| R-071 | station | `Station` | Detail is title + "N transmissions" + two charts + "THIS WEEK VS LAST" caption lines + non-tappable rows. The artboard: a facts table (overs with `Log`, attribution split, frequencies, first/last heard), "When they are around" with a legend line and a one-sentence summary, `Recent overs` with `All 612`, rows that open the over. Rows must be tappable (intent §0). | design | building |
| R-072 | station › pattern | `Station-Pattern`, FR-UI-11/12 | Day-of-week is rendered as a second bar chart. The artboard is an hour × day grid with the not-listening hatch per cell, a three-swatch legend, a By hour / Hour × day / Change over time toggle, and a "What this says" list that names the unknown days and hours explicitly. The week-over-week text lines are the "change over time" data and belong under that toggle. | design | building |
| R-073 | station › identity | `Station-Identity`, FR-SPK-10, constitution III | Absent: how the station is known (heard / lexicon / voiceprint cluster / name you gave), the never-leaves-the-device statement, `Rename`, `Split`. | spec | building |
| R-074 | frequencies | `Frequencies`, `Frequency`, `Frequency-Change` | List rows are frequency + count. The artboard: what it is (repeater · band · mode), tonight's count and station count, a 14-night sparkline with hatched nights, "busier than usual" in amber. Detail lacks the facts table (offset, tone, listened hours, nets), the typical-night chart, `Regulars`. `Frequency-Change` (departure vs usual, with the cause) is absent. | design | building |
| R-075 | station, frequency | `Charts` | Chart title says "(UTC)" while `ReaderPolling` buckets day-of-week in `ZoneId.systemDefault()`. Artboards and the operator's expectation are local time on both. Resolve to local, and drop the zone from the title. | spec | building |

## Setup — WP9

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-080 | first run | `Setup-Welcome` … `Setup-Done`, `Flow-Setup`, P8 | There is no setup sequence. `MainActivity` requests RECORD_AUDIO then POST_NOTIFICATIONS with bare OS prompts, fires the battery intent, starts capture, opens the reader. P8: a guided sequence, each step verifiable before proceeding. Thirteen boards. | spec | fixed |
| R-081 | setup › input | `Setup-Input`, `Setup-Verify`, `Setup-Route-Mismatch`, FR-CAP-2a, F1 | No input selection, no verified-route step, no halt on mismatch. `getRoutedDevice()` is checked in `:capture-android`; nothing surfaces it. | spec | fixed |
| R-082 | setup › level | `Setup-Level`, FR-CAP-3 | No level step. | spec | fixed |
| R-083 | setup › overnight | `Setup-Battery` | The battery-exemption intent fires with no rationale and no statement that the API lies (the artboard says it in the operator's words). | spec | fixed |
| R-084 | setup › radio | `Setup-Rig`, `Setup-Rig-Usb`, `Setup-Rig-Verified` | No rig setup. | spec | fixed |
| R-085 | setup › mic denied | `Setup-Mic-Denied` | Denying the mic twice leaves the app on "Requesting microphone access…" forever with no path to settings. | halt | fixed |

## Settings, improve, digest, sessions — WP10

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-090 | settings | `Settings`, `Settings-*` (10) | The Settings destination opens straight into F-008's `ModelsScreen` — there is no settings root. The board is `Settings.dc.html` (grouped rows: Capture / Records / Privacy / About, each with a one-line status sub-line) with `Settings-Assets` one row down. Absent: FR-CFG, FR-STO (retention with announced deletion), FR-TIER, FR-RIG, FR-EXP, FR-CON (per-category consent, nothing on by default, the never-included list), FR-OBS (bundle preview), About. | spec | open |
| R-093 | settings › models | `Settings-Assets`, `Fail-Lexicon`, guide §6.7 | F-008's `ModelsScreen` (real, and its `ModelsController` logic is right — keep it): the presentation is M3 filled `Button`s ("Download", "Side-load from file") side by side, status as `bodyLarge` sentences ("Installed (checksum verified)"), a free-text `lastMessage`, and the unknown-checksum reason printed in full as a paragraph. The board is one row per asset — marker (green verified / hollow not installed), name with an `active` tag, a sub-line of size · checksum prefix · tier — then a single `Install from a file` outlined button and the checksum/record-count promise beneath; a refused import is the `Fail-Lexicon` screen (what was checked, pass/fail per check, what stays active). Copy per guide §9: no "(checksum unknown — not verified against a published value)" in a row label. | design | open |
| R-091 | improve | `Improve`, `Improve-Select`, `Improve-Running`, `Improve-Done`, `Flow-Improve`, P12, FR-REP | `PlaceholderScreen`. P12 calls this a headline capability. | spec | open |
| R-092 | earlier nights, digest | `Sessions`, `Session`, `Digest`, `Digest-Item`, FR-DIG | `PlaceholderScreen` for Earlier nights; no session detail (span, coverage chart with gaps, gap list with reasons, session facts); no digest. | spec | open |

## Failure states — WP11

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-100 | every | `Fail-*` (22), §12 | Of F1–F22, only F5 has any UI (the unclean-end banner, as a sentence) and `CaptureState.failureReason` is appended to the state label as " — reason". Each failure needs its board: the banner (amber, or red for F1/F16), the copy, the recovery action, and the live-bar variant. | spec | building |
| R-101 | capture | `Fail-Route`, F1 | Route mismatch mid-session has no halt UI; the constitution says never continue. | halt | building |
| R-102 | notification | `Capture-Notification`, FR-SVC-1 | The foreground notification's content (built in `:pipeline`'s `RealCaptureService`) must be verified against N05: title `Capturing · 6:42 · 412 overs`, second line frequencies · tier (amber when degraded), expanded rows, `Open` / `Stop`. One notification, updated in place. | spec | building |
| R-103 | every | `Flow-Degrade` | Recovery is not announced (tier back to 3, rig reconnected, input back) — a toast each. | spec | building |
| R-104 | capture status | `Fail-Thermal`, `Fail-Rig`, `Capture-Status` | Found by WP0: `:pipeline` has no thermal signal at all (`ShedSignals` carries battery / backlog / storage only) and no rig-connection-state holder (the rig module is unbuilt, R-084). F7 and F9 cannot be simulated, shown, or built until a process-wide holder exists for each — the `ShedStatus` / `AsrAvailability` pattern. WP11 adds `ThermalStatus` (from `PowerManager.getThermalStatus` + measured RTF) and `RigStatus` (connected / stale-since / absent) in `:pipeline`, and `Scenarios.kt` gains `thermal` and `rig-lost`. | spec | fixed |
| R-105 | capture status | `Fail-Storage`, `Settings-Storage`, FR-STO-3 | Found by WP0: between "fine" and the 100 MiB loud stop there is no "getting low" state — FR-STO-3's early warning (the board's "warned at 3 nights left" and "1 night left" stages) has no signal to render. WP11 adds a storage-forecast facet (nights left at the current rate, thresholds) beside the floor. | spec | fixed |
| R-106 | log gap row | `Rows`, `Fail-Call`, FR-RUN-11, F15 | Found by WP0: `CaptureGapEntity.cause` is a closed enum with no call value and no reason text, so "not listening · 38 s · incoming call" is not representable — the nearest is `INTERRUPTION`. The gap row and the session's gap list both carry the reason on the boards. WP11 extends the cause enum (`CALL`, `INPUT_LOST`, `OS_STOPPED`, `ROUTE_LOST`) and the persister sets it from the `CaptureEvent` cause — a `:data` enum change, lead-approved for WP11 as the one exception to its ownership. | spec | building |
| R-107 | sessions | `Sessions`, `Settings-Tier` | Found by WP0: `SessionEntity.deviceTier` is written (`"T1"` in the `field-tier1` scenario) and read nowhere. The `Sessions` board shows a `TIER 1` chip and "can be improved" on such a session; `Improve` groups by it. WP10. | design | open |
| R-112 | capture status, level meter, setup | `Level-Meter`, `Capture-Status` (Level row), `Setup-Level`, `Fail-Level`, FR-CAP-3, F3 | Found by WP4: `:pipeline` publishes no level signal at all (peak / RMS dBFS, noise floor, clipping) — `LevelMeterScreen` can only render its honest `not measured` state and S07 cannot verify a level. WP11c adds `LevelStatus` (holder on the `ShedStatus` pattern, fed cheaply from the capture frame path without ever blocking capture) and `level-low` / `level-clip` scenarios. | spec | fixed |
| R-113 | capture status, setup | `Capture-Status` (Input row), `Setup-Verify`, `Setup-Route-Mismatch`, FR-CAP-2a, FR-UI-7 | Found by WP4: the selected input device, whether its route was verified, the native rate and the resampler identity are known inside `RealCaptureService` at open time but never republished to a process-wide holder, so the Input row reads `not measured` and setup S05 cannot show the four checks. WP11c adds `InputStatus` (device descriptor, route verified / mismatch, native rate, resampler id) published at open and on every route change, and an `input-verified` scenario. | spec | fixed |

## Debug simulator and audit tooling — WP0

| id | screen | artboard | what is wrong | sev | status |
|---|---|---|---|---|---|
| R-110 | — | plan §E | There is no way to put the app into a named state for validation. Every phase-E scenario needs `adb shell am broadcast -a org.ort.app.debug.SCENARIO --es name <x>` to load synthetic rows and set status flags, in the debug build only. | process | fixed |
| R-111 | — | plan §E | No script boots a named AVD on a given port, installs, fires a scenario, and screenshots every screen to `results/ui-audit/<scenario>/<screen>.png`. | process | fixed |
