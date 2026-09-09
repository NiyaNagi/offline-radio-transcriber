# Design intent

Every screen, every state, every interaction — with the requirement each one exists to serve.

This is the **inventory**. [`design-guide.md`](design-guide.md) says how these look;
[`canvas/`](canvas/) is where they are drawn. An artboard that is not listed here should not
exist; a row here with no artboard is not yet designed.

**How to read a row.** `Artboard` is the `.dc.html` file. `Serves` cites functional-spec
requirement ids — a screen with no citation is decoration and should be cut. `Interactions`
enumerates every control on the screen and what it does; this is the list an audit walks.

Status: `drawn` (artboard exists) · `planned` (in this inventory, not yet drawn).

---

## 0. Conventions this inventory assumes

- **Every screen while a session runs carries the live bar** (§6.6 of the guide, P4/P5). It is
  omitted from each row's interaction list because it is universal; it is listed once here.
  Tapping it opens Capture status.
- **Every screen has the drawer** at the top-left, and the drawer is the only navigation. Back
  from a drill-in returns to the destination beneath it.
- **Every list row that represents a transmission** opens Transmission detail on tap. Stated once.
- **Every attribution rendered anywhere carries its state marker** (P1, FR-UI-4). There is no
  screen on which this is optional.

---

## 1. Foundations

The system itself, drawn so it can be checked rather than described.

| # | Artboard | Purpose | Serves | Status |
|---|---|---|---|---|
| C01 | `Tokens.dc.html` | Colour ramp: surfaces, lines, nine text steps, both accent families, with the OKLCH value printed beside each swatch | FR-A11Y-4 | drawn |
| C02 | `Type.dc.html` | The type ramp in place — every role from §4 of the guide at real size, sans/mono split shown | FR-A11Y-3 | drawn |
| C03 | `Grid.dc.html` | Page margin, vertical rhythm, log row column widths, touch-target overlay at 44px | FR-A11Y-2 | drawn |
| C04 | `States.dc.html` | The four attribution states + greyscale proof | FR-UI-4, AC-62, FR-A11Y-1 | **drawn** |
| C05 | `Controls.dc.html` | Buttons, text actions, chips, fields, toggles — each in default/pressed/disabled | FR-A11Y-2 | drawn |
| C06 | `Rows.dc.html` | Every log row variant: normal, group header, gap, rejected, ambiguous, partial | FR-UI-1, P7, P9 | drawn |
| C07 | `Feedback.dc.html` | Banner, toast, dialog, empty, loading, failed — the six feedback treatments | P10 | drawn |
| C08 | `Icons.dc.html` | The icon set at 11/13/18/19/21px, one stroke style | — | drawn |
| C09 | `Charts.dc.html` | Activity chart: hour bars, day-of-week grid, the not-listening hatch, axis treatment | FR-UI-11, FR-UI-12 | drawn |

---

## 2. Setup — the guided sequence

