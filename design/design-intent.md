# Design intent

Every screen, every state, every interaction — with the requirement each one exists to serve.

This is the **inventory**. [`design-guide.md`](design-guide.md) says how these look;
[`canvas/`](canvas/) is where they are drawn. An artboard that is not listed here should not
exist; a row here with no artboard is not yet designed.

**How to read a row.** `Artboard` is the `.dc.html` file. `Serves` cites functional-spec
requirement ids — a screen with no citation is decoration and should be cut. `Interactions`
enumerates every control on the screen and what it does; this is the list an audit walks.

Status: `drawn` (artboard exists) · `drawn + built` (the built screen matches the artboard per `results/ui-audit/register.md`; accepted deviations are noted in the cell) · `planned` (in this inventory, not yet drawn).

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
| C01 | `Tokens.dc.html` | Colour ramp: surfaces, lines, nine text steps, both accent families, with the OKLCH value printed beside each swatch | FR-A11Y-4 | drawn + built |
| C02 | `Type.dc.html` | The type ramp in place — every role from §4 of the guide at real size, sans/mono split shown | FR-A11Y-3 | drawn + built |
| C03 | `Grid.dc.html` | Page margin, vertical rhythm, log row column widths, touch-target overlay at 44px | FR-A11Y-2 | drawn + built |
| C04 | `States.dc.html` | The four attribution states + greyscale proof | FR-UI-4, AC-62, FR-A11Y-1 | **drawn + built** |
| C05 | `Controls.dc.html` | Buttons, text actions, chips, fields, toggles — each in default/pressed/disabled | FR-A11Y-2 | drawn + built |
| C06 | `Rows.dc.html` | Every log row variant: normal, group header, gap, rejected, ambiguous, partial | FR-UI-1, P7, P9 | drawn + built |
| C07 | `Feedback.dc.html` | Banner, toast, dialog, empty, loading, failed — the six feedback treatments | P10 | drawn + built |
| C08 | `Icons.dc.html` | The icon set at 11/13/18/19/21px, one stroke style | — | drawn + built |
| C09 | `Charts.dc.html` | Activity chart: hour bars, day-of-week grid, the not-listening hatch, axis treatment | FR-UI-11, FR-UI-12 | drawn + built |

---

## 2. Setup — the guided sequence

