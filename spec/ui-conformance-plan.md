# UI conformance program

Bring the built reader into conformance with the design canvas, then prove it on an emulator
across every app state the designs show. This plan is the working contract for that program;
it sits beside [`build-plan.md`](build-plan.md) and does not replace it.

**Inputs.** [`design/design-intent.md`](../design/design-intent.md) (the inventory — every screen,
state and interaction, cited to requirement ids), [`design/design-guide.md`](../design/design-guide.md)
(tokens and components), [`design/canvas/*.dc.html`](../design/canvas/) (the artboards).
**Output.** A reader whose every screen matches its artboard, verified by screenshot per state,
with the findings register at [`results/ui-audit/register.md`](../results/ui-audit/register.md)
closed.

## Roles

| Role | Model | Does | Does not |
|---|---|---|---|
| **Lead** | Fable | Draws every artboard. Writes this plan. Audits code and screenshots against the artboards. Partitions work, briefs agents, reviews their reports, re-delegates fixes. Merges. | Write product code. Run emulator sessions. |
| **Builder** | Sonnet | One work package at a time, in its own worktree, owning only the files the package names. Strict TDD, changelog entry, `./gradlew check` green before reporting. | Touch files outside its package. Change a design. Decide scope. |
| **Validator** | Sonnet | Boots an emulator, drives one scenario set, screenshots every screen/state in it, compares each against its artboard, files findings. | Fix anything. Modify the app. |

An agent that finds it must touch a file outside its package **stops and reports** — the lead
re-partitions. This is the rule that keeps packages from conflicting.

## Phases

```
A  Draw remaining artboards ······················ Lead
B  Review artboards vs spec (coverage + consistency) · Lead
C  Audit code + as-built UI vs artboards → register · Lead
D  Build work packages (parallel, worktrees) ······· Builders
E  Validate on emulator per scenario set ··········· Validators
F  Review reports → re-delegate fixes → repeat E ··· Lead + Builders
G  Close: register empty, changelog, memory ········ Lead
```

D and E overlap once WP0 lands: validation of early packages runs while later packages build.

### Phase A — draw

Every `planned` row in `design-intent.md` becomes `drawn`. Order: setup (S), now/capture (N),
log (L), threads (T), detail (D), search (Q), stations/frequencies (ST/FQ), digest (DG),
improve (R), settings (CF), failures (F), flows (FL), remaining foundations (Grid, Icons).
Canvas republished after each group. Done when the intent file has no `planned` row.

### Phase B — review vs spec

Lead walks four tables and fixes the artboards, not the tables:

1. `design-intent.md` §14 — each FR-UI-1..12 has at least one artboard that fully expresses it.
2. §13 P1–P12 — each principle has a screen where its cost is visibly paid.
3. §12 F1–F22 — each failure artboard shows the *response* column, not just the failure.
4. Consistency — every attribution marker, chip, banner, row and chart on every artboard matches
   the foundation board for that component. One pass, board by board.

Output: a short `design/review-2026-09.md` naming what changed and why.

### Phase C — audit → register

Lead reads every file under `app/src/main/kotlin/org/ort/app/ui/` and the manifest/theme against
the artboards, and the existing emulator screenshots against the same. Each finding is one row:

```
| id | screen | artboard | what is wrong | severity | package |
```

Severity: **halt** (wrong or misleading to the operator — constitution I), **spec** (an FR/P/F
not met), **design** (artboard not matched), **polish**. Every row is assigned to exactly one
work package below. The register is the only place findings live.

### Phase D — work packages

Partitioned by **file ownership**, so builders never conflict. A package lists every file it may
create or edit; anything else is off limits.

| WP | Scope | Owns | Depends on |
|---|---|---|---|
| **WP0** | Debug state simulator + audit tooling | `app/src/debug/**` (new `ScenarioReceiver`, `Scenarios.kt`, fixtures), `tools/ui-audit/**` (boot, install, scenario, screenshot scripts), `results/ui-audit/README.md` | — |
| **WP1** | Theme, manifest, activity scaffold | `AndroidManifest.xml`, `res/values/**`, `ui/theme/**`, `ui/ReaderActivity.kt`, `MainActivity.kt` (remove TextView smoke UI, route into setup) | — |
| **WP2** | Shared components | `ui/components/**` — `AttributionMarker`, `ActivityPatternChart`, new `LiveBar`, `FilterChip`, `Banner`, `Toast`, `LogRow`, `ScoreChip`, `Badge`, `SectionLabel`, `OrtIcons`, `EmptyState`, `FailedState` | WP1 |
| **WP3** | Drawer + nav host | `ui/navigation/**` | WP1, WP2 |
| **WP4** | Now + capture status | `ui/screens/NowScreen.kt`, `ui/screens/StatusScreen.kt` → `CaptureStatusScreen.kt`, `status/**`, `ui/data/ReaderPolling.kt` (status half), new `ui/data/CaptureStatusViewState.kt` | WP2 |
| **WP5** | Log + threads | `ui/screens/LogScreen.kt`, `ui/screens/ThreadScreen.kt`, `ui/data/ThreadViewData.kt`, `ui/data/TransmissionDetail.kt` (list-entry half), new `ui/screens/LogFilterSheet.kt` | WP2 |
| **WP6** | Detail, inspection, correction, playback | `ui/screens/TransmissionDetailScreen.kt`, `ui/data/CorrectionFlow.kt`, `ui/data/InspectionSurface.kt`, `ui/audio/**`, new `ui/screens/DetailWhyScreen.kt`, `ui/screens/CorrectionSheet.kt` | WP2 |
| **WP7** | Search | `ui/screens/SearchScreen.kt`, `ui/data/SearchViewData.kt`, new `ui/screens/SearchFiltersSheet.kt` | WP2 |
| **WP8** | Stations + frequencies | `ui/screens/StationScreen.kt`, `ui/screens/FrequencyScreen.kt`, `ui/data/StationsAndFrequencies.kt`, `ui/data/ActivityPattern.kt` | WP2 |
| **WP9** | Setup sequence | new `ui/setup/**`, `permissions/**` | WP1, WP2 |
| **WP10** | Settings, improve, digest, sessions | new `ui/settings/**`, `ui/improve/**`, `ui/digest/**` | WP2, WP3 |
| **WP11** | Failure states wiring | new `ui/failures/**` (one composable per F-id, fed by a `FailureEvent` flow), `pipeline/**` only where an event must be *emitted* (list per file in the brief) | WP2, WP4 |