P8: "Setup is a guided sequence, once. Each step verifiable before proceeding." Every step has a
verify state; no step advances on assumption.

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| S01 | `Setup-Welcome.dc.html` | What this app does, offline promise, what it will ask for | FR-PLT-1 | `Begin` → S02. `What is captured?` → privacy sheet | drawn |
| S02 | `Setup-Mic.dc.html` | Microphone rationale before the OS prompt | FR-CAP-1 | `Allow microphone` → OS prompt → S03. Denied → S02-denied | drawn |
| S02b | `Setup-Mic-Denied.dc.html` | Microphone refused twice — the OS prompt is gone. Halting, with the settings path | FR-CAP-1, F1 | `Open app settings` → OS. `Check again` re-reads the grant; auto-advances to S03 when granted | drawn |
| S03 | `Setup-Notify.dc.html` | Notification rationale — the persistent capture notification | FR-SVC-1, FR-PLT-1 | `Allow notifications` → S04. `Skip` → S04, with a warning noted | drawn |
| S04 | `Setup-Input.dc.html` | Choose the audio input. Lists every route with its type | FR-CAP-2 | Tap a route → S05 verify. `Refresh` re-enumerates | drawn |
| S05 | `Setup-Verify.dc.html` | Verified-route check in progress, then pass | FR-CAP-2a, F1 | Auto-advances on pass → S06. `Retry` on fail | drawn |
| S06 | `Setup-Route-Mismatch.dc.html` | The route resolved to the built-in mic. **Halt.** | F1, FR-CAP-2a | `Choose another input` → S04. No "continue anyway" | drawn |
| S07 | `Setup-Level.dc.html` | Set input level against noise, live meter, headroom target | FR-CAP-3, F3 | Level slider. `Test` arms the meter. `Continue` enabled only in range | drawn |
| S08 | `Setup-Battery.dc.html` | Battery-optimisation exemption. States plainly that the API lies and liveness is proven by heartbeat | FR-PLT-1, F5 | `Open settings` → OS. `Skip` → S09, diagnostic-only, never blocks | drawn |
| S09 | `Setup-Rig.dc.html` | Optional rig connection, or skip | FR-RIG-1 | `Connect a radio` → S10. `Not now` → S12 | drawn |
| S10 | `Setup-Rig-Usb.dc.html` | USB device attach + permission grant, with the re-attach warning | FR-PLT-2, F16 | `Grant` → OS prompt → S11. `Back` → S09 | drawn — **built as the honest fallback**: FR-RIG is unbuilt, so every S09 rig row lands on the no-rig-support banner with manual frequency entry (R-285, R-344); the USB attach flow is deferred with FR-RIG |
| S11 | `Setup-Rig-Verified.dc.html` | Rig identified, descriptor and verified command set shown | FR-RIG-2, FR-RIG-3 | `Continue` → S12. `Change radio` → S09 | drawn — **unreachable in this build** (FR-RIG unbuilt; only the `RigStatus.Stale` "last known" halt renders via the `rig-lost` scenario, R-125) |
| S12 | `Setup-Done.dc.html` | Summary of what was configured, one action to start | P8 | `Start capture` → N01 | drawn |

---

## 3. Now — the home

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| N00 | `Menu.dc.html` | The drawer: session header, ten destinations with icons and counts, a divider before the trailing group, the storage footer | D26, canvas `integrated` note | Row → that destination. Storage footer → CF03 | **drawn** |
| N01 | `Main.dc.html` | The home: header, session title + counts, activity chart, Worth knowing, Stations heard, live bar | FR-UI-1, FR-DIG-1, FR-UI-11 | Drawer. Search icon → Q01. Elapsed → N04. Chart bar → L01 filtered to that hour. Digest item → its subject. `All 19` → ST01. Station row → ST02. Live bar → N04 | **drawn** |
| N02 | `Now-Idle.dc.html` | No session running. Not an empty state — an idle instrument | P4 | `Start capture` → N01. Prior sessions listed → DG03 | drawn |
| N03 | `Now-First.dc.html` | Session running, nothing heard yet. Says so without looking broken | P4, P11 | As N01, with empty Worth knowing / Stations heard | drawn |
| N04 | `Capture-Status.dc.html` | **The** status surface. Running state, elapsed, input device + verified route, rig state, transmissions, backlog depth, current tier, storage used, battery | **FR-UI-7**, P4 | `Stop capture` (confirm). `Input` → CF02. `Rig` → CF06. `Storage` → CF03. `Tier` → CF05. Backlog → R01 | drawn |
| N05 | `Capture-Notification.dc.html` | The persistent notification, collapsed and expanded | FR-SVC-1, FR-PLT-1 | `Stop`. `Open`. Expanded shows elapsed + count | drawn |
| N06 | `Level-Meter.dc.html` | Live level with noise floor, headroom, clip indication | FR-CAP-3, F3 | `Adjust` → CF02. Auto-warns on low/clipping | drawn |

---

## 4. Log