P8: "Setup is a guided sequence, once. Each step verifiable before proceeding." Every step has a
verify state; no step advances on assumption. **Eight stages since 2026-09-10** (D33): the first
chooses the capture mode and presets the Input and Radio steps; the counter reads `n of 8`.
Rows marked `drawn` below with a P19 note were redrawn or added for D33/D34 and are the
subject of `spec/e2e-capture-modes-plan.md`; `results/e2e-audit/checklist.md` is their burn-down.

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| S00 | `Setup-Mode.dc.html` | **How is the radio connected?** Three capture modes — local microphone, USB-connected radio, Bluetooth-connected radio — each stating what it presets (audio route, rig link) and what it costs. Stage 1 of 8 | **FR-CAP-8, FR-CAP-9**, D33 | Tap a mode → S02 (Bluetooth mode inserts S02c after S02). Footer states a change applies at the next session (FR-CAP-12) | drawn + built — tour runs 3–5 (`setup-mode/S00`, reviewers A2/A4), V8 and V11 on device (R-851 icon added). Accepted deviation: the row emphasis is on the recommended mode, not the chosen one (R-814/R-815) |
| S01 | `Setup-Welcome.dc.html` | What this app does, offline promise, what it will ask for | FR-PLT-1 | `Begin` → S00. `What is captured?` → privacy sheet | drawn + built |
| S02 | `Setup-Mic.dc.html` | Microphone rationale before the OS prompt | FR-CAP-1 | `Allow microphone` → OS prompt → S03 (or S02c in Bluetooth mode). Denied → S02-denied | drawn + built — counter now `2 of 8` (P19 re-verifies) |
| S02b | `Setup-Mic-Denied.dc.html` | Microphone refused twice — the OS prompt is gone. Halting, with the settings path | FR-CAP-1, F1 | `Open app settings` → OS. `Check again` re-reads the grant; auto-advances to S03 when granted | drawn + built — counter now `2 of 8` |
| S02c | `Setup-Bluetooth-Permission.dc.html` | **Nearby devices** — the `BLUETOOTH_CONNECT` rationale, Bluetooth mode only. Says what it is used for (paired list, serial link, optional headset-class input) and what denying does (falls back to the USB lane, says so) | FR-CAP-8, FR-RIG-14, FR-PLT-1 | `Allow nearby devices` → OS prompt → S03. `Not now — use USB instead` → mode becomes USB, S03 | drawn + built — tour (revoked-permission pass, `setup-bt-permission/S02c` at 1.0/2.0/2.0-end, R-940 clearance confirmed by V11 and A4), V8 on device: the real OS prompt, `Don't allow`, and the USB fallback landing on S04 with the USB preset |
| S03 | `Setup-Notify.dc.html` | Notification rationale — the persistent capture notification | FR-SVC-1, FR-PLT-1 | `Allow notifications` → S04. `Skip` → S04, with a warning noted | drawn + built — counter now `3 of 8` |
| S04 | `Setup-Input.dc.html` | Choose the audio input. Lists every route with its type and native rate; a **preset chip** names the mode that preselected one; **every route is selectable** — the built-in mic and Bluetooth rows carry a disclosure sub-line, never a refusal; an unrecognised type says the app cannot identify it | FR-CAP-2, **FR-CAP-2b, FR-CAP-9, FR-CAP-10, FR-CAP-11**, CON-CAP-1 | Tap a route → selects. `Verify this input` → S05. `Refresh` re-enumerates | drawn + built — tour (`setup-mode`, `mode-local-mic`, `mode-usb`, `mode-bluetooth` S04: the built-in mic a real choice with its room-audio disclosure, the matched preset pre-selected, the honest "none attached, choose a route" chip when no route matches — R-816/R-852/R-902), V8 on device (the override by tap in every lane). The Bluetooth route row itself needs a Bluetooth audio device: hardware H5 |
| S05 | `Setup-Verify.dc.html` | Verified-route check in progress, then pass | FR-CAP-2a, F1 | Auto-advances on pass → S07. `Retry` on fail | drawn + built — counter now `4 of 8` |
| S06 | `Setup-Route-Mismatch.dc.html` | The route resolved to a device other than the selection. **Halt.** | F1, FR-CAP-3, FR-CAP-3a | `Choose another input` → S04. No "continue anyway" | drawn + built — counter now `4 of 8` |
| S07 | `Setup-Level.dc.html` | Set input level against noise, live meter, headroom target | FR-CAP-3, F3 | Level slider. `Test` arms the meter. `Continue` enabled only in range | drawn + built — counter now `5 of 8` |
| S08 | `Setup-Battery.dc.html` | Battery-optimisation exemption. States plainly that the API lies and liveness is proven by heartbeat | FR-PLT-1, F5 | `Open settings` → OS. `Skip` → S09, diagnostic-only, never blocks | drawn + built — counter now `6 of 8` |
| S09 | `Setup-Rig.dc.html` | The rig **catalogue**, generated from the installed descriptor set: each entry names its transports and per-transport capabilities; the null module and generic ASCII CAT are always present; a preset chip names the mode; `Import it` for a descriptor file | FR-RIG-1, **FR-RIG-16, FR-RIG-17, FR-RIG-18, FR-RIG-19** | Tap a rig → S09b. `No radio` → S10 (frequency entry). `Import it` → file picker → S09. `Not now` → S12 | drawn + built — tour (`setup-radio/S09`: the null entry's board copy, the inline `Import it`, one emphasised entry — R-813/R-814/R-815), V8 on device |
| S09b | `Setup-Rig-Transport.dc.html` | **How is the rig linked?** Both transports for the chosen rig with the capabilities of each, the mode's preset selected, the cost of each stated (Bluetooth drops more often; USB permission does not survive a re-plug) | **FR-RIG-13, FR-RIG-14, FR-RIG-17**, FR-CAP-9 | Pick a transport. `Connect over …` → S10 (USB) or S10b (Bluetooth). `Back` → S09 | drawn + built — tour (`setup-rig-transport`, `setup-rig-transport-preset` with the preset selected — R-941 title, E2-E09), V8 on device; the ghost "Back" at the top of the frame is the emulator renderer (R-884/R-946, hardware H14) |
| S10 | `Setup-Rig-Usb.dc.html` | USB device attach + permission grant, with the re-attach warning | FR-PLT-2, F16 | `Grant` → OS prompt → S11. `Back` → S09b | drawn — counter now `7 of 8`; **built as the honest fallback** until P19: FR-RIG was unbuilt, so every S09 rig row landed on the no-rig-support banner with manual frequency entry (R-285, R-344) |
| S10b | `Setup-Rig-Bluetooth.dc.html` | **Bluetooth rig**: the paired-device list (serial-port devices selectable, headset-only devices listed but not a rig link), `Pair a device in system settings`, then the open-link → identify → verify checklist | **FR-RIG-14, FR-RIG-15**, FR-RIG-11 | Pick a device. `Continue` enabled once verified → S11. `Use USB instead` → S10. `Refresh` re-reads the paired list | drawn + built — tour: the paired list and all four link states through `DebugRigLinkPortOverride` + `EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS` (`setup-rig-bluetooth*`, R-802/R-805/R-900/R-942 closed); V8 drove connect → identify → verify and the drop by real taps; the live link itself is hardware H2. **Board amended 2026-09-12 (R-1005c, R-1003d):** the pinned block now carries `Continue without connecting` with its consequence stated beneath it — the operator was trapped on this step on the reference device, because `Continue` is gated on `Verified` alone, the build passes `onBack = null` although this board has always drawn a back chevron, and the only escape (`Use USB instead`) is no escape for a Bluetooth-only rig. It lands on FR-RIG-2's existing null module. The board is also the first to draw the **navigation-bar inset band** below its action block per guide §10.1 — the whole reason the escape read as absent is that it was rendered *underneath* the system navigation bar |
| S11 | `Setup-Rig-Verified.dc.html` | Rig identified, transport named, descriptor and verified command set shown | FR-RIG-2, FR-RIG-3, FR-RIG-14 | `Continue` → S12. `Change radio` → S09 | drawn — counter now `7 of 8`, subtitle names the transport; **unreachable until P19** (only the `RigStatus.Stale` halt renders via `rig-lost`, R-125) |
| S12 | `Setup-Done.dc.html` | Summary of what was configured, one action to start. Now leads with the **Mode** row (`Change` → S00) and reports **Models** as bundled and verified rather than "no model yet" | P8, FR-CAP-12, FR-AST-3 | `Start capture` → N01. `Change` on Mode → S00. `Fix` rows as before | drawn + built — tour (`setup-verified`, `setup-verified-local-mic`, `assets-bundled`, `asset-corrupt`, `rig-bt-connected/S12-ready-bt` at 2.0: the amber count from real rows, "4 of 5 bundled · ready — Gemma 3 1B not in this build", the amber `Install` at 47.7 dp, every fact row wrapped at words — R-812/R-862/R-866/R-882), V8 on device (AC-130 at S12), V11 at 2.0 |

---

## 3. Now — the home

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| N00 | `Menu.dc.html` | The drawer: session header, ten destinations with icons and counts, a divider before the trailing group, the storage footer | D26, canvas `integrated` note | Row → that destination. Storage footer → CF03 | **drawn + built** |
| N01 | `Main.dc.html` | The home: header, session title + counts, activity chart, Worth knowing, Stations heard, live bar | FR-UI-1, FR-DIG-1, FR-UI-11 | Drawer. Search icon → Q01. Elapsed → N04. Chart bar → L01 filtered to that hour. Digest item → its subject. `All 19` → ST01. Station row → ST02. Live bar → N04 | **drawn + built** |
| N02 | `Now-Idle.dc.html` | No session running. Not an empty state — an idle instrument | P4 | `Start capture` → N01. Prior sessions listed → DG03 | drawn + built |
| N03 | `Now-First.dc.html` | Session running, nothing heard yet. Says so without looking broken | P4, P11 | As N01, with empty Worth knowing / Stations heard | drawn + built |
| N04 | `Capture-Status.dc.html` | **The** status surface. Running state, elapsed, input device + verified route, rig state, transmissions, backlog depth, current tier, storage used, battery | **FR-UI-7**, P4 | `Stop capture` (confirm). `Input` → CF02. `Rig` → CF06. `Storage` → CF03. `Tier` → CF05. Backlog → R01 | drawn + built |
| N05 | `Capture-Notification.dc.html` | The persistent notification, collapsed and expanded | FR-SVC-1, FR-PLT-1 | `Stop`. `Open`. Expanded shows elapsed + count | drawn + built |
| N06 | `Level-Meter.dc.html` | Live level with noise floor, headroom, clip indication | FR-CAP-3, F3 | `Adjust` → CF02. Auto-warns on low/clipping | drawn + built |
| N01b | `Main-Room-Audio.dc.html` | N01 in **local-microphone mode**: the persistent room-audio disclosure — a chip under the session title and a `room` mark in the live bar — so a session recorded from the room is never confusable with one from the radio, on any screen | **FR-CAP-3a, FR-CAP-10, AC-129** | As N01. The chip → CF11 | drawn + built — tour (`mode-local-mic/N01b` at 1.0/2.0/2.0-end; the chart and DG04's coverage share one source, R-913), V10 on device: one live bar with the `room` mark on all nine destinations (R-910/R-911), V11 at 2.0 |
| N04 | `Capture-Status.dc.html` | *(row amended 2026-09-10)* Input sub-line names the capture mode and audio route; Radio sub-line names the rig transport | FR-UI-7, FR-CAP-13, FR-RIG-14 | As before | drawn + built — tour (`mode-usb`, `mode-bluetooth`, `bt-audio-session`, `overnight-live` N04; the "no radio support in this build" sentence retired, R-839; rig name without manufacturer, R-916), V9/V10 on device |

---

## 4. Log

P7: scanned, not read.

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| L01 | `Log.dc.html` | The dense table: QSO group headers, rows, gap row, rejected row, NEW badge, alternates, signal column | FR-UI-1, P7, P9 | Row → D01. `Filter` → L02. Chip → toggles filter. Group header → T02. Gap row → explains the gap | **drawn + built** |
| L02 | `Log-Filter.dc.html` | The filter sheet: frequency, band, station, attribution state, accepted/rejected, time range | FR-UI-3 | Each filter sets a chip. `Apply`. `Clear all` | drawn + built |
| L03 | `Log-Partial.dc.html` | Pass A partials in the list, and the legible swap to Pass B | **FR-UI-1, P5** | Partial rows are italic/dim, no marker. Swap animates once, leaves a `revised` mark | drawn + built |
| L04 | `Log-Empty.dc.html` | Session running, no transmissions yet | P4 | — | drawn + built |
| L05 | `Log-Rejected.dc.html` | Rejected segments shown with their reason, reachable not hidden | **P9**, FR-ASR-5 | `Show rejected` chip. Row → D-rejected, with audio retained | drawn + built |

---

## 5. Threads

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| T01 | `Threads.dc.html` | Conversations, each with participants, span and over count | FR-UI-2 | Thread → T02 | drawn + built |
| T02 | `Thread-Detail.dc.html` | One QSO, showing **which over confirmed a callsign and which inherited it** | **FR-UI-2, FR-UI-4** | Over → D01. `confirmed in 02:14:07` link → that over | drawn + built |
| T03 | `Threads-Ungrouped.dc.html` | Threading not yet available — honest, not faked | P1, P11 | — | drawn + built |

---

## 6. Transmission detail

P2: every machine conclusion inspectable in one tap.

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| D01 | `Detail-Confirmed.dc.html` | Confirmed attribution: header, playback, transcript with the callsign span, why, correct | FR-UI-4, FR-UI-5 | `Back`. `Play`. `Full lattice` → D05. `Not right?` → D08. `Record a label` → label sheet | drawn + built |
| D02 | `Detail.dc.html` | Inferred: score + **link to the transmission that confirmed it** | **FR-UI-4** | `02:14:07` → that over. `Not right?` → D08. `Confirm` records agreement. Otherwise as D01 | **drawn + built** |
| D03 | `Detail-Ambiguous.dc.html` | Candidates with separation shown, tappable to choose | FR-UI-4, §4.1 | Candidate → applies as a correction (Tier A) | drawn + built |
| D04 | `Detail-Unknown.dc.html` | Nothing claimed. Says what was tried | P1, P11 | `Correct` → D09/D10 | drawn + built |
| D05 | `Detail-Why.dc.html` | **The inspection surface**: phonetic lattice with per-slot alternates, ranked candidates, per-prior contribution with cold-start distinguished from argued-against | **FR-UI-8, P2, F11** | Lattice slot → alternates. Prior → what it is | drawn + built |
| D06 | `Detail-Playback.dc.html` | Playing, scrubbing, no-audio-retained, unavailable | FR-UI-5 | Play/pause, scrub, speed | drawn + built |
| D07 | `Detail-Revisions.dc.html` | Superseded transcripts, kept and readable | **P9**, FR-ASR-6 | Version → diff against current | drawn + built |
| D08 | `Detail-Correct-A.dc.html` | Tier A: pick a resolved candidate | FR-UI-6, FR-SPK-23, D32 | Candidate → D11 | drawn + built |
| D09 | `Detail-Correct-B.dc.html` | Tier B: search known stations | FR-UI-6, FR-SPK-7 | Query, result → D11 | drawn + built |
| D10 | `Detail-Correct-C.dc.html` | Tier C: free text, recorded **unverified**, does not feed priors | FR-UI-6, FR-SPK-24 | Text, `Save unverified` → D11 | drawn + built |
| D11 | `Detail-Propagated.dc.html` | What the correction changed — **the propagation made visible** | **P3, FR-SPK-7** | `Undo`. `View the 6 affected overs` → L01 filtered | drawn + built |

---

## 7. Search

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| Q01 | `Search.dc.html` | Query field, recent searches, filter entry | FR-UI-3 | Type → Q03 on submit. `Filters` → Q02 | drawn + built |
| Q02 | `Search-Filters.dc.html` | Callsign, frequency, band, **time range**, attribution state, accepted/rejected | **FR-UI-3** | Each control; `Apply`; `Clear` | drawn + built |
| Q03 | `Search-Results.dc.html` | Results with matched term highlighted, filter chips shown above | FR-UI-3 | Result → D01. Chip `×` removes that filter | drawn + built |
| Q04 | `Search-Empty.dc.html` | No results, with the filters that produced none and a way to widen | FR-UI-3 | `Clear filters` | drawn + built — **accepted deviation**: a widen category is omitted when it would add zero results for the query (e.g. "Include inferred and ambiguous" for a literal typo), so the list only ever offers real ways out (R-503) |
| Q05 | `Search-Unavailable.dc.html` | Full-text index unavailable; other filters still applied. Says which | P1, F12 | `Retry` | drawn + built |

---

## 8. Stations and frequencies

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| ST01 | `Stations.dc.html` | Every station heard, with over count, last heard, state marker | FR-UI-9 | Station → ST02. Sort control | drawn + built |
| ST02 | `Station.dc.html` | One station across sessions: overs, bands, first/last heard, activity pattern | FR-UI-9, FR-UI-11 | `Overs` → L01 filtered. Pattern bar → that hour | drawn + built |
| ST03 | `Station-Pattern.dc.html` | Hour-of-day and day-of-week patterns, **not-heard vs not-listening distinguished** | **FR-UI-11, FR-UI-12** | Toggle hour/day. Hatch legend explains | drawn + built |
| ST04 | `Station-Identity.dc.html` | How this station is known: heard callsign, voiceprint cluster, operator-named. Never leaves the device | FR-SPK-10, constitution III | `Rename`. `Split cluster` (F10) | drawn + built — **accepted deviation**: "stable since <date>" is the first-seen date of the currently bound voiceprint cluster — a real fact — not a pipeline stability judgement, which does not exist; it is omitted when no voiceprint is bound (R-572) |
| FQ01 | `Frequencies.dc.html` | Every frequency heard, with band, activity, station count | FR-UI-10 | Frequency → FQ02 | drawn + built |
| FQ02 | `Frequency.dc.html` | One frequency across sessions, its regulars, its pattern | FR-UI-10, FR-UI-11 | `Overs` → L01 filtered. Station → ST02 | drawn + built |
| FQ03 | `Frequency-Change.dc.html` | A frequency departing from its usual pattern | FR-UI-11, FR-DIG | `Compare to usual` | drawn + built |

---

## 9. Digest and earlier nights

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| DG01 | `Digest.dc.html` | The night's digest: what was notable and why | FR-DIG-1..6 | Item → its subject. `Full log` → L01 | drawn + built |
| DG02 | `Digest-Item.dc.html` | One digest finding expanded, with its evidence | FR-DIG-2a, P2 | `Why this is notable`. `The 6 overs` → L01 | drawn + built |
| DG03 | `Sessions.dc.html` | Earlier nights, each with span, counts, gaps | FR-UI-1, FR-RUN-12 | Session → DG04 | drawn + built |
| DG04 | `Session.dc.html` | One past session: span, coverage, gaps, unclean end if any. *(Amended 2026-09-10)* **Mode**, **Input** (route, type, room/radio audio, Bluetooth profile where applicable) and **Rig link** (transport, stale spans) fact rows are now real per-session facts | FR-RUN-12, FR-RUN-16, **FR-CAP-13** | `Log` → L01 for that session. Gap → explains | drawn — the R-450 deviation ("not tracked per session") is **retired** for Input by FR-CAP-13's schema columns (P19 WPC1/WPF); Models stays as it was |
| DG05 | `Digest-Prose.dc.html` | The digest with the **LLM prose block**: an "In their words" section, badged `generated`, one card per thread in italic, each citing the overs it came from with a `Read the overs` action, and a footnote stating the model never names a station the log did not | **FR-DIG-3, FR-DIG-4, FR-DIG-6, FR-DIG-11**, D36 | `Read the overs` → L01 filtered to the thread. Off in CF04 | drawn + built — tour (`llm-enabled-prose/DG05` on the Digest view with a single header, `GENERATED` badge, cards titled by their stations, R-840/R-930/R-931; `llm-disabled/DG01` with no prose section, AC-140), V9 on device: `Read the overs` filters the Log to the cited overs |

---

## 10. Improve records — reprocessing

P12 calls this a **headline capability**, not an edge case: capture in the field on a spare
phone, improve it at home.

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| R01 | `Improve.dc.html` | What can get better and why — records processed below current capability | **P12, FR-REP-1..4** | `Improve all`. Group → R02 | drawn + built |
| R02 | `Improve-Select.dc.html` | Choose scope, see the estimate: time, power, what changes | FR-REP-5, P12 | Scope controls. `Start` → R03 | drawn + built |
| R03 | `Improve-Running.dc.html` | Progress, pausable, capture unaffected | FR-REP-6, constitution | `Pause`. `Cancel` | drawn + built |
| R04 | `Improve-Done.dc.html` | What changed: counts, and a diff of a sample record | FR-REP-7, P9 | `Review changes` → L01 filtered to revised | drawn + built |

---

## 11. Settings and system

| # | Artboard | Purpose | Serves | Interactions | Status |
|---|---|---|---|---|---|
| CF01 | `Settings.dc.html` | Root list, grouped | FR-CFG-1 | Each row → its screen | drawn + built |
| CF02 | `Settings-Capture.dc.html` | Input device, verified route, level, enhancement. *(Amended 2026-09-10)* leads with a **Capture mode** row | FR-CAP, FR-ENH, **FR-CAP-12** | Mode `Change` → CF11. Route → re-verify. Level → N06 | drawn + built — tour (`overnight`, `mode-bluetooth`, `mode-change-pending` CF02 at 1.0/`-end`/2.0/2.0-end: the Capture-mode row agreeing with the Input row, the profile named, the manual frequency, the viewport ending at the live bar — R-804/R-821/R-861/R-864/R-951/R-957/R-980), V9 on device |
| CF11 | `Settings-Mode.dc.html` | **Capture mode**, the settings re-entry: the same three modes as S00 with the current one marked, the two rows the mode set (audio route, rig link) each with `Change`, and — while a session is live — the amber "applies when it ends" banner | **FR-CAP-8, FR-CAP-9, FR-CAP-12, FR-CAP-13, AC-131** | Pick a mode → presets the two rows. Audio route `Change` → S04. Rig link `Change` → S09b | drawn + built — tour (`mode-change-pending/CF11`, `mode-bluetooth/CF11`: the current mode marked, the two rows stating the seeded facts — R-822/R-860/R-950/R-864), V8/V9 on device: the AC-131 banner, the pending choice applied at the next session, `Change` from CF02 |
| CF03 | `Settings-Storage.dc.html` | Used by category, retention policy, **deletion announced in advance** | **FR-STO-1..8, P9** | Policy controls. `What will be deleted` → preview | drawn — **accepted deviation**: the board's nights-based "Keep audio for N nights" control is replaced by the GB budget chips and auto-prune toggle that FR-STO-3/D26 and FR-STO-3a/AC-124 mandate (R-133); the usage bar, legend and "Next deletion … Review" row are built as drawn (R-351); the "Then stop retaining audio" row stacks its value under the label at font scale ≥ 1.3 and sits beside it below that (R-551, R-590) |
| CF04 | `Settings-Assets.dc.html` | Models and lexicon. *(Redrawn 2026-09-10, D35/D36)* every model row reads **bundled · verified · size**; a **Prose digest** section with the Gemma row ("stored, loaded only at tier 3 while idle and charging") and the `Write prose summaries` toggle; a **Space** row stating bundled assets are not counted against the recording budget; `Install from a file` retained for replacement | **FR-AST-3, FR-AST-3a, FR-AST-3b, FR-DIG-3b**, FR-AST-1..2, F13 | Toggle prose. `Install from a file`. `Replace` (deferred to next session, F21) | drawn + built — tour (`assets-bundled`, `asset-corrupt`, `tier0-llm-stored`, `model-missing` CF04 at 1.0/`-end`/2.0/2.0-end: real sizes byte for byte, the persisted checksum rejection named, a 0-byte placeholder never verified, the Lexicon row's action and the Space/Replacing sections reached — R-841/R-842/R-865/R-934/R-933/R-970), V9 on device |
| CF05 | `Settings-Tier.dc.html` | Current tier, what this device can do, **what it therefore does not know**. *(Amended)* tier 3 names the prose-digest capability; the ramp says only tier 3 loads the language model, never for callsigns | **FR-TIER, P11**, FR-DIG-3b, D5 | Tier override. Explains recall vs precision | drawn + built — tour (`overnight/CF05`: prose tier names with their cost lines, R-932/R-974), V9 on device. Accepted deviation: the summary card lists no capability bullets until P11's tier detector exists (R-952) |
| CF06 | `Settings-Rig.dc.html` | Rig state, descriptor, verified commands, band mapping. *(Amended)* the **Link** row names the transport and address with a `Switch` action; the disconnect row mentions the retry ladder | FR-RIG-1..12, **FR-RIG-14, FR-RIG-15** | `Reconnect`. `Change radio`. `Switch` → S09b | drawn + built — tour (`rig-bt-connected` CF06 at 1.0/2.0/2.0-end and `rig-bt-lost` CF06: the Rig module row with CAT mnemonics, the Link sub-line stating the address or "not yet reported", `vid/pid not yet verified (H1)`, honest "not reported by this rig module" counts and battery, "stale since" as a clock time — R-835/R-845/R-871/R-881/R-923), V9 on device. Accepted deviation: per-band over counts wait on the pipeline's band attribution |
| CF07 | `Settings-Export.dc.html` | What can be exported and in what form | FR-EXP-1..6 | Format, scope, `Export` | drawn + built |
| CF08 | `Settings-Contribute.dc.html` | Corpus contribution consent. States exactly what never leaves the device | **FR-CON-1..8, constitution III** | Per-category consent. Nothing on by default | drawn + built |
| CF09 | `Settings-Diagnostics.dc.html` | Diagnostic bundle contents, shown before it is produced | FR-OBS-1..5a | `Preview bundle`. `Save` | drawn + built — **accepted deviation**: `Preview` is an in-app listing of the bundle entries with real sizes rather than opening each file in an external reader (R-137); `Save` writes the real zip through the system file picker |
| CF10 | `Settings-About.dc.html` | Version, build, licences, the offline promise | — | — | drawn + built — **accepted deviation**: no ONNX Runtime or usb-serial-for-android version lines — the runtime ships inside sherpa-onnx and the serial library is not a dependency of this build (R-138) |

---

## 12. Failure states

Full screen each, per §12's F1–F22. Each names the failure in operator terms, says what the app
did about it, and carries the recovery action. None of these is a bare error.

| # | Artboard | Failure | Response the design must show | Serves | Status |
|---|---|---|---|---|---|
| F01 | `Fail-Route.dc.html` | F1 routed to built-in mic | **Halt.** Visible error, never continue | FR-CAP-2a | drawn + built |
| F02 | `Fail-Disconnect.dc.html` | F2 input disconnected mid-session | Surfaced immediately, retrying with backoff, session stays open | FR-CAP-4 | drawn + built |
| F03 | `Fail-Level.dc.html` | F3 level too low or clipping | Warn, link to the level meter | FR-CAP-3 | drawn + built |
| F04 | `Fail-Hallucination.dc.html` | F4 hallucination on squelch tail | Marked rejected, audio retained, counted | FR-ASR-5, P9 | drawn + built |
| F05 | `Fail-Killed.dc.html` | F5 OEM killed the service | Session recovered, gap reported, re-guide to exemption | FR-RUN-16, F5 | drawn + built |
| F06 | `Fail-Storage.dc.html` | F6 storage exhausted | Warn early, stop audio before text, **never stop capture silently** | FR-STO-3 | drawn + built |
| F07 | `Fail-Thermal.dc.html` | F7 thermal throttling | Tier degraded, surfaced, restores when cool | FR-TIER, P10 | drawn + built |
| F08 | `Fail-Backlog.dc.html` | F8 backlog unbounded | Tier degraded, capture prioritised, items marked deferred | FR-RUN-10, P10 | drawn + built |
| F09 | `Fail-Rig.dc.html` | F9 rig disconnected | Last-known frequency marked **stale**, capture continues. *(Amended 2026-09-10)* the banner names the transport that dropped and carries the reconnect-ladder sentence ("Retry 3 of 8, next in 20 s") from the real backoff — omitted, never invented, when no ladder is running | FR-RIG-9, FR-RIG-15 | drawn + built — amended; re-judge at the next tour run |
| F10 | `Fail-Cluster.dc.html` | F10 cluster mis-merge | Split, re-derive, mark corrected | FR-SPK-7 | drawn + built |
| F11 | `Fail-Wrong.dc.html` | F11 confident but wrong | Lattice and candidates always shown; correction one tap | FR-UI-8, P2 | drawn + built |
| F12 | `Fail-Lexicon.dc.html` | F12 lexicon import corrupt | Reject import, keep previous, report | FR-LEX-12 | drawn + built |
| F13 | `Fail-Model.dc.html` | F13 model missing/incompatible | Fall back to lower tier, surface it | FR-AST-3, P11 | drawn + built — accepted deviation (R-034/R-139, reconfirmed on tour run 3 by reviewer D2): the genuinely-no-model case renders as the Now screen's inline amber card with `Install a model`, not the board's full-screen narrative; the board still governs the load-failure/incompatible case (`model-missing` vs `asset-corrupt` scenarios) |
| F14 | `Fail-Clock.dc.html` | F14 clock/DST change | Durations from monotonic clock; both times stored | FR-RUN-15 | drawn + built |
| F15 | `Fail-Call.dc.html` | F15 phone call took the mic | CaptureGap recorded, resumed automatically | FR-RUN-11, FR-UI-12 | drawn + built |
| F16 | `Fail-Usb.dc.html` | F16 USB permission lost on re-attach | **Surfaced loudly** — a realistic way to lose an overnight run | FR-PLT-2 | drawn + built |
| F17 | `Fail-Interrupted.dc.html` | F17 killed mid-pass | Returned to captured, re-queued, passes idempotent | FR-RUN-8 | drawn + built |
| F18 | `Fail-Pass.dc.html` | F18 a pass errors repeatedly | Failed with recorded error, visible, manually retryable, never blocks | FR-RUN-9 | drawn + built |
| F19 | `Fail-Reconcile.dc.html` | F19 audio/DB mismatch | Both directions reported, **neither side deleted** | FR-AST-8, P9 | drawn + built |
| F20 | `Fail-Migration.dc.html` | F20 migration failed | Audio and superseded transcripts preserved, records marked for reprocessing | FR-AST-6 | drawn + built |
| F21 | `Fail-Asset-Swap.dc.html` | F21 asset replaced mid-session | Activation deferred to next session, or reprocess | FR-AST-4 | drawn + built — **accepted deviation**: two options are offered (wait for the next session, activate now) — the ones `ModelsController` can honour; the board's third, "stop capture, swap, start a new session", needs a capture-service restart seam FR-AST-4 does not require (R-544). The screen maps from the real `StagedActivation` signal (WP10/WP11b) |
| F22 | `Fail-Calibration.dc.html` | F22 confidence drift after model change | Refit calibration; versioned asset, no app release | FR-LEX-18 | drawn + built |
| F23 | `Fail-Bluetooth-Audio.dc.html` | **Bluetooth audio link dropped mid-session** (D34). The F2 response with the transport named: a gap is recorded from the moment of the drop, the retry ladder is shown, the rig link is stated as separate; every Bluetooth-captured row carries the `bt audio` mark | FR-CAP-5, **FR-CAP-11, FR-CAP-13**, FR-RUN-12 | `Retry now`. `Switch to a wired input` → S04 | drawn + built — tour (`bt-audio-dropped/F23-now` at 1.0/2.0/2.0-end and `F23-log`: the real ladder sentence, the broken-link glyph, the `Bluetooth audio dropped` gap row with the interrupted-connector glyph, the muted live bar — R-832/R-836/R-837/R-838/R-883 closed), V10 on device |

---

## 13. Flows

Screen-by-screen sequences, drawn as flow artboards with the screens as thumbnails and the
transitions labelled.

| # | Artboard | Flow | Serves | Status |
|---|---|---|---|---|
| FL1 | `Flow-Setup.dc.html` | S01 → S12, with the F1 halt branch and the skip branches | P8 | drawn + built |
| FL2 | `Flow-Capture.dc.html` | Start → capture → Pass A partial → Pass B final → log row | P5, FR-UI-1 | drawn + built |
| FL3 | `Flow-Correct.dc.html` | Log row → detail → why → correct (A/B/C) → propagation → affected overs | P2, P3 | drawn + built |
| FL4 | `Flow-Search.dc.html` | Search → filters → results → detail → back with filters intact | FR-UI-3 | drawn + built |
| FL5 | `Flow-Improve.dc.html` | Field capture on a weak device → home → improve → diff | **P12** | drawn + built |
| FL6 | `Flow-Degrade.dc.html` | Nominal → thermal → tier drop → backlog → recovery, and what is announced at each step | P10, P11 | drawn + built |
| FL7 | `Flow-Mode.dc.html` | **The three capture-mode lanes**: S00 → the shared steps with each lane's presets marked → S12, plus the settings re-entry and the independence rule (Bluetooth control with cabled audio) | **D33, FR-CAP-8, FR-CAP-9, FR-RIG-13** | drawn + built — every lane walked on the device by V8 (three fresh installs, AC-130 by hand at S12) and by the tour's `setup-*`, `mode-*` and `setup-verified-local-mic` scenarios |

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

## 15. Coverage against D33–D36 (added 2026-09-10)

The capture-mode, Bluetooth, bundling and LLM decisions, and where each is designed. This table
is the audit's first check for `spec/e2e-capture-modes-plan.md`.

| Requirement | Designed in |
|---|---|
| FR-CAP-8 closed set of modes | S00, CF11, FL7 |
| FR-CAP-9 presets both axes, each overridable | S00, S04 (preset chip), S09 (preset chip), S09b, CF11, FL7 |
| FR-CAP-10 local microphone first-class, room-audio disclosure | S04, N01b, S12 |
| FR-CAP-11 Bluetooth audio disclosed, profile recorded | S04, F23, DG04 |
| FR-CAP-12 changeable later, applies next session | S00 footer, S12, CF02, CF11 |
| FR-CAP-13 mode and route recorded per session | DG04, N04, F23 (row mark) |
| FR-CAP-2b every route selectable, disclosure not refusal | S04 |
| FR-RIG-13 transport independent of audio route | S09b, CF11, FL7 |
| FR-RIG-14 Bluetooth transport, TH-D75A parity | S09, S09b, S10b, S11, CF06, N04 |
| FR-RIG-15 Bluetooth drop degrades like USB | S09b, S10b, CF06, F9 |
| FR-RIG-16..19 catalogue-generated picker, capabilities, always-present entries, import | S09 |
| FR-AST-3, 3a, 3b bundled, sized, excluded from budget, verified | CF04, S12 |
| FR-DIG-3, 3b, 4, 6, 11 prose digest, disableable, marked, no callsign | DG05, CF04, CF05 |
| S02c Bluetooth permission | S02c, FL7 |