Rules every builder follows, restated because they are the ones most often skipped:

- **Read `AGENTS.md` and the constitution first.** Constitution check in the report.
- **TDD.** Test named for the AC/FR it establishes, failing for the right reason, then code.
- **Every screen composable takes a view-state, never a `Context`.** Polling and I/O stay in
  `ui/data`. This is what makes the scenario simulator able to drive every state.
- **Every artboard element becomes something the test can find** — a `testTag` or content
  description matching the intent file's interaction name.
- **Changelog entry** in the format at the top of `CHANGELOG.md`, before the commit.
- **`./gradlew check` and `dependencyRules` green.** Report the output, not "it passed".
- **Report** = constitution check, files touched, tests added, what was verified, what is open.
  Never "done" without the output that shows it.

### Phase E — validation scenarios

Each validator gets one scenario set and its own emulator port. WP0's simulator makes every
state reachable by broadcast: `adb shell am broadcast -a org.ort.app.debug.SCENARIO --es name <x>`.

| Set | Scenarios | Screens exercised | Port |
|---|---|---|---|
| **V1 Setup** | fresh-install, mic-denied, route-mismatch, level-low, battery-skip, rig-usb, rig-verified | S01–S12, F01, F03, F16 | 5554 |
| **V2 Capture** | idle, first-session, overnight, unclean-end, gap-call, storage-warn, thermal, backlog, rig-lost | N01–N06, F02, F05–F09, F15 | 5556 |
| **V3 Reader** | overnight (rows of every variant), pass-a-partial, rejected-shown, filters, threads, ungrouped | L01–L05, T01–T03 | 5554 |
| **V4 Detail** | confirmed, inferred, ambiguous, unknown, corrected, no-audio, revisions, correction A/B/C, propagated | D01–D11, F10, F11 | 5556 |
| **V5 Search + views** | search states, stations, station-pattern, frequency-change | Q01–Q05, ST01–ST04, FQ01–FQ03 | 5554 |
| **V6 System** | settings, assets-missing, tier, storage-full, export, contribute, diagnostics, improve-flow, migration-failed, reconcile, lexicon-corrupt, asset-swap, calibration | CF01–CF10, R01–R04, DG01–DG04, F12–F14, F17–F22 | 5556 |
| **V7 Accessibility** | every screen at font scale 1.0 and 2.0; TalkBack tree dump per screen; greyscale | all | 5554 |

A validator's finding is one register row with the screenshot path and the artboard it was
compared against. Validators do not fix.

### Phase F — iterate

Lead reads every validator report, confirms or rejects each finding against the artboard, assigns
confirmed ones to the owning WP, re-briefs that builder (same agent, context intact), re-runs the
affected scenario set. Repeat until a full pass of V1–V7 files zero `halt`/`spec`/`design`
findings. `polish` findings are fixed if a builder is already open on that package.

### Phase G — close

Register empty. `CHANGELOG.md` has one entry per merged package. `design-intent.md` statuses all
`drawn` + `built`. `results/ui-audit/` holds the final screenshot set per screen per scenario.
Memory updated.

## Concurrency and safety

- **Worktrees.** Every builder runs in `isolation: worktree`. The lead merges to `main` in
  dependency order (WP0, WP1 → WP2 → the rest). Two builders on packages with no shared files can
  run concurrently; the table's `Owns` column is the conflict test.
- **Emulators.** At most two AVDs at once (`ort_audit` on 5554, `ort_audit_2` on 5556).
  Validators are told their port and never touch the other.
- **Never read the `eval` fold.** No scenario uses corpus audio; fixtures are synthetic rows.
- **Nothing leaves the device.** Fixtures contain no real callsigns tied to real people, no
  voiceprints, no location. Synthetic callsigns from the existing tests' vocabulary only.
- **Do not stop.** A blocked package is reported and re-partitioned, not abandoned. A failing
  scenario is re-run after the fix, not waived.