P7: scanned, not read.

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| L01 | `Log.dc.html` | The dense table: QSO group headers, rows, gap row, rejected row, NEW badge, alternates, signal column | FR-UI-1, P7, P9 | Row → D01. `Filter` → L02. Chip → toggles filter. Group header → T02. Gap row → explains the gap | **drawn** |
| L02 | `Log-Filter.dc.html` | The filter sheet: frequency, band, station, attribution state, accepted/rejected, time range | FR-UI-3 | Each filter sets a chip. `Apply`. `Clear all` | drawn |
| L03 | `Log-Partial.dc.html` | Pass A partials in the list, and the legible swap to Pass B | **FR-UI-1, P5** | Partial rows are italic/dim, no marker. Swap animates once, leaves a `revised` mark | drawn |
| L04 | `Log-Empty.dc.html` | Session running, no transmissions yet | P4 | — | drawn |
| L05 | `Log-Rejected.dc.html` | Rejected segments shown with their reason, reachable not hidden | **P9**, FR-ASR-5 | `Show rejected` chip. Row → D-rejected, with audio retained | drawn |

---

## 5. Threads

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| T01 | `Threads.dc.html` | Conversations, each with participants, span and over count | FR-UI-2 | Thread → T02 | drawn |
| T02 | `Thread-Detail.dc.html` | One QSO, showing **which over confirmed a callsign and which inherited it** | **FR-UI-2, FR-UI-4** | Over → D01. `confirmed in 02:14:07` link → that over | drawn |
| T03 | `Threads-Ungrouped.dc.html` | Threading not yet available — honest, not faked | P1, P11 | — | drawn |

---

## 6. Transmission detail

P2: every machine conclusion inspectable in one tap.

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| D01 | `Detail-Confirmed.dc.html` | Confirmed attribution: header, playback, transcript with the callsign span, why, correct | FR-UI-4, FR-UI-5 | `Back`. `Play`. `Full lattice` → D05. `Not right?` → D08. `Record a label` → label sheet | drawn |
| D02 | `Detail.dc.html` | Inferred: score + **link to the transmission that confirmed it** | **FR-UI-4** | `02:14:07` → that over. `Not right?` → D08. `Confirm` records agreement. Otherwise as D01 | **drawn** |
| D03 | `Detail-Ambiguous.dc.html` | Candidates with separation shown, tappable to choose | FR-UI-4, §4.1 | Candidate → applies as a correction (Tier A) | drawn |
| D04 | `Detail-Unknown.dc.html` | Nothing claimed. Says what was tried | P1, P11 | `Correct` → D09/D10 | drawn |
| D05 | `Detail-Why.dc.html` | **The inspection surface**: phonetic lattice with per-slot alternates, ranked candidates, per-prior contribution with cold-start distinguished from argued-against | **FR-UI-8, P2, F11** | Lattice slot → alternates. Prior → what it is | drawn |
| D06 | `Detail-Playback.dc.html` | Playing, scrubbing, no-audio-retained, unavailable | FR-UI-5 | Play/pause, scrub, speed | drawn |
| D07 | `Detail-Revisions.dc.html` | Superseded transcripts, kept and readable | **P9**, FR-ASR-6 | Version → diff against current | drawn |
| D08 | `Detail-Correct-A.dc.html` | Tier A: pick a resolved candidate | FR-UI-6, FR-SPK-23, D32 | Candidate → D11 | drawn |
| D09 | `Detail-Correct-B.dc.html` | Tier B: search known stations | FR-UI-6, FR-SPK-7 | Query, result → D11 | drawn |
| D10 | `Detail-Correct-C.dc.html` | Tier C: free text, recorded **unverified**, does not feed priors | FR-UI-6, FR-SPK-24 | Text, `Save unverified` → D11 | drawn |
| D11 | `Detail-Propagated.dc.html` | What the correction changed — **the propagation made visible** | **P3, FR-SPK-7** | `Undo`. `View the 6 affected overs` → L01 filtered | drawn |

---

