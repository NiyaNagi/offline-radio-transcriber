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
log (L), threads (T), detail (D), search, stations/frequencies (ST/FQ), digest (DG),
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
| **WP3** | Drawer + nav host | `ui/navigation/**`. Also **moves** F-008's `ModelsContent`/`messageFor`/`copyPickedFileToCache` out of `OrtNavHost.kt` into a new `ui/settings/ModelsContent.kt` unchanged, so `OrtNavHost` dispatches `SETTINGS` to a `ui/settings` entry point WP10 then owns — WP3 may create that one file and nothing else under `ui/settings/` | WP1, WP2 |
| **WP4** | Now + capture status | `ui/screens/NowScreen.kt`, `ui/screens/StatusScreen.kt` → `CaptureStatusScreen.kt`, new `LevelMeterScreen.kt`, `NowContent.kt`, `CaptureStatusContent.kt`, `status/**`, **all of** `ui/data/ReaderPolling.kt`, the `NowSummaryMapper` file, new `ui/data/CaptureStatusViewState.kt`, `LiveBarPolling.kt`, `NowViewState.kt` | WP2 |
| **WP5** | Log + threads | `ui/screens/LogScreen.kt`, `ThreadScreen.kt`, new `ThreadDetailScreen.kt`, `LogFilterSheet.kt`, `LogContent.kt`, `ThreadContent.kt`, `ui/data/ThreadViewData.kt`, **all of** `ui/data/TransmissionDetail.kt`, new `ui/data/LogViewData.kt` | WP2 |
| **WP6** | Detail, inspection, correction, playback | `ui/screens/TransmissionDetailScreen.kt`, new `DetailWhyScreen.kt`, `DetailRevisionsScreen.kt`, `CorrectionSheet.kt`, `PropagatedScreen.kt`, `TransmissionDetailContent.kt`, `ui/data/CorrectionFlow.kt`, `InspectionSurface.kt`, `LabelledSample.kt`, new `ui/data/DetailViewState.kt`, `CorrectionPolling.kt`, `ui/audio/**` | WP2 |
| **WP7** | Search | `ui/screens/SearchScreen.kt`, new `SearchFiltersSheet.kt`, `SearchContent.kt`, `ui/data/SearchViewData.kt`, new `ui/data/RecentSearches.kt` | WP2 |
| **WP8** | Stations + frequencies | `ui/screens/StationScreen.kt`, `FrequencyScreen.kt`, new `StationPatternScreen.kt`, `StationIdentityScreen.kt`, `FrequencyChangeScreen.kt`, the four `*Content.kt` for these destinations and drill-ins, `ui/data/StationsAndFrequencies.kt`, `ui/data/ActivityPattern.kt`, new `ui/data/StationPolling.kt` | WP2 |
| **WP9** | Setup sequence | new `ui/setup/**`, `permissions/**`, `MainActivity.kt` (WP1 has merged; its interim permission screens move into `ui/setup/`), `AndroidManifest.xml` only to register `SetupActivity` | WP1, WP2 |

**The nav-host rule for this wave.** `OrtNavHost.kt` is WP3's. Every other package ships the
`*Content.kt` composable(s) its row names — the polling wrapper the host dispatches to — and WP3
deletes the inline copies from the host and calls those. A package needing a new read path adds
it in a file its row names; `ReaderPolling.kt` is WP4's alone.
| **WP10** | Settings, improve, digest, sessions | `ui/settings/**` (including `ModelsContent.kt` once WP3 has moved it), `ui/screens/ModelsScreen.kt`, `ui/data/ModelsViewData.kt`, new `ui/improve/**`, `ui/digest/**` | WP2, WP3 |
| **WP11a** | Failure *signals* — the non-UI half | `pipeline/src/main/kotlin/org/ort/pipeline/capture/{ThermalStatus,RigStatus,StorageForecast}.kt` (new holders on the `ShedStatus` pattern), `RealCaptureService.kt` (wiring and the notification only), `pipeline/.../GapPersister.kt`, `:capture-android`'s `CaptureNotificationBuilder.kt` (non-transcript fields only — AC-61 stays structural) and `AndroidShedSignals.kt`; the `CaptureGapCause` enum in `:data` (lead-approved exception, R-106); `app/src/debug/**` to add the `thermal` / `rig-lost` / `storage-warn` scenarios; tests beside each | WP0 |
| **WP11b** | Failure *screens* | new `ui/failures/**` (one composable per F-id, fed by the WP11a holders) | WP2, WP4, WP11a |