## 7. Search

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| Q01 | `Search.dc.html` | Query field, recent searches, filter entry | FR-UI-3 | Type → Q03 on submit. `Filters` → Q02 | drawn |
| Q02 | `Search-Filters.dc.html` | Callsign, frequency, band, **time range**, attribution state, accepted/rejected | **FR-UI-3** | Each control; `Apply`; `Clear` | drawn |
| Q03 | `Search-Results.dc.html` | Results with matched term highlighted, filter chips shown above | FR-UI-3 | Result → D01. Chip `×` removes that filter | drawn |
| Q04 | `Search-Empty.dc.html` | No results, with the filters that produced none and a way to widen | FR-UI-3 | `Clear filters` | drawn |
| Q05 | `Search-Unavailable.dc.html` | Full-text index unavailable; other filters still applied. Says which | P1, F12 | `Retry` | drawn |

---

## 8. Stations and frequencies

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| ST01 | `Stations.dc.html` | Every station heard, with over count, last heard, state marker | FR-UI-9 | Station → ST02. Sort control | drawn |
| ST02 | `Station.dc.html` | One station across sessions: overs, bands, first/last heard, activity pattern | FR-UI-9, FR-UI-11 | `Overs` → L01 filtered. Pattern bar → that hour | drawn |
| ST03 | `Station-Pattern.dc.html` | Hour-of-day and day-of-week patterns, **not-heard vs not-listening distinguished** | **FR-UI-11, FR-UI-12** | Toggle hour/day. Hatch legend explains | drawn |
| ST04 | `Station-Identity.dc.html` | How this station is known: heard callsign, voiceprint cluster, operator-named. Never leaves the device | FR-SPK-10, constitution III | `Rename`. `Split cluster` (F10) | drawn |
| FQ01 | `Frequencies.dc.html` | Every frequency heard, with band, activity, station count | FR-UI-10 | Frequency → FQ02 | drawn |
| FQ02 | `Frequency.dc.html` | One frequency across sessions, its regulars, its pattern | FR-UI-10, FR-UI-11 | `Overs` → L01 filtered. Station → ST02 | drawn |
| FQ03 | `Frequency-Change.dc.html` | A frequency departing from its usual pattern | FR-UI-11, FR-DIG | `Compare to usual` | drawn |

---

## 9. Digest and earlier nights

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| DG01 | `Digest.dc.html` | The night's digest: what was notable and why | FR-DIG-1..6 | Item → its subject. `Full log` → L01 | drawn |
| DG02 | `Digest-Item.dc.html` | One digest finding expanded, with its evidence | FR-DIG-2a, P2 | `Why this is notable`. `The 6 overs` → L01 | drawn |
| DG03 | `Sessions.dc.html` | Earlier nights, each with span, counts, gaps | FR-UI-1, FR-RUN-12 | Session → DG04 | drawn |
| DG04 | `Session.dc.html` | One past session: span, coverage, gaps, unclean end if any | FR-RUN-12, FR-RUN-16 | `Log` → L01 for that session. Gap → explains | drawn |

---

## 10. Improve records — reprocessing

P12 calls this a **headline capability**, not an edge case: capture in the field on a spare
phone, improve it at home.

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| R01 | `Improve.dc.html` | What can get better and why — records processed below current capability | **P12, FR-REP-1..4** | `Improve all`. Group → R02 | drawn |
| R02 | `Improve-Select.dc.html` | Choose scope, see the estimate: time, power, what changes | FR-REP-5, P12 | Scope controls. `Start` → R03 | drawn |
| R03 | `Improve-Running.dc.html` | Progress, pausable, capture unaffected | FR-REP-6, constitution | `Pause`. `Cancel` | drawn |
| R04 | `Improve-Done.dc.html` | What changed: counts, and a diff of a sample record | FR-REP-7, P9 | `Review changes` → L01 filtered to revised | drawn |

---