Rules every builder follows, restated because they are the ones most often skipped:

- **Read `AGENTS.md` and the constitution first.** Constitution check in the report.
- **TDD.** Test named for the AC/FR it establishes, failing for the right reason, then code.
- **Every screen composable takes a view-state, never a `Context`.** Polling and I/O stay in
  `ui/data`. This is what makes the scenario simulator able to drive every state.
- **Every artboard element becomes something the test can find** — a `testTag` or content
  description matching the intent file's interaction name.
- **Changelog entry** in the format at the top of `CHANGELOG.md`, before the commit.
- **The gate is green**, with the output in the report, not "it passed": `./gradlew build
  dependencyRules platformGuards`, then `./gradlew -p buildSrc test` (a plain `build` does not
  run buildSrc's tests), then `python tools/spec_check.py`, then `./gradlew coverageMatrix` and
  `./gradlew coverageMatrixCheck` as **separate** invocations (together they trip Gradle's
  implicit-dependency validation).
- **Never `git stash`.** The stash is shared across every worktree of this repo; one agent's
  `stash pop` has pulled in another agent's uncommitted work. Fast-forward onto `main` before
  starting; never rebase after.
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
| **V5 Search + views** | search states, stations, station-pattern, frequency-change | the five `Search*` boards, ST01–ST04, FQ01–FQ03 | 5554 |
| **V6 System** | settings, assets-missing, tier, storage-full, export, contribute, diagnostics, improve-flow, migration-failed, reconcile, lexicon-corrupt, asset-swap, calibration | CF01–CF10, R01–R04, DG01–DG04, F12–F14, F17–F22 | 5556 |
| **V7 Accessibility** | every screen at font scale 1.0 and 2.0; TalkBack tree dump per screen; greyscale | all | 5554 |

A validator's finding is one register row with the screenshot path and the artboard it was
compared against. Validators do not fix.

**Bulk capture (added 2026-09-08, WP12).** Driving the emulator by hand costs 150–300 tool calls a
pass, so screen-by-screen capture is done by the in-process screenshot tour instead:
`tools/ui-audit/tour.ps1 -Port <p>` runs `ScreenshotTourActivity` (debug build) over
`tools/ui-audit/tour.json` — each step seeds a scenario in-process, composes the real host with a
`NavSeed` for the destination or drill-in, applies the font scale through `LocalDensity`, renders
the bitmap and writes it plus a manifest line; the script pulls the set into `results/ui-audit/`.
`tools/ui-audit/diff.py --before <git ref>` lists which captures changed since the last run.
**Reviewers** (Sonnet, no device) then compare slices of the PNG set against the artboards in
parallel, each with a lead-assigned finding range; only interactive checks — taps, TalkBack tree,
system back, the correction flow — still use a validator on a port. Steps the tour cannot reach
(sheets open, submitted search states, S05/S06/S10/S11) are listed in `results/ui-audit/README.md`.

### Phase F — iterate

Lead reads every validator report, confirms or rejects each finding against the artboard, assigns
confirmed ones to the owning WP, re-briefs that builder (same agent, context intact), re-runs the
affected scenario set. Repeat until a full pass of V1–V7 files zero `halt`/`spec`/`design`
findings. `polish` findings are fixed if a builder is already open on that package.

### Phase G — close

Register empty. `CHANGELOG.md` has one entry per merged package. `design-intent.md` statuses all
`drawn` + `built`. `results/ui-audit/` holds the final screenshot set per screen per scenario.
Memory updated.

#### Close-out record (2026-09-09, main `e24b9ef`)

- **Gate.** `./gradlew build dependencyRules platformGuards` green in 479 s (lint, `:app`
  `testDebugUnitTest` 1,365 / 0, `smokeTestDebugUnitTest` 110 / 0), `-p buildSrc test` green,
  `spec_check.py` 8 / 8, `coverageMatrix` + `coverageMatrixCheck` up to date (192 of 419 ids).
- **Register** (`results/ui-audit/register.md`): 327 rows — 202 closed on a device or a committed
  capture, 121 fixed by a builder and confirmed by a later capture of the same screen, 4 not closed:
  - R-220 / R-280 / R-340 — a faint duplicate of the secondary action below the status bar on
    two-button Setup screens. Bisected to centred text on the AVD's software renderer; never seen
    on S01 (single button). **Needs a run on the reference phone or a `-gpu host` AVD** before it
    is called a product defect.
  - R-381 (partial) — the Search field's own `EditText` node exports an empty description; its
    child carries "Search text", which TalkBack reads. Three device-verified shapes failed; the
    next candidate is recorded in the CHANGELOG.
- **Accepted deviations** are recorded in the status cell of `design/design-intent.md`: S10, S11
  (FR-RIG unbuilt), CF03 (GB budget per FR-STO-3/D26; retention-order row stacks at ≥ 1.3 font
  scale), CF09 (bundle preview as a list), CF10 (no third-party version lines), DG04 (Input/Models
  not tracked per session), F21 (two of three options), Q04 (empty widen categories omitted),
  ST04 ("stable since" is the cluster's first-seen date).
- **Evidence.** Five in-process tour runs (`tools/ui-audit/tour.json`, 142 steps: every screen
  and failure state at font scale 1.0 and 2.0, scrolled-to-end frames for the long screens, the
  station sub-screens), five parallel capture-review rounds, seven validator sets with up to five
  device passes each, one final interactive sweep (`*-vfinal.png`). 797 PNGs under
  `results/ui-audit/`; the run-5 set (`tour-manifest.json`) is the final screenshot set.
- **Process lessons** are in the CHANGELOG entries for cb1d8cd and the second poison hunt
  (`7ac846b`): Compose tests that leave a poller, a file-backed `OrtDatabase` or an
  `AndroidComposeRule` activity alive poison the shared Robolectric JVM; such classes live in
  `smokeTestDebugUnitTest` (`forkEvery = 1`), and the main task runs `forkEvery = 4`.
  `clearAndSetSemantics` on a clickable must also redeclare `text`, or every text matcher and the
  chip's dismiss node disappear (R-380/R-381/R-543).

## Concurrency and safety

- **Worktrees.** Every builder runs in `isolation: worktree`. The lead merges to `main` in
  dependency order (WP0, WP1 → WP2 → the rest). Two builders on packages with no shared files can
  run concurrently; the table's `Owns` column is the conflict test.
- **Emulators.** At most two AVDs at once (`ort_audit` on 5554, `ort_audit_2` on 5556) while
  a Gradle gate runs — the host starves otherwise (2026-09-08: `adb install` hung 40 minutes).
  A third, `ort_audit_3` on 5558, may run the screenshot tour only when no full gate is running.
  Validators are told their port and never touch the others.
- **Never read the `eval` fold.** No scenario uses corpus audio; fixtures are synthetic rows.
- **Nothing leaves the device.** Fixtures contain no real callsigns tied to real people, no
  voiceprints, no location. Synthetic callsigns from the existing tests' vocabulary only.
- **Do not stop.** A blocked package is reported and re-partitioned, not abandoned. A failing
  scenario is re-run after the fix, not waived.