## 11. Settings and system

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| CF01 | `Settings.dc.html` | Root list, grouped | FR-CFG-1 | Each row → its screen | drawn |
| CF02 | `Settings-Capture.dc.html` | Input device, verified route, level, enhancement | FR-CAP, FR-ENH | Route → re-verify. Level → N06 | drawn |
| CF03 | `Settings-Storage.dc.html` | Used by category, retention policy, **deletion announced in advance** | **FR-STO-1..8, P9** | Policy controls. `What will be deleted` → preview | drawn — **accepted deviation**: the board's nights-based "Keep audio for N nights" control is replaced by the GB budget chips and auto-prune toggle that FR-STO-3/D26 and FR-STO-3a/AC-124 mandate (R-133); the usage bar, legend and "Next deletion … Review" row are built as drawn (R-351) |
| CF04 | `Settings-Assets.dc.html` | Models and lexicon: installed, version, checksum, what is missing | FR-AST-1..9, F13 | `Install`. `Verify`. `Replace` (deferred to next session, F21) | drawn |
| CF05 | `Settings-Tier.dc.html` | Current tier, what this device can do, **what it therefore does not know** | **FR-TIER, P11** | Tier override. Explains recall vs precision | drawn |
| CF06 | `Settings-Rig.dc.html` | Rig state, descriptor, verified commands, band mapping | FR-RIG-1..12 | `Reconnect`. `Change radio` | drawn |
| CF07 | `Settings-Export.dc.html` | What can be exported and in what form | FR-EXP-1..6 | Format, scope, `Export` | drawn |
| CF08 | `Settings-Contribute.dc.html` | Corpus contribution consent. States exactly what never leaves the device | **FR-CON-1..8, constitution III** | Per-category consent. Nothing on by default | drawn |
| CF09 | `Settings-Diagnostics.dc.html` | Diagnostic bundle contents, shown before it is produced | FR-OBS-1..5a | `Preview bundle`. `Save` | drawn — **accepted deviation**: `Preview` is an in-app listing of the bundle entries with real sizes rather than opening each file in an external reader (R-137); `Save` writes the real zip through the system file picker |
| CF10 | `Settings-About.dc.html` | Version, build, licences, the offline promise | — | — | drawn — **accepted deviation**: no ONNX Runtime or usb-serial-for-android version lines — the runtime ships inside sherpa-onnx and the serial library is not a dependency of this build (R-138) |

---

## 12. Failure states

Full screen each, per §12's F1–F22. Each names the failure in operator terms, says what the app
did about it, and carries the recovery action. None of these is a bare error.

| # | Artboard | Failure | Response the design must show | Serves | Status |
|---|---|---|---|---|---|
| F01 | `Fail-Route.dc.html` | F1 routed to built-in mic | **Halt.** Visible error, never continue | FR-CAP-2a | drawn |
| F02 | `Fail-Disconnect.dc.html` | F2 input disconnected mid-session | Surfaced immediately, retrying with backoff, session stays open | FR-CAP-4 | drawn |
| F03 | `Fail-Level.dc.html` | F3 level too low or clipping | Warn, link to the level meter | FR-CAP-3 | drawn |
| F04 | `Fail-Hallucination.dc.html` | F4 hallucination on squelch tail | Marked rejected, audio retained, counted | FR-ASR-5, P9 | drawn |
| F05 | `Fail-Killed.dc.html` | F5 OEM killed the service | Session recovered, gap reported, re-guide to exemption | FR-RUN-16, F5 | drawn |
| F06 | `Fail-Storage.dc.html` | F6 storage exhausted | Warn early, stop audio before text, **never stop capture silently** | FR-STO-3 | drawn |
| F07 | `Fail-Thermal.dc.html` | F7 thermal throttling | Tier degraded, surfaced, restores when cool | FR-TIER, P10 | drawn |
| F08 | `Fail-Backlog.dc.html` | F8 backlog unbounded | Tier degraded, capture prioritised, items marked deferred | FR-RUN-10, P10 | drawn |
| F09 | `Fail-Rig.dc.html` | F9 rig disconnected | Last-known frequency marked **stale**, capture continues | FR-RIG-9 | drawn |
| F10 | `Fail-Cluster.dc.html` | F10 cluster mis-merge | Split, re-derive, mark corrected | FR-SPK-7 | drawn |
| F11 | `Fail-Wrong.dc.html` | F11 confident but wrong | Lattice and candidates always shown; correction one tap | FR-UI-8, P2 | drawn |
| F12 | `Fail-Lexicon.dc.html` | F12 lexicon import corrupt | Reject import, keep previous, report | FR-LEX-12 | drawn |
| F13 | `Fail-Model.dc.html` | F13 model missing/incompatible | Fall back to lower tier, surface it | FR-AST-3, P11 | drawn |
| F14 | `Fail-Clock.dc.html` | F14 clock/DST change | Durations from monotonic clock; both times stored | FR-RUN-15 | drawn |
| F15 | `Fail-Call.dc.html` | F15 phone call took the mic | CaptureGap recorded, resumed automatically | FR-RUN-11, FR-UI-12 | drawn |
| F16 | `Fail-Usb.dc.html` | F16 USB permission lost on re-attach | **Surfaced loudly** — a realistic way to lose an overnight run | FR-PLT-2 | drawn |
| F17 | `Fail-Interrupted.dc.html` | F17 killed mid-pass | Returned to captured, re-queued, passes idempotent | FR-RUN-8 | drawn |
| F18 | `Fail-Pass.dc.html` | F18 a pass errors repeatedly | Failed with recorded error, visible, manually retryable, never blocks | FR-RUN-9 | drawn |
| F19 | `Fail-Reconcile.dc.html` | F19 audio/DB mismatch | Both directions reported, **neither side deleted** | FR-AST-8, P9 | drawn |
| F20 | `Fail-Migration.dc.html` | F20 migration failed | Audio and superseded transcripts preserved, records marked for reprocessing | FR-AST-6 | drawn |
| F21 | `Fail-Asset-Swap.dc.html` | F21 asset replaced mid-session | Activation deferred to next session, or reprocess | FR-AST-4 | drawn |
| F22 | `Fail-Calibration.dc.html` | F22 confidence drift after model change | Refit calibration; versioned asset, no app release | FR-LEX-18 | drawn |

---

## 13. Flows

Screen-by-screen sequences, drawn as flow artboards with the screens as thumbnails and the
transitions labelled.

| # | Artboard | Flow | Serves | Status |
|---|---|---|---|---|
| FL1 | `Flow-Setup.dc.html` | S01 → S12, with the F1 halt branch and the skip branches | P8 | drawn |
| FL2 | `Flow-Capture.dc.html` | Start → capture → Pass A partial → Pass B final → log row | P5, FR-UI-1 | drawn |
| FL3 | `Flow-Correct.dc.html` | Log row → detail → why → correct (A/B/C) → propagation → affected overs | P2, P3 | drawn |
| FL4 | `Flow-Search.dc.html` | Search → filters → results → detail → back with filters intact | FR-UI-3 | drawn |
| FL5 | `Flow-Improve.dc.html` | Field capture on a weak device → home → improve → diff | **P12** | drawn |
| FL6 | `Flow-Degrade.dc.html` | Nominal → thermal → tier drop → backlog → recovery, and what is announced at each step | P10, P11 | drawn |

---

## 14. Coverage against FR-UI

The twelve reader requirements, and where each is designed. This table is the audit's first check.

| Requirement | Designed in |
|---|---|
| FR-UI-1 live view | N01, L01, L03, L04 |
| FR-UI-2 thread view | T01, T02, T03 |
| FR-UI-3 search + filters | Q01–Q05, L02 |
| FR-UI-4 four states, never omitted, inferred links to source | C04, D01–D04, L01 |
| FR-UI-5 playback with transcript | D01, D06 |
| FR-UI-6 one-tap correction | D08, D09, D10, D11 |
| FR-UI-7 capture status in one tap | N04, N05, N06 |
| FR-UI-8 candidates + lattice on demand | D05 |
| FR-UI-9 station view | ST01, ST02, ST04 |
| FR-UI-10 frequency view | FQ01, FQ02 |
| FR-UI-11 activity patterns | ST03, FQ02, FQ03, N01, C09 |
| FR-UI-12 not heard vs not listening | ST03, C09, F15, DG04 |
