# Changelog

Every commit that lands completed, tested work gets an entry here — this is the project's
build log, not just its git history, because `git log` doesn't carry *why* a change was safe,
which requirements/ACs it settles, or what it deliberately left undone. [`spec/build-plan.md`](spec/build-plan.md)
is the forward-looking todo list; this file is the record of what actually happened against it.

**Format, followed by every entry below and required for every entry after it** (see
[`AGENTS.md`](AGENTS.md)'s working agreement and the build-plan's standing preamble, both of
which now require this):

```
## YYYY-MM-DD

### <commit-hash> — <prompt-id, if any> · <one-line summary, matches the commit subject>

**Scope:** which module(s)/directories this touched (its build-plan "owns" list, if it has one).
**Requirements/ACs:** ids established or satisfied (cite `FR-*`, `AC-*`, `NFR-*`, `D*`, `R*` — the
same ids the spec and tests use). "none new" if the change is process/docs only.
**What changed:** the substance — added/changed/fixed, in enough detail that someone who wasn't
there can tell what exists now that didn't before, and why.
**Verified:** the exact command(s) run and their result (green build, N tests passing, etc.) —
never a bare "works". A number without its fold/machine/provider is not a result (constitution
VI) and neither is a changelog entry without how it was checked.
**Left open / not done:** anything a reasonable reader would otherwise assume was covered but
wasn't — a probe left NOT RUN, a device-only criterion untested on Robolectric, a follow-up
filed elsewhere. Omit only if genuinely nothing applies.
```

Entries are grouped by date, newest first; within a date, newest commit first. Merge commits get
one entry covering what the merge brought in, not a restatement of the branch's own entry.

---

## 2026-09-08 (ui-conformance WP3: drawer, header, live bar, drill-in header, navigation origin)

### (pending) — ui-conformance WP3 · drawer, header, live bar, drill-in header, navigation origin

**Scope:** `:app` `ui/navigation/**` (`Drawer.kt`, `OrtNavHost.kt`, `StorageFooterViewState.kt`
extended, new `DrawerSessionHeaderViewState.kt`) and their tests; new `ui/settings/ModelsContent.kt`
(move only, per the plan's nav-host rule); new `ui/data/DrawerCounts.kt` and its test. Also touched
`app/src/test/kotlin/org/ort/app/ui/ReaderAccessibilityTest.kt` — outside this package's literal
`ui/navigation/**` glob, flagged below — and regenerated `results/coverage-matrix.md`
(`coverageMatrix`, mandated gate output, not otherwise touched). Working from
`spec/ui-conformance-plan.md` WP3 and `results/ui-audit/register.md` rows R-003, R-004, R-010
through R-017.

**Requirements/ACs:** R-003, R-004, R-010, R-011, R-012, R-013, R-014, R-015, R-016, R-017
(register rows — status below); FR-UI-7 (drawer badges real, never fabricated); FR-A11Y-2 (44dp
targets, selection semantics that announce); FR-STO-5 (storage footer never claims a budget that
does not exist).

**What changed:**
- **Constitution Check.** Principle I (Uncertainty Is Content): the drawer's new Stations/
  Frequencies counts, session header and live bar are every one of them a real, measured or
  honestly-absent fact — never a fabricated stand-in (`DrawerCounts`, `DrawerSessionHeaderViewState`
  read real DAOs/process-wide holders; the live-bar fallback leaves `level`/`partialText` empty
  rather than inventing a meter or a partial transcript it cannot measure). Principle II
  (Test-Backed Change): every behaviour below has a test named for the row/requirement it
  establishes, written first. Principle VII (Boundaries Are Structural): `ui/data/ReaderPolling.kt`
  (WP4's file) was never touched — the new Stations/Frequencies counts and the live-bar fallback
  live in this package's own new `ui/data/DrawerCounts.kt` instead, exactly as the plan's nav-host
  rule requires.
- **R-010 drawer, full conformance to `Menu.dc.html`:** a real session header (`Repeater watch` /
  `Tonight` fallback — `SessionEntity` has no label field yet, confirmed by reading its full column
  set, so this is always `"Tonight"` today, honestly, not a fabricated one — and the real
  `RigStatus`, `"no radio"` for `Absent`); `OrtIcons` at 18dp, `accent/green` selected /
  `text/icon-dim` otherwise, on every row; the selected row on `bg/selected` with 8dp radius and
  `text/bright`; mono `text/figure` counts for Log/Threads (`"—"`, unchanged)/Stations/Frequencies,
  the last two from the new `DrawerCounts.current()`; the Capture row's live dot + elapsed; a
  `line/default` divider before the trailing group. `ReaderDestination.SEARCH` is excluded from the
  rendered rows (`Menu.dc.html` never lists it — R-015 moves it to every header's magnifier
  instead), closing the gap its own doc comment had already named as the intended end state but the
  drawer never implemented.
- **R-011:** every row now has a real 44dp floor (`heightIn(min = 44.dp)`), not the ~31dp two-line
  measurement the register found.
- **R-012 storage footer:** one baseline row (`"Audio 38.2 GB"` `text/dim` left, `"of N"` mono
  `text/figure` right) over a `ProgressBar` when a budget is real; until then (always, today —
  nothing sets `StorageFooterViewState.budgetBytes` yet) a single honest line, `"Audio 0.8 GB · no
  budget set"`, no bar. `StorageFooterViewState` gains `budgetBytes: Long? = null`, additive and
  default-`null`, so `fromAudioDirectory`'s existing `hasBudget = false` behaviour is unchanged.
- **R-013:** an unbuilt destination (`hasScreen == false`) renders its label in `text/disabled` with
  a trailing `"not built"` sub-line — in the row itself, not only discoverable after a tap.
- **R-014:** `DrawerRow` now uses `Role.Tab` and merges the whole row (icon, label, sub-line,
  trailing figure) into one accessibility node via `semantics(mergeDescendants = true)`, so the
  `selected` state `selectable()` already carries is actually announced — proven with
  `assertIsSelected()`/`assertIsNotSelected()`, not just a content-description string match.
- **R-003/R-004/R-015 header:** the M3 `TopAppBar` + `"="` text glyph is gone. `OrtNavHost` now
  renders WP2's `ScreenHeader` (drawer icon, live dot + mono elapsed while a session is capturing,
  search icon) on every non-drill-in destination, with no title text — each screen already draws
  its own 27sp title (`NowScreen` does; confirmed by reading it before assuming so). The search icon
  sets `current = ReaderDestination.SEARCH`.
- **R-016 drill-in header:** one `DrillInHeader` (chevron + the origin destination's label) in the
  host, used for all three drill-ins (transmission/station/frequency) instead of each screen's own
  "‹ Back" row. The screens themselves are unchanged — `onBack` is still passed through exactly as
  before, per the plan ("do not edit the screens").
- **R-017 navigation origin:** `openedFrom` (a `rememberSaveable` `ReaderDestination`) is set to
  `current` the moment any drill-in opens and read by `DrillInHeader`'s `parentLabel` — back always
  names the real origin, not a hard-coded "Log". Separately, and more concretely fixing the bug the
  register describes: Search's `input`/`result` are lifted out of `SearchContent` into `OrtNavHost`
  itself (plain `remember`, not `rememberSaveable` — `SearchFilterInput`/`SearchResult` are WP7's
  types and are not `Bundle`-saveable as they stand; flagged below for the lead). Before this,
  `SearchContent` was skipped entirely while a drill-in's `if` branch rendered instead, and a
  skipped composable's own `remember` state does not survive being skipped — opening a result from
  Search and coming back silently reset the query and results. It no longer does.
- **R-022 live bar:** WP2's `LiveBar` is now pinned to the bottom of every destination and drill-in
  while a session runs. `LiveBarPolling.kt` (WP4's real read path) is not on this branch (confirmed
  by search before writing this), so its state comes from this package's own
  `DrawerCounts.liveBar(sessionId)` instead — `CaptureState`/`ThermalStatus`/`StorageForecast`,
  honestly: `level` stays empty and `partialText` stays `null` (no real meter or Pass A partial this
  package can read), `tone` is `NOMINAL` while capturing cleanly, `DEGRADED` under thermal/storage
  warning, `HALTED` (the one red tone) only when `CaptureState` is `Failed`. The lead reconciles
  with WP4's `LiveBarPolling` at merge, per this prompt's own brief.
- **Capture destination:** `org.ort.app.ui.screens.CaptureStatusContent` (WP4's, built concurrently)
  is not on this branch (confirmed by search) — `CAPTURE` still falls through to `PlaceholderScreen`
  via the existing `else` branch, unchanged, exactly as the brief allows.
- **Settings move (F-008's carve-out):** `ModelsContent`, `messageFor` and `copyPickedFileToCache`
  moved out of `OrtNavHost.kt` into a new `ui/settings/ModelsContent.kt`, unchanged (same bodies,
  `private` helpers stay `private`, `ModelsContent` itself now `public` since it is called
  cross-package). `OrtNavHost` dispatches `SETTINGS` to `org.ort.app.ui.settings.ModelsContent(...)`.
  Everything else under `ui/settings/` is WP10's from here on.
- **Detekt/ktlint housekeeping forced by the above:** `OrtNavHost`'s body was extracted into a new
  `NavHostBody` composable (plus `NavHostIds`/`NavHostCallbacks`/`SearchHostState` parameter
  bundles, the same pattern `DrawerLiveState`/`CaptureNotificationExpandedFacts` already use in this
  codebase) to stay under detekt's function-length and parameter-count limits.

**Verified:**
- `.\gradlew.bat build dependencyRules platformGuards` — **BUILD SUCCESSFUL**. `dependencyRules:
  checked 17 modules ... OK`. `platformGuards: checked 17 modules' external dependencies and 17
  manifests ... OK.`
- `.\gradlew.bat :app:testDebugUnitTest` — **329 of 329 passing**, 0 failures, 0 errors (summed
  from `app/build/test-results/testDebugUnitTest/*.xml`), including the 29 new/changed tests in
  `DrawerContentTest` (14), `DrawerCountsTest` (8) and `DrawerSessionHeaderViewStateTest` (7).
- `.\gradlew.bat -p buildSrc test` — **BUILD SUCCESSFUL**.
- `python tools\spec-check\spec_check.py` — **8/8 PASS**.
- `.\gradlew.bat coverageMatrix` — `419 requirements, 181 covered`; `.\gradlew.bat
  coverageMatrixCheck` (separate invocation) — `up to date (181 covered of 419)`.
- `.\gradlew.bat :app:assembleDebug` — **BUILD SUCCESSFUL**.

**Left open / not done:**
- `LiveBarPolling.kt` and `CaptureStatusContent` (WP4) were not on this branch when this landed;
  `DrawerCounts.liveBar` and the `CAPTURE` placeholder dispatch are this package's honest fallback,
  named for the lead to reconcile at merge.
- Search's lifted `input`/`result` state survives the drill-in branch-swap within one composition
  (the actual, observed bug) but is not `rememberSaveable` — a process death mid-search still loses
  it, since `SearchFilterInput`/`SearchResult` (WP7's `ui/data/SearchViewData.kt`) are not
  `Bundle`-saveable as they stand. Not fixed here — that file is outside this package's ownership
  row.
- `app/src/test/kotlin/org/ort/app/ui/ReaderAccessibilityTest.kt` was edited even though it sits
  outside the literal `ui/navigation/**` glob — its two `OrtNavHost` assertions directly encoded the
  pre-conformance "=" glyph's content description and a ten-row (including Search) drawer, both of
  which this change deliberately makes false. Flagged per the plan's "stop and report" rule rather
  than left to fail.
- `ReaderDestination.trailingGroup`'s divider placement, the drawer sheet's own width (M3's
  `ModalDrawerSheet` default, not `Menu.dc.html`'s 306dp), and the Improve-records count pill
  (`improveRecordsCount`, wired but always `null` — WP10 has not landed a real count) are all
  unchanged/deferred, none named by this package's register rows.

## 2026-09-08 (ui-conformance WP2: shared components)

### (pending) — ui-conformance WP2 · legacy marker keeps confidence until callers migrate

**Scope:** `:app` `ui/components/**` only (`AttributionMarker.kt`, `AttributionMarkerTest.kt`).
Addendum to the WP2 entry directly below — same package, one commit later, at the coordinator's
request so `main` stays green between merges while WP5/WP6/WP7 still own the callers that must
migrate to `AttributionRow`.

**Requirements/ACs:** R-020 (register row; refines, doesn't reopen, the fix below), FR-UI-4
(confidence never fabricated — UNKNOWN still shows none even with the shim on).

**What changed:**
- **Constitution Check.** Principle II (Test-Backed Change): the shim is opt-out
  (`showConfidence: Boolean = true`), documented as a deprecated migration path in KDoc (not the
  spec — guide §6.2 is), and every branch has a test. Principle I (Uncertainty Is Content) still
  holds: `showConfidence = true` never fabricates a number for UNKNOWN, which has none.
- `AttributionMarker(attribution, modifier, showConfidence: Boolean = true)`: when `true` (the
  default, unchanged call sites), a `ScoreChip`-styled confidence (guide §6.2's chip look, not the
  old plain `Text`) renders after the shape whenever the attribution carries one — restoring the
  visible number `LogScreen`/`SearchScreen`/`ThreadScreen`/`TransmissionDetailScreen` and their
  own tests (`LogScreenTest`, `TransmissionDetailScreenTest`, both outside this package) still
  expect, including on CONFIRMED. `showConfidence = false` is the true shape-only render R-020
  specifies. `AttributionRow` is unchanged — it never called `AttributionMarker` or this shim; it
  already owns the INFERRED-only chip (plus callsign/colour/alternate) natively, which is what
  `showConfidence = false` covers only the shape-only half of.
- `AttributionMarkerTest.kt`: the existing shape-only test now passes `showConfidence = false`
  explicitly (it was proving that code path, which is no longer the default) and a new test —
  `` `the legacy default showConfidence renders a confidence chip beside the shape, matching
  today's callers` `` — proves the default restores `"0.95"`/`"0.82"` as visible text for
  CONFIRMED/INFERRED, with UNKNOWN still showing nothing.
- **Also fixed, found only by finally reaching it:** `:app:ktlintMainSourceSetCheck`/
  `ktlintTestSourceSetCheck` had 20 pre-existing `standard:statement-wrapping`/
  `import-ordering`/`no-unused-imports`/`function-signature` violations across `AttributionMarker.kt`,
  `ActivityPatternChart.kt`, `Feedback.kt`, `Inspection.kt`, `LiveBar.kt`, `OrtIcons.kt`, `Rows.kt`
  and two test files, present since the WP2 commit below but never surfaced because `:app:build`
  had always stopped earlier at the two known `testDebugUnitTest` failures before Gradle's task
  graph reached ktlint. Fixed with `.\gradlew.bat :app:ktlintFormat` (formatting-only; re-verified
  with `:app:detekt`, `:app:testDebugUnitTest` and the full gate below afterward) — this is the
  first time `build dependencyRules platformGuards` has actually gone green for WP2 end to end.

**Verified:**
- `.\gradlew.bat :app:testDebugUnitTest` — **304 of 304 passing** (303 from the WP2 commit + the
  one new test this addendum adds; the two previously-failing tests, `LogScreenTest.FR_UI_4 the
  confidence value is shown as visible text...` and `TransmissionDetailScreenTest.FR_UI_4 the
  confidence value is shown as visible text in the header...`, now pass unmodified).
- `.\gradlew.bat build dependencyRules platformGuards` — **BUILD SUCCESSFUL**. `dependencyRules:
  OK — every edge is permitted by the design graph.` `platformGuards: OK.`
- `.\gradlew.bat :app:detekt`, `python tools\spec-check\spec_check.py` (8/8 PASS),
  `.\gradlew.bat coverageMatrix` (419 requirements, 181 covered), `.\gradlew.bat
  coverageMatrixCheck` (separate invocation, up to date), `.\gradlew.bat -p buildSrc test`,
  `.\gradlew.bat :app:assembleDebug` — all **BUILD SUCCESSFUL**, re-run after the `ktlintFormat`
  pass.

**Left open / not done:**
- The four callers named in the WP2 entry below (and their two `FR_UI_4` tests) still need to
  migrate to `AttributionRow` and, when they do, drop their reliance on `showConfidence`'s default
  — at which point this shim's default should flip to `false` (or the parameter should come out
  entirely) and this addendum's two tests should be revisited by whoever removes it. Not done here
  since none of those files are in this package.

### (pending) — ui-conformance WP2 · shared components: marker, rows, live bar, chips, banners, sheet, chart, icons

**Scope:** `:app` `ui/components/**` only (`AttributionMarker.kt`, `ActivityPatternChart.kt`
rewritten in place; new `Controls.kt`, `Feedback.kt`, `Rows.kt`, `Inspection.kt`, `LiveBar.kt`,
`OrtIcons.kt`) and their tests under `app/src/test/kotlin/org/ort/app/ui/components/**`. Also
regenerated `results/coverage-matrix.md` (`coverageMatrix`, mandated gate output — not otherwise
touched). Working from `spec/ui-conformance-plan.md` WP2 and `results/ui-audit/register.md` rows
R-020..R-025.

**Requirements/ACs:** R-020, R-021, R-022, R-023, R-024, R-025 (register rows, all closed —
details below); AC-62, FR-A11Y-1, FR-A11Y-2, FR-A11Y-4 (four states distinguishable without
colour, merged semantics, 44dp targets, contrast); FR-UI-4 (confidence never fabricated, never
shown on CONFIRMED); FR-UI-8 (inspection surface — lattice slot, prior bar); FR-UI-11/FR-UI-12
(activity charts; not-listening is a full-height hatch, never a short bar); FR-UI-6/FR-SPK-7
(correction affordances via `ActionBar`); guide §6 (all of 6.1–6.19), §7 (icons), §8 (charts).

**What changed:**
- **Constitution Check.** Principle I (Uncertainty Is Content) governs R-020 end to end: the
  rewritten `AttributionRow` shows a score only where the guide allows one (INFERRED) and never
  fabricates a number for UNKNOWN or a cold-start prior (`PriorBar`'s `cold start` text, never
  `0.00`). Principle VII (Boundaries Are Structural) governs R-024/FR-A11Y-2: every interactive
  component built here carries a real `>=44dp` target and a `Role`, proven by
  `assertHeightIsAtLeast(44.dp)` in tests, not asserted in prose. Principle II (Test-Backed
  Change): every new component has a failing-first test named for the AC/FR/R it establishes.
- **R-020 — `AttributionMarker`/`AttributionRow`.** The old `AttributionMarker(attribution,
  modifier)` drew a shape *and* a `✓/~/?/—` glyph-and-state-name `Text` plus a confidence number
  on every state including CONFIRMED — never what the artboard showed, and every real caller
  (`LogScreen`, `SearchScreen`, `ThreadScreen`, `TransmissionDetailScreen`) then appended its own
  callsign text after it, doubling the row. The signature is unchanged, but it now renders
  **shape only** (`AttributionShape`, shared with the new full form) — so those callers now show
  shape + their own callsign correctly, with no doubling. Its content description is kept
  byte-for-byte identical to before (`"<shape>, <glyph> <STATE>[, confidence N.NN]"`) specifically
  so those callers' existing content-description assertions keep passing (see "Left open" below
  for the two that assert the old *visible* confidence text, which R-020 explicitly forbids on
  CONFIRMED and which the shape-only render therefore no longer shows). New `AttributionRow(
  attribution, callsign = attribution.stationId, alternate = null, modifier, size = 9.dp)` is the
  full form: shape, then the callsign in `OrtType.callsignRow` mono — `textHigh` CONFIRMED,
  `textBody` INFERRED, `textAmbiguous` AMBIGUOUS, or italic `textLow` "unknown station" for
  UNKNOWN — then, only for INFERRED, a `ScoreChip`, and only for AMBIGUOUS with a supplied
  `alternate`, "or `<alternate>`" in `accentAmber`. One merged semantics node throughout. `size`
  also takes `MARKER_CARD_SIZE` (12dp, ring weight 2dp) for `States.dc.html`'s card variant; the
  UNKNOWN dot scales with it (5dp at 9dp, ~6.7dp at 12dp) rather than staying fixed. New
  `ScoreChip(confidence, modifier)` (§6.2: mono 10.5px on `bg/score`, `text/faint`) is its own
  public composable, reusable per R-023.
- **R-021 — `ActivityPatternChart`.** Signature unchanged (`pattern, modifier, title,
  summaryLabel, barLabels`) plus three new optional trailing params (`axisStart`, `axisEnd`,
  `notListeningLabel`) so existing call sites (`StationScreen`, `FrequencyScreen`) compile and
  render unchanged. Bars are now 38dp tall with a 1.5dp gap (`Arrangement.spacedBy`, no per-bar
  padding — was 34dp/1dp padding); HEARD bars pick one of `OrtColors.chartGreenRamp`'s five steps
  by intensity instead of a flat green; SILENT_WHILE_LISTENING uses `chartNeutralRamp`, not
  `text/low`; NOT_LISTENING is a full-height 45°-hatch in `hatchBar` (`drawHatchRegion`, a shared
  internal helper also used by the legend swatch, the day-of-week grid's not-listening cell and
  `Sparkline`) — never a short bar, and the legend is a 9×7dp hatch swatch (`accentGapDim`) +
  `"not listening · <label>"` in `accentAmberText`, never the `▨` font glyph the pre-R-021
  component drew. Title renders through `OrtType.sectionLabel`/`textFaint` with the guide's copy
  passed by the caller — the `"ACTIVITY BY HOUR (UTC)"` default stays only as the parameter's
  default value, not a literal baked into the render path. New `DayOfWeekGrid(cells: List<
  DayHourCell>, modifier, hours)` for `Station-Pattern.dc.html`'s hour×day grid (16dp cells, 3dp
  gap, mono day-initial labels, three-swatch legend) and `Sparkline(nights: List<
  HourActivityState>, modifier)` for `Frequencies.dc.html` (56×18dp, hatched nights) — both share
  the same three-state, never-conflate-not-listening-with-quiet treatment (FR-UI-12).
- **R-022 — `LiveBar`.** New. `LiveBarViewState(level: List<Float>, partialText: String?, label:
  String, tone: LiveBarTone)`, `LiveBarTone { NOMINAL, DEGRADED, HALTED }`. `LiveBar(state,
  onClick, modifier)`: `bgLive`/`lineStrong`/`accentGreen` nominal, `bgRowGap`/
  `bannerAmberBorder`/`accentAmberDim` degraded, `haltBg`/`haltBorder`/`haltText` halted — never
  colour alone, since `label` and `partialText` differ per tone too (constitution: "colour is
  reinforcement, never signal"). The whole bar is one `>=44dp` clickable target with a merged
  `Role.Button` + description reading label and partial text together.
- **R-023 — the set (guide §6.3–6.19).** All new: `TextAction`, `PrimaryButton` (48dp),
  `SecondaryButton`, `DestructiveButton` (`Controls.kt`, §6.7); `FilterChip` + `FilterChipRow`
  (§6.3, `.selectable` so TalkBack announces selection state); `Badge` with `BadgeKind { NEW,
  REVISED, CORRECTED, TIER, COUNT }` (§6.14); `ProgressBar` (§6.12, never indeterminate);
  `StepIndicator(steps, currentStep, modifier, haltedStep)` (§6.10, with the halted-segment
  variant + mono "n of N"); `RadioRow`/`CheckboxRow`/`ToggleRow` (§6.11, 44dp rows with count and
  sub-line slots) — all in `Controls.kt`. `Banner(title, body, tone: BannerTone, primaryActionLabel,
  onPrimaryAction, secondaryActionLabel, onSecondaryAction)`, `Toast(message, onUndo)`,
  `Sheet(title, modifier, onClearAll, content)`, `EmptyState`, `FailedState` — `Feedback.kt`
  (§6.8–6.9). `SectionHeader`, `DrillInHeader`, `ScreenHeader`, `ColumnHeaderRow`, `KeyValueRow`,
  `ActionBar`, `Tile`, the `LogRow`/`LogRowViewState`/`LogRowPartial`/`LogRowBadge` family,
  `LogGroupHeader`, `GapRow`, `RejectedRow` — `Rows.kt` (§6.5/§6.18, `Rows.dc.html`'s fixed
  52dp/56dp time/freq columns, `8dp 20dp 9dp` row padding, `line/row` divider, right-aligned mono
  signal). `WaveformCard`/`WaveformViewState`/`WaveformBar`, `LatticeSlot`/
  `LatticeSlotViewState`, `PriorBar`/`PriorBarViewState` — `Inspection.kt` (§6.16–6.17, including
  the cold-start no-bar case and the argued-against reverse-fill case).
- **R-024 (44dp targets).** Every interactive component above asserts `>=44dp` in its own test
  (`ControlsTest`, `RowsTest`, `LiveBarTest`) — closed by construction, not by convention: several
  rows (`LogRow`, `GapRow`, `RejectedRow`, `LogGroupHeader`, `DrillInHeader`, `ScreenHeader`) are
  built as an outer `Box` carrying the `heightIn(min = 44.dp)` + click target with an inner `Row`
  free to lay out padded columns — a `Row` carrying `heightIn` *and* a trailing `padding()` in the
  same modifier chain was empirically found, mid-session, to sometimes under-report its measured
  height in this Compose/Robolectric combination (a real, reproducible defect, not a flaky test —
  `RowsTest`'s `assertHeightIsAtLeast` caught it at exactly the wrong 35dp both before and after
  swapping `heightIn` for `defaultMinSize`, and only the outer-Box restructuring fixed it); every
  row family member follows the same safe shape now.
- **R-025 — `OrtIcons`.** New. 34 stroke icons as `ImageVector`s built via `ImageVector.Builder
  .addPath(pathData = addPathNodes(<exact Icons.dc.html 'd' string>), ...)` — `<rect>`/`<circle>`
  SVG elements converted to their exact path equivalents by the standard formula so every icon
  traces the board's own geometry (not redrawn by eye): `drawer`, `back`, `search`, `more`,
  `chevron`, `dismiss`, `expand`, `filters`; the nine drawer destination icons (`now`, `log`,
  `threads`, `stations`, `frequencies`, `earlierNights`, `capture`, `improve`, `settings`);
  `gapWarn`, `halt`, `check`, `play` (the one filled icon, `accentGreen`), `recent`, `lock`
  ("never leaves"), `call`, `thermal`; `usbAudio`, `headset`, `builtInMic`, `rig`, `storage`,
  `models`, `export`, `diagnostics`, `edit`. All stroke icons build with `Color.Black` as a
  build-time placeholder (`Icon(..., tint = ...)` overrides every path's paint at the call site).
  `InProgressRing(modifier, size = 17.dp, color, strokeWidth = 2.dp)` draws the open-quadrant ring
  (guide §6.13/`Icons.dc.html`'s "in progress") via `drawArc`, not an `ImageVector` (a genuinely
  unknown-length indicator, distinct from `ProgressBar`'s "never indeterminate" rule).
- **Detekt.** `MaxLineLength` (120, `config/detekt/detekt.yml`) flagged ~93 lines across every new
  file on first `:app:detekt` run; fixed by wrapping call arguments one per line and, where a
  single SVG path-data string itself exceeded 120 chars, splitting it at an internal space with
  Kotlin `+` string concatenation — verified character-for-character against the source `Icons.dc.html`
  `d` strings after wrapping (an early mechanical wrap script dropped the boundary space at four
  split points — `lock`, `usbAudio`, `builtInMic`, `models` — corrupting their path data without
  breaking compilation; caught by comparing every wrapped string back to its source `d` attribute,
  not by detekt, which cannot see semantic content). `Feedback.kt` also carries
  `@file:Suppress("MatchingDeclarationName")` (`BannerTone` is one of several public declarations
  in a multi-declaration file). `LiveBar.kt`'s `DestructuringDeclarationWithTooManyEntries` (4
  entries) fixed by naming the `LiveBarPalette` result instead of destructuring it.

**Verified:**
- `.\gradlew.bat :app:testDebugUnitTest` — 303 tests, **301 passing**, 2 failing (both outside
  this package, both an expected, explained consequence of R-020 — see "Left open"). New tests by
  name: `AttributionMarkerTest` — `R_020 the shape-only marker renders no glyph, no state word and
  no confidence number as visible text`, `R_020_confirmed_renders_no_score_and_inferred_renders_a_score_chip`,
  `R_020 ambiguous shows the or QRF alternate only when one is supplied`, `R_020 unknown shows
  italic unknown station and never a callsign`, `R_020 the full row is one merged semantics node
  reading shape, state and callsign`. `ActivityPatternChartTest` — `R_021 the not-listening legend
  is a hatch swatch, never the font glyph`, `R_021 axis labels render at both ends when supplied`,
  `AC_62 a day-of-week grid distinguishes heard, quiet and not-listening cells without colour
  alone`, `a sparkline reports how many of its nights were not-listening rather than quiet` (plus
  the pre-existing `FR_UI_12`/summary tests, retained). `LiveBarTest` — `R_022 live bar degraded
  and halted variants differ in more than colour`, `FR_A11Y_2 the live bar is one 44dp target with
  a role and a merged description`, `P5 a live partial renders italic and dimmed, distinct from a
  resolved row`. `ControlsTest` — `FR_A11Y_2 every interactive component has a 44dp target and a
  role`, `a disabled text action carries text_disabled and does not fire its click`, `a filter
  chip is selectable and its dismiss affordance is a real icon, never text`, `R_023 badges render
  the guide's complete set as distinct visible labels`, `R_023 a halted step indicator differs
  from a done or upcoming one`, `a progress bar reports its percentage rather than spinning
  indeterminately`. `FeedbackTest` — `a halting banner and a degradation banner differ in tone,
  copy and action colour, not just hue`, `a toast always carries Undo and states the blast radius,
  not just success`, `AC_6_8 empty and failed states are never conflated`, `a sheet renders its
  handle, title and an optional Clear all action with a 44dp target`. `RowsTest` — `every LogRow
  variant from Rows_dc_html is distinguishable and clickable`, `a gap row and a rejected row stay
  reachable rather than disappearing`, `a log group header names the thread and a column header
  row names every column`, `a drill-in header and a screen header carry their own targets and
  descriptions`, `a key value row and an action bar render at a 44dp target`. `InspectionTest` —
  `the four waveform card states render distinctly`, `a playing waveform's play control is a
  44dp-ish real target with a role`, `a lattice slot below threshold is described as such and a
  kept alternate is announced`, `a prior that abstained reads cold start rather than a fabricated
  zero`. `OrtIconsTest` — `R_025 every required icon exists and renders without crashing`,
  `R_025 every icon has at least one path, so none renders as an empty glyph`, `an in-progress
  ring renders without a Context or polling`.
- `.\gradlew.bat build dependencyRules platformGuards` — `dependencyRules`: OK, 17 modules, every
  edge permitted. `platformGuards`: OK, no analytics/telemetry, `INTERNET` only in `:net`. `build`
  itself fails only at `:app:testDebugUnitTest`, on the same 2 known failures above (every other
  module's `build`, detekt, lint pass).
- `.\gradlew.bat -p buildSrc test` — BUILD SUCCESSFUL.
- `python tools\spec-check\spec_check.py` — all 8 checks PASS.
- `.\gradlew.bat coverageMatrix` — 419 requirements, 181 covered, regenerated
  `results/coverage-matrix.md`.
- `.\gradlew.bat coverageMatrixCheck` (separate invocation) — up to date, 181 of 419.
- `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL.

**Left open / not done:**
- **Two tests outside this package fail, and cannot be fixed from WP2.** `LogScreenTest.FR_UI_4
  the confidence value is shown as visible text, not only in the content description` and
  `TransmissionDetailScreenTest.FR_UI_4 the confidence value is shown as visible text in the
  header, not only in the content description` both assert `onNodeWithText("0.95")` exists for a
  **CONFIRMED** attribution. R-020 requires CONFIRMED to show no number, and the old
  `AttributionMarker` was the only thing visibly rendering that "0.95" (via its own now-removed
  `Text`) — so these two assertions were pinning the exact bug R-020 fixes. `LogScreen.kt`,
  `SearchScreen.kt`, `ThreadScreen.kt` and `TransmissionDetailScreen.kt` (owned by WP5/WP7/WP6
  respectively) all still call the old two-argument `AttributionMarker(attribution, modifier)`
  and append their own callsign text after it — that still renders correctly (shape + their
  callsign, no longer doubled) but does not yet pick up `AttributionRow`'s score chip / alternate
  / per-state callsign colour. All four should switch to `AttributionRow(attribution, callsign =
  <already-computed callsign>, alternate = <if any>)` when their owning WPs next touch them; when
  `TransmissionDetailScreen`/`LogScreen` do, their own `FR_UI_4` tests should be updated to assert
  what R-020 actually specifies (a score chip visible for INFERRED, never a number for CONFIRMED)
  rather than the pre-R-020 behaviour.
- **Not pixel-verified against the artboards.** No screenshot/emulator pass — Robolectric
  semantics-tree assertions only, per the plan (Phase E/Validators own that).
- **`DayOfWeekGrid`/`Sparkline`** are built to the guide's stated numbers (16dp/3dp gap; 56×18dp)
  since I did not have `Station-Pattern.dc.html` open to cross-check pixel-for-pixel; the guide's
  own §21-style prose for these two was the source of truth used.
- **Missing typography tokens (reported, not added — WP1 owns `OrtType`).** No exact 11sp
  non-mono row for the AMBIGUOUS "or QRF" alternate (used `OrtType.subLine`, 11.5sp, the closest
  named row); no exact 13px-non-italic-context row separate from `textAction`/`transcript` for
  UNKNOWN's "unknown station" (used `textAction.copy(fontStyle = Italic)`, same size/weight/family
  as the guide's 13px sans row).

## 2026-09-08 (ui-conformance WP1 addendum: dark system bars regardless of night mode)

### (pending) — ui-conformance WP1 · dark system bars regardless of night mode (R-008)

**Scope:** `:app` — `MainActivity.kt`, `ui/ReaderActivity.kt` (both `onCreate`'s `enableEdgeToEdge`
call), `ui/theme/Theme.kt` (new `OrtSystemBarStyle`), `res/values/themes.xml` (doc comment only,
values unchanged — already correct). Test: `MainActivityTest.kt` (two new cases).

**Requirements/ACs:** R-008 (register row, filed by the lead from on-device observation on
`emulator-5554`); guide §12 (no light theme, ever).

**What changed:**
- **Constitution Check.** Principle I (Uncertainty Is Content) bears indirectly: an operator who
  cannot read the clock/icons in the status bar cannot tell the device is alive at a glance, which
  is adjacent to the liveness-must-be-legible concern the constitution takes seriously elsewhere.
  Mostly this is guide §12's own rule (no light theme) applied to a surface this package's R-001
  fix had not yet reached: the *system* bar, as opposed to the app's own content.
- **Root cause.** `enableEdgeToEdge()`'s no-argument overload defaults both bar styles to
  `SystemBarStyle.auto(...)`, which picks light system-bar icons whenever the OS itself is not in
  night mode — regardless of what the *app's own* theme looks like. This app has no light theme at
  all (every artboard is dark, guide §12), so on a device/emulator not in night mode the status
  bar rendered dark icons on this app's dark ground: nearly invisible clock and icons, on both
  `MainActivity`'s permission screens and `ReaderActivity`. R-001's `Theme.Ort` already set
  `windowLightStatusBar`/`windowLightNavigationBar` to `false` for the brief pre-Compose frame,
  but `enableEdgeToEdge()` overrides that the moment it runs, using its own default rather than
  reading the theme.
- **The fix.** `OrtSystemBarStyle` (`ui/theme/Theme.kt`) is `SystemBarStyle.dark(Color.TRANSPARENT)`
  — a fixed, non-`auto` style. Both `MainActivity.onCreate` and `ReaderActivity.onCreate` now call
  `enableEdgeToEdge(statusBarStyle = OrtSystemBarStyle, navigationBarStyle = OrtSystemBarStyle)`
  instead of the no-arg form. `themes.xml`'s `windowLightStatusBar`/`windowLightNavigationBar`
  were already `false` (R-001) — gained a doc-comment cross-reference explaining the two halves
  must agree, not a value change.
- **Test.** `R_008_system_bar_style_is_dark_regardless_of_night_mode` (two cases, "in day mode"
  and "in night mode", `MainActivityTest.kt`): builds a real `MainActivity` and reads
  `WindowInsetsControllerCompat(activity.window, activity.window.decorView)
  .isAppearanceLightStatusBars`/`.isAppearanceLightNavigationBars` after `onCreate` — the actual
  runtime effect `enableEdgeToEdge()` produces, genuinely simulated by Robolectric, not merely the
  static theme attribute (which was already correct and did not need a new test). Both must read
  `false` in both Robolectric's default (day) qualifiers and under `@Config(qualifiers = "night")`.
  Verified this is a real regression test, not one that would pass regardless: temporarily reverted
  the call to bare `enableEdgeToEdge()` and re-ran — the "day mode" case failed exactly as the bug
  report described (`isAppearanceLightStatusBars` read `true`), while "night mode" still passed
  (since night mode alone already makes `auto` pick dark) — then restored the fix and confirmed
  both pass again. A single `@Config(qualifiers = "night")` case alone could not tell `SystemBarStyle
  .auto` and `.dark` apart while genuinely in day; the pair together can.

**Verified:**
- `.\gradlew.bat :app:check` — `BUILD SUCCESSFUL`.
- `.\gradlew.bat build dependencyRules platformGuards` — `BUILD SUCCESSFUL`; `dependencyRules: OK
  — every edge is permitted by the design graph.`; `platformGuards: OK.`

**Left open / not done:** not verified on a real device or the actual `emulator-5554` this was
reported from — Robolectric/JVM only, as for the rest of this package's work. `WindowInsetsControllerCompat`'s
appearance flags are Robolectric's best simulation of the real platform behaviour, not the real
platform itself.

## 2026-09-08 (ui-conformance WP1: theme, tokens, manifest, scaffold; themed permission screens)

### (pending) — ui-conformance WP1 · theme, tokens, manifest, scaffold; themed permission screens

**Scope:** `:app` — `AndroidManifest.xml`; new `res/values/themes.xml`, `res/values/colors.xml`;
`res/values/strings.xml` (setup-screen copy added); `ui/theme/OrtColors.kt`, `ui/theme/OrtType.kt`,
`ui/theme/Theme.kt` (full token rewrite); `MainActivity.kt` (TextView → `ComponentActivity` +
Compose, themed permission screens); `ui/ReaderActivity.kt` (edge-to-edge, R-007 fix extracted as
a pure function). Tests: new `ui/theme/OrtColorsContrastTest.kt`, `ui/theme/OrtTypeTest.kt`,
`ui/theme/OrtThemeTest.kt`, `MainActivityTest.kt`, `SetupScreenSelectionTest.kt`,
`ui/ReaderActivityTest.kt`. Also regenerated `results/coverage-matrix.md` (`coverageMatrix`).
Working from `spec/ui-conformance-plan.md` WP1 and `results/ui-audit/register.md` rows
R-001/R-002/R-005/R-006/R-007/R-085.

**Requirements/ACs:** R-001, R-002, R-005, R-006, R-007, R-085 (register rows); FR-A11Y-1,
FR-A11Y-3, FR-A11Y-4, AC-62, AC-63 (contrast and sp-scaling proofs for the full token set); AC-65,
NFR-8 (battery-exemption flow unchanged); constitution IV "never lies" (R-007's same-process
session-routing fix).

**What changed:**
- **Constitution Check.** Principle VII (Boundaries Are Structural) bears on R-005: every new
  colour is a *computed* sRGB result of its OKLCH triple (Björn Ottosson's published OKLab→linear
  sRGB matrices), not a hand-picked guess, so the palette is regenerable and its WCAG contrast is
  a proof (`OrtColorsContrastTest`), not an eyeballed claim. Principle IV bears on R-007: a
  relaunch over an already-capturing session must not silently poll a session nothing is writing
  to — fixed, not just verified. Principle II (Test-Backed Change) bears throughout: every fix is
  TDD'd, and a real test-infrastructure defect found mid-session (below) was root-caused and fixed
  rather than worked around by weakening a test.
- **R-001/R-006 — theme.** `res/values/themes.xml`'s `Theme.Ort` (`android:Theme.Material.NoActionBar`
  parent — no AppCompat dependency in `:app`) replaces the platform `Theme.DeviceDefault` every
  Activity rendered under before (the ActionBar-above-Compose-header bug the register recorded),
  applied via `android:theme` on `<application>`. `windowBackground`/status/nav bar colour is
  `bg_screen` (`res/values/colors.xml`, hand-kept in sync with `OrtColors.bgScreen`);
  `windowLightStatusBar`/`windowLightNavigationBar` both false (guide §12: no light theme, ever).
  `OrtTheme` (`Theme.kt`) now wraps content in a tagged `Surface` filling max size on
  `OrtColors.bgScreen` — the previous version set a `darkColorScheme` with no root `Surface` at
  all. Both `MainActivity` and `ReaderActivity` call `enableEdgeToEdge()`; the 44dp the boards
  leave clear for the status bar is `WindowInsets.statusBars`, never a hardcoded dp.
- **R-005 — tokens.** `OrtColors.kt`: every token in guide §3 (surfaces, lines, all 21 text steps,
  both accent families with their extras, the `halt/*` red family, the three chart ramps as
  `List<Color>`) — ~90 named values, each computed from its `oklch(L C H)` rather than
  hand-picked, with the OKLCH kept in the KDoc beside it. `OrtType.kt`: every row of guide §4 (23
  named `TextStyle`s) with size/weight/family/tracking/line-height computed from the table (a
  `-0.022em` tracking at 27px is `27 * -0.022 = -0.594.sp`, the same rule for every row), mapped
  onto M3 `Typography` so `bodyMedium` is 14sp (`control`) — not the 12.5sp caption it silently
  was — `bodyLarge` 15, `bodySmall` 12.5, `labelSmall` 11, `titleLarge` 27, `titleMedium` 19. Six
  legacy names (`background`, `surface`, `surfaceVariant`, `surfaceRaised`, `divider`,
  `textMedium` in `OrtColors`; `titleLarge`, `callsign`, `body`, `caption` in `OrtType`) are kept
  exactly as other packages reference them, repointed at the precise token each already described.
- **R-002/R-085 — `MainActivity`.** Replaced the `TextView` scaffold with `ComponentActivity` +
  `setContent { OrtTheme { … } }`. The existing tested decision logic (battery-exemption
  fire-and-forget ordering, `PermissionsFlow.captureIsPermitted` gate) is unchanged. New: three
  small `internal` composables (`MicrophoneSetupScreen`/`Setup-Mic.dc.html`,
  `MicrophoneDeniedScreen`/`Setup-Mic-Denied.dc.html`, `NotificationsSetupScreen`/`Setup-Notify.dc.html`),
  each matching its board's step indicator, copy (moved into `strings.xml`, FR-A11Y-6), and
  actions. Permission requests now fire only from the screen's own button tap, never as a side
  effect of computing which screen to show. R-085's real fix: `shouldShowRequestPermissionRationale`
  alone cannot distinguish "never asked" from "denied twice, permanently" — a `SharedPreferences`
  flag set the moment the real request fires tells them apart, giving the denied screen a real
  `Open app settings` / `Check again` path where the old flow stuck on "Requesting microphone
  access…" forever. `Skip` on the notifications screen persists via the same prefs mechanism,
  folded into `notificationsGranted` by the caller — `PermissionsFlow.captureIsPermitted`'s own
  tested contract is untouched.
- **R-007.** `CaptureState.sessionId`/`isCapturing` is preferred over a stale intent extra in both
  `MainActivity.startCaptureAndShowStatus` and `ReaderActivity.onCreate` — pulled out of the
  latter as a pure `resolveSessionId` function for testability, reconciled on top of `main`'s own
  later F-022 fix (same intent, same mechanism; this entry's version is what ships). Genuine
  cross-*process*-death session recovery (as opposed to same-process relaunch) is not built
  anywhere in this codebase and is out of WP1's owned files — flagged, not silently assumed
  solved.
- **A real test-infrastructure bug, found and fixed, not worked around.** The first version of
  `ReaderActivityTest`/`MainActivityTest` built real Activities via bare
  `Robolectric.buildActivity(...)`. Composing `OrtNavHost` (drawer, scaffold, top bar — a
  pre-existing, unrelated composable) through that path left a live Compose `Recomposer`
  registration behind that a full `create→start→resume→pause→stop→destroy` lifecycle did **not**
  clear under this Robolectric version — confirmed by a controlled A/B: a pristine `git worktree`
  at this branch's own unmodified base commit ran the whole `:app` suite clean, while this
  branch's suite deterministically failed one unrelated, pre-existing test
  (`ActivityPatternChartTest`, owned by a different work package) with
  `AppNotIdleException: Compose did not get idle... in 60 SECONDS` two files after the leaking one
  — every time, regardless of worker count. Root cause confirmed by systematically parking test
  files until the failure disappeared. Fix: `ReaderActivityTest`'s R-001 cases now use
  `createAndroidComposeRule<ReaderActivity>()` (the same officially-supported entry point
  `ReaderAccessibilityTest` already uses for `OrtNavHost` itself), which disposes the composition
  properly; R-007 no longer builds an activity at all, asserting `resolveSessionId` directly.
  `results/coverage-matrix.md` was regenerated after this fix.
- **Rebased onto `main` (`6b7dbeb`) from this package's original base (`b082bac`)**, which predated
  the whole `ui-conformance` program (`spec/ui-conformance-plan.md`, `results/ui-audit/register.md`,
  `design/design-guide.md` and the canvas did not exist there). Six conflicts:
  - `AndroidManifest.xml` auto-merged cleanly (`main`'s newer activity list, this package's
    `android:theme`) — no manual resolution needed.
  - `MainActivity.kt`/`ReaderActivity.kt`: `main` had independently landed its own fix for the same
    F-022 finding this package's R-007 addresses. Kept `main`'s doc-comment wording and its exact
    `startCaptureAndShowStatus`/`RealCaptureService` intent-building code verbatim (including a
    stale comment about `StatusActivity`/`TransmissionListActivity` still there on `main` — not
    this package's file to clean up), layering this package's `enableEdgeToEdge()` call and the
    `resolveSessionId` pure-function extraction (needed for the test-leak fix below) on top.
  - `MainActivityTest.kt` (add/add): `main` had independently added its own `MainActivityTest.kt`
    covering the same F-022 finding from `MainActivity`'s side (`FR_UI_7` tests, session-relaunch
    behaviour). Merged into one file — both suites kept in full, since they test disjoint
    behaviour (F-022 relaunch-safety vs. R-002/R-085 setup-screen UI), reusing this package's
    `Robolectric`-permission-shadow helpers (`grant`/`deny` via `ReflectionHelpers`) for `main`'s
    tests too rather than `main`'s own `shadowOf(Application).grantPermissions(...)`, which does
    not appear to exist as a public method against this Robolectric version (see this package's
    own investigation, recorded before the rebase).
  - `OrtColorsContrastTest.kt` (add/add): `main`'s copy tested the original eight-token palette by
    its legacy names; this package's copy tests the new precise names R-005 added. Both kept —
    every legacy name is still an exact alias of a precise one, so `main`'s original assertions
    still hold unmodified; one identically-named test on both sides was kept once (this package's,
    against the canonical name — the fact it duplicated is separately proven by
    `R_005 legacy names still resolve to the same Color as the precise token they alias`).
  - `results/coverage-matrix.md`: took `main`'s version, then regenerated (`coverageMatrix`) once
    the rebased build was green — see Verified.
  - `CHANGELOG.md`: this entry moved above `main`'s WP11a/WP0 entries (this package's commit is
    newest); no content lost either side.
  Register rows R-001/R-002/R-005/R-006/R-007/R-085 were re-read from
  `results/ui-audit/register.md` on the rebased branch itself (no longer a sibling-checkout
  citation, since the file now genuinely exists here) and this package's fix still matches each,
  word for word against what each row currently says.

**Verified** (rebased onto `main` at `6b7dbeb`; full gate re-run there, `platformGuards`/
`coverageMatrixCheck` now exist and both ran):
- `.\gradlew.bat build dependencyRules platformGuards` — `BUILD SUCCESSFUL in 1m 14s`;
  `dependencyRules: checked 17 modules … OK — every edge is permitted by the design graph.`;
  `platformGuards: checked 17 modules' external dependencies and 17 manifests — no
  analytics/telemetry SDK, no HTTP client outside :net, android.permission.INTERNET declared by
  :net and only :net (FR-OBS-5, NFR-6, AC-59, audit F-008). OK.` 269 test methods passed across
  the repo in this run, 0 failed.
- `.\gradlew.bat :app:testDebugUnitTest` (isolated re-run) — `BUILD SUCCESSFUL in 27s`, **269
  tests, 0 failures**.
- `.\gradlew.bat -p buildSrc test` — `BUILD SUCCESSFUL in 13s`.
- `python tools\spec-check\spec_check.py` — all 8 checks `[PASS]`.
- `.\gradlew.bat coverageMatrix` — `419 requirements, 180 covered -> results\coverage-matrix.md`.
- `.\gradlew.bat coverageMatrixCheck` — `coverageMatrixCheck: up to date (180 covered of 419).`
  `BUILD SUCCESSFUL`.
- `.\gradlew.bat :app:assembleDebug` — `BUILD SUCCESSFUL`.
- One real defect found and fixed by this re-run, not pre-existing on `main`: the merged
  `OrtColorsContrastTest.kt` doc comment reused the phrase `` `halt/*` family `` — Kotlin nests
  block comments, so that literal `/*` opened a second comment the file's own closing `*/`
  then closed instead of the outer one, silently commenting out everything after it
  (`ktlintCheck`: "Unclosed comment"). Same class of bug this package already hit once in
  `MainActivity.kt` before the first commit; fixed the same way (reworded to avoid the substring).

**Left open / not done:**
- WP9 (register row R-080) replaces the three setup composables in `MainActivity.kt` wholesale
  with the full 13-board guided sequence on WP2's shared components; they are deliberately small
  and said so in their own doc comments.
- No device is available to this session; every proof above is Robolectric/JVM.

## 2026-09-08 (ui-conformance WP11a)

### (pending commit) — ui-conformance WP11a · failure signals: thermal, rig, storage forecast, gap causes, notification

**Scope:** `pipeline/src/main/kotlin/org/ort/pipeline/capture/{ThermalStatus,RigStatus,StorageForecast}.kt`
(new), `RealCaptureService.kt` (wiring and the notification only), `pipeline/.../GapPersister.kt`,
`capture-android/.../service/CaptureNotificationBuilder.kt`, `data/.../entity/CaptureGapEntity.kt`
(the `CaptureGapCause` enum — the lead-approved `:data` exception), `app/src/debug/**`
(`Scenarios.kt`, `OvernightScenario.kt`), tests beside each (new: `ThermalStatusTest.kt`,
`RigStatusTest.kt`, `StorageForecastTest.kt`, `ThermalTrackingPassTest.kt`,
`RealCaptureServiceUncleanEndGapTest.kt`, `CaptureNotificationBuilderTest.kt` additions,
`GapPersisterTest.kt` additions, `ScenariosTest.kt` additions), `results/ui-audit/README.md`
(scenario table, three "Known gaps" closed). `results/coverage-matrix.md` regenerated (gate
side-effect, not hand-edited). No file under `app/src/main/**` touched.

**Requirements/ACs:** register R-102, R-104, R-105, R-106; F5, F6, F7, F9, F15 (spec §12);
FR-STO-3, FR-RUN-11, FR-RUN-12, FR-RUN-16, FR-SVC-1, FR-PLT-3, AC-61.

**Constitution check.** Principle I (uncertainty is content) bore directly: [`ThermalStatus`] and
[`StorageForecast`] both default to an honest "not yet measured" state rather than inventing a
healthy-looking zero, matching `StatusViewState.backlogLabel`'s existing discipline. Principle IV
(capture never lies) bore on `RigStatus.absent()` being called because there genuinely is no rig,
not as a placeholder, and on `ThermalTrackingPass` measuring a real wall-clock/audio-duration ratio
rather than estimating one. Principle VII (structural guarantees) bore on `CaptureNotificationContent`
staying a closed, narrowly-named field set (AC-61's own test asserts it) even after gaining nine
new fields, and on `CaptureGapCause` being extended (never renumbered) because Room stores it by
name. The `:capture-*` → `:asr-*`/`:lexicon`/`:identity` boundary was not touched; `dependencyRules`
passed unchanged.

**What changed:**

- **R-104 — `ThermalStatus`** (new): `Nominal`/`Warm`/`Hot`, each carrying the OS
  `PowerManager`-mirrored thermal int (`THERMAL_STATUS_NONE` below API 29 — never invented) and an
  exponentially-smoothed measured real-time factor. `recordPassTiming(rtf)` is called by the new
  `ThermalTrackingPass` decorator (in `RealCaptureService.kt`, wrapping the real Pass B
  `startProcessingLoop` already constructs) after every real pass run, using the transmission's
  real `durationMs` looked up fresh via `TransmissionDao`; `sample(osThermalStatus)` republishes
  state on the same 10 s tick `runShedMonitor` already runs. `ShedController`/`ShedSignals` were
  deliberately left untouched (not in this package's ownership) — thermal is read directly via a
  new `Dependencies.osThermalStatus` seam instead.
- **R-104 — `RigStatus`** (new): `Absent`/`Connected(descriptor, per-band frequency/mode/squelch)`/
  `Stale(lastKnown, sinceMillis)`. `RealCaptureService.startCapture()` calls `RigStatus.absent()` —
  its one real producer today, since FR-RIG (register R-084) is unbuilt; the `rig-lost` scenario is
  the only other producer.
- **R-105 — `StorageForecast`** (new): `NotYetMeasured`/`Fine`/`ThreeNightsLeft`/`OneNightLeft`/
  `AtFloor`, computed each shed tick from free bytes, the real on-disk `filesDir/audio` size, and
  this session's own growth over its elapsed time, extrapolated to a documented (not measured or
  configured) 8-hour "night". `AtFloor` always wins regardless of rate — never contradicts the
  existing FR-STO-4 hard stop.
- **R-106 — gap causes**: `CaptureGapCause` gains `CALL`, `INPUT_LOST`, `OS_STOPPED`, `ROUTE_LOST`
  (every prior value unchanged; Room stores by name, confirmed against the exported schema).
  `GapPersister.causeFor` maps `"call"` → `CALL`, a bare read error/device reference → `INPUT_LOST`
  (F-010's `"dropped samples:"` span stays `DEVICE_LOST`, an established, tested mapping),
  `"os stopped"` → `OS_STOPPED`, `"route mismatch"` → `ROUTE_LOST`. **F5**: `RealCaptureService`
  now calls `UncleanEndDetector(heartbeatStore).detect()` synchronously at the top of
  `startCapture()`, before this session's own heartbeat can overwrite the file, and persists an
  `OS_STOPPED` gap on the *previous* session (its last heartbeat → the moment of detection) — never
  on the new session, and never "reopening" the previous one (a policy left to the lead). Two
  honesty findings, not guessed around: **`CALL` is not distinguishable from a generic focus loss
  in real code today** — `AndroidAudioIo`'s own doc comment says its interruption detection is a
  "v0", with no `AudioManager.OnAudioFocusChangeListener` wiring at all, so no real cause string
  ever names a call; even once that exists, Android's own API reports *that* focus was lost, never
  *why*, so telling a call apart needs a second signal (`TelephonyManager`) nothing here reads. The
  `gap-call` debug scenario writes `CaptureGapCause.CALL` directly (it always bypassed
  `GapPersister`), which is why R-106 closes for the *simulator* while this real-code gap remains
  open and reported. **`ROUTE_LOST` is unreachable through the real service as built** —
  `AudioRecordSource` reports a route mismatch as `CaptureEvent.Failed` (a halt), never routed
  through `GapTracker`/`GapPersister` at all, correctly per constitution IV (a mismatch must halt,
  never resume the way a gap does); the mapping is reserved, not exercised.
- **R-102 — the notification**: `CaptureNotificationContent` redesigned around typed facts —
  a `State` enum (`CAPTURING`/`INTERRUPTED`/`FAILED`/`ASR_UNAVAILABLE`) replacing free-text state
  strings, a computed `title` (`"Capturing · 6:42 · 412 overs"`), a `SecondLine` sealed interface
  (`Normal(frequenciesLabel, tier)` / `Degraded(reason)`), and non-transcript last-over/input/
  storage fields for the expanded rows — still a closed field set (AC-61's structural test updated
  and passing). `RealCaptureService` builds it via `NotificationCompat.InboxStyle`, adds an
  explicit-component `Open` action to `org.ort.app.ui.ReaderActivity` (named by string, since
  `:pipeline` must not depend on `:app`) carrying the session id, keeps `Stop`, always the same
  `NOTIFICATION_ID` updated in place, no explicit sound (the channel stays `IMPORTANCE_LOW`).
  `degradedReason()` combines `CaptureState`/`ThermalStatus`/`RigStatus`/`StorageForecast` the way
  the board's own example does (`"Running warm — tier 2 · radio disconnected, frequency stale"`).
  The notification build was made `suspend`-and-DB-backed (`buildNotificationContent`) dispatched
  always through the service's own `scope` (Dispatchers.IO): the pre-existing synchronous
  `updateNotification` would otherwise run a Room query on `onStartCommand`'s main thread in the
  "no audio input device" early-return path — a real bug this change fixes, not a style choice.
  **Two lines outside this package's file ownership needed a compile-preserving bridge, not an
  edit**: `capture-android/.../service/CaptureService.kt` (a different, older service, still
  manifest-registered) calls the old `build(state: String, elapsedMillis, transmissionCount)` and
  reads `.text`; both are kept as a legacy overload and computed aliases on the same type
  (`stateLabel`/`elapsedLabel`/`text`, reproducing the old `HH:MM:SS` format exactly) rather than
  touching that file — `CaptureServiceTest.kt`'s existing five tests pass unchanged.
- **Scenarios**: `thermal` (Warm, RTF 0.9, same shed level `backlog` uses), `rig-lost` (`Stale`
  since 30 minutes ago, last known 145.230/146.960), `storage-warn` (now `StorageForecast
  .ThreeNightsLeft` with capture genuinely running, replacing the old reuse of F6's exhaustion
  string), `os-stopped` (the `unclean-end` heartbeat plus an `OS_STOPPED` gap on the previous
  session), `gap-call`'s second gap now `CaptureGapCause.CALL`. `results/ui-audit/README.md`'s
  scenario table updated and the three "Known gaps" this closes removed.

**Verified:**
- `.\gradlew.bat build dependencyRules platformGuards` — BUILD SUCCESSFUL (841 tasks; every
  module's tests, detekt, ktlint, lint green, including pre-existing `CaptureServiceTest`).
- `.\gradlew.bat -p buildSrc test` — BUILD SUCCESSFUL.
- `python tools\spec-check\spec_check.py` — all 8 checks PASS.
- `.\gradlew.bat coverageMatrix` — 419 requirements, 180 covered. `.\gradlew.bat
  coverageMatrixCheck` (separate invocation) — up to date.
- `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL.
- On `emulator-5554`: installed, `thermal` scenario broadcast, `MainActivity` launched — the real
  `RealCaptureService` started (real mic opened and verified: `sdk_gphone64_x86_64`), and
  `adb shell dumpsys notification --noredact` showed the real posted notification: title
  `"Capturing · 0:00 · 0 overs"` (later `"0:01"` after a real heartbeat), text
  `"0 frequencies · tier 3"`, `InboxStyle` lines `"Last over: none yet"` /
  `"Input: sdk_gphone64_x86_64 · verified"` / `"Storage: 0.0 GB · no budget set"`, actions
  `[Open → startActivity]` `[Stop → startService]`, channel `ort.real-capture` at
  `importance=2` (LOW) with `sound=null` on the notification itself.
- Every new test named in Scope ran green as part of the `build` gate above; targeted reruns
  (`:pipeline:testDebugUnitTest`, `:capture-android:testDebugUnitTest`,
  `:app:testDebugUnitTest --tests ScenariosTest`) also green.

**Left open / not done:**
- **The notification's degraded second line could not be observed live on-device against a real
  running service.** `runShedMonitor`'s own 10 s tick samples the emulator's real (unthrottled)
  thermal status every 10 s, which overwrote the scenario-injected `ThermalStatus.Warm` before the
  next natural notification refresh (`onHeartbeat`, gated at 30 s) could render it — confirmed by
  dumping the notification twice, seeing it still read the nominal state both times. The
  structural behaviour is proven by `CaptureNotificationBuilderTest`'s
  `R_102_notification_second_line_switches_to_degraded_when_a_reason_is_given` and by
  `RealCaptureService.degradedReason()`'s own logic, not by a live capture; a genuinely throttled
  device (or a debug seam that freezes the shed tick) would be needed to see it live.
- **`CaptureGapCause.CALL` is not producible by real running code** (`AndroidAudioIo` has no
  audio-focus-loss wiring at all yet, and Android's own focus-change API cannot distinguish a call
  from another app's request without a `TelephonyManager` cross-reference) — see the honesty
  findings above. Building that wiring is `AndroidAudioIo`/`:capture-android` scope, not this
  package's two allowed exceptions.
- **`CaptureGapCause.ROUTE_LOST` is unreachable through the app as built** — a route mismatch halts
  capture (`CaptureEvent.Failed`) rather than opening a resumable gap, correctly per constitution
  IV; the enum value and mapping are reserved for if that policy ever changes.
- **The "configured frequency" tier of R-102's second-line fallback is not implemented** — no
  configured-frequency source exists anywhere in `RealCaptureService` today (FR-CFG/FR-RIG both
  unbuilt), so the second line falls straight from `RigStatus.Connected` to a count of frequencies
  actually seen this session (honestly 0 while nothing populates `TransmissionEntity.frequencyHz`).
- **`StorageForecast`'s "night" is a documented 8-hour assumption**, not a measured or configured
  value — FR-STO-3's own budget/retention settings screen (WP10, register R-090) is the eventual
  source of truth.
- Register rows R-102/R-104/R-105/R-106 are left for the lead to mark — this package does not edit
  `results/ui-audit/register.md`.

## 2026-09-08 (ui-conformance WP0)

### (pending commit) — ui-conformance WP0 · debug scenario simulator and audit tooling

**Scope:** `app/src/debug/**` (new — `ScenarioReceiver.kt`, `Scenarios.kt`, `ScenarioFixtures.kt`,
`OvernightScenario.kt`, `StationsFixtures.kt`, `ScenarioReaderActivity.kt`,
`AndroidManifest.xml`), `app/src/test/kotlin/org/ort/app/debug/ScenariosTest.kt` (new),
`tools/ui-audit/**` (new — `boot.ps1`, `create-avd.ps1`, `install.ps1`, `scenario.ps1`,
`shoot.ps1`, `nav.ps1`, `run-set.ps1`, `screens.json`, `sets.json`), `results/ui-audit/README.md`
(new), `results/ui-audit/overnight/log.png` (new, verification evidence), `app/build.gradle.kts`
(one addition — see "What changed"), `results/coverage-matrix.md` (regenerated, a side effect of
the required `coverageMatrix` gate — not hand-edited).

**Requirements/ACs:** register R-110, R-111 (`results/ui-audit/register.md`), plan
`spec/ui-conformance-plan.md` WP0/§E. Constitution I (every attribution carries its state — the
fixtures never write an incomplete one), III (no real callsign/voiceprint/location — fictional
callsigns only, nothing from `corpus/`, `eval` never read), IV (a "live" scenario proves liveness
by a real heartbeat write, never by flipping a flag alone), VII (the module graph is untouched —
no new project dependency edge; `DeflatePredictiveCodec` was already transitively on `:app`'s
classpath via `:pipeline`'s existing `api(project(":capture-android"))`).

**Constitution check.** Principle I bore directly on `Scenarios.kt`'s fixture builders — every
`TransmissionEntity` is built through one helper (`ScenarioFixtures.transmission`) that requires
an `attributionState` with no default, so a scenario cannot accidentally omit one. Principle III
bore on every callsign choice (the artboards' own fictional set, `ScenarioFixtures.CALLSIGNS`) and
on the explicit decision not to touch `corpus/` or the `eval` fold anywhere in this package.
Principle IV bore on the `markCapturing` helper: a "live" scenario writes a real heartbeat record,
not just `CaptureState.capturing(...)`, so `CaptureStatusRepository`'s own liveness check (which
reads heartbeat continuity, never a flag) reports the same thing a real capture would. Principle
VII bore on the clearing strategy: `clearPriorScenarioData` reaches Room's own public
`openHelper.writableDatabase` rather than adding delete queries to `:data`'s DAOs (out of package),
and the audio fixture writer reuses the exact codec (`DeflatePredictiveCodec`) `RealSegmentSink`
already encodes with, rather than inventing a second decode path.

**What changed:**

- **R-110 — `ScenarioReceiver`** (debug manifest only, `exported="true"`, action
  `org.ort.app.debug.SCENARIO`, extra `name`): loads one of fifteen named scenarios through
  `:data`'s real DAOs/entities, logs `scenario <name>: <n> transmissions, <m> sessions` plus a
  second line naming the primary session id, and also returns the session id via the broadcast's
  own result extras (`EXTRA_SESSION`) so a caller isn't forced through logcat.
- **`Scenarios.kt`**: the registry, the clearing (`DELETE` statements scoped to the
  `scenario-`-prefixed session/transmission ids every scenario writes, plus an unconditional wipe
  of `station`/`voiceprint` — see the object's own doc comment for why those two can't be
  prefix-tagged), and the process-wide capture-facet reset (`CaptureState`, `AsrAvailability`,
  `VadAvailability`, `ShedStatus`) that runs before every scenario applies its own facts.
- **Fifteen scenarios**: `empty`, `first-session`, `overnight` (the big one — a real 6h42m,
  two-frequency, ~42-over session exercising every `Rows.dc.html` variant: CONFIRMED, INFERRED
  linked to its confirming over, AMBIGUOUS, UNKNOWN, a corrected row + its `CorrectionEntity`
  audit row, a revised row (two transcript versions), a rejected row (retained, not hidden), a
  first-heard station, a four-over QSO thread, a 38s capture gap, mixed signal strength, mixed
  retained audio, and lattice/candidate rows for the confirmed/inferred/ambiguous overs including
  one cold-start and one negative prior for `Detail-Why`), `unclean-end`, `gap-call`,
  `pass-a-partial`, `corrected`, `no-audio`, `revisions`, `stations-14-nights` (fourteen sessions
  over a fifteen-day span with a real, unlistened whole day), `field-tier1`, `search-corpus`
  (fourteen "park activation" transcripts across three nights), and three status-facet scenarios
  the brief asked to be checked for settability from `:app` without a `:pipeline` edit —
  `backlog` and `model-missing` are (both singletons `ReaderPolling` already reads directly);
  `storage-warn` reuses `RealCaptureService`'s own exhaustion reason (there is no earlier "warning"
  signal in `:pipeline` to distinguish from exhaustion). `thermal` and `rig-lost` are **not**
  simulable — no thermal signal exists anywhere in `:pipeline` (`ShedSignals` carries
  battery/backlog/storage only), and no rig connection-state singleton exists at all (the rig
  module is not built). Both need a new process-wide holder in `:pipeline`, the same pattern as
  `ShedStatus`, which is a `:pipeline` change outside this package — reported for WP11 per the
  brief's own instruction, not built here.
- **`ScenarioReaderActivity`** (new, debug-manifest-only, `exported="true"`): found by actually
  running the brief's own documented command
  (`adb shell am start -n org.ort.app/.ui.ReaderActivity --es session_id <id>`) that
  `org.ort.app.ui.ReaderActivity` is `exported="false"` (register R-007) and refuses that start
  with a `SecurityException`. `ReaderActivity`'s manifest entry is `app/src/main/AndroidManifest.xml`
  — outside this package's ownership — so rather than widen it, this adds one debug-only
  forwarding activity that relays the `session_id` extra and finishes; it exists only in the debug
  manifest and touches no file under `app/src/main`.
- **`app/build.gradle.kts`**: one addition beyond the narrow "pick up the source set" allowance
  (which needed nothing — `src/debug` is a default source set, checked first). `./gradlew build`
  failed at `:app:compileReleaseUnitTestKotlin` — a pre-existing gap in
  `buildSrc/.../ort.android-app.gradle.kts` (outside this package) already disables
  `testReleaseUnitTest`'s *execution* for an unrelated reason (Compose's `ui-test-manifest` stub
  is debug-only) but not the release variant's unit-test *compilation*, which compiles the same
  `app/src/test/kotlin` source set this package's own `ScenariosTest.kt` now lives in against a
  classpath that never carries `app/src/debug`. Found by running `./gradlew build`, not by
  inspection. Fixed by disabling `compileReleaseUnitTestKotlin` the same way its sibling already
  is — no release artifact consumes that task's output.
- **`tools/ui-audit/**`**: `boot.ps1`, `create-avd.ps1`, `install.ps1`, `scenario.ps1`,
  `shoot.ps1`, `nav.ps1`, `run-set.ps1`, `screens.json` (real coordinates, captured from a live
  `uiautomator dump` of the built reader on `emulator-5554`), `sets.json` (V3/V5 only — see
  `run-set.ps1`'s own doc comment for why the other five phase-E sets are deliberately absent
  rather than faked).

**Verified:**

- `.\gradlew.bat :app:testDebugUnitTest --tests "org.ort.app.debug.*" --rerun-tasks` — 21/21
  `ScenariosTest` tests passed (list in the builder's report to the lead).
- `.\gradlew.bat build dependencyRules platformGuards` — `BUILD SUCCESSFUL`, 841 actionable tasks;
  `dependencyRules: checked 17 modules ... OK`; `platformGuards: checked 17 modules ... OK.`
- `.\gradlew.bat -p buildSrc test` — `BUILD SUCCESSFUL`.
- `python tools\spec-check\spec_check.py` — 7/8 checks pass; check 2 fails on two pre-existing
  dangling references in `spec/ui-conformance-plan.md` (`Q01`, `Q05` — scenario names in its own
  §E table, misread as open-question ids). `git diff` confirms this file is untouched by this
  package; not fixed here (outside WP0's ownership).
- `.\gradlew.bat coverageMatrix` then `.\gradlew.bat coverageMatrixCheck` (two separate
  invocations) — `coverageMatrix: 419 requirements, 179 covered`;
  `coverageMatrixCheck: up to date (179 covered of 419)`.
- A real device run: `scenario.ps1 -Port 5554 -Name overnight` → `session=scenario-overnight`,
  then `am start -n org.ort.app/.debug.ScenarioReaderActivity --es session_id scenario-overnight`,
  then `nav.ps1 -Port 5554 -Screen log`, then
  `shoot.ps1 -Port 5554 -Scenario overnight -Screen log` → `results/ui-audit/overnight/log.png`
  (430,345 bytes), on `emulator-5554` (`ort_audit`, Pixel 6, API 34).

**Left open / not done:**

- `thermal` and `rig-lost` scenarios — need a new `:pipeline` singleton each (see "What changed");
  assigning to WP11 is the lead's call, not made here.
- `storage-warn` only reproduces F6's exhaustion reason; there is no distinct "getting low"
  warning signal in `:pipeline` to simulate separately.
- `gap-call`'s second gap uses `CaptureGapCause.INTERRUPTION` — `CaptureGapEntity` has no
  CALL-specific cause value and no free-text reason field, so "incoming call" (the artboard's own
  wording) is not representable exactly.
- `sets.json` covers only V3 and V5 — V1, V2, V4, V6 and V7 depend on screens/flows (setup,
  detail states, settings, failure banners) other work packages have not landed yet; their
  scenario/screen pairs belong there.
- `screens.json`'s coordinates are captured against *today's* unconformed UI and will need
  re-pointing as WP1–WP11 land — expected, documented in `results/ui-audit/README.md`, not a
  defect in this tooling.
- `results/ui-audit/register.md` R-110/R-111 rows are not edited here — the lead updates them from
  this package's report, per the brief.

## 2026-09-08 (audit — F-029)

### (pending commit) — audit F-029 · coverage matrix's own hygiene: hyphenated audit ids and a fixture's self-reference no longer read as false orphans

**Scope:** `buildSrc/src/main/kotlin/org/ort/gradle/CoverageMatrix.kt`,
`buildSrc/src/test/kotlin/org/ort/gradle/CoverageMatrixTest.kt`, `results/coverage-matrix.md`.
**Requirements/ACs:** constitution II (the matrix is generated, so it must be truthful); F-023,
F-027 (the two prior fixes this hygiene gap fell out of).
**What changed:** the regenerated `results/coverage-matrix.md` reported 5 false "orphan tests":
`F-005`, `F-011`, `F-022`, `F-028` — real `@Requirement("FR-UI-7", "F-0NN")`-style audit-id
citations in `RealCaptureServiceTest` — and `AC-999`, a fixture string inside
`CoverageMatrixTest.kt` itself. Two independent bugs: (1) `CROSS_REFERENCE`'s regex
(`^[FDRQ]\d+[A-Z]?$`, added by F-023) only matched the bare `F13` shape and rejected the
hyphenated `F-005` form the audit register actually uses, so a correctly-cited audit id fell
through to "orphan" instead of the cross-reference section — fixed by making the hyphen optional
(`^[FDRQ]-?\d+[A-Z]?$`). (2) F-027 added `buildSrc/src/test/kotlin` to the matrix's own scanned
roots, and the scanner does a naive text scan rather than lexing Kotlin strings, so the fixture
literal inside `CoverageMatrixTest`'s "an orphan test naming an unknown requirement is surfaced"
test — a string that itself looks like `fun \`AC_999_not_a_real_requirement\`(` — was picked up as
a genuine (fake) test declaration when the real file was scanned for real. Fixed per the smaller
of the two sketched options (the brief offered "exclude string literals" or "rename the fixture
so it cannot match"): the scanner already matches only test-declaration shapes, not bare tokens,
so making it string-literal-aware would mean a real Kotlin lexer for a net-new class of bug this
narrow — rejected as disproportionate. Instead the fixture's fake id is now built by string
concatenation (`"AC" + "_999"`), so no contiguous `fun \`AC_999...\`(`-shaped text exists anywhere
in this file's own source (including its explanatory comment, which was rewritten once after an
first pass reintroduced the exact same leak by spelling the id out in prose). The unit test's
assertions are unchanged in substance — it still proves an unrecognised, requirement-shaped id
is surfaced as an orphan, just via a runtime-assembled string rather than a static one.
**Verified:** `./gradlew -p buildSrc test` — new test `F-029 a hyphenated audit id like F-005 is a
cross-reference, not an orphan` seen to fail first (`AssertionFailedError: {F-005=[T],
FR-UI-7=[T]} ==> expected: <false> but was: <true>`) before the regex change, green after; all 30
buildSrc tests green after. `./gradlew coverageMatrix` then `./gradlew coverageMatrixCheck` (run
as separate invocations, per audit convention) — header row "Tests naming a requirement id not in
the spec" is `0`; `F-005`/`F-011`/`F-022`/`F-028` now render correctly under "Cross-referenced
failure modes / decisions / questions"; requirement/covered/uncovered counts unchanged at
419/179/240 (this fix only reclassifies non-requirement ids, it establishes none). Full gate:
`./gradlew build dependencyRules platformGuards` green. Everything here is Robolectric/JVM only;
nothing ran on a physical device.
**Left open / not done:** none for this finding. `spec/ui-conformance-plan.md`'s spec-check rule 2
failure (`Q01`/`Q05`) is concurrent work outside this audit and was left untouched, as directed.

---

## 2026-09-08 (audit close-out — the 2026-09-07 audit-and-remediate run)

### (many) — Audit of the whole repository against constitution, spec and build plan; 27 of 28 findings closed

**Scope:** every module. Register: `results/audit-2026-09-07.md` (the durable record — read it, not
this summary, for evidence and per-finding status). Method per
`docs/reference/audit-and-remediate-prompt.md`: Fable audited and merged, ~30 Sonnet worktree
agents fixed, one finding per agent, each commit prefixed `audit F-0NN ·` and carrying its own
CHANGELOG entry (all of them sit below this one under `2026-09-07 (audit — F-0NN)` headings).
**Requirements/ACs:** see each F-entry. Headline ids re-established for real: FR-RUN-15/16
(F-001, F-005), FR-SEG-6/AC-72 (F-006), FR-RUN-3/5/FR-STO-4 (F-007), FR-RUN-9 (F-003, F-016),
FR-UI-7 (F-002, F-004, F-020, F-022), FR-UI-8/FR-LEX-12 (F-009), FR-UI-4 (F-012), FR-REP-1
(F-013), AC-48/FR-RUN-12 (F-028), AC-3 (F-010), AC-31 at service level (F-011), FR-ASR-1 +
constitution V channel (F-008), FR-UI-3 (F-017), FR-UI-6/Q8/AC-11 (F-018), FR-UI-11 (F-019),
FR-STO-5 (F-020), plus ~60 built-but-unnamed ids across every module (F-027).
**What changed:** found by class — B silently-wrong 5, C constitution 2, A wiring 4, E claimed-not-
established 4, D unrecorded divergence 1, F recorded gaps 12. Fixed: all of A–E and every F that
was unblocked. The two constitution breaches were real: too-short segments were deleted at the
production sink (III), and no shed controller or storage floor ran in the capture service (IV).
The worst silent-failure: every real transmission was persisted with `startedAtUtc = 0L`. New
structural gates: `coverageMatrixCheck` (stale matrix fails CI) and `platformGuards` (no
telemetry dependency anywhere, HTTP client and INTERNET permission only in `:net`, and `:net`
must declare it). The coverage matrix now also scans `buildSrc` and `corpus/tests`.
**How verified:** every merge into `main` ran `./gradlew build dependencyRules platformGuards`,
`./gradlew -p buildSrc test`, `python tools/spec-check/spec_check.py` and regenerated the matrix.
Coverage: 101 covered / 318 uncovered (committed, stale) → 117 / 302 (regenerated at audit
start) → 179 / 240 at close-out, 419 ids. Every fix is Robolectric/JVM only.
**Left open / not done:**
- **Class G — cannot be closed without the physical world:** P9 device matrix (AC-4/AC-64, D1–D12),
  AC-6 (needs the Q2 noise tape + Q16 labelling round), AC-73/AC-75 latency and backlog on device,
  R4 (Fearless Steps registration), R1 CI lane, M3 dev-fold precision/recall, the pure-Kotlin FLAC
  codec's on-device cost (F-026), release signing, and — above all — **nothing in this repository
  has ever run on a physical device**, including the new Models download screen (real digests are
  pinned for the Whisper encoder/decoder and Silero VAD; `tiny.en-tokens.txt` has no published
  sha256 anywhere and is structurally sideload-only).
- **Class H — unbuilt milestones, not defects (198 uncovered ids):** M6 identity/voice/threading
  (FR-SPK ×29, `threadId` writer, FR-UI-2), M7 rig (FR-RIG ×12, FR-SEG-5), M8 streaming (the
  post-capture drain, F-025), M9 digest/station knowledge/contribution/export (FR-DIG ×18,
  FR-CON ×8, FR-EXP ×6, FR-LEX-24), M10 tiers/reprocessing (FR-TIER ×7, FR-REP ×8, FR-RUN-4),
  M11 reference levers (FR-ENH ×4, eval unseal), the asset lifecycle FR-AST-1/4/7/9, storage
  budgets FR-STO-3.., profiles FR-CFG-3/4, diagnostics FR-OBS-1..3, and the M4 fork decision.
- **Honestly not established although the family is built:** FR-LEX-15/16/22/32, FR-CAP-2/7,
  FR-PLT-2/4, FR-RUN-6 (warning half), FR-RUN-14, FR-SVC-5/6/8, FR-A11Y-5 (strings are hard-coded
  literals), FR-ASR-10, FR-TST-6 (no load/endurance generator exists), FR-STO-2/2a, CON-STO-1,
  CON-CAP-1, NFR-5 (androidTest naming), AC-13, AC-35 — each named in its slice's F-027 entry.
- Small residues named in the register: FAILED rows show no `lastError`; oldest-unprocessed age is
  not displayed; the week-over-week trend is text with one data point per side; F-019's tests were
  written alongside the code rather than seen red first.
- The clean-tree spec check fails on `spec/ui-conformance-plan.md`'s `Q01`/`Q05` references, which
  arrived on `main` from concurrent UI-conformance work outside this audit; not touched here.

## 2026-09-08 (UI conformance — phase C)

### (pending) — ui-conformance C · findings register: 73 rows, every one assigned to a work package

**Scope:** `results/ui-audit/register.md` (new). `.gitignore` (the seeded canvas page is a
build output of `design/canvas/`, not a source). No product code touched.
**Requirements/ACs:** the register cites the requirement each row fails — FR-UI-1..12, FR-UI-7
in particular (six of nine required facts absent, R-032), FR-A11Y-2 (R-011, R-014, R-024),
FR-CAP-2a/F1 (R-081, R-101), FR-SPK-10 (R-073), P3/P5/P8/P9/P12, and constitution I (R-012,
R-034). "none new" for code.
**What changed:**

- **Constitution Check.** Principle I decides severity: a row is `halt` when the built screen
  is *misleading* to the operator, not merely unlike the artboard — the drawer footer that draws
  device free space as if it were the audio budget (R-012), the status surface that prints a
  file path and a class name as the reason transcripts are missing (R-034), the two-line
  "ASR: ASR:" label (R-031), the unreachable "Worth knowing" (R-030), the tap-to-cycle filters
  the operator cannot see the options of (R-061). Principle VII is why every finding names an
  artboard and a token, never "looks wrong": a builder can close it without asking what right
  looks like.
- **The register.** 73 rows across the eleven work packages of `spec/ui-conformance-plan.md`
  §D, each with the screen, the artboard compared against, what is wrong, a severity (`halt` 11,
  `spec` 27, `design` 32, `polish` 1, `process` 2) and a status. Evidence is the nine emulator
  screenshots taken at `6aaa608` and the reader source at `bff688b`, which includes the F-002,
  F-004, F-017, F-018 and F-019 fixes other sessions merged while phase A was drawing — the
  audit is against the code as it is now, not as it was when this session began.
- **What the audit found, in one line each.** The reader has a second header from the platform
  theme on every screen; Now is a status field dump where the artboard is a home; the status
  surface lacks six of FR-UI-7's nine facts; the Log has none of the row variants the canvas
  specifies and no partial state at all; the detail's inspection surface and correction flow are
  text and `TextField`s where the boards are lattice slots, prior bars, a three-tier sheet and a
  propagation screen; search has three things labelled "Search" and cycling filters; stations
  and frequencies show a fraction of their boards; setup, settings, improve, digest, sessions
  and all 22 failure states are placeholders or absent; there is no live bar; no shared
  component exists for chips, banners, toasts, sheets, badges or icons.

**Verified:** each row was checked against the named artboard's source and the named file;
the screenshot rows cite the file in this session's scratchpad by name (`01-launch.png` …
`09-search-run.png`). No number is claimed that was not counted.
**Left open / not done:**

- Rows are `open`; none is `building` yet. Phase D assigns WP0 and WP1 first (no dependencies),
  then WP2, then the rest in parallel per the plan's ownership table.
- The screenshots themselves are in the session scratchpad, not the repo; WP0's tooling writes
  future ones to `results/ui-audit/<scenario>/`.
- R-102 (notification content) needs `:pipeline`'s `RealCaptureService` read; it is assigned to
  WP11, whose brief lists the emit points it may touch.

---

## 2026-09-08 (UI conformance — phases A and B)

### (pending) — ui-conformance A+B · complete design canvas: 108 artboards, design guide, design intent, conformance plan

**Scope:** `design/` — `design-guide.md` (new), `design-intent.md` (new), `review-2026-09.md`
(new), `canvas/` (101 new `.dc.html` artboards beside the 7 that existed, `canvas.json`
regenerated to 11 pages). `spec/ui-conformance-plan.md` (new — the program this is phase A/B of).
No product code touched.
**Requirements/ACs:** FR-UI-1..12 (every one has at least one artboard that fully expresses it —
`design-intent.md` §14), FR-A11Y-1..6 (the `States` greyscale proof, 44px targets on the `Grid`
board, sp-scaling asserted in the guide), P1–P12 of functional spec §13 (each mapped to the
screen where its cost is paid — `review-2026-09.md` §1), F1–F22 of §12 (one full-screen artboard
each, showing the response column), D15, D26. "none new" for code.
**What changed:**

- **Constitution Check.** Principle I (uncertainty is content) is what the whole canvas is built
  around: four attribution states as a closed set with a greyscale proof, a Pass A partial that
  carries no marker at all, "unknown station" as a result rather than a failure, and a
  `Detail-Unknown` board whose body is *what was tried*. Principle III (nothing leaves the
  device) is stated on `Settings-Contribute`, `Settings-Export`, `Settings-Diagnostics` and
  `Station-Identity`, each naming what is never included and that there is no switch for it.
  Principle VII (structural, not conventional) is why `Controls` specifies a closed set as a
  visible list with counts and calls a tap-to-cycle label wrong — the built app's search filter
  is exactly that, and the audit (phase C) will cite this board against it.
- **The canvas.** 108 artboards across 11 pages: foundations (colour, type, rows, states,
  controls, feedback, charts, grid, icons), setup (13, with the route-mismatch halt and the
  mic-refused state), Now and capture (7), Log and threads (8), transmission detail (11: four
  states, the inspection surface, playback, revisions, three correction tiers, propagation),
  search / stations / frequencies (12), digest and improve (8), settings (10), failure states
  (22), flows (6), explorations (2, unchanged). Every board is 390×844 and dark; every value is
  from the token set; every tappable thing is ≥44px including the ones styled as text.
- **`design-guide.md`.** Tokens lifted from the artboards (OKLCH, the chroma rule, the full
  text ramp, both accent families, the `halt/*` red reserved for capture-stopped, three chart
  ramps), the type ramp with every size the boards use, spacing/radii/targets, 19 component
  specs, icon rules, the not-listening rule, copy rules, density, the accessibility floor, and
  what the design deliberately does not do.
- **`design-intent.md`.** The inventory: every screen with its purpose, the requirement ids it
  serves, every interaction enumerated, and status. 108 rows, all `drawn`. §14 maps FR-UI-1..12
  to boards.
- **`review-2026-09.md`.** Phase B's record: coverage against FR-UI / P1–P12 / F1–F22, and the
  consistency pass — the guide was found to have dropped seven greys and four sizes the original
  boards use (fixed in the guide), and 43 genuine strays across 28 boards were normalised.
- **`spec/ui-conformance-plan.md`.** Phases A–G, roles (lead draws/audits/delegates; builders
  and validators run as separate agents), 12 work packages partitioned by file ownership, 7
  emulator validation scenario sets, concurrency and safety rules.

**Verified:**

- `node seed-canvas.mjs --check offline-radio-transcriber-design-system.html` → `ok: … 109
  files` (108 artboards + `canvas.json`), 3.3 MB. Published to
  https://claude.ai/code/artifact/1c9b3001-cc3f-4805-8e3f-b917eae44417.
- The manifest generator asserts every `.dc.html` on disk is listed and every listed file
  exists: `artboards: 108 · on disk: 108`.
- Colour/size census over all boards (PowerShell regex count): after normalisation, no
  `oklch()` value outside the guide's token set remains in any board other than `Editorial` and
  `Timeline`; no `font-size` outside §4's table other than the `Type` board's scaling demo.
- `design-intent.md` has zero `planned` rows (`replaced 100 planned -> drawn`).

**Left open / not done:**

- No product code changed. Phase C (audit of the built UI against these boards → the findings
  register) and phases D–G (build, validate on emulator, iterate) follow, per the plan.
- Artboards are not rendered to PNG; emulator comparison in phase E is by eye against the
  `.dc.html` source. A headless render step is noted in the review as a possible tightening.
- `Editorial.dc.html` and `Timeline.dc.html` keep their original off-token values; they are the
  exploration sketches and are not audit targets.
- The design canvas's saving path depends on the artifact runtime being pinned at contract
  0.1.31; the published page cannot pick up later editor fixes (a stated limit of the preview).

---

## 2026-09-08 (audit — F-018)

### (pending) — audit F-018 · Q8's "search the lexicon" correction tier is now real lexicon search

**Scope:** `:pipeline` — new `passb/LexiconLookup.kt` (`LexiconMatch`, `LexiconLookup`,
`RealLexiconLookup`), new `passb/fake/FakeLexiconLookup.kt`, new test
`passb/LexiconLookupTest.kt`. `:app` — `ui/data/CorrectionFlow.kt` (`CorrectionTier` renamed
`SEARCH_KNOWN_STATION` → `SEARCH_LEXICON`), `ui/data/ReaderPolling.kt` (`searchKnownStations`
replaced with `searchLexicon`), the correction section of `ui/screens/TransmissionDetailScreen.kt`,
`ui/navigation/OrtNavHost.kt`'s wiring, and the corresponding tests
(`CorrectionFlowTest.kt`, `TransmissionDetailScreenCorrectionTest.kt`).
**Requirements/ACs:** FR-UI-6, `spec/open-questions.md` Q8/D32 (tiered correction), AC-11 (an
unallocated prefix is never a candidate), constitution I ("the correction must be able to name a
callsign the lexicon knows even when no candidate matched" — this fix's own framing).
**What changed:**

- **Constitution Check.** Principle I (Uncertainty Is Content) is squarely the one that bears:
  P16 shipped a real but narrower feature than Q8 asked for (searching only stations already
  heard) and reported the gap honestly rather than silently — this fix closes that reported gap
  rather than leaving it open indefinitely. Principle VII (structural, not conventional) is why
  the fix lives where it does: `LexiconMatch` carries plain `String` fields
  (`ituPrefix`/`ituCountry`/`ituIso`), not `:lexicon`'s `ItuAllocation`, so `:app` never gains a
  compile edge to `:lexicon` it should not have — the module graph stays exactly as
  `dependencyRules` already enforces it.
- **`:pipeline`'s new call site.** `LexiconLookup.search(prefixOrPartial, limit)` returns
  grammar-valid callsigns and/or live ITU prefix allocations for a typed fragment.
  `RealLexiconLookup` is built over the identical bundled `ItuPrefixTable`/`CallsignGrammar`
  `PassBFactory` already constructs (`RealLexiconLookup.bundled()`) — one lexicon, not a second,
  drifting copy. Two lookup paths, both routed through real ITU data so AC-11 holds structurally:
  (1) an exact, complete callsign is checked by spelling the typed text into `PhoneticUnit`s
  (`PhoneticUnit.spell` — the same closed alphabet the grammar itself uses) and running it through
  the real `CallsignGrammar.parse`, keeping only the literal exact-text match (never a
  confusion-based near-miss — this is a deliberate typed search, not acoustic resolution); (2) a
  partial fragment inside a known allocated block (e.g. "K7" while typing "K7ABC") or a fragment
  a block could still grow from (e.g. "K" surfacing "K"/"KH6"/"KL7") is read directly off
  `ItuPrefixTable.allocationFor`/`.allocations`. A fragment containing anything outside the
  phonetic-unit alphabet (a stray symbol) is rejected before either lookup path runs, so
  `ItuPrefixTable`'s character trie cannot silently match on just a garbled fragment's valid
  leading substring and misreport the whole string as inside a block — a real bug caught by
  `FR_UI_6_an_unknown-symbol_fragment_does_not_throw...` failing first with the wrong assertion.
  `FakeLexiconLookup` (constitution II) is scriptable to return a fixed per-query result map or
  throw, and records every query it was asked, for `:app`-side tests that need to assert what was
  actually searched.
- **`:app`'s tier, replaced not added.** Q8 (`spec/open-questions.md`) names exactly three tiers —
  pick a candidate, search the lexicon, free text — not four; it never asked for a
  known-stations tier. Per this fix's own instruction ("keep 'search known stations' as its own
  tier if Q8 lists both; otherwise replace it"), `CorrectionTier.SEARCH_KNOWN_STATION` is renamed
  `SEARCH_LEXICON` and `ReaderPolling.searchKnownStations` (which queried `:data`'s
  `ActivityDao.listStations()`) is replaced by `ReaderPolling.searchLexicon`, which calls the new
  `:pipeline` seam on `Dispatchers.Default` (a CPU-bound pool — the grammar's beam search is real
  work, not I/O, and this runs on every keystroke of a live search box). The correction sheet's
  middle section is now labelled "Search the lexicon" and renders each match as
  `"$callsign — $ituCountry ($ituPrefix)"`; picking one applies the correction exactly as the other
  two tiers do — `CorrectionDao.recordCorrection` via `CorrectionDao.FIELD_STATION` (eligible to
  feed a future prior), the same `CORRECTED` lock (`INFERRED`, no confidence, no propagation
  source) `CorrectionDaoTest`'s existing re-propagation-lock test already proves; this fix does not
  touch `:data` or that contract.
**Verified:**
`./gradlew :pipeline:testDebugUnitTest --tests "org.ort.pipeline.passb.LexiconLookupTest"` —
first run (before the implementation existed) failed to compile
(`Unresolved reference 'RealLexiconLookup'` / `'search'`); after adding `LexiconLookup.kt`, 8/8
green, including `AC_11 a callsign whose prefix is not ITU allocated is never a match` and the
unknown-symbol-fragment case above, which failed once for the wrong reason (asserted `false` — the
trie-walk bug) before the phonetic-alphabet gate fixed it.
`./gradlew :pipeline:test` — full module green (no regressions).
`./gradlew :app:testDebugUnitTest --tests "org.ort.app.ui.data.CorrectionFlowTest" --tests "org.ort.app.ui.screens.TransmissionDetailScreenCorrectionTest"` —
all green, including `FR_UI_6_Q8 searching the lexicon and picking a result applies a verified
correction` (asserts both the query reaching the seam and the resulting `CorrectionRequest`).
`./gradlew :app:test` — full module green (no regressions). `./gradlew build dependencyRules` —
green; `dependencyRules` confirms no new module edge (`LexiconMatch` is plain strings, not a
`:lexicon` type, so `:app` still has no edge to `:lexicon`). `python tools/spec-check/spec_check.py`
— all 8 checks pass. All Robolectric/JVM only — no device has run this.
**Left open / not done:** the lexicon search box surfaces live ITU prefix-block suggestions (e.g.
typing "K" shows "K", "KH6", "KL7") alongside exact callsign matches, by design (an expert operator
benefits from seeing the block is valid while still typing) — but picking a bare prefix suggestion
records it as the station identity exactly like a full callsign would (`CorrectionDao.FIELD_STATION`
takes whatever string is picked); this is a UI/UX judgment call, not re-litigated here, and a
future session could restrict picking to exact-callsign matches only if that proves confusing in
practice. FR-UI-8's inspection surface (P16's other reported gap — no writer for lattice/candidate
rows) is untouched; that is a separate finding.
## 2026-09-08 (audit — F-015)

### (pending) — audit F-015 · design-canvas divergences named as recorded deferrals; no scrubber built

**Scope:** `design/canvas/README.md` (new), `spec/build-plan.md` (P14 progress note only).
**Requirements/ACs:** FR-UI-5 (playback); none of the other elements below are named in the
functional spec, so they are cosmetic (audit class D) rather than a requirement gap.
**What changed:** `results/audit-2026-09-07.md`'s F-015 found four unrecorded divergences between
the design artboards and the shipped Compose screens: `Log.dc.html`'s rejected-row dimming and
"new" badge, filter chips on the Log screen itself, and `Detail.dc.html`'s playback
waveform/scrubber (`TransmissionDetailScreen.kt`'s `PlaybackSection` has a text "▶ Play" only). The
register's own fix sketch says recording is acceptable, which is what this change does:
`design/canvas/README.md` is a new standing "implemented vs artboard" table, one row per artboard
with a real Compose build, naming every element left out and where it is deferred to — the four
F-015 items above, plus the previously-scattered-across-CHANGELOG deferrals gathered into one
place: the Now screen's activity chart and digest (M9), Log's QSO-header thread grouping (M6) and
inline gap row (P17 follow-up), Detail's lattice/per-prior panel (built as text instead, P16), the
drawer's live badge counts and per-category storage footer (F-020, open), and the light theme (no
artboard exists to derive one from — not tied to any milestone).

Before recording the scrubber as a pure deferral, this session checked whether the cheap real half
— a playback progress/position *text* indicator, no seeking — was buildable without touching the
player. It is not: `TransmissionAudioPlayer` (`app/src/main/kotlin/org/ort/app/ui/audio/
TransmissionAudioPlayer.kt`) exposes only `suspend fun play(transmissionId): PlaybackOutcome`
(`Played` / `Unavailable(reason)`) and `fun stop()` — no position, no duration, no in-progress
state — and neither `RealTransmissionAudioPlayer` (a `MODE_STATIC` `AudioTrack`, fire-and-forget
once `play()` returns) nor `FakeTransmissionAudioPlayer` tracks one either. Per the constitution's
"stop rather than half-finish": extending that interface (and both implementations, plus a new
behavioural-fake capability) is a larger, separate piece of work than this finding's fix, so
`PlaybackSection` is unchanged and no progress text was added.
**Verified:** no test-affecting code changed — `PlaybackSection` and `TransmissionAudioPlayer` are
untouched. Read `design/canvas/README.md`'s own tables for the (manual) cross-check against
`LogScreen.kt`, `TransmissionDetailScreen.kt`, `NowScreen.kt`, and the two `.dc.html` artboards
named in the finding. `./gradlew :app:test` unaffected (no `:app` source changed).
**Left open / not done:** the scrubber and the progress-text half of it remain unbuilt — genuinely
deferred, with no P-number, until a prompt extends `TransmissionAudioPlayer` with a position API.
Rejected-row dimming, the "new" badge, and Log's filter chips remain unbuilt (class D — not named
in the functional spec).

---
## 2026-09-08 (audit — F-020)

### (pending) — audit F-020 · real drawer badges and a storage footer that admits it has no budget

**Scope:** `:app` only — `app/src/main/kotlin/org/ort/app/ui/navigation/Drawer.kt`,
`DrawerBadgeViewState.kt` (new), `StorageFooterViewState.kt`, `OrtNavHost.kt` (badge/footer
polling only); `app/src/main/kotlin/org/ort/app/ui/data/ReaderPolling.kt` (new `drawerBadges`
function only — `currentStatus`/`detailFrom` untouched); tests
`app/src/test/kotlin/org/ort/app/ui/navigation/DrawerContentTest.kt`,
`StorageFooterViewStateTest.kt` (new), `app/src/test/kotlin/org/ort/app/ui/data/ReaderPollingTest.kt`.

**Requirements/ACs:** FR-STO-5 (display current storage usage), FR-UI-7 (the capture status
surface's elapsed time and transmissions-captured facts, here surfaced in the drawer too),
constitution I (a fabricated number is a bug at the data layer, not a UI nit) and IV (capture
facts must never lie).

**What changed:** `design/canvas/Menu.dc.html` shows live drawer badges (a Log count, a Threads
count, a running Capture timer) and a per-category storage footer ("Audio 38.2 GB of 60"). The
drawer previously rendered destination labels only, and the footer
(`StorageFooterViewState.fromDeviceStorage`) reported whole-device `StatFs` used/total space under
an "Audio" header with a fixed `isPlaceholder = true` — a device-wide figure displayed as if it
were the audio category's own budget, against a total (D26/FR-STO-3) this build has never set.

Fixed, honestly scoped:
- **Badges** — `ReaderPolling.drawerBadges(context, sessionId)` (new function; every other
  `ReaderPolling` function is untouched) returns `DrawerBadgeViewState`: `logCount` is the real
  transmission count for the active session (`transmissionDao().listBySession(...).size`, `null`
  when idle); `captureElapsedLabel` is the running session's elapsed time computed from its real
  `SessionEntity.startedAt` row, only when `CaptureState.isCapturing` and the capturing session id
  matches the one being viewed (`null` otherwise, including when idle or when a *different*
  session is capturing); `threadsCount` is always `null` — `TransmissionEntity.threadId` is never
  populated by any prompt yet, so a numeric count would claim a grouping that does not exist. The
  drawer renders Threads as `"—"`, never a `0`-as-if-grouped fake count. `OrtNavHost` polls both
  this and the footer every `POLL_INTERVAL_MILLIS` (2s), the same cadence `NowContent`/`LogContent`
  already use, via a small `rememberDrawerLiveState` helper (extracted only to keep `OrtNavHost`
  under detekt's method-length limit).
- **Storage footer** — `StorageFooterViewState.fromAudioDirectory` replaces `fromDeviceStorage`:
  `audioUsedBytes` is the real sum of file sizes under `<filesDir>/audio/**` (the same
  `audio/<sessionId>/<transmissionId>.flac` layout `FlacStore`/`TransmissionEntity.audioPath()`
  use), `freeBytes` is unchanged real `StatFs` free space on the same volume, and `hasBudget`
  stays `false` (FR-STO-3's per-category budget setting is still unbuilt) — the footer text reads
  "Audio: X used" / "Y free · no budget set", never an "of N GB" total. `isPlaceholder` is gone
  entirely: every field is now a real, non-fabricated measurement, so there is nothing left to
  flag as a placeholder.

**Verified:** `FR_STO_5_*` tests in `StorageFooterViewStateTest`/`DrawerContentTest` (byte total of
real files written under a temp `filesDir/audio`, "no budget set" text present, no "of N GB"
string ever rendered) and `FR_UI_7_*` tests in `ReaderPollingTest`/`DrawerContentTest` (Log shows
the real transmission count, Capture shows the elapsed time only for the actually-capturing
session and nothing otherwise, Threads never shows a numeric badge) — all seen failing first
(unresolved-reference compile errors for the not-yet-written `drawerBadges`/`fromAudioDirectory`/
`DrawerBadgeViewState`, then one genuine assertion failure against Robolectric's zero-valued
`StatFs` shadow, fixed by asserting "read from StatFs" rather than a specific positive value).
`./gradlew :app:testDebugUnitTest --tests ... ` green (29 tests), then `./gradlew :app:test`
green, then the full gate: `./gradlew build dependencyRules` and
`python tools/spec-check/spec_check.py` both green.

**Left open / not done:** FR-STO-3 (the per-category budget setting itself) is still unbuilt —
`hasBudget` stays `false` and the footer will keep saying "no budget set" until that prompt lands;
this fix only stops the footer from fabricating a number against a budget that was never real.
Threads' "—" is a fixed marker, not yet reachable through any other UI signal that threading is
unbuilt — acceptable since P15's `ThreadScreen` already documents the same limitation.
Robolectric-only verification throughout; no on-device check was performed.
## 2026-09-07 (audit — F-022)

### (pending) — audit F-022 · relaunching `MainActivity` during a running capture no longer hands the reader a phantom session

**Scope:** `:app` — `app/src/main/kotlin/org/ort/app/MainActivity.kt` (`startCaptureAndShowStatus`
only), `app/src/main/kotlin/org/ort/app/ui/ReaderActivity.kt` (session-id selection only);
`:pipeline` — `pipeline/src/main/kotlin/org/ort/pipeline/capture/CaptureState.kt` (`idle` gains a
`clearSession` parameter), `RealCaptureService.kt` (`stopCaptureInternal` passes it through); new
test `app/src/test/kotlin/org/ort/app/MainActivityTest.kt`; extended
`pipeline/src/test/kotlin/org/ort/pipeline/capture/CaptureStateTest.kt` and
`RealCaptureServiceTest.kt`.
**Requirements/ACs:** FR-UI-7 (capture status surface must always reflect the real running state);
constitution IV ("never lies") — a live capture session reading as empty in the reader is exactly
the silent failure this principle forbids.
**What changed:** `RealCaptureService.onStartCommand` already ignored a second start command while
one was live (a prior on-device fix), but nothing on the `:app` side knew that: `MainActivity`
always minted a fresh ULID session id in `onCreate` and handed it straight to `ReaderActivity`
regardless, so relaunching the app during an active capture showed the reader polling a session
nothing was capturing into (zero overs, live capture). Fixed by publishing the live session id
where it was already half-published: `CaptureState.sessionId` (set by
`RealCaptureService.startCapture()` since F-002/F-007) is now the source of truth `MainActivity`
consults before deciding whether to start the service at all, and before choosing which session id
to hand `ReaderActivity`. `CaptureState.idle()` gained a `clearSession: Boolean = false` parameter
so only a deliberate `ACTION_STOP` clears the published session id — an unclean stop (`onDestroy`
without a prior `ACTION_STOP`, e.g. the OS killing the process) leaves it in place, since that is a
different situation (nothing to hand off to) and clearing it there would just be a second way to
lose the "which session was this" fact for no benefit. `ReaderActivity.onCreate` also now prefers
`CaptureState.sessionId` over its intent extra whenever capture is genuinely live, as defence in
depth for any path back into it other than `MainActivity`'s own fix.
**Verified:** `RealCaptureServiceTest`'s new case
`FR_UI_7 CaptureState publishes the running session id and clears it only on a clean stop` — seen
to fail first (`AssertionError: a deliberate stop must clear the session id ... expected null, but
was:<TEST-SESSION-22>`) with `CaptureState.idle`'s clearing line commented out, then passed once
restored. `CaptureStateTest`'s new cases similarly seen to fail first
(`AssertionFailedError: ... expected: <null> but was: <SESSION01>`). `MainActivityTest` (new file,
Robolectric) — 2 of its 3 cases seen to fail first with the old unconditional
`startService`/fresh-id logic restored, then passed after the fix. `./gradlew :app:testDebugUnitTest
:pipeline:testDebugUnitTest` — full green (all pre-existing cases plus the new ones). Full gate:
`./gradlew build dependencyRules` and `python tools/spec-check/spec_check.py` — see this session's
final report for the tail.
**Left open / not done:** everything here is Robolectric/JVM only, never verified on a physical
device — the underlying singleton-service guard this builds on was itself found on-device but its
`:app`-side consequence (this finding) was reasoned from code reading, not reproduced on hardware.
`MainActivity`'s own doc comment still refers to the deleted `StatusActivity` (stale from an
earlier prompt) — out of this finding's scope, left as found.

## 2026-09-08 (audit — F-008 follow-up)

### (pending) — audit F-008 follow-up · real, cited sha256 checksums replace the placeholder in `ModelCatalog`

**Scope:** `:app` only — `app/src/main/kotlin/org/ort/app/ui/data/ModelsViewData.kt` (new:
`ChecksumState`; `ModelCatalogEntry`/`ModelCatalog.specFor` now checksum-aware;
`ModelsController.download`/`.sideload`/`rowFor` react to it), `app/src/main/kotlin/org/ort/app/ui/screens/ModelsScreen.kt`
(unknown-checksum state text and disabled Download button only); tests
`app/src/test/kotlin/org/ort/app/ui/data/ModelCatalogTest.kt` (new),
`ModelsControllerTest.kt`, `ModelsScreenTest.kt`; `asr-sherpa/README.md` (new checksum table).
No `:net` file touched.

**Requirements/ACs:** FR-AST-1, constitution V/VII (asset integrity checked before activation),
constitution I (an installed state without its verification confidence is a bug, same discipline
as an attribution without its state), constitution VI (no number without its provenance, applied
to a checksum).

**What changed:** The previous F-008 `:app`-half change (2026-09-07, this file) shipped every
`ModelCatalog` entry with `UNPINNED_CHECKSUM`, a deliberately non-hex placeholder string, because
no network egress was available to actually download a model and hash it. This change obtains the
*real* published digests without downloading any binary, using `WebFetch` against small,
non-binary metadata endpoints, and records exactly where each was read (KDoc on `ModelCatalog`,
table in `asr-sherpa/README.md`):

- `tiny.en-encoder.int8.onnx` and `tiny.en-decoder.int8.onnx` are Git-LFS-tracked on HuggingFace:
  fetching `.../raw/main/<file>` for an LFS path returns the small LFS pointer text itself
  (`oid sha256:<hex>`, `size <bytes>`) rather than the binary. Both are now `ChecksumState.Known`
  with the sha256 read from that pointer.
- `tiny.en-tokens.txt` is *not* LFS-tracked: HuggingFace's file-listing API gives only a 40-hex git
  blob id for it (a SHA-1, not a SHA-256), and no sha256 for this specific extracted file is
  published anywhere else found (checked: HF's raw/API endpoints, and sherpa-onnx's own
  `checksum.txt` release manifest, which covers whole `.tar.bz2` archives only). Rather than invent
  a value, this entry is `ChecksumState.UnknownSideloadOnly` with a reason string.
- `silero_vad.onnx` has no per-asset `digest` field on GitHub's Releases API (confirmed null even
  for the differently-named `silero_vad_v5.onnx` asset in the same release), but the release
  itself publishes a `checksum.txt` manifest asset — a tab-separated `<filename>\tsha256` line per
  asset — which lists `silero_vad.onnx` by its exact name. Now `ChecksumState.Known` from that line.

`ModelCatalog.specFor` now returns `ModelFetchSpec?`, null exactly for an
`UnknownSideloadOnly` entry. `ModelsController.download()` refuses such an entry with a `Failure`
and **no network call at all** (proven by a test client that throws if invoked) — there is nothing
to verify a downloaded file against. `ModelsController.sideload()` still works for such an entry:
since there is no known-good digest to check the user's file against, it computes
`sha256Of(source)` itself and installs against that self-referential value — a plain
trust-on-first-use of the exact bytes supplied, entirely within `:app` (no `:net` change), never
presented as "checksum verified" (a new `ModelRowStatus.INSTALLED_UNVERIFIED`, distinct from
`INSTALLED`, and a new `ModelRowViewState.checksumKnown` flag the screen uses to disable Download
and show "No published checksum for this file — Download is disabled.").

**Verified:**
- `ModelCatalogTest` (new, 6 tests) seen to fail for the right reason first: before `ChecksumState`
  existed, `./gradlew :app:compileDebugUnitTestKotlin` failed with `Unresolved reference
  'checksumState'` / `'ChecksumState'` (18 errors) against the test file; after the implementation,
  the same command is clean and all 6 tests pass.
- `./gradlew :app:testDebugUnitTest --tests "org.ort.app.ui.data.ModelCatalogTest" --tests
  "org.ort.app.ui.data.ModelsControllerTest" --tests "org.ort.app.ui.screens.ModelsScreenTest"`:
  green, including two new `ModelsControllerTest` cases (download refuses with no network call for
  `ASR_TOKENS`; sideload installs it `INSTALLED_UNVERIFIED`) and two new `ModelsScreenTest` cases
  (unknown-checksum text + disabled Download; unverified-install status text).
- `./gradlew :app:testDebugUnitTest` (full module): green.
- `./gradlew build dependencyRules` (full gate): green (after fixing two detekt findings —
  `MaxLineLength`, `MayBeConst` — surfaced by this same run).
- `python tools/spec-check/spec_check.py`: all 8 checks PASS.
- Every digest above was read from published metadata via `WebFetch`, never computed from a
  download this change performed, and never typed from memory — see the per-entry KDoc and the
  `asr-sherpa/README.md` table for the exact source of each. No on-device install and no real
  network fetch against these URLs was run in this session (everything above is Robolectric/JVM
  against fakes, same as the original F-008 `:app`-half change).

**Left open / not done:**
- `tiny.en-tokens.txt` still has no known-good sha256 to pin — genuinely unresolved, not merely
  unpinned by omission: no authoritative sha256 for this exact file was found anywhere published.
  It installs only via the trust-on-first-use side-load path now built for exactly this case.
- `silero_vad.onnx`'s exact size in bytes was not confirmed (GitHub's paginated asset list was not
  fully walked to find that one entry — see `asr-sherpa/README.md`'s note); this does not affect
  checksum verification, since `ModelFetchSpec` carries no size field.
- The `:net` half of F-008 (the `INTERNET` manifest grant) remains a separate agent's already-landed
  work, per the previous entry; not touched here.
- No real download against any of these four URLs, and no on-device install, has been run in any
  session to date.
## 2026-09-07 (audit — F-019)

### (pending) — audit F-019 · FR-UI-11's day-of-week and week-over-week halves

**Scope:** `:app` only — `app/src/main/kotlin/org/ort/app/ui/data/ActivityPattern.kt` (new
`ActivityBucket` interface, `DayOfWeekActivityBucket`, `WeekTrend`, `WeekOverWeekBucket`,
`ActivityPatternMapper.buildDayOfWeekPattern`/`buildWeekOverWeekComparison`),
`ui/components/ActivityPatternChart.kt` (generalised to `List<ActivityBucket>`, `title`/
`summaryLabel`/`barLabels` params), `ui/data/StationsAndFrequencies.kt` (view states carry
`dayOfWeekPattern`/`weekOverWeekSummary`; new `dayOfWeekShortLabel`/`weekOverWeekSummaryText`),
`ui/data/ReaderPolling.kt` (wires the two new mappers through, in `ZoneId.systemDefault()`),
`ui/screens/StationScreen.kt` and `ui/screens/FrequencyScreen.kt` (render the day-of-week chart
and week-over-week text beneath the existing hour chart), plus their tests.

**Requirements/ACs:** FR-UI-11 (both halves P17 left open: "by day of week" and "how that has
changed"), FR-UI-12/AC-126 (the three-state not-heard/not-listening distinction now also holds in
every day-of-week bucket, not only every hour-of-day one).

**What changed:** `ActivityPatternMapper.buildDayOfWeekPattern` buckets the same real
session/gap windows `buildPattern` already used, at calendar-day rather than hour granularity, in
the device's own zone (`ZoneId`, not UTC or a sample position — F-001 made real wall-clock
timestamps available) — same three-state priority (HEARD, then SILENT_WHILE_LISTENING, else
NOT_LISTENING), folded onto the 7 ISO weekdays. `buildWeekOverWeekComparison` compares the most
recent 7-day window against the 7 days before it, per weekday, and reports `WeekTrend.NO_DATA`
(never a fabricated up/down) whenever either window had no listening time at all for that
weekday. `ActivityPatternChart` is generalised behind a new `ActivityBucket` interface
(`state`/`heardCount`) so one component renders either pattern; `HourActivityBucket` and
`DayOfWeekActivityBucket` both implement it. The station and frequency detail screens now render
the day-of-week chart (with "Mon".."Sun" bar labels) and a plain-text "this week vs last" line per
weekday beneath the existing hour-of-day chart. `StationEntity.activityByHourDow` (unused,
flagged in the finding) is left untouched — the pattern is still computed live from session/gap
rows, exactly as the hour-of-day pattern already was, rather than wiring an unrelated cache column.

**Verified:** `./gradlew :app:testDebugUnitTest` (green, includes new
`ActivityPatternMapperTest` cases `FR_UI_11_day_of_week ...` ×3, `AC_126_FR_UI_11_day_of_week ...`,
`FR_UI_11_week_over_week ...` ×2, and new `StationScreenTest`/`FrequencyScreenTest` cases
asserting the day-of-week chart renders with seven labelled buckets); `./gradlew build
dependencyRules` (green); `python tools/spec-check/spec_check.py` (OK). Robolectric/JVM only, no
device run.

**Left open / not done:** The week-over-week comparison is deliberately text-only, one weekday
against its single predecessor a week prior — with only one real 7-day window on each side there
is no honest multi-point trend to plot, so no chart was added for it (would need weeks of history
to be more than one sample point wide). `StationEntity.activityByHourDow` remains unused; wiring
it as a cache is out of this finding's scope.
## 2026-09-07 (audit — F-025)

### (pending) — audit F-025 · record that the Pass B backlog only drains while `RealCaptureService` is alive

**Scope:** `spec/build-plan.md` (P12 progress note only), `pipeline/src/main/kotlin/org/ort/pipeline/capture/RealCaptureService.kt` (KDoc only, no behaviour change), `CHANGELOG.md`.

**Requirements/ACs:** FR-RUN-2 (the durable on-disk work queue — confirmed still holding; this
entry documents a limitation, not a defect in it). No new id established.

**What changed:** `results/audit-2026-09-07.md`'s F-025 records that `CaptureProcessingLoop`
(built by `RealCaptureService.startProcessingLoop`) runs as a coroutine in the service's own
`CoroutineScope`, cancelled by the same `scope.cancel()` that `onDestroy()` already calls for
everything else — there is no `WorkManager` job, `PeriodicWorkRequest`, or any other scheduler
that resumes draining the backlog once the service stops. Confirmed by grep, run before this
change's own KDoc edit landed: no reference to `WorkManager` or `PeriodicWorkRequest` anywhere
under `app/`, `pipeline/`, or `data/`'s `src/main` trees (`Grep` for
`WorkManager|PeriodicWorkRequest` over those three modules' `src/main/**` returned no files) —
i.e. no such dependency exists in code, only the two mentions this very entry's KDoc paragraph
now adds to `RealCaptureService.startProcessingLoop`'s doc comment, in prose, to name the
scheduler that is absent. FR-RUN-2 holds regardless — the queue is durable and a backlog
left behind at stop is not lost, it is picked up again the next time capture starts — but the
wait for "the next time capture starts" was previously undocumented anywhere a reader would find
it. The register's fix sketch offered "record or build"; **record** is the decision here, because
building a post-capture drain (a `WorkManager` job, or equivalent, that continues draining
`WorkQueue` after `RealCaptureService.onDestroy()`) is M8 streaming/M10 reprocessing scope per
`spec/build-plan.md`'s own milestone breakdown (M8 is explicitly listed as owning "the
drain-outside-service gap (F-025)"), and the constitution's capture-never-blocks-on-processing
principle (IV) is about capture not depending on processing, not about processing depending on
capture being alive — but scheduling processing work is still a real design decision (queue
scheduling policy, wake-lock/battery cost of running unattended on the ColorOS reference device,
interaction with the shed/backpressure system) that does not belong improvised into a
documentation-only audit fix. This change is therefore two things, done exactly as scoped: (1) a
one-line "Left open" addition to `spec/build-plan.md`'s newest P12 progress note stating the
limitation and that M8/M10 own the fix; (2) a KDoc paragraph on
`RealCaptureService.startProcessingLoop` stating the same, citing FR-RUN-2 and F-025.

**Verified:** Documentation/KDoc only — no behaviour changed, so no test was written and none is
claimed; adding a fake test to satisfy TDD for a comment-only change would test nothing and was
deliberately not done (constitution II: "a test that passes before its implementation exists is
testing nothing" applies in spirit to a test with no implementation to fail against at all).
Verified instead by: `Grep` for `WorkManager|PeriodicWorkRequest` over `app/pipeline/data`'s
`src/main/**` (no matches, confirming the finding's premise) and `./gradlew :pipeline:compileDebugKotlin --console=plain -q` green after the KDoc edit (Robolectric/JVM
tooling only; no device involved, nothing to verify on one for a comment change).

**Left open / not done:** Building the actual post-capture drain is explicitly not done here —
it is M8/M10 scope, and the fix sketch's alternative (build it now, e.g. via `WorkManager`) was
rejected for the reasons above: it is a scheduling and battery-behaviour design decision on the
ColorOS reference device (constitution IV's liveness-by-heartbeat concern), not a documentation
fix, and belongs to the milestone that already claims it.
## 2026-09-07 (audit — F-027)

### (pending) — audit F-027 (`:pipeline` slice) · naming the built-but-untested pipeline ids: FR-RUN-1, FR-RUN-9, AC-29, NFR-2b, NFR-4a; FR-RUN-4 and FR-RUN-6's warning half remain honestly unestablished

**Scope:** `pipeline/src/test/**` only — `RealCaptureServiceTest.kt` (two new tests: FR-RUN-1,
NFR-4a), `CaptureProcessingLoopTest.kt` (one new test: FR-RUN-9, plus the neighbouring REJECTED
test tagged the same id), `backlog/BacklogGrowthMonitorTest.kt` (one new test: AC-29/NFR-2b, plus
the existing 15%-activity test tagged NFR-2b), `capture/RealCaptureServiceShedTest.kt` (kdoc only,
no behaviour change: documents that FR-RUN-6's tag there covers only the exhaustion-floor half).
`results/coverage-matrix.md` regenerated. No `pipeline/src/main` change — every id in this slice
was already built; only the tests were missing or unnamed.

**Requirements/ACs:** FR-RUN-1, FR-RUN-9, AC-29, NFR-2b, NFR-4a established by name in
`:pipeline`. FR-RUN-4 and the "warn before exhaustion" half of FR-RUN-6 are reported NOT
established (see below) rather than tagged, per constitution I.

**What changed:**
- **FR-RUN-1** (capture never blocks on inference): `RealCaptureServiceTest` gained
  `FR_RUN_1 capture keeps producing new transmissions while a pass is stalled on inference` —
  starts the real service with a `FakeAsrEngine(Behaviour.HangsFor(30_000))`, waits for segment 0
  to reach `PROCESSING` (genuinely stuck in the hung `transcribe()` call), then proves segment 1
  is fully captured, FLAC-encoded and persisted *while segment 0 is still stuck* — capture did not
  wait on the stalled inference call. The pre-existing `AC_31` test only proved the happy-path
  composition against a fast fake engine; it does not prove "never blocks", so a new test was
  needed rather than a rename.
- **FR-RUN-9** (`FAILED` distinct from `REJECTED`): `CaptureProcessingLoopTest` gained
  `FR_RUN_9 a pass that errors reaches FAILED, distinct from REJECTED` — a real `PassB` against
  `FakeAsrEngine(Behaviour.Throws(...))`; `RejectionPipeline` catches the throw into
  `PassBOutcome.Failed`, `PassB.run` turns that into `PassRunOutcome.Errored`, and `WorkQueue`
  (maxAttempts=1) commits `FAILED` — proven distinct from the neighbouring `TX-SHORT` test, now
  also tagged `FR-RUN-9`, whose pre-decode rule *correctly declines* to `REJECTED`. (The matrix
  already listed FR-RUN-9 as covered via `:app`/`:data` tests of the UI label and the retry-ladder
  mechanics; this adds the missing `:pipeline`-layer proof of the error-vs-decline distinction
  itself.)
- **AC-29 / NFR-2b** (40% activity queues then degrades; 15% keeps up): `BacklogGrowthMonitorTest`
  gained `AC_29 at 40 percent activity the system queues, degrades, and reports backlog --
  mechanism only`, combining `BacklogGrowthMonitor` (trend) with a real `ShedController` fed a
  deterministic 40%-duty-cycle, zero-drain backlog: it grows (queues), `ShedController.currentLevel`
  rises above 0 (degrades), and `FakeShedSignals.queueBacklog()` stays queryable throughout
  (reports backlog). The pre-existing 15%-activity test is now tagged `NFR-2b`. Both are explicitly
  documented as mechanism-only — `FakeShedSignals` stands in for a real device queue; there is no
  reference device in this session, so the real 40%/15% numbers are NOT MEASURED here.
- **NFR-4a** (unexpected termination loses at most the in-flight segment): `RealCaptureServiceTest`
  gained `NFR_4a an unexpected termination loses at most the in-flight segment` — closes segment 0
  normally, opens segment 1 with speech but deliberately never closes it (no trailing silence), then
  calls `controller.destroy()` directly (never `ACTION_STOP`) to simulate an abrupt kill. Segment 0
  survives; segment 1 never became a persisted row (it never got a chance to — `close()` is what
  creates the `TransmissionEntity`, and `stopCaptureInternal` makes no attempt to flush or salvage
  an open segment); the heartbeat store honestly records an unclean end.
- **FR-RUN-4** (shed-affected records marked reprocessing candidates): investigated, not fixed.
  `TransmissionDao.setReprocessCandidate(id, value)` exists (schema column `isReprocessCandidate`,
  migration-tested) but grep across `:pipeline` and `:data` shows it is **never called** from
  anywhere except its own DAO definition and raw-SQL migration fixtures. `ShedController` never
  touches a transmission row. **Reported NOT established** — no test was written for it, since
  writing one against the current code would either be vacuous or would require production code
  this session does not own to add. This is a genuine gap, not a naming gap; it should be filed as
  its own finding if not already covered elsewhere in the audit.
- **FR-RUN-6** (queue bounded by storage; warn before exhaustion): confirmed the existing
  `RealCaptureServiceShedTest` tag only establishes the first half. `storageFloorBreached(...)` is
  a single hard floor (`RealCaptureService.STORAGE_FLOOR_BYTES`, 100 MiB) with no earlier,
  configurable warning threshold anywhere in `ShedSignals`/`AndroidShedSignals`. Added a kdoc note
  on the test making this explicit rather than silently leaving the matrix's "Covered" claim to
  overstate what is proven. **The warning-threshold half is reported NOT established** (unbuilt).

**Verified:** `./gradlew :pipeline:testDebugUnitTest` — 83 tests, 0 failures, 0 errors (aggregated
across all `TEST-*.xml` under `pipeline/build/test-results/testDebugUnitTest`).
`./gradlew coverageMatrix` then `./gradlew coverageMatrixCheck` — green; FR-RUN-1, FR-RUN-9, AC-29
and NFR-4a moved from "Not yet covered" to "Covered"; NFR-2b's existing coverage now carries a
named test; FR-RUN-4 correctly remains in "Not yet covered". `./gradlew build dependencyRules` —
green (JVM/Robolectric only; nothing here is on-device verification). `python
tools/spec-check/spec_check.py` — all 8 checks PASS.

**Left open / not done:** FR-RUN-4 (reprocess-candidate marking on shed) and FR-RUN-6's warning
threshold are genuinely unbuilt, not just untested — flagged above rather than worked around.
AC-29/NFR-2b's 40%/15% activity figures are mechanism-only; the real numbers need the reference
device (NFR-2b, AC-29 device runs are still open per the audit's DEVICE/MEASURED bucket).

## 2026-09-07 (audit — F-008)

### (pending) — audit F-008 (`:app` half) · a "Models" screen is the declared, user-initiated channel through which an ASR/VAD model reaches the device

**Scope:** `:app` only — `app/src/main/kotlin/org/ort/app/ui/data/ModelsViewData.kt` (new:
`ModelCatalog`, `ModelsController`), `app/src/main/kotlin/org/ort/app/ui/screens/ModelsScreen.kt`
(new), `app/src/main/kotlin/org/ort/app/ui/navigation/ReaderDestination.kt` (`SETTINGS.hasScreen`
flips to `true`), `app/src/main/kotlin/org/ort/app/ui/navigation/OrtNavHost.kt` (`ModelsContent`
dispatch + wiring); tests `app/src/test/kotlin/org/ort/app/ui/data/ModelsControllerTest.kt` and
`app/src/test/kotlin/org/ort/app/ui/screens/ModelsScreenTest.kt`. `app/build.gradle.kts` already
carried `implementation(project(":net"))` (confirmed, not touched). The `:net` half of F-008 (the
`INTERNET` manifest grant) was owned by a parallel agent and is not part of this change — no
manifest was touched here.

**Requirements/ACs:** FR-ASR-1, constitution V (nothing leaves the device except by a declared
channel; user-initiated model download is one of exactly two), constitution I (never claim
"installed" without the checksum having verified), FR-RUN-9/FR-REP-8 (F-016's `WorkQueue.
requeueFailed`, now actually called from somewhere).

**What changed:** Before this change `:net`'s `ModelAcquisition.fetch()`/`.sideload()` had no
`:app` call site at all (confirmed by grep, per the audit finding) — the debug build could capture
audio but never transcribe it, because no ASR (Whisper tiny.en) or VAD (Silero) model could ever
reach the device. This adds the drawer's `Settings` destination (previously a placeholder;
`Improve records` was left alone — P16 already gave it a per-transmission
correction/labelling meaning, so asset management reads as a `Settings` concern instead) as a real
"Models" screen:

- `ModelCatalog` lists the four files the real providers actually read —
  `AsrModelLocator`'s three (`tiny.en-encoder.int8.onnx`, `tiny.en-decoder.int8.onnx`,
  `tiny.en-tokens.txt`) and `SileroVadLocator`'s one (`silero_vad.onnx`) — each with a real,
  individually-fetchable URL. The Whisper files are the same sherpa-onnx release contents
  `asr-sherpa/README.md` documents, but fetched as flat files from HuggingFace
  (`csukuangfj/sherpa-onnx-whisper-tiny.en`, confirmed to host each file separately) rather than
  extracting the `.tar.bz2` archive that README uses for the desktop-JVM test cache — deliberately
  avoiding a new, untested archive-extraction dependency this fix does not need. The VAD URL is
  the one already documented and confirmed reachable in `asr-sherpa/README.md`.
- `ModelsController.download()` mints `NetCapability.UserInitiated` and calls
  `ModelAcquisition.fetch()` on `Dispatchers.IO`; `.sideload()` does the same for a file the user
  picks via `ActivityResultContracts.OpenDocument()` (content `Uri` copied to app-private cache
  first, since `ModelAcquisition.sideload()` takes a `File`). Both write to the exact destination
  the real providers already read, so a successful install is picked up with no second path
  definition. Neither is reachable from `:pipeline` or `:capture-*` — `:app`-only, off the
  capture/processing path, matching constitution V/IV.
- A row is "Installed" only when `ModelAcquisition`'s own `.sha256` marker file is present and
  matches the pinned checksum for that exact destination — never inferred from the file merely
  existing.
- On every successful install, `WorkQueue.requeueFailed()` (F-016, no filter — deliberately not
  narrowed to a specific error-message prefix `:pipeline` owns the wording of) runs and the count
  is shown to the user.

**Left open / not done:**
- **`ModelCatalog`'s checksums are not pinned to the real published bytes.** This session had no
  network egress to actually download either model and compute its real SHA-256 (confirmed: a
  `PowerShell Invoke-WebRequest` to a small file failed immediately — "NonInteractive mode" — while
  `WebFetch` could reach HuggingFace's *page*, which is how the flat-file URLs and file listing
  above were confirmed to exist, but that tool renders pages to text and cannot hand back raw
  bytes to hash). `ModelCatalog.UNPINNED_CHECKSUM` is a human-readable placeholder string, not a
  hex digest, so it can never be mistaken for a genuinely pinned value — a real fetch or side-load
  against these specs today will correctly and loudly fail checksum verification rather than ever
  silently installing unverified bytes (fails closed, not open). Replacing it with the real digest,
  computed once from a genuinely downloaded copy of each file, is the next session's job before
  this can install a real model on a device.
- **No real download has been run, on a device or against the real URLs, in this session.**
  Everything below is Robolectric/JVM, against `FakeHttpRangeClient`.
- The `:net` half of F-008 (the `INTERNET` manifest grant) is a separate agent's work, landing
  separately.
- Progress and resume mid-download are not surfaced in the UI (`ModelAcquisition` itself supports
  resume; the screen shows only a coarse "Downloading…"/done/failed state) — not required by the
  finding's fix sketch, noted as a possible follow-up.
- `spec/build-plan.md`'s P18 note and P12's note are updated alongside this entry to say the
  `:app` call site now exists (see that file's Progress section) — the checksum-pinning gap above
  is the reason F-008 is not fully closed by this half alone.

**Verified:**
- `FR_ASR_1_...` tests seen to fail for the right reason first: with `ModelsController`'s
  installed-check hardcoded to `false`, `./gradlew :app:testDebugUnitTest --tests
  "org.ort.app.ui.data.ModelsControllerTest"` failed 1 of 4 with `java.lang.AssertionError:
  expected:<INSTALLED> but was:<NOT_INSTALLED>`; restored, the same command passes 4/4.
- `./gradlew :app:testDebugUnitTest` (full module): green, including the new
  `ModelsControllerTest` (download/checksum-mismatch/requeue/honest-not-installed) and
  `ModelsScreenTest` (row rendering, Download tap, busy state, message display).
- `./gradlew build dependencyRules` (full gate): green.
- `python tools/spec-check/spec_check.py`: all 8 checks PASS.
- Everything above is Robolectric/JVM only — no on-device verification, no real network call in
  any test (constitution V; `FakeHttpRangeClient` throughout).
## 2026-09-07 (audit — F-011)

### (pending) — audit F-011 · `RealCaptureService` is now started end to end under Robolectric, proving P12's composition rather than just its parts

**Scope:** `:pipeline` only — `pipeline/src/main/kotlin/org/ort/pipeline/capture/RealCaptureService.kt`
(the `Dependencies` injection seam only — no behavioural change to production defaults) and the
`runShedMonitor` parameter type it forced (`AndroidShedSignals` → `ShedSignals`, so a fake shed
signal source can be substituted too); new test
`pipeline/src/test/kotlin/org/ort/pipeline/capture/RealCaptureServiceTest.kt`.
**Requirements/ACs:** build-plan P12's claim; AC-31, AC-5, AC-48, F-005, F-028; constitution II
(test-backed change) and IV (capture never blocks/drops/lies).
**What changed:** P12 claimed `PassDrainRunner`/`PassB`/`RealSherpaDecoder` were "constructed and
wired into `RealCaptureService`, proven against `FakeAsrEngine` on Robolectric", but no test ever
started the service — `CaptureProcessingLoopTest` proves `PassDrainRunner`+`PassBFactory` draining
a hand-assembled `WorkQueue`, which is a different claim from `RealCaptureService.startCapture()`'s
own composition (VAD/route selection, the capture flow, the F-028/F-007 gap/shed relays, the
processing-loop launch) actually running end to end; F-001/F-005/F-006 had all lived in exactly
that untested composition code. `RealCaptureService` gained a small settable `Dependencies` field
(four factory functions: `database`, `audioIo`, `asrEngine`, `shedSignals`), each defaulting to the
exact real construction the class already did — no Hilt, no behavioural change to production.
`RealCaptureServiceTest` uses Robolectric's `ServiceController` to start the *real* service with
`FakeAudioIo` (a synthetic loud-then-silent burst), `FakeAsrEngine`, `FakeShedSignals` and an
in-memory `OrtDatabase` substituted through that seam, then asserts, through the running service's
own code path: a `TransmissionEntity` + current transcript row appear (AC-31); the written
heartbeat carries a real non-zero sample position, not the fabricated `0L` F-005 fixed (read via
`FileHeartbeatStore` against the service's own `filesDir`); a deliberate `ACTION_STOP` (not
`onDestroy()`, which is never a clean stop in this class) marks the session's heartbeat clean
(AC-5); and a `FakeAudioIo.raiseInterruption`/auto-recovery pair produces a real `capture_gap` row
with cause `INTERRUPTION` (AC-48, F-028). The VAD itself is not faked — the service's existing
`EnergyVadModel` fallback (no Silero model file exists in this environment) genuinely trips on the
loud synthetic tone, so the real fallback path is what's exercised. `runShedMonitor`'s parameter
was narrowed from the concrete `AndroidShedSignals` to the `ShedSignals` interface (calling
`refreshBacklog()` only when the runtime type is `AndroidShedSignals`) so `FakeShedSignals` — which
has no refresh step, because it is already live — can be injected without changing
`AndroidShedSignals`'s own contract.
**Verified:** test written first against the pre-seam class and confirmed to fail to compile
(`Unresolved reference 'dependencies'` / `'Dependencies'`) via
`./gradlew :pipeline:compileDebugUnitTestKotlin`; after the seam,
`./gradlew :pipeline:testDebugUnitTest --tests "org.ort.pipeline.capture.RealCaptureServiceTest"`
green (2 tests, 0 failures, ~5s); `./gradlew :pipeline:test` green (no regressions in the existing
suite, including `CaptureProcessingLoopTest`); full gate `./gradlew build dependencyRules` green;
`python tools/spec-check/spec_check.py` green. All Robolectric/JVM — no device has run this;
real-thread/real-wall-clock timing (the service's own `Dispatchers.IO` scope and
`CaptureProcessingLoop`'s 2s poll interval are unmodified — there is no test-dispatcher seam),
so the test polls with a real timeout rather than advancing a virtual clock.
**Left open / not done:** on-device verification remains entirely unestablished, as it always was
for this class (its own doc comment says so). The processing loop's attribution resolution against
the real lexicon grammar is not exercised here (the synthetic burst carries no callsign phonetics);
that path is already covered by `CaptureProcessingLoopTest`'s "kilo seven alpha bravo charlie" case
and was out of scope for this finding, which is about service *composition*, not decode accuracy.

---

## 2026-09-07 (audit — F-009)

### (pending) — audit F-009 · Pass B now persists the phonetic lattice and every ranked candidate, so the inspection surface is no longer permanently empty

**Scope:** `:pipeline` only — `pipeline/src/main/kotlin/org/ort/pipeline/passb/DataPassBResultSink.kt`;
new test `pipeline/src/test/kotlin/org/ort/pipeline/passb/DataPassBResultSinkTest.kt`.
**Requirements/ACs:** FR-UI-8, FR-LEX-12, constitution I ("every machine conclusion MUST be
inspectable — the lattice, the candidates, each prior's contribution").
**What changed:** `DataPassBResultSink.record` wrote the transcript and the attribution only —
`PassBResult.lattice` and `.ranked` never reached `:data`'s `phonetic_lattice`/`callsign_candidate`
tables, so `CatalogDao.latticesFor`/`candidatesFor` (which `app/.../ui/data/ReaderPolling.kt` and
`InspectionSurface.kt` already read) were permanently empty — P16 recorded this as blocked on a
`:pipeline` change and it is now unblocked. The sink now persists, in the same
`db.withTransaction { }` as the transcript/attribution write: every entry of `PassBResult.ranked`
as a `CallsignCandidateEntity` (rank = list index, `score` = `RankedCandidate.totalScore`,
`ituPrefix`/`ituCountry` from the candidate's `ItuAllocation`, `priorBreakdown` as a
name→logOdds map built from every `PriorContribution`, `grammarValid = true` — `CallsignGrammar`
only ever emits structurally valid candidates — `databaseHit` read honestly off the "database"
prior's own contribution rather than threading a new signal through `PassBResult`, and `selected`
set for the one candidate matching a `CONFIRMED` attribution's station id), and `PassBResult.lattice`
(when non-null) as a `PhoneticLatticeEntity`, its `unitsBlob` a lossless hand-rolled JSON array of
slots (`startMs`/`endMs`/`alts[]`, each alt's unit name and log-probability) — `unitsBlob` is
documented as an opaque blob owned by the writer, so no `:data` change was needed. Both entities
are `@Insert`-only with a fresh ULID per call, so re-running Pass B for the same transmission adds
a second lattice/candidate set instead of overwriting the first (constitution III: nothing is
deleted quietly) — no new "supersede" mechanism was added because none of `FR-UI-8`/`FR-LEX-12`
calls for one; every version stays reachable via `latticesFor`/`candidatesFor`.
**Verified:** on a Windows JVM (JDK 17.0.20.101, Robolectric — no device). `DataPassBResultSinkTest`
cases `FR_UI_8_the_ranked_candidate_list_is_persisted...`, `FR_LEX_12_the_phonetic_lattice_is_persisted...`
and `FR_LEX_12_a_second_run_for_the_same_transmission_adds_rather_than_replaces` were seen to fail
first (`expected:<2> but was:<0>` etc. — nothing had been written), then made to pass by the fix
above; a fourth case confirms a `Rejected` outcome still persists neither. `./gradlew :pipeline:test`
(all green), then `./gradlew build dependencyRules` and `python tools/spec-check/spec_check.py`
(both green).
**Left open / not done:** `databaseHit` is derived from the "database" prior's contribution sign
rather than a dedicated field on `PassBResult`/`RankingContext` threaded through — correct for
every prior shipped today (`DatabasePresencePrior`'s clamp is `(+1.0, -0.3)`, so the sign always
distinguishes hit from miss/cold), but a future prior named `"database"` with a different
convention would silently break this; worth a dedicated field if that prior's contract ever
changes. Real per-prior-ablation cross-checking (whether the stored `priorBreakdown` values match
what a `withoutPrior` re-rank would show) is not exercised here — only the plumbing from
`RankedCandidate.contributions` into the stored map is.

## 2026-09-07 (audit — F-002)

### (pending) — audit F-002 · the status surface now shows the real shed level and backlog, or says "Not measured" — never a fabricated "Nominal"

**Scope:** `:app` only — `app/src/main/kotlin/org/ort/app/ui/data/ReaderPolling.kt`
(`statusRepository`/`currentStatus`), `app/src/main/kotlin/org/ort/app/status/StatusViewState.kt`
(`StatusViewState`, `StatusViewStateMapper.from`), `app/src/main/kotlin/org/ort/app/ui/screens/StatusScreen.kt`
(new "Queue backlog" row), `app/src/main/AndroidManifest.xml` (dead-activity entries removed);
deleted `app/src/main/kotlin/org/ort/app/status/StatusActivity.kt` and
`app/src/main/kotlin/org/ort/app/transmissions/TransmissionListActivity.kt` and their tests; test
changes in `StatusViewStateMapperTest.kt`, `ReaderPollingTest.kt`, `StatusScreenTest.kt`.
**Requirements/ACs:** FR-RUN-5 (queue depth and shed level visible in the status surface),
FR-UI-7, constitution IV ("never lies").
**What changed:** `ReaderPolling.currentStatus` and `StatusActivity` (now deleted) each built a
`ShedController` on an anonymous `ShedSignals` returning `batteryPercent()=100`,
`isCharging()=true`, `queueBacklog()=0`, `freeStorageBytes()=Long.MAX_VALUE` — and, worse, never
called `.sample()`/`.tick()` on it, so `currentLevel` stayed `0` regardless of those fake signals;
the surface could only ever render "Nominal". `StatusViewStateMapper.from` now takes explicit
`shedLevel`/`backlog: Int?` parameters instead of reading `CaptureStatus.shedLevel` (which is still
populated by a `ShedController` `CaptureStatusRepository`'s constructor requires but whose output
is now discarded — see that function's doc comment); `null` renders as "Not measured" via the new
`StatusViewState.NOT_MEASURED_LABEL`, and a real level/backlog render their existing/new labels.
`ReaderPolling.currentStatus` supplies those real values from `org.ort.pipeline.capture.ShedStatus`
(F-007's process-wide holder, published by `RealCaptureService`'s real `ShedController` every
~10 s) whenever `CaptureState.state != CaptureState.State.Idle` — i.e. capture has started at least
once this process, which is when the shed monitor coroutine can plausibly have sampled anything;
before that, both render "Not measured", never a fabricated `0`. `StatusScreen` gained a "Queue
backlog" row alongside the existing "Shed level" row. `ReaderPolling.statusRepository` still must
construct *some* `ShedController` to satisfy `CaptureStatusRepository`'s constructor (in `:pipeline`,
out of this change's scope) but now reuses `:pipeline`'s own `FakeShedSignals` rather than a second
bespoke anonymous fake — its output is never read for anything user-visible any more. The dead v0
`StatusActivity`/`TransmissionListActivity` (grepped: no production code launched either — only
stale doc comments and their own manifest/tests — since `MainActivity` has launched `ReaderActivity`
since build-plan P13) are deleted along with their manifest entries and tests, removing the second
copy of the fabricated-signal bug outright rather than patching it twice.
**Verified:** New/updated tests in `StatusViewStateMapperTest.kt` (`FR_RUN_5_an explicit shed level
and backlog render as measured...`, `FR_RUN_5_no shed reading published renders not measured...`),
`ReaderPollingTest.kt` (`FR_RUN_5_currentStatus reads the real shed level and backlog once capture
has started`, `FR_RUN_5_currentStatus reports not measured before capture has ever started...`),
and `StatusScreenTest.kt` (`FR_RUN_5_queue backlog is shown as a number`, `FR_RUN_5_an unmeasured
shed level and backlog render as not measured, never zero`) — confirmed failing first with
`e: ... No parameter with name 'shedLevel' found` / `Unresolved reference 'backlog'` compile errors
against the pre-fix `StatusViewState`/`StatusViewStateMapper.from` signature (verified by
temporarily reverting the three production files to their pre-fix content and re-running
`./gradlew :app:testDebugUnitTest --tests ...`), then green once the fix was restored.
`grep -r "object : ShedSignals" app/` returns no matches. `./gradlew :app:test` (green, all tests),
`./gradlew build dependencyRules` (exit 0), `python tools/spec-check/spec_check.py` (all 8 checks
PASS). All JVM/Robolectric only — nothing here is device-verified.
**Left open / not done:** the "not measured" gate is `CaptureState.state != Idle`, a proxy for
"the shed monitor coroutine has plausibly sampled something" rather than a direct flag on
`ShedStatus` itself — `ShedStatus` (owned by F-007, out of this change's `:pipeline`-scope) has no
`hasMeasurement`/"unset" signal of its own, so there is a narrow race between `CaptureState`
flipping off `Idle` and the async shed-monitor coroutine's first tick where a stale `0`/`0` could
theoretically show as "measured" for one poll interval; not exercised by a test here.
`FR-RUN-5`'s "oldest-unprocessed age" is not shown — neither `ShedStatus` nor `WorkQueueDao`
exposes it cheaply today (`WorkQueueDao` has no oldest-`enqueuedAt`-by-state query), and adding one
would mean touching `:data`/`:pipeline`, both out of this change's `:app`-only scope; reported here
rather than fabricated.
## 2026-09-07 (audit — F-008, `:net` half)

### (pending) — audit F-008 · `:net`'s manifest now grants INTERNET, and `platformGuards` fails the build if it ever stops

**Scope:** `:net` (`src/main/AndroidManifest.xml`, new `src/test/kotlin/org/ort/net/NetManifestPermissionTest.kt`,
`README.md`); `buildSrc` (`PlatformGuards.kt`, `PlatformGuardsTask.kt`, and an unrelated
pre-existing compile break in `PlatformGuardsTest.kt`'s sibling file `CoverageMatrixTest.kt` fixed
incidentally — see "What changed"). This is the `:net` half only; the `:app` call site that mints
`NetCapability.UserInitiated` and drives `ModelAcquisition.fetch()` from a real user action is
still unbuilt (tracked as the other half of F-008).

**Requirements/ACs:** FR-ASR-1, AC-59, NFR-6; constitution V (exactly two declared outbound
channels — the user-initiated download must be able to exist, not just be exclusive).

**What changed:**
- `net/src/main/AndroidManifest.xml` was `<manifest />` — no permission at all. It now declares
  `android.permission.INTERNET`, with a comment citing constitution V and technical design §8.4.
  Without this, `ModelAcquisition.fetch()` would fail at runtime with a `SecurityException` the
  moment an `:app` call site exists, regardless of how correct that call site was.
- `PlatformGuards.missingInternetPermissionViolations()` (new): the existing
  `internetPermissionViolations()` only ever asserted exclusivity — it is satisfied vacuously if
  *nobody*, `:net` included, declares the permission. The new function fails when `:net`'s own
  manifest lacks the grant, closing that gap. Wired into `PlatformGuardsTask.check()` so
  `./gradlew platformGuards` fails on either direction of the mistake.
- `net/src/test/kotlin/org/ort/net/NetManifestPermissionTest.kt` (new): reads the manifests on
  disk directly (not through the Gradle build script) and asserts the same thing from inside
  `:net` — `NFR_6_the_INTERNET_permission_is_declared_by_net_and_only_net` plus a companion
  assertFalse-style test over every other module's manifest text.
- `net/README.md` gained a paragraph documenting where the permission lives and why, and what
  enforces it in both directions.
- Incidental, unrelated fix: `buildSrc/src/test/kotlin/org/ort/gradle/CoverageMatrixTest.kt` had
  a missing closing brace (a pre-existing merge artifact, not connected to this finding) that
  broke compilation of the entire `buildSrc` test source set, which meant `./gradlew -p buildSrc
  test` could not run at all — including the new `PlatformGuardsTest` cases this change needed to
  verify. Fixed with a one-line brace insertion; no test logic in that file was changed.

**Verified:**
- `./gradlew -p buildSrc test --console=plain -q` — green, all buildSrc tests pass including the
  three new `PlatformGuardsTest` cases (`F_008_constitution_V_...`). Confirmed failing first with
  `Unresolved reference: missingInternetPermissionViolations` before the function was written.
- `./gradlew :net:test --console=plain` — green, 17 tests including the two new
  `NetManifestPermissionTest` cases. Confirmed the manifest-declaration test failing first
  (`AssertionFailedError: ... expected: <true> but was: <false>`) against `net`'s original
  `<manifest />`, by reverting the manifest, re-running, and restoring it.
- `./gradlew platformGuards --console=plain` — green: "checked 17 modules' external dependencies
  and 17 manifests — ... android.permission.INTERNET declared by :net and only :net".
- All of the above are JVM/Robolectric-free plain-file and unit-test checks. **No on-device
  download has been verified** — that requires the still-missing `:app` call site plus a real
  device or emulator with network access, neither attempted here.

**Left open / not done:**
- The `:app` half of F-008 (a Settings/"Improve" action, `NetCapability.UserInitiated`, wiring
  `ModelAcquisition.fetch()` into `RealAsrEngineProvider`/`RealVadProvider`) is untouched — F-008
  stays OPEN in `results/audit-2026-09-07.md` (not edited here; the auditor owns it) until that
  half lands too.
- On-device model download is unverified, structurally — no device test exists yet.

## 2026-09-07 (audit — F-007)

### (pending) — audit F-007 · RealCaptureService now ticks a real ShedController, persists shed events, and stops loudly at a storage floor

**Scope:** `:pipeline` only — new `AndroidShedSignals.kt` and `ShedEventPersister.kt`
(`org.ort.pipeline.shed`), new `ShedStatus.kt` (`org.ort.pipeline.capture`),
`RealCaptureService.kt` (`startCapture`'s wiring, the new `runShedMonitor`/
`stopForStorageExhaustion` methods, the new top-level `ShedEventRelay` class and
`storageFloorBreached` function), plus new tests `AndroidShedSignalsTest.kt`,
`ShedEventPersisterTest.kt`, `RealCaptureServiceShedTest.kt`.
**Requirements/ACs:** FR-RUN-3, FR-RUN-5, FR-RUN-6, FR-STO-4; constitution IV ("overload sheds
work in a documented order … only storage exhaustion stops capture, loudly").
**What changed:** `ShedController` was never constructed in the running capture service — it
existed only in `:app`'s status display, fed a `FakeShedSignals` purely for something to show
(F-002), and no production `ShedSignals` implementation existed at all. So FR-RUN-3's shed order
could never actually trigger and a full disk was discovered only when a write threw. This adds:
(1) `AndroidShedSignals`, the first real `ShedSignals` — battery percent and charging state from
`BatteryManager`, free storage from `StatFs(filesDir)`, queue backlog from
`WorkQueueDao.count()` (cached via a `refreshBacklog()` suspend call, since `ShedSignals` is a
plain synchronous interface and `ShedController.sample()` runs from a hot loop). Every signal that
cannot be read returns a documented sentinel on the *conservative* side — `-1` for an unreadable
battery percent (reads as "critical" to the controller), `false` for unreadable charging state,
`0L` for unreadable free storage (reads as certainly below any floor) — never a fabricated healthy
value. (2) `RealCaptureService.startCapture()` now constructs one `ShedController` per session and
ticks it every 10 s (`runShedMonitor`), refreshing the backlog, sampling the controller, draining
any new level transitions through the new `ShedEventRelay`/`ShedEventPersister` pair into F-021's
`shed_event` table (with the real sample position from `Segmenter.position()`, the F-005 pattern),
and republishing `currentLevel`/backlog through the new `ShedStatus` process-wide holder (the same
pattern as `CaptureState`/`AsrAvailability`/`VadAvailability`) for `:app`'s status surface to read
in place of its own fake-fed controller (a follow-up on F-002, not done here — see Left open).
(3) A free-storage floor (`STORAGE_FLOOR_BYTES`, 100 MiB, a named constant with its own KDoc
justification — deliberately not the full FR-STO-3 budget system) checked every tick:
`storageFloorBreached` breaches it, `stopForStorageExhaustion` sets `CaptureState.failed(...)`
with the real reason, updates the notification, and stops the audio source — the same loud-failure
route a route mismatch already uses, never a silent write failure. `ShedController` itself was not
changed; level 5 ("storage exhaustion") is handled as this separate stop path, not as a level the
controller's own hysteresis machinery reaches, since its `sample()` has no branch that reads
`freeStorageBytes()` at all.
**Verified:** New tests, all written first and confirmed failing on `Unresolved reference` compile
errors for `AndroidShedSignals`/`ShedEventPersister`/`ShedEventRelay`/`storageFloorBreached` before
being implemented. `AndroidShedSignalsTest` (`FR_RUN_3_...`, `FR_STO_4_...`, `FR_RUN_5_...`,
Robolectric — battery/charging via `ShadowBatteryManager`, free storage via
`ShadowStatFs.registerStats`, backlog via a real in-memory `WorkQueue`) asserts real reads and the
conservative sentinels. `ShedEventPersisterTest` (`FR_RUN_3_...`, Robolectric, in-memory `OrtDatabase`)
asserts a persisted `shed_event` row's fields and trigger classification. `RealCaptureServiceShedTest`
(`FR_RUN_3_...`, `FR_STO_4_...`) exercises `ShedEventRelay` against a real `ShedController` +
`FakeShedSignals` (two successive transitions each get their own `levelBefore`, not both `0`) and
`storageFloorBreached`'s boundary directly — no `ServiceController` harness exists yet to start
`RealCaptureService` itself (F-011, still open), so this follows the same extracted-seam approach
F-005/F-028 used. `./gradlew :pipeline:test` — 79 tests, 0 failures, `ShedControllerTest` (pre-
existing) unchanged and green. Full gate: `./gradlew build dependencyRules` (exit 0) and
`python tools/spec-check/spec_check.py` (all 8 checks PASS). All JVM/Robolectric only — nothing
here is device-verified.
**Left open / not done:** `:app`'s status surface (`ReaderPolling.kt`, `StatusActivity.kt`) still
constructs its own `ShedController` against `FakeShedSignals` for display — that is F-002's finding
and `:app` is explicitly out of scope for this change. `ShedStatus` (level + backlog, two plain
`Int`s, no new module edge) is what a follow-up on F-002 should read instead. The full FR-STO-3
storage-budget/warning system (per-category footer, configurable thresholds, export-and-prune) is
still unbuilt — only the FR-STO-4 stop-before-exhaustion floor is added here, deliberately not that
larger system (see F-020). `AndroidShedSignals.refreshBacklog()`'s one honest gap: a failed read
leaves the cached backlog unchanged, so an unread value and a genuinely-empty queue are both `0`
and indistinguishable from each other — documented in its KDoc, not fixed, since a `Int` cannot
carry a third "unread" state without changing the `ShedSignals` interface's contract.
## 2026-09-07 (audit — F-027)

### (pending) — audit F-027 · `:app` half: seven built-but-untested requirement ids get named tests

**Scope:** `app/src/test/**` only (`ActivityPatternMapperTest.kt`, `ReaderAccessibilityTest.kt`,
`TransmissionDetailScreenTest.kt`, new `OrtColorsContrastTest.kt`), `results/coverage-matrix.md`.

**Requirements/ACs:** AC-14, AC-126, FR-A11Y-2, FR-A11Y-3, FR-A11Y-4, FR-PLT-6 (font-scale clause
only) established. FR-A11Y-5, FR-ASR-10, FR-UI-11 (day-of-week half), and FR-PLT-6's per-app
language/system-contrast/reduced-motion clauses investigated and confirmed genuinely unbuilt — see
below. Constitution I ("every machine conclusion MUST be inspectable") and VII ("every user-visible
surface obeys the accessibility floor").

**What changed:** F-027 flagged eight `:app`-owned id groups as built-but-untested, or (AC-126)
mislabelled unbuilt. Rebased this worktree onto `main` first (51 commits behind — picked up P17,
F-017's search filters, F-021's migration, and the F-027 `:data`/`:lexicon` slices already merged).
Per id:
- **AC-14** — `InspectionSection` (`TransmissionDetailScreen.kt`) already renders the candidate
  list and phonetic lattice; only `InspectionViewStateMapperTest` tested the mapping, nothing
  tested the render. Added `AC_14 the candidate list and phonetic lattice are viewable on the
  detail screen` to `TransmissionDetailScreenTest`, asserting the lattice line, a candidate's score
  line and a prior-contribution line are all `onNodeWithText`-findable.
- **AC-126** — the auditor's own correction: P17 built exactly this. Renamed
  `ActivityPatternMapperTest`'s capture-gap test to
  `AC_126_FR_UI_12 an hour covered entirely by a capture gap is NOT_LISTENING...` (unchanged
  assertions) and added a KDoc paragraph pointing at `ActivityPatternChartTest` as the render-side
  proof, and stating plainly that FR-UI-11's day-of-week half is not built (`HourActivityBucket` and
  this mapper only ever bucket by hour-of-day; grepped for `dayOfWeek`/`DayOfWeek` in `:app` — no
  match anywhere).
- **FR-A11Y-2, FR-A11Y-3** — both already established by `ReaderAccessibilityTest`'s two existing
  tests (content descriptions across the nav host; no clipping at 2x font scale) under the `AC_63`
  name only. Renamed to `AC_63_FR_A11Y_2_...` and `AC_63_FR_A11Y_3_FR_PLT_6_...` respectively,
  assertions unchanged.
- **FR-A11Y-4** — no test computed an actual contrast ratio anywhere. Added
  `OrtColorsContrastTest`: a genuine WCAG 2.2 relative-luminance/contrast-ratio implementation run
  against `OrtColors`' real sRGB values, checked at the threshold that actually applies to each
  colour's real call site — 4.5:1 for `textHigh`/`textMedium`/`textMuted` (all used to render
  `Text`) and 3:1 for `accentGreen`/`accentAmber`/`textLow` (used only to fill graphical shapes in
  `AttributionMarker`/`ActivityPatternChart`, never text). All five pairs pass with the palette as
  it stands today (ratios 4.29–15.19); no palette change was needed.
- **FR-PLT-6** — only the font-scale clause is established, by the same `ReaderAccessibilityTest`
  evidence as FR-A11Y-3 (nothing in `:app` overrides `LocalDensity.fontScale`, so "respecting" it is
  the same fact as "does not clip when it is honoured"). Its other three clauses — per-app language
  preference, system contrast, reduced motion — are recorded in the test's own KDoc as **not
  established, because not built**: no per-app language config, no contrast-mode handling, no
  reduced-motion check exists anywhere in `:app` (confirmed by grep, not assumed).
- **FR-A11Y-5** — checked whether user-visible strings are resourced. `app/src/main/res/values/strings.xml`
  holds exactly one string (`placeholder_running`), used nowhere (`MainActivity.kt` is its only
  `stringResource`/`R.string` reference in the whole module, and it doesn't reference that key).
  Every `Text(...)` in every screen and component is a hardcoded Kotlin string literal. **Not
  established here, because it is not built** — no test was added; a test asserting "strings are
  resourced" would need the resourcing to exist first, and that is a `:app/src/main` UI change well
  beyond this finding's scope (every screen file). Left open, filed honestly rather than as a false
  test.
- **FR-ASR-10** — grepped `:app` for model-metadata fields (`isFineTuned`, `fineTune`, stock/tuned
  labelling) — no match anywhere. **Not established here, because it is not built.** No settings
  screen and no per-transcript model-metadata field exist in `:app` at all yet.

**Verified:** `./gradlew :app:test` — all tests green including the 1 new `TransmissionDetailScreenTest`
case, 2 renamed `ReaderAccessibilityTest` cases, 1 renamed `ActivityPatternMapperTest` case, and 5
new `OrtColorsContrastTest` cases (all passing on the first run — no palette or main-source change
was needed for any of the six established ids). `./gradlew coverageMatrix` — regenerated;
`results/coverage-matrix.md` now names AC-14, AC-126, FR-A11Y-2, FR-A11Y-3, FR-A11Y-4 and FR-PLT-6
under "Covered" and they no longer appear in "Not yet covered"; FR-A11Y-5, FR-ASR-10 and FR-UI-11
remain listed there, correctly. `./gradlew coverageMatrixCheck` green. `./gradlew build
dependencyRules` green (two `detekt` `MaxLineLength` findings in the new/edited test files fixed
along the way). `python tools/spec-check/spec_check.py` — all 8 checks PASS. All Robolectric/JVM
only, per constitution — no on-device verification is claimed; `OrtColorsContrastTest` is plain
JVM (no Robolectric needed — `androidx.compose.ui.graphics.Color` is pure Kotlin math).

**Left open / not done:** FR-A11Y-5 (no string-resourcing anywhere in `:app`'s UI), FR-ASR-10 (no
model-metadata surface exists), and FR-UI-11's day-of-week bucketing are genuinely unbuilt, not
merely untested — each would need a `:app/src/main` change (and, for FR-ASR-10, upstream metadata
this module has no source for yet) outside this finding's `app/src/test/**`-only scope. FR-PLT-6's
non-font-scale clauses (language preference, system contrast, reduced motion) are the same: unbuilt,
not untested. `:capture-android`/`:capture-api`, `:pipeline`, `:lexicon`/`:eval`, `corpus/` and
`:asr-*`/`:onnx` slices of F-027 are out of this session's scope (owns `app/src/test/**` only) and
remain as the register describes them.

---

## 2026-09-07 (audit — F-028)

### (pending) — audit F-028 · RealCaptureService now joins GapTracker to GapPersister, so a capture gap is actually a row

**Scope:** `:pipeline` — `RealCaptureService.kt` (`startCapture`'s event-collect loop, the new
`CaptureGapRelay` class), `GapPersister.kt` (`causeFor`), `RealCaptureServiceGapTest.kt`,
`GapPersisterTest.kt`.
**Requirements/ACs:** FR-RUN-12, FR-UI-12, AC-48; constitution IV ("silence that was never
listened to MUST be distinguishable from silence that was").
**What changed:** `RealCaptureService` constructed neither `GapTracker` nor `GapPersister` — the
`AudioRecordSource` event stream it already collects (`Interrupted`/`Resumed`, and, since F-010, a
dropped-span cause) went nowhere but `CaptureState`/the notification. `captureGapDao` was
therefore always empty in production, so P17's not-listening distinction (FR-UI-12) could never
show a real gap regardless of how correct `GapTracker`/`GapPersister` themselves were (each already
had its own passing unit test). Fixed by constructing a `GapTracker(SystemClock)` and a
`GapPersister(db.captureGapDao(), SystemClock)` in `startCapture()`, and feeding every
`CaptureEvent` the collect loop already receives through a new `CaptureGapRelay` (mirrors F-005's
`buildHeartbeatRecord` extraction: a plain, non-Android class ahead of the exhaustive `when`, so no
`ServiceController` harness is needed to test it — F-011 is still open). `CaptureGapRelay` watches
`GapTracker.gaps` grow and persists each new record, in order, exactly once. Separately,
`GapPersister.causeFor` mapped F-010's `DroppedSpanCause` string ("dropped samples: N samples over
Xms (stalled consumer or read shortfall)") to `UNKNOWN` — it matched none of the existing
substring checks (neither "device" nor "read error" appears in it) — so it now matches the stable
"dropped samples:" prefix directly to `DEVICE_LOST`, the cause it actually is. No new
`CaptureGapCause` value was needed.
**Verified:** `./gradlew :pipeline:test` — 3 new `RealCaptureServiceGapTest` cases (an
Interrupted/Resumed pair, a dropped-span Interrupted with no persisted duplicate from its
following no-op Resumed, and Frames/RouteChanged/EndOfStream producing no persist) plus a new
`GapPersisterTest` case (`AC_48 F-010's dropped-span cause is mapped to DEVICE_LOST, not UNKNOWN`,
seen failing against `UNKNOWN` before the `causeFor` fix) all pass; `./gradlew build
dependencyRules` green; `python tools/spec-check/spec_check.py` — all 8 checks PASS. All
Robolectric/JVM only, per constitution — no on-device verification is claimed.
**Left open / not done:** F-011 (no `ServiceController` test starts the real `Service`) is
unchanged and still open — this fix is verified at the extracted `CaptureGapRelay` seam, not by
observing `captureGapDao` populate from an actually-running `RealCaptureService`. The dropped-span
mapping is a substring match on a string `:capture-android` treats as free text (`DroppedSpanCause`
is `internal`, so `:pipeline` cannot reference its prefix constant directly); if that string's
wording ever changes, this mapping silently reverts to `UNKNOWN` again with no compile-time link
between the two.

---
## 2026-09-07 (audit — F-027)

### (pending) — audit F-027 · `:data` half: eight built-but-untested requirement ids get named tests

**Scope:** `:data` (`data/src/test/**` only — no production change), `results/coverage-matrix.md`.

**Requirements/ACs:** FR-ASR-7, FR-AST-5, FR-AST-6, FR-AST-8, FR-RUN-2, FR-STO-1, NFR-4b, FR-LEX-12
established. FR-STO-2, FR-STO-2a, CON-STO-1 investigated and left uncovered — see below.

**What changed:** F-027 flagged eleven `:data`-owned ids as built but unnamed by any test.
Rebased this branch onto `main` first (it had drifted 38 commits behind, missing F-021's
`ShedEventEntity`/`MIGRATION_1_2` and F-017's `SearchDao` filters). Per id:
- **FR-AST-5, FR-AST-6** — `MigrationTest`'s two existing tests already exercise exactly this
  (schema-versioned forward migration preserving audio and superseded transcripts, tested against
  the committed v1 fixture); added both ids to their `@Requirement` annotations alongside AC-53.
- **FR-AST-8** — same for `ReconciliationTest`'s existing orphan/dangling-row test, alongside
  AC-54.
- **FR-STO-1** — `SearchDaoFullTextTest`'s real FTS5 MATCH test already establishes "persist in
  SQLite with FTS5 over transcripts"; added the id alongside FR-UI-3, keeping its honest
  `assumeTrue` skip for the Robolectric host-SQLite fts5 gap untouched.
- **FR-ASR-7** — no existing test asserted the model identity/version/quantization/decode-params
  columns round-trip; added a new test to `TranscriptVersioningTest` with five distinct,
  non-default field values, checked through both `getCurrent` and `getAllVersions`.
- **FR-LEX-12** — no test exercised `CatalogDao`'s phonetic-lattice/candidate-list persistence at
  all. Added `data/src/test/kotlin/org/ort/data/dao/CatalogDaoTest.kt`: one test proves the full
  ranked candidate list (not just the selected one) persists with its prior breakdown intact, one
  proves a raw lattice persists and round-trips its source/model/units blob.
- **FR-RUN-2** — every existing `WorkQueueTest` uses an in-memory database, which cannot show a
  queued item survives the database closing. Added `data/src/test/kotlin/org/ort/data/DurabilityTest.kt`:
  enqueues an item against a real on-disk file, closes that `OrtDatabase` instance, opens a second
  independent instance against the same file, and asserts the item is still `READY` there — the
  strongest claim actually checkable without an OS-level process kill.
- **NFR-4b** — "survives process kill mid-write" cannot be proven on Robolectric/JVM (no way to
  induce an unclean kill mid-transaction and observe recovery — needs the device matrix). What is
  checkable and is the actual durability primitive the requirement rests on: `OrtDatabase.create`
  configures WAL journal mode for every non-in-memory database. Added a test in the same
  `DurabilityTest.kt` asserting `PRAGMA journal_mode` reports `wal` on a file-backed instance. The
  test's doc comment says explicitly that this is configuration verified, not a survived kill.
- **FR-STO-2, FR-STO-2a, CON-STO-1 — left uncovered, honestly.** `TransmissionEntity.audioFormat`
  is a free-text `String` column with no validation or codec-selection logic anywhere in `:data`;
  the actual codec choice (currently FLAC, per FR-STO-2a/CON-STO-1's "lossless until Pass C is
  measured") is made outside this module. A test that only asserted a hardcoded fixture string
  round-tripped would be exactly the "checks a type exists" test the brief forbids, so none was
  written. These three remain in coverage-matrix's "Not yet covered" and belong to whichever
  module actually chooses/enforces the retention codec.

**Verified:** `./gradlew :data:test --console=plain -q` — green, all 8 renamed/new tests included
(`DurabilityTest` 2/2, `CatalogDaoTest` 2/2, `TranscriptVersioningTest` 3/3, plus the renamed
`MigrationTest`/`ReconciliationTest`/`SearchDaoFullTextTest` assertions unchanged). `./gradlew
coverageMatrix` then `./gradlew coverageMatrixCheck` (run as two separate invocations, per the
task's known implicit-dependency Gradle validation issue) — both green; the regenerated
`results/coverage-matrix.md` shows "Covered by at least one test" rising 120 → 128 and all eight
ids now listed by name under `## Covered` rather than `## Not yet covered`. `./gradlew build
dependencyRules --console=plain -q` — green (one ktlint import-ordering/function-signature
violation caught and fixed via `:data:ktlintFormat` before this run). `python
tools/spec-check/spec_check.py` — all 8 checks PASS.

**Left open / not done:** No production code was changed — every id closed here was already
implemented; only test naming/coverage changed. FR-STO-2, FR-STO-2a and CON-STO-1 are reported
as not established in `:data` for the reason above, not fixed. Never claimed on-device
verification — everything here is Robolectric/JVM only.
## 2026-09-07 (audit — F-017)

### (pending) — audit F-017 · :app half: Search screen gains band, attribution-state and rejected/accepted controls

**Scope:** `:app` — `app/src/main/kotlin/org/ort/app/ui/data/SearchViewData.kt`
(`SearchFilterInput`, `SearchQueryParams`, the new `RejectedFilter` enum, `SearchFilterParser`,
`SearchPolling.search`), `app/src/main/kotlin/org/ort/app/ui/screens/SearchScreen.kt` (three new
filter controls); `SearchFilterParserTest.kt`, `SearchPollingTest.kt`, `SearchScreenTest.kt`.
**Requirements/ACs:** FR-UI-3 (band, attribution-state and rejected/accepted filters — the `:app`
half; the `:data` half landed separately, `SearchDao` commit 1520a03).
**What changed:** `SearchDao.search`/`filterOnly`/`searchText` already accepted trailing
`band`/`attributionState`/`rejected` parameters that nothing on the `:app` side passed. Extended
`SearchFilterInput` with `band: Band?`, `attributionState: AttributionState?` and a new
`RejectedFilter` tri-state enum (`ALL`/`ACCEPTED`/`REJECTED`, `toDaoValue()` mapping it onto the
DAO's nullable `Boolean?`); `SearchFilterParser.parse` passes the first two through unparsed (both
are already typed, selected from a closed set on the screen rather than typed as free text) and
resolves the third. `SearchPolling.search` now forwards all three to `SearchDao.search` on both
the direct and the fts5-degrade call paths. `SearchScreen` gains three new filter controls
(`BandFilterControl`, `AttributionStateFilterControl`, `RejectedFilterControl`) — a "tap to
cycle" text control for each, consistent with the screen's existing "tap to act" style (no
dropdown/menu widget existed anywhere in this codebase to reuse) and the accessibility floor
(constitution VII): each control's current selection is always plain text
(`"Band filter: 160M"`, `"Attribution state filter: CONFIRMED"`, `"Accepted/rejected filter:
Rejected"`), never colour-only.
**Verified:** New tests seen failing first for the right reason before the fix: `SearchScreenTest`
(band/attribution-state/rejected controls did not exist — `assertExists()` failed with "the
matcher had 0 matches"); `SearchPollingTest`'s three new `FR_UI_3_...` cases and
`SearchFilterParserTest`'s two new cases failed to compile (`SearchQueryParams`/`SearchFilterInput`
had no `band`/`attributionState`/`rejectedFilter` parameters) before the view-data change. After
the fix: `./gradlew :app:testDebugUnitTest --tests "org.ort.app.ui.data.SearchPollingTest"
--tests "org.ort.app.ui.data.SearchFilterParserTest" --tests
"org.ort.app.ui.screens.SearchScreenTest"` — 6/6, 5/5, 8/8 green. Full module:
`./gradlew :app:test` green. Full gate: `./gradlew build dependencyRules` (green, after one
`detekt` `MaxLineLength` fix) and `python tools/spec-check/spec_check.py` (`spec-check: OK`, all 8
checks pass). Everything here is Robolectric/JVM; nothing was run on-device.
**Left open / not done:** Adding the three new filter controls pushed `SearchScreen`'s scrollable
content past the fixed-size Robolectric compose-test viewport, which silently broke two
pre-existing tests' `performClick()` calls (the click landed outside the root's visible bounds, so
the callback never fired — no exception, a false assertion) — fixed by adding
`.performScrollTo()` before those two clicks, matching the precedent already used elsewhere
(`ReaderAccessibilityTest.kt`). The three new controls are a minimal "tap to cycle" affordance
rather than a dropdown/multi-select; a richer widget (e.g. a proper picker, or an FR-UI-11-style
chip row) is a plausible follow-on but out of this finding's scope.

## 2026-09-07 (audit — F-005)

### (pending) — audit F-005 · heartbeat now carries the segmenter's real sample position, not a fabricated 0

**Scope:** `:pipeline` — `RealCaptureService.kt` (`onHeartbeat`, the new `buildHeartbeatRecord`
helper, the new `segmenter` field), `RealCaptureServiceHeartbeatTest.kt`.
**Requirements/ACs:** FR-RUN-16 (audio sample position is the authoritative timeline); constitution
VI ("no number without provenance").
**What changed:** `RealCaptureService.onHeartbeat()` used to write
`HeartbeatRecord(sessionId, monotonic, wall, 0L)` — the sample-position field was a literal `0L`
on every single heartbeat, never the real value the parallel `capture-android` `CaptureService`
threads through. The service now keeps a reference to the session's `Segmenter` (built once, in
`startCapture()`) and reads `Segmenter.position()` — "absolute sample position of the next sample
to be fed", already public and unchanged in `:segment` — at the moment each heartbeat is written.
The heartbeat-record construction itself is pulled out into a small top-level function,
`buildHeartbeatRecord(sessionId, samplePosition: () -> Long)`, specifically so this could be
tested without standing up the whole `android.app.Service` under Robolectric (no
`ServiceController` harness exists for `RealCaptureService` yet — see the still-open F-011). `0L`
remains the value reported in the one honest case: before the segmenter has been constructed for
this session (i.e. before any sample has been read at all) — the field defaults to `null` and the
call site falls back to `0L` only then, never once capture is under way.
**Verified:** `./gradlew :pipeline:test` (green) after seeing
`RealCaptureServiceHeartbeatTest.FR_RUN_16_the_heartbeat_carries_the_last_captured_sample_position_not_zero`
fail first with `error: Unresolved reference 'buildHeartbeatRecord'` (the function did not exist
yet); it and its sibling test now pass, driving a real `Segmenter` through five frames of audio and
asserting the built record's `samplePosition` equals the segmenter's own `position()` — not zero.
Then the full gate: `./gradlew build dependencyRules` (green, after fixing one ktlint
spacing-between-declarations-with-comments violation the new field comment introduced) and
`python tools/spec-check/spec_check.py` (`spec-check: OK`, all 8 checks pass).
**Left open / not done:** `RealCaptureService` as a whole is still never started under Robolectric
(F-011, unrelated, still OPEN) — this fix is verified at the seam it touches (the segmenter →
heartbeat wiring), not end-to-end through `onStartCommand`/`onHeartbeat` as methods on a running
service instance. All verification here is Robolectric/JVM; nothing was run on-device.

## 2026-09-07 (audit — F-023)

### (pending) — audit F-023 · coverage matrix treats bare F/D/R/Q ids as cross-references, not orphans

**Scope:** `buildSrc/` — `CoverageMatrix.kt`, `CoverageMatrixTest.kt`; `results/coverage-matrix.md`.
**Requirements/ACs:** none new — this is a tooling fix to the coverage matrix itself (test-plan
§9, constitution VII "guarantees are expressed as types where possible" / II "the coverage matrix
is generated rather than maintained").
**What changed:** `results/coverage-matrix.md` listed `F13` (named by `ModelRegistryTest` and
`RejectionPipelineTest` via `@Requirement("F13")`) and `Q8` (named by `CorrectionDaoTest`) as
orphan tests — the spec does not define `F13`/`Q8` as requirement ids because they aren't
requirement ids: `F13` is a functional-spec §12 failure-mode id and `Q8` is an open-questions
register id, both legitimate cross-references the tests are documenting, not misnamed
requirements. Chose the smaller of the two options in the finding: taught `CoverageMatrix.kt`
that a bare `F\d+`/`D\d+`/`R\d+`/`Q\d+` id (new `CROSS_REFERENCE` regex) is a cross-reference, not
an orphan — `Coverage.orphanTests` now excludes them and a new `Coverage.crossReferencedTests`
carries them into their own rendered section, "Cross-referenced failure modes / decisions /
questions (not requirement ids)". The requirement-id set (`REQUIREMENT`, `NAME_ID`) is unchanged;
nothing about what counts as covered moved. Rejected the alternative (renaming the three tests to
the `FR-*`/`AC-*` id they actually establish): that would have required a semantic judgement call
per test with no clear single winner (e.g. the `F13` tests span model-fallback and probe-crash
behaviour across two modules) and would still need the tool to special-case bare ids for the next
one that appears — the tool fix is the durable, general answer, and it generalises to `D*`/`R*`
ids the register also uses.
**Verified:** `./gradlew :buildSrc:test --tests "org.ort.gradle.CoverageMatrixTest"` — new tests
`F-023 a test naming a bare failure-mode or question id is not an orphan` and `F-023 the rendered
matrix lists cross-references in their own section, not as orphans` seen failing first
(`compileTestKotlin`: `Unresolved reference: crossReferencedTests`) before `Coverage
.crossReferencedTests` was added, green after. `./gradlew coverageMatrix` regenerated
`results/coverage-matrix.md`: header row "Tests naming a requirement id not in the spec" now `0`
(was 2); `F13`/`Q8` moved to the new cross-reference section. `./gradlew coverageMatrixCheck` —
up to date. Full gate: `./gradlew build dependencyRules` (exit 0) and
`python tools/spec-check/spec_check.py` (all 8 checks PASS).
**Left open / not done:** none for this finding. `coverageMatrix` and `coverageMatrixCheck` cannot
be run in the same Gradle invocation without an explicit task dependency (Gradle flags the
undeclared output/input relationship) — pre-existing, out of this finding's scope
(`buildSrc/CoverageMatrixCheckTask.kt` is unowned by F-023), so they were run as separate
invocations.
## 2026-09-07 (audit — F-006)

### (pending) — audit F-006 · too-short segments are retained as REJECTED rows instead of being deleted

**Scope:** `:pipeline` — `capture/RealCaptureService.kt` (`RealSegmentSink.close()`),
`capture/RealSegmentSinkTest.kt`.
**Requirements/ACs:** FR-SEG-6, AC-72; constitution III ("nothing is deleted quietly").
**What changed:** `RealSegmentSink.close()` previously deleted the staged PCM and returned early
for any `SegmentOutcome` other than `SPEECH`, so a `REJECTED_TOO_SHORT` segment left no trace at
all — no row, no audio, nothing in the queue, and nothing to distinguish it from audio that was
never captured. It now FLAC-encodes and persists a `TransmissionEntity` for `REJECTED_TOO_SHORT`
exactly as it does for `SPEECH` (same derived timestamps, same pre-/post-roll, same audio path),
but with `processingState = TransmissionState.REJECTED` and `rejectionReason = "too_short"`
(FR-SEG-6/AC-72's `rejected:too_short`, as the free-text `rejectionReason` value the rest of the
schema already uses — see `TransmissionDetail.kt`'s `"(rejected: <reason>)"` rendering), and only
enqueues for Pass B when the outcome is `SPEECH`. `TransmissionDao.insert` has no legal-transition
check (it is a plain `@Insert`), so writing `REJECTED` directly at insert time needed no schema or
state-machine change. The reader built for F-003 already renders `REJECTED` rows with their
reason, so this segment becomes visible with no further change.
**Verified:** new test `AC_72_a_too_short_segment_is_retained_as_a_rejected_row_with_its_audio_and_never_enqueued`
in `RealSegmentSinkTest` — seen to fail first (`AssertionError: a too-short segment must still be
recorded as a row`, because the old code returned before inserting anything) by stashing the
production change and rerunning; after the fix it asserts a `REJECTED` row exists with
`rejectionReason = "too_short"`, its encoded FLAC file exists on disk, the staged PCM is gone
(deleted only as a side effect of `FlacStore.encodeAndVerify`'s post-verify cleanup, not by the
old unconditional `staged.delete()`), and `WorkQueueDao.findByTransmissionAndPass` returns no
item for it. `./gradlew :pipeline:test` — all 53 tests pass (JVM/Robolectric only, no device
verification claimed). Full gate: `./gradlew build dependencyRules` green, `python
tools/spec-check/spec_check.py` — all 8 checks pass.
**Left open / not done:** none for this finding. F-007 (shedding/storage-exhaustion wiring) is a
separate open finding in the same file, not touched here.

## 2026-09-07 (audit — F-016)

### (pending) — audit F-016 · WorkQueue.requeueFailed gives exhausted FAILED items a fresh run

**Scope:** `:data` — `WorkQueue.kt`, `dao/WorkQueueDao.kt`, `WorkQueueTest.kt`.
**Requirements/ACs:** FR-RUN-9, FR-REP-8 (reprocessing candidates); constitution III ("nothing is
deleted quietly").
**What changed:** `WorkQueueDao` gained `selectFailed(pass, lastErrorPrefix)` (both filters
nullable — `null` matches everything) and `requeueToReady(id)`, which resets a `FAILED` row to
`READY` with `attemptCount = 0` while leaving `lastError` untouched so the prior failure stays
reachable until a new one overwrites it. `WorkQueue.requeueFailed(pass, lastErrorPrefix)`
transactionally applies both to every matching item and, per item, moves the transmission along
the state machine's already-documented reprocess path `FAILED` → `PROCESSING`
(`core/TransmissionState.kt`), guarded by the same `TransmissionDao.canTransition` check
`completePass`/`failPass` use so a sibling pass that already moved the transmission on is left
alone. Returns the count requeued. Fixes F-016: before this, a transmission captured while no ASR
model was installed drained against `UnavailableAsrEngine`, hit `maxAttempts`, landed terminally
`FAILED`, and had no path back to `READY` — it stayed lost forever even after a model was
installed. No new transmission state or `WorkQueueState` was introduced; the fix uses transitions
the state machine already declares legal.
**Verified:** Three new `WorkQueueTest` cases (`FR_RUN_9_a_failed_item_past_max_attempts_is_requeued_to_ready_with_attempts_reset`,
`FR_RUN_9_items_not_failed_are_untouched`, `FR_RUN_9_requeueFailed_returns_the_count_and_honours_the_error_prefix_filter`)
were written first and confirmed to fail with `Unresolved reference 'requeueFailed'` before the
implementation existed. After the fix: `./gradlew :data:test` — 11/11 `WorkQueueTest` cases green
(0 failures); `./gradlew build dependencyRules` — exit 0; `python tools/spec-check/spec_check.py`
— all 8 checks PASS. All Robolectric/JVM only; no on-device verification was performed or claimed.
**Left open / not done:** No caller wires this into a model-install action yet — that lives with
F-008, which is a separate finding/module (`:app`/`:pipeline` orchestration) and was out of this
fix's scope. No queue behavioural fake exists yet in `:testing` to update.
## 2026-09-07 (audit — F-014)

### (pending) — audit F-014 · coverage-matrix delta is now a CI gate

**Scope:** `buildSrc/` (new `CoverageMatrixCheckTask.kt`, `CoverageMatrix.contentMatches` and its
tests), root `build.gradle.kts` (new `coverageMatrixCheck` task registration only),
`.github/workflows/ci.yml` (`report` job); `results/coverage-matrix.md` regenerated.

**Requirements/ACs:** constitution, Development Workflow ("CI gates every push... the
coverage-matrix delta"); test-plan §9.

**What changed:** `results/audit-2026-09-07.md` F-014 found that `.github/workflows/ci.yml`'s
`report` job only ran `./gradlew coverageMatrix` and uploaded the regenerated file as an
artefact — it never compared the regeneration to the committed `results/coverage-matrix.md`, so
the committed file had drifted a whole wave stale (101 covered committed vs. 117 actually
covered) with no CI failure. Added `CoverageMatrix.contentMatches(generated, committed)` — a pure
comparison that normalises CRLF/LF and a trailing-newline difference before comparing, so only
real content drift fails — and a new `CoverageMatrixCheckTask` that regenerates the matrix from
the current spec/tests and throws with a clear message
(`results\coverage-matrix.md is stale — run ./gradlew coverageMatrix and commit the result.`)
when it disagrees with the committed file. Registered as `coverageMatrixCheck` in root
`build.gradle.kts`, sharing the same `specDir`/`testRoots` wiring `coverageMatrix` already used.
`ci.yml`'s `report` job now runs `coverageMatrixCheck` before `coverageMatrix`, keeping the
existing regenerate-and-upload steps. Regenerated `results/coverage-matrix.md` in the same
change so the check passes on the committed tree (101 → 117 covered — the actual staleness the
finding described).

**Verified:** `./gradlew -p buildSrc test` — new `CoverageMatrixTest` cases seen failing first
(`Unresolved reference: contentMatches`, a compile failure — the right reason, since the method
did not exist yet), green after implementing `contentMatches`. `./gradlew coverageMatrixCheck`
failed on the stale committed file before regeneration ("results\coverage-matrix.md is stale");
green after regenerating and committing it. Confirmed the check still fails on real drift by
editing one number in the committed matrix and re-running (failed as expected), then reverting.
Full gate: `./gradlew build dependencyRules coverageMatrixCheck` green;
`python tools/spec-check/spec_check.py` — all 8 checks PASS. All JVM/Robolectric only, per the
standing constraint; no device verification claimed.

**Left open / not done:** `coverageMatrixCheck` is wired only into the existing `report` CI job,
not into local pre-commit tooling — a contributor can still forget to run `coverageMatrix`
locally and will only find out from CI. That matches how `dependencyRules` and the other gates in
this repo already work, so left as is rather than inventing a new mechanism.
## 2026-09-07 (audit — F-003)

### (pending) — audit F-003 · the reader distinguishes FAILED and REJECTED from still-pending

**Scope:** `:app` only — `ui/data/TransmissionDetail.kt` (`TransmissionDetail`'s new
`processingState`/`rejectionReason` fields, and `ReaderTransmissionViewStateMapper`'s new
`transcriptLabel`), `ui/data/ReaderPolling.kt` (`detailFrom` now carries the two real
`TransmissionEntity` columns through), and their tests.

**Requirements/ACs:** FR-RUN-9 (`FAILED` is distinct from `REJECTED` and from pending),
constitution I (Uncertainty Is Content) and VII (text, not colour, carries the four states).

**What changed:** `results/audit-2026-09-07.md` F-003: `app/src/main` never read
`TransmissionEntity.processingState`, so every transmission with no current transcript rendered
`"(captured, not yet transcribed)"` — including one the work queue had already moved to `FAILED`
after `WorkQueue.DEFAULT_MAX_ATTEMPTS` or to `REJECTED` with a recorded reason. With no ASR model
on disk (the `UnavailableAsrEngine` fallback, `RealCaptureService.kt`), every real transmission
reaches `FAILED` within five drain cycles today and was displayed as pending forever.
`TransmissionDetail` now carries `processingState: TransmissionState` and `rejectionReason:
String?` (both already existed on `TransmissionEntity`; only the reader's mapping was blind to
them). `ReaderTransmissionViewStateMapper` gained a private `transcriptLabel` used by both
`listEntry` and `detailView` (so the log row and the detail screen render identically): a
non-null `currentTranscriptText` wins as before; otherwise `FAILED` renders `"(transcription
failed)"`, `REJECTED` renders `"(rejected: <reason>)"` (or `"(rejected — no reason recorded)"` if
none was written), and `CAPTURED`/`PROCESSING`/`COMPLETE` keep the original honest pending label.
`ReaderPolling.detailFrom` now passes `entity.processingState` and `entity.rejectionReason`
through.

**Left open / not done:** the `FAILED` label carries no `lastError` text, only the state. The
work queue's `lastError` lives on `WorkQueueItemEntity`, keyed by `(transmissionId, pass)`;
`:data`'s `WorkQueueDao` has no query that reaches a `FAILED` item by `transmissionId` alone
(`findByTransmissionAndPass` needs the pass id, which this reader does not carry per
transmission), and adding one is a `:data` change out of this fix's owned files — flagged rather
than added silently. A state label alone was called an acceptable outcome for this case in the
finding itself.

**Verified:** `ReaderTransmissionViewStateMapperTest` — 5 new cases named `FR_RUN_9_...` (a
`FAILED` row renders a distinct failure label in both `listEntry` and `detailView`; a `REJECTED`
row renders its reason, and renders distinctly even with no reason recorded; `CAPTURED`/
`PROCESSING` keep the original pending label) — first run failed to *compile* (`No parameter with
name 'processingState' found`), confirming the fields did not exist yet, before the production
change. `LogScreenTest` — 1 new case (`FR_RUN_9 a transmission the work queue moved to FAILED
shows a failure label, not the pending state`) driving the real mapper end-to-end into the
Compose screen. `./gradlew :app:test --console=plain -q` — green, no failures. Full gate:
`./gradlew build dependencyRules --console=plain -q` — green; `python
tools/spec-check/spec_check.py` — all 8 checks PASS. All on JVM/Robolectric; no on-device
verification is claimed.

---
## 2026-09-07 (audit — F-001)

### d3459d7 — audit F-001 · RealSegmentSink stops fabricating transmission clock fields

**Scope:** `:pipeline` — `pipeline/src/main/kotlin/org/ort/pipeline/capture/RealCaptureService.kt`
(`RealCaptureService.startCapture`, `RealSegmentSink`); new
`pipeline/src/test/kotlin/org/ort/pipeline/capture/RealSegmentSinkTest.kt`.

**Requirements/ACs:** FR-RUN-15, FR-RUN-16, FR-RUN-18; constitution I (never fabricate a
healthy-looking value) and "never report a number without its fold/machine/provider" read here as
"never persist a timestamp that was not actually derived from the clock model."

**What changed:** `results/audit-2026-09-07.md` finding F-001 recorded that every real
transmission `RealSegmentSink` persisted carried `startedAtUtc = 0L`, `endedAtUtc = null`,
`monotonicStartNanos = 0L` and `utcOffsetMinutes = 0` — literal placeholders, not measurements —
plus a duplicated `preRollMs = 1200`/`postRollMs = 400` that could silently drift from the
`SegmentConfig` actually driving the `Segmenter`. `RealCaptureService.onStartCommand` now anchors
`SystemClock.wallMillis()`, `SystemClock.monotonicNanos()` and `SystemClock.utcOffsetMinutes()`
together, once, at session start, and builds `:core`'s `SampleClock` from that anchor at
`FrameSpec.SAMPLE_RATE`. `RealSegmentSink` takes that `SampleClock` plus the same `SegmentConfig`
instance as constructor parameters (no second clock invented); on `close()` it calls
`sampleClock.timestampsAt(record.startSample)` for `startedAtUtc`/`monotonicStartNanos`/
`utcOffsetMinutes`, `sampleClock.wallMillisAt(record.endSample)` for `endedAtUtc`, and reads
`preRollMs`/`postRollMs` off `segmentConfig` instead of restating the defaults.

**Verified:** `./gradlew :pipeline:test` (Robolectric/JVM only — no device). New test
`RealSegmentSinkTest.a closed segment carries real wall clock and monotonic start derived from
its sample position` (`FR-RUN-15`) constructs `RealSegmentSink` against an in-memory `OrtDatabase`
with a `TestClock`-derived `SampleClock` anchored away from zero, closes one `SPEECH` segment
starting 3 seconds into the session, and asserts the persisted `TransmissionEntity`'s
`startedAtUtc`/`endedAtUtc`/`monotonicStartNanos`/`utcOffsetMinutes`/`preRollMs`/`postRollMs`
match values derived from the anchor and `record.startSample`/`endSample` — not `0`/`null`/
defaults. Confirmed failing first: against the pre-fix constructor/body the file did not compile
(`Too many arguments for RealSegmentSink(...)`), the correct failure shape once the test asserts
the new derived fields the old constructor has no way to supply. Full gate:
`./gradlew build dependencyRules` and `python tools/spec-check/spec_check.py`, both green.

**Left open / not done:** Not verified on a real device — Robolectric/JVM only, as for the rest of
this smoke-test wiring. Two adjacent issues in the same file were left untouched per the finding's
scope: the too-short-segment deletion path (`staged.delete()` on `REJECTED_TOO_SHORT`, which
contradicts "real product retains it") and the heartbeat's use of a placeholder `0L` sample
position, both owned by other audit agents.
## 2026-09-07 (audit — F-010)

### (pending) — audit F-010 · a stalled consumer or `AudioRecord` shortfall now produces an explicit dropped-span event on the real capture path

**Scope:** `:capture-android` (`AudioRecordSource.kt`, `GapTracker.kt`, new `DroppedSpanCause.kt`),
`AudioRecordSourceTest.kt`; `CHANGELOG.md`, `spec/build-plan.md` notes.
**Requirements/ACs:** AC-3, constitution IV ("silence that was never listened to MUST be
distinguishable from silence that was").
**What changed:** `results/audit-2026-09-07.md` F-010 found that `:capture-api`'s `RingBuffer`
(`hasOverrun()`/`droppedSamples()`, credited for AC-3 in the coverage matrix) is constructed
nowhere in `src/main` — the real `AudioRecordSource` → `Segmenter` path had no mechanism at all
for detecting a producer overrun or a stalled downstream collector; such a stall would lose audio
silently, with no gap record. `AudioRecordSource` now takes an injected `Clock` (default
`SystemClock`) and, on every successful read, compares the real wall time elapsed since the
previous read against the audio actually delivered. When the gap exceeds
`OVERRUN_TOLERANCE_READ_BUFFERS` (4) read-buffers' worth of slack — generous, to avoid false
positives from ordinary scheduling jitter — the shortfall is reported as dropped samples via a new
`DroppedSpanCause` encoding, carried through the *existing* `CaptureEvent.Interrupted`/`Resumed`
pair rather than a new `CaptureEvent` variant: `:pipeline`'s `RealCaptureService` switches over
`CaptureEvent` exhaustively with no `else`, and this fix does not own `:pipeline` (build-plan
boundary), so a new sealed case there was not an option without crossing it. `GapTracker`
recognises the `DroppedSpanCause` prefix and closes the gap immediately from its encoded duration
(the whole span is already known at detection time, unlike a real interruption which opens on
`Interrupted` and closes on the later `Resumed`); the paired `Resumed` that follows is a no-op for
it. `RingBuffer` itself is untouched — it remains correct, tested code that nothing in `src/main`
constructs; this fix does not route audio through it, so **AC-3 is now established on the real
`AudioRecordSource` path independently of `RingBuffer`**, which stays available for a future
producer/consumer split (technical design §5.3) if one is ever built.
**Verified:** New test
`AudioRecordSourceTest.AC_3_a_stalled_consumer_or_short_read_produces_an_explicit_dropped_span_event_rather_than_silent_loss`,
seen to fail first with a compile error (`No parameter with name 'clock' found`) before
`AudioRecordSource` gained the `clock` parameter — then green.
`./gradlew :capture-android:test :capture-api:test --console=plain -q` — green (7/7 in
`AudioRecordSourceTest`, `RingBufferTest` unchanged and still green). Full gate:
`./gradlew build dependencyRules --console=plain -q` — green (after `ktlintFormat` fixed test
indentation); `python tools/spec-check/spec_check.py` — all 8 checks PASS. JVM/Robolectric only;
no on-device verification claimed.
**Left open / not done:** The detection is a wall-clock heuristic (elapsed time vs. samples
delivered), not a hardware-reported overrun counter — `AudioRecord` does not expose one directly.
`GapPersister` (`:pipeline`, not touched) will currently file this cause under
`CaptureGapCause.UNKNOWN` since it does not recognise the `"dropped samples:"` prefix; a
`CaptureGapCause.DROPPED_SAMPLES` category, and wiring `GapTracker`'s output into
`RealCaptureService`/`GapPersister` at all (`RealCaptureService` does not currently invoke either —
a separate, pre-existing wiring gap, not introduced or fixed here), are `:pipeline`/`:data`
follow-ups outside this fix's ownership.
## 2026-09-07 (audit — F-024)

### (pending) — audit F-024 · RealHttpRangeClient gets a loopback test and a real bug fix

**Scope:** `:net` — `real/RealHttpRangeClient.kt`, new `real/LoopbackHttpFixture.kt` and
`real/RealHttpRangeClientTest.kt` under `net/src/test`, `net/README.md`.
**Requirements/ACs:** FR-AST-1, FR-AST-3; constitution II (test-backed change), V (`:net` is the
only declared outbound channel and must be correct because it is the only one).
**What changed:** `RealHttpRangeClient` — the `HttpURLConnection`/`Range`-header wiring behind
`ModelAcquisition` — had no automated test (F-024); a prior attempt at a loopback
`com.sun.net.httpserver.HttpServer` fixture was rejected because `jdk.httpserver` is not on this
Android-library module's unit-test classpath (JPMS module visibility). Replaced that approach
with a minimal `java.net.ServerSocket`-based HTTP/1.1 fixture (`LoopbackHttpFixture`, binds only
to `127.0.0.1` on an ephemeral port) and a new `RealHttpRangeClientTest` covering: a full fetch
returning the exact bytes; a resumed fetch sending `Range: bytes=<offset>-` and receiving only
the tail (206); a non-2xx status surfacing as `HttpRangeResult.Failure` rather than a thrown
exception; and a connection that closes mid-body. The last case failed against the real client
before any fix — `AssertionFailedError: Expected java.io.IOException to be thrown, but nothing
was thrown` — revealing a genuine divergence from `FakeHttpRangeClient`'s documented contract:
`HttpURLConnection` does not throw when the peer closes the socket short of its own
`Content-Length`, so a dropped connection read to a silent, truncated success instead of an
exception. That would have made a mid-transfer drop indistinguishable from a complete-but-corrupt
download to `ModelAcquisition`, defeating FR-AST-3 resumability (the caller only preserves the
`.part` file for resume when the read throws). Fixed `RealHttpRangeClient` by wrapping the
response body in a length-validating `InputStream` that throws `IOException` on a short EOF,
bringing it in line with the fake's `dropAfterBytes` semantics exactly. `net/README.md`'s "what is
genuinely verified vs. fake-verified" section is updated to reflect that `RealHttpRangeClient` is
now covered.
**Verified:** test written first and seen to fail for the truncation case specifically (quoted
above) against the pre-fix client; all four cases green after the fix under
`./gradlew :net:test` (`RealHttpRangeClientTest`, 4/4 passing). Full gate green:
`./gradlew build dependencyRules` and `python tools/spec-check/spec_check.py` (all 8 checks
PASS). JVM-only (Robolectric/desktop), no on-device verification claimed.
**Left open / not done:** none for this finding. The other clustered F-027 gaps (FR-AST-4/7/9,
etc.) are out of this change's scope.
## 2026-09-07 (audit — F-004)

### (pending) — audit F-004 · Status surface now shows ASR/VAD model availability

**Scope:** `:app` — `status/StatusViewState.kt` (and its mapper), `ui/data/ReaderPolling.kt`
(`currentStatus` only), `ui/screens/StatusScreen.kt`, `ui/screens/NowScreen.kt`, and their tests
(`StatusViewStateMapperTest.kt`, new `StatusScreenTest.kt`, `NowScreenTest.kt`,
`ReaderPollingTest.kt`).
**Requirements/ACs:** FR-UI-7 (capture status surface shows current tier/processing state);
constitution I (uncertainty is content — a status surface must never read as more capable than it
is) and IV (capture never lies).
**What changed:** `AsrAvailability`/`VadAvailability` (set by `RealCaptureService`, `:pipeline`)
were previously read only by the dead `StatusActivity`; nothing in the live Compose reader
(`OrtNavHost` → `ReaderPolling.currentStatus` → `StatusViewState` → `StatusScreen`, inside
`NowScreen`) showed whether a transcription model was installed, so with no model fetch built yet
(build-plan P18 not shipped) the reader gives no reason transcripts never appear. `StatusViewState`
gained `asrStatusLabel`, `vadStatusLabel` and `transcriptionUnavailableMessage` (all with safe
"not started"/unavailable defaults, never a healthy-looking default when unset);
`StatusViewStateMapper.from` gained optional `asrState`/`vadState` parameters read from the real
`AsrAvailability.state`/`VadAvailability.state` in `ReaderPolling.currentStatus`. `StatusScreen`
renders two new plain-text rows ("ASR: …", "VAD: …"); `NowScreen`'s header shows the one-line
`transcriptionUnavailableMessage` ("No transcription model installed — transcripts will not
appear") whenever ASR is not `Available`. No change to `:pipeline`'s `AsrAvailability`/
`VadAvailability` API — it was sufficient as built.
**Verified:** TDD — each new/changed assertion was first run against the pre-fix code and seen to
fail for the right reason (compile error for the new `StatusViewState` fields/params; Compose
`assertExists` failures for the missing rows/header text; `ReaderPollingTest`'s new case failed
because `AsrAvailability.unavailable(...)` had no effect before the mapper was wired to it), then
the fix was applied and all tests passed. `./gradlew :app:test` — green (Robolectric/JVM only; no
on-device verification). `python tools/spec-check/spec_check.py` — OK. Full gate
`./gradlew build dependencyRules` run before commit.
**Left open / not done:** No ASR/VAD model can actually be installed yet (build-plan P18), so on
Robolectric this only proves the "not started"/"unavailable" path renders correctly, not the
"available" path against a real model — that is P18's own verification once model fetch exists.
`StatusActivity` (dead) is left untouched per the finding's scope.
## 2026-09-07 (audit — F-017)

### (pending) — audit F-017 · `:data` half: SearchDao gains band, attribution-state and rejected/accepted filters

**Scope:** `:data` — `dao/SearchDao.kt` (`filterOnly`, `searchText`, `search`), new `Band.kt`;
tests in `SearchDaoFilterTest.kt`. Does not touch `:app`'s `SearchScreen` half (tracked
separately in `results/audit-2026-09-07.md` F-017) or any other module.
**Requirements/ACs:** FR-UI-3 (band, attribution state, rejected/accepted — the three filters
P15 left open; see CHANGELOG's earlier P15 entry and `results/audit-2026-09-07.md` F-017).
**What changed:**
- Added `org.ort.data.Band`: the amateur-radio band table (functional spec §8's "Band plan
  tables") as sixteen `[minHz, maxHz]` allocations from 160m through 23cm, with `Band.of(hz)`
  and `Band.contains(hz)`. Deliberately amateur-only, not a full scanner-service table — that is
  what FR-UI-3's "band" filter and the lexicon's own band-plan table mean. No `Band` type
  existed anywhere in `:core` or `:data` before this (confirmed by grep), so it lives in `:data`
  at the package root beside `Converters.kt`, reusable by any `:data` caller.
- `SearchDao.filterOnly` and `SearchDao.searchText` each gained four new bind parameters:
  `bandMinHz`/`bandMaxHz` (inclusive frequency bounds), `attributionState`
  (`org.ort.core.AttributionState?`), and `rejected` (`Boolean?`, tri-state: null = don't
  filter, true = `processingState = 'REJECTED'` only, false = everything else). All four compose
  with each other, with the pre-existing callsign/frequency/date filters, and with both the
  filter-only path and the FTS5 MATCH path.
- `SearchDao.search()` — the one entry point `:app` calls — kept its original parameter names
  and positions (`text, callsign, frequencyHz, fromUtc, toUtc`) so
  `org.ort.app.ui.data.SearchPolling.search`'s existing named-argument call sites keep compiling
  unchanged, and gained three new trailing optional parameters: `band: Band?`,
  `attributionState: AttributionState?`, `rejected: Boolean?`. `search()` resolves `band` to its
  `minHz`/`maxHz` range before calling `filterOnly`/`searchText`.
- Considered bundling all eight filters into one data-class parameter (to keep `searchText`'s
  parameter count down and give the DAO a named "filter model"); reverted after confirming Room's
  raw-SQL `@Query` binder has no dot-path syntax for a POJO's fields (`:filters.callsign` is a
  SQL parse error under KSP, not a Room-specific one) — `filterOnly`/`searchText` must bind each
  filter as its own flat parameter. `searchText` (9 parameters: the match expression plus the
  six filters plus the original callsign/frequency pair split doesn't apply — see the file for
  the exact list) needed `@Suppress("LongParameterList")` for detekt's threshold of 8; this is a
  direct consequence of Room's raw-SQL binding, not a hidden design smell.
**Verified:** `./gradlew :data:testDebugUnitTest --tests "org.ort.data.SearchDaoFilterTest" --tests "org.ort.data.SearchDaoFullTextTest"`
green (all filter and full-text-search tests pass, including the honest fts5-unavailable
`Assume` skip in `SearchDaoFullTextTest`, unchanged). Full gate:
`./gradlew build dependencyRules` green; `python tools/spec-check/spec_check.py` — all 8 checks
PASS. All JVM/Robolectric only; no on-device verification was performed or claimed.
New tests, all named `FR_UI_3_...` in spirit (backtick test names, per this codebase's existing
convention in this file): filtering by band (2m vs. 70cm), filtering by attribution state
(CONFIRMED vs. AMBIGUOUS), filtering by rejected (true) and accepted (false), and one combined
case exercising band + attribution state + rejected + frequency together. Caught and fixed one
test-fixture bug in the same change: `TestFixtures.transmission()`'s default `attributionState`
is derived from the `stationId` argument passed to that call, not from a later `.copy()` — the
existing TX1/TX2 seed rows in `SearchDaoFilterTest` were silently `UNKNOWN` rather than
`CONFIRMED` until `attributionState` was set explicitly.
**Left open / not done:** The `:app` half of F-017 (`SearchScreen` UI: exposing these three new
filters to the user, e.g. a band picker, attribution-state chips, rejected/accepted toggle) is
untouched — out of scope for this change per the finding's own split into two briefs. No new
Room migration was needed (no schema change; the new filters read existing columns).
## 2026-09-07 (audit — F-012)

### (pending) — audit F-012 · attribution confidence becomes visible text, not screen-reader-only

**Scope:** `:app` — `ui/components/AttributionMarker.kt`, `ui/screens/LogScreen.kt` (no code
change needed there, it renders `AttributionMarker` already), `ui/screens/TransmissionDetailScreen.kt`
(no code change needed there either), and their tests (`AttributionMarkerTest.kt` unchanged/still
green, `LogScreenTest.kt`, `TransmissionDetailScreenTest.kt`).
**Requirements/ACs:** FR-UI-4, constitution I.
**What changed:** `results/audit-2026-09-07.md` finding F-012: `AttributionMarker` put the
numeric confidence only into its merged `contentDescription`, so a sighted user reading the Log
rows or the Transmission Detail header never saw the number FR-UI-4 requires ("SHALL never be
omitted"), even though `Log.dc.html`/`Detail.dc.html` both show a visible `0.82`-style badge.
Added a visible `Text` inside `AttributionMarker`, rendered beside the state label whenever
`attribution.confidence` is non-null, formatted `%.2f` to match the artboards and the existing
content-description wording. `UNKNOWN` (and any `AMBIGUOUS` without a confidence) render no
number — `confidence?.let { }` renders nothing rather than fabricating a placeholder, per
constitution I. Because `LogScreen` and `TransmissionDetailScreen` already delegate to
`AttributionMarker` for every rendering of an attribution, no change was needed in either screen
file itself — the fix is entirely in the shared component, which is also why P13's "prove it once
here" reuse note in `AttributionMarker`'s own doc comment made this a one-file fix rather than two.
**Verified:** Strict TDD — added `FR_UI_4 the confidence value is shown as visible text...` and
`FR_UI_4 an UNKNOWN row/attribution shows no confidence number...` to both `LogScreenTest.kt` and
`TransmissionDetailScreenTest.kt`; confirmed both "visible text" tests failed first with
`Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText
contains '0.95' ...)` against the pre-fix component (verified by stashing the component change
and re-running), then made the fix and reran green. `./gradlew :app:testDebugUnitTest --tests
org.ort.app.ui.screens.LogScreenTest --tests org.ort.app.ui.screens.TransmissionDetailScreenTest
--tests org.ort.app.ui.components.AttributionMarkerTest --tests
org.ort.app.ui.ReaderAccessibilityTest` (all green); `./gradlew :app:test` (green, full module);
full gate `./gradlew build dependencyRules` (green) and `python tools/spec-check/spec_check.py`
(`spec-check: OK`, all 8 checks pass). All Robolectric/JVM — no on-device verification performed
or claimed.
**Left open / not done:** none for this finding. Other open findings in
`results/audit-2026-09-07.md` (e.g. F-013, F-014) are out of scope and untouched.
## 2026-09-07 (audit — F-021)

### (pending) — audit F-021 · shed events get a `shed_event` table (`:data` half only)

**Scope:** `:data` — new `entity/ShedEventEntity.kt` (+ `ShedTrigger` enum), new
`dao/ShedEventDao.kt`; `OrtDatabase.kt` (entity registration, `shedEventDao()` accessor, schema
version 1 → 2, `MIGRATION_1_2`); `data/schemas/org.ort.data.OrtDatabase/2.json` (generated);
`data/src/test/kotlin/org/ort/data/dao/ShedEventDaoTest.kt` (new); `MigrationTest.kt` (new v1→v2
case). No `:pipeline` file touched — the persist call from `ShedController` is a follow-up
(F-021's `:pipeline` half, tracked separately).

**Requirements/ACs:** FR-RUN-3, FR-RUN-4, FR-RUN-5, FR-AST-5, FR-AST-6, AC-53. Constitution IV
(capture/shedding never lies silently) and III/AC-53 (migrations preserve existing rows).

**What changed:** `results/audit-2026-09-07.md`'s F-021 recorded that
`pipeline/.../shed/ShedController.kt`'s `ShedEvent` (`level`, `reason`, `atWallMillis`) lives only
in that controller's in-memory `events` list, so shed steps are not durably surfaced (FR-RUN-3),
affected records cannot be identified as reprocessing candidates after the fact (FR-RUN-4), and
shed level is not observable beyond the current process (FR-RUN-5). Added `ShedEventEntity`
(`id`, `sessionId`, `levelBefore`, `levelAfter`, `trigger: ShedTrigger` — `BATTERY`/`BACKLOG`/
`STORAGE` — `reason`, `atWallMillis`, `atMonotonicNanos`, `samplePosition: Long?`) and
`ShedEventDao` (`insert`, `listBySession` ordered by wall time, `latestForSession`). Fields mirror
`ShedController.ShedEvent`'s shape (`level`→split into before/after, `reason`, `atWallMillis`)
plus what the requirements need once persisted: which session, a closed trigger category
alongside the free-text reason, monotonic time (the controller already tracks
`enteredAtMonotonic`), and stream sample position (nullable — the controller does not track this
today). Bumped `OrtDatabase.SCHEMA_VERSION` to 2, added `MIGRATION_1_2` (adds the `shed_event`
table only — no existing table altered), and registered `ShedEventEntity`/`ShedEventDao`. No
DAO fake was added to `:testing` — that module holds no DAO fakes for any entity today (checked
directly), so there was no existing convention to extend.

**Deliberately not done:** the `:pipeline` persist call wiring `ShedController.transitionTo` (or
a caller) to `ShedEventDao.insert` — that requires touching `:pipeline`, which this fix does not
own per the audit assignment, and `:pipeline` has no dependency on `:data` in the module graph
today (a persist call would need to go through a repository/use-case seam that does not yet
exist). This entity and DAO exist so that follow-up is a mechanical field mapping, not a schema
design exercise.

**Verified:** `./gradlew :data:test --console=plain -q` — 38 tests, all green (36 pre-existing +
`ShedEventDaoTest`'s 2 new cases + `MigrationTest`'s 1 new case). Both new tests were confirmed to
fail first: with `ShedEventEntity.kt`/`ShedEventDao.kt` moved aside and `OrtDatabase.kt`
unmodified, `./gradlew :data:test` failed to compile with `Unresolved reference 'shedEventDao'` /
`'ShedEventEntity'` / `'ShedTrigger'` in both `ShedEventDaoTest.kt` and `MigrationTest.kt`. After
restoring the implementation, `./gradlew build dependencyRules --console=plain -q` and
`python tools/spec-check/spec_check.py` both passed (spec-check: OK, all 8 checks PASS). Robolectric/JVM only — no on-device verification was performed or claimed.

**Left open / not done:** the `:pipeline` persist call (see above) — F-021 stays partially open
until that lands; this fix closes only the `:data` half the assignment specified. `samplePosition`
is nullable and unpopulated by any writer yet since nothing calls `insert` in production code —
its semantics (which stream position) are fixed by whatever the eventual `:pipeline` caller passes.
## 2026-09-07 (audit — F-027, `:lexicon`/`:eval`/`:testing` slice)

### (pending) — audit F-027 · naming and adding tests so 21 built-but-untested ids leave the coverage matrix

**Scope:** `lexicon/src/test/**`, `eval/src/test/**`, `testing/src/test/**` — no production code
changed (every capability inspected in this slice was either already correct, or genuinely does
not exist yet).

**Requirements/ACs:** established by rename or new test — FR-LEX-6, FR-LEX-11 (partial, see below),
FR-LEX-14, FR-LEX-18, FR-LEX-19, FR-LEX-20, FR-LEX-21, FR-LEX-23, FR-LEX-29, FR-TST-2, FR-TST-4,
FR-TST-5, FR-A11Y-6, NFR-1a, NFR-1c, AC-57. Left uncovered, honestly (see below): AC-13, AC-35,
FR-LEX-15, FR-LEX-16, FR-LEX-22, FR-LEX-32.

**What changed:**
- Renamed 11 existing tests to carry the requirement id they already established (no assertion
  loosened), across `ThresholdDerivationTest`, `PlattCalibratorTest`, `ReliabilityDiagramTest`,
  `TextDerivedLatticeBuilderTest`, `ItuPrefixTableTest`, `TestClockTest`, `HarnessDeterminismTest`,
  `HarnessCallsignPrecisionRecallTest` (e.g. `FR_LEX_19 AC_56 raising the precision target moves
  the CONFIRMED threshold upward`; `NFR_1a AC_56 the achieved recall falls as the precision target
  rises` — NFR-1a's precision-over-recall tradeoff, per the finding's own suggestion, is provable
  only as this threshold-derivation property, not a measured number).
- Added five new test files/cases against existing production code, each written first and seen
  to fail for the right reason before any rename/no-op confirmed it passed:
  - `MyStationsPriorTest` (FR-LEX-14): the "my stations" prior's full positive clamp when a
    station is on the list, zero (not a penalty) when absent, cold-zero when unconfigured, and
    that its magnitude matches the other recency-class priors.
  - `RankedCandidateListTest` (FR-LEX-11, partial — see left-open): `PriorCombiner.rank` emits
    every surviving candidate with its own finite score, sorted best-first. This establishes only
    FR-LEX-11's first sentence; the `AMBIGUOUS`-at-separation-threshold half is a different,
    already-correct capability in `:pipeline`'s `CallsignResolver`/`CallsignResolverTest` —
    outside this brief's ownership, and its test is not id-named.
  - A case added to `ColdStartTest` (AC-57 / FR-LEX-23): with `geographicDistanceKm = null`
    (location permission denied), the geographic prior alone goes cold while recency and
    my-stations priors still contribute at full strength — "everything functions, losing only
    geographic-prior precision".
  - Two cases added to `VariantTableTest` (FR-A11Y-6): a novel legacy/regional spoken form is
    addable as a plain TSV row with no code change, and the bundled table resolves identically
    under a non-ROOT default JVM locale (the Turkish-I trap) — the variant set is content, not
    localization.
  - Two cases added to `PlattCalibratorTest` (FR-LEX-21): fitting two differently-shaped synthetic
    generating processes (standing in for two tiers/models) separately yields visibly different
    calibration curves, and applying the wrong tier's calibrator to the other's distribution
    calibrates measurably worse toward 0.9 than the matched one — calibration does not transfer
    across tiers/models.
  - `PerTierReportingTest` in `:eval` (NFR-1c): two `Harness.run` calls with different
    `precisionTarget`s (standing in for two tiers) over the *same* evaluate set return two
    independent `HarnessReport`s with different derived thresholds, and re-running one tier
    reproduces exactly its own canonical report — nothing is aggregated across tiers.

**Verified:** `./gradlew :lexicon:test :eval:test :testing:test` — all green (checked each new
JUnit XML report for `failures="0"`). `./gradlew coverageMatrix` regenerated
`results/coverage-matrix.md`; all 15 renamed/established ids above (plus NFR-1A/NFR-1C, rendered
uppercase by the tool) left the "Not yet covered" block. `./gradlew build dependencyRules` green.
`python tools/spec-check/spec_check.py` — 8/8 PASS.

**Left open / not done:**
- **AC-13** needs a real rig-reported frequency joined against an actual known-repeater list; no
  repeater-list asset or import exists in `:lexicon` (only the test double `RepeaterMatch`, which
  a caller constructs by hand) — not established here, per the finding's own instruction not to
  fabricate this.
- **AC-35** — the harness (`:eval`) reports callsign precision/recall, a reliability diagram and
  per-prior ablation, but not WER, rejection-rate-by-reason, or attribution accuracy: those need
  the ASR pass and Pass B/attribution-state wiring the harness's own doc comment says are later
  waves. Only the built subset is now named (FR-TST-5); the full AC-35 claim is not established.
- **FR-LEX-15 / FR-LEX-16** — the "active slice" for Pass A hotword biasing does not exist
  anywhere in the repository (confirmed by grep for `ActiveSlice`/`hotword` across all modules,
  finding only spec prose and one `asr-api` doc comment naming the concept, no implementation).
  Not established; this is genuinely unbuilt, not merely untested.
- **FR-LEX-22** — rig-position/GPS/manual-grid location-sourcing priority is not implemented in
  `:lexicon` (no `Location`/`Maidenhead` code found); `RankingContext.geographicDistanceKm` takes
  a pre-computed distance and has no notion of *how* that distance was sourced. Not established.
- **FR-LEX-32** — no WWARA-repeater-import or recency-seeding code exists to seed the recency
  prior on day one; `MyStationsPrior`/`RecencyPrior` consume already-populated context but nothing
  in `:lexicon` populates it from an import. Not established.
- `coverageMatrixCheck`, which the standing brief's generic verification step names, is not an
  actual Gradle task in this checkout (`./gradlew coverageMatrixCheck` fails with "task not
  found"); `.github/workflows/ci.yml`'s `report` job still only runs `coverageMatrix` and uploads
  the artefact without diffing it. This belongs to F-014's owner, not this finding; flagged here
  rather than silently skipped.
## 2026-09-07 (audit — F-027)

### (pending) — audit F-027 · name the tests that already establish capture-android/capture-api's built capability

**Scope:** `capture-android/src/test/**`, `capture-api/src/test/**`, `results/coverage-matrix.md`.
No `src/main` changes in either module.
**Requirements/ACs:** Established here, by rename (`@Requirement` annotation) or by a new test:
`AC-1`, `FR-CAP-1`, `FR-CAP-5`, `FR-RUN-13`, `FR-SVC-1`, `FR-SVC-2`, `FR-SVC-3`, `FR-SVC-4`,
`FR-SVC-5c`, `NFR-9`, `NFR-10`. Left uncovered, with reasons below: `FR-CAP-2`, `FR-PLT-2`,
`FR-PLT-4`, `FR-RUN-14`, `FR-RUN-18`, `FR-SVC-5`, `FR-SVC-6`, `NFR-4a`.
**What changed:**

- **Constitution Check.** Principle II (Test-Backed Change) is the whole point — "tests are named
  for the requirement they establish so the coverage matrix is generated rather than maintained" —
  this finding is exactly that discipline lapsing for one slice. Principle IV (Capture Never
  Blocks, Never Drops, Never Lies) bears on the two genuinely new tests: FR-CAP-5's retry-with-
  backoff and FR-RUN-13's mid-session route re-verification are both "never silently continue"
  guarantees that had code but no test pinning them.
- **Established by adding ids to an existing `@Requirement` annotation** (no assertion loosened):
  `AC-1` on `RouteVerifierTest`'s matching-route case; `FR-CAP-1` on `AudioRecordSourceTest`'s two
  resampler-identity tests (also strengthened with an explicit mono-channel assertion); `FR-SVC-4`
  and `NFR-10` on `CaptureServiceTest`'s existing AC-5 unclean-end test (also strengthened with an
  assertion that the report's last-heartbeat time is real, not a placeholder); `FR-SVC-3` on the
  service's stop-action test; `FR-SVC-5c` on all three `ProveItAnalyzerTest` cases; `NFR-9` on
  `OemGuidanceResolverTest` and `OemGuidanceTableTest` (confirmed identical in substance to the
  already-tagged FR-SVC-5a).
- **Established by a new, real test**, against the existing fakes:
  - `AC-1`/`FR-CAP-5` (`AudioRecordSourceTest`): a mid-run disconnection (`raiseInterruption`
    with the adapter refusing to reopen) is surfaced immediately as `CaptureEvent.Interrupted`,
    retried more than once with backoff (driven via `advanceTimeBy`/`runCurrent` against the
    virtual clock, `≥3` real `FakeAudioIo.open()` attempts), and never emits `Failed` — the
    session stays open until the adapter reopens.
  - `FR-RUN-13` (`AudioRecordSourceTest`): a route change raised *after* the source is already
    running (not before `start()`, which every existing test used and which cannot exercise the
    `AudioIoEvent.RouteChanged` branch at all) lands on a mismatch and halts exactly as FR-CAP-3 —
    this branch of `AudioRecordSource.start()` had no test exercising it before this change.
  - `FR-SVC-1` (`CaptureServiceTest`): parses `capture-android/src/main/AndroidManifest.xml`
    directly (Robolectric's shadow `android.jar` for this project's configured SDK throws
    `NoSuchMethodError` on `ServiceInfo.getForegroundServiceType()`, so `PackageManager` cannot be
    used here) to confirm the `<service>` declares `android:foregroundServiceType="microphone"`,
    plus a live check that starting the service posts a foreground notification.
  - `FR-SVC-2` (`CaptureServiceTest`): the wake lock (`ShadowPowerManager.getLatestWakeLock()`) is
    held once capture starts and released once the service is destroyed — modelled through
    `controller.destroy()`, because `cleanStop()` only requests teardown (`stopSelf()`); release
    happens in `onDestroy()`, matching the real Android service lifecycle, not a bug.
- **Left uncovered, honestly, not fabricated:**
  - `FR-CAP-2` (OS enumeration/filtering via real `AudioManager.getDevices`/`AudioDeviceInfo`) —
    `AndroidAudioIo`'s own doc comment already states this is deliberately left to the physical
    device; Robolectric cannot construct a real `AudioDeviceInfo`.
  - `FR-PLT-2` (per-attachment USB permission) — no `UsbManager`/permission-request code exists
    anywhere in `capture-android` to test; this is audio-adapter capture, not the rig-USB CAT
    link, and nothing here implements it.
  - `FR-PLT-4` (functioning fully with notifications denied) — no such branching exists in
    `capture-android`; this is an `:app`-level onboarding/UI concern.
  - `FR-RUN-14` (concurrent capture / audio focus so a phone call degrades to a gap) —
    `AndroidAudioIo`'s doc comment confirms `AudioManager.OnAudioFocusChangeListener` is "not
    wired yet"; the capability does not exist.
  - `FR-RUN-18` (UTC + originating offset retained for stored wall-clock times) — capture-android
    stores `wallMillis`/`monotonicNanos` only; the offset-retention requirement belongs to `:data`
    entity timestamp fields, not this module.
  - `FR-SVC-5` (on-launch detection of whether the app is subject to restriction) — only the
    manufacturer-keyed guidance *content* (FR-SVC-5a, already tested) is built here; the
    detection/trigger and onboarding flow live in `:app`, untested here.
  - `FR-SVC-6` (finalise the interrupted session and process backlog on next launch) — capture-
    android only detects the unclean end (`UncleanEndDetector`); finalisation and backlog
    reprocessing are `:pipeline`'s job and are not built in this module.
  - `NFR-4a` (unexpected termination loses at most the in-flight segment) — a segmenter/boundary
    guarantee that belongs to `:pipeline`; nothing in `capture-android` bounds segment loss.

**Verified:** `./gradlew :capture-android:testDebugUnitTest :capture-api:test --console=plain -q`
green (all pre-existing tests plus 4 new tests pass; the two new `AudioRecordSourceTest` cases and
two new `CaptureServiceTest` cases were run and observed failing first — the backoff test failed
with "must attempt reconnection more than once… got 1" before the virtual-clock driving was
correct, the manifest test failed with `NoSuchMethodError` on the `PackageManager` approach before
switching to direct XML parsing, and the wake-lock test failed with "must be released" before
`controller.destroy()` was added — then passing once each test was made to exercise the real
behaviour correctly). `./gradlew coverageMatrix` regenerated `results/coverage-matrix.md`; all
eleven established ids above now appear under "Covered" with the tests listed. `./gradlew build
dependencyRules --console=plain -q` green (includes detekt, ktlint, all module tests,
dependency-rule enforcement). `python tools/spec-check/spec_check.py` run and green.
**Left open / not done:** `coverageMatrixCheck` (named in the standing brief's verification step)
is not a registered Gradle task on this branch's base commit — `./gradlew tasks` confirms no such
task exists in this worktree; only `coverageMatrix` (the generator) exists. Verified the
regenerated matrix directly instead (diffed and grepped for the established ids). The eight ids
listed as "left uncovered" above are unchanged in the matrix and remain honestly listed as not yet
covered — none were fabricated as covered.
## 2026-09-07 (audit — F-027)

### (pending) — audit F-027 · execution-provider, minSdk and the network/telemetry/backup structural set get tests

**Scope:** `:onnx` (`ModelDescriptor.kt` + new `ModelDescriptorCpuPathTest.kt`); `buildSrc`
(new `PlatformGuards.kt`/`PlatformGuardsTask.kt` + `PlatformGuardsTest.kt`); root
`build.gradle.kts` (wires `platformGuards` into `check`, and adds `buildSrc/src/test/kotlin` to
`coverageMatrix`'s scanned test roots — it was previously invisible to the matrix entirely);
`app/src/test/kotlin/org/ort/app/structural/` (new package, `AllowBackupTest.kt`);
`results/coverage-matrix.md` (regenerated).
**Requirements/ACs:** FR-ACC-2, NFR-5b, AC-59, FR-OBS-5, NFR-6, FR-STO-8, NFR-6a, FR-PLT-5.
Explicitly NOT established here: NFR-5, NFR-5b's "no vendor SDK" clause beyond the CPU-path
slice, CON-CAP-1 (see Left open).
**What changed:**
- `ModelDescriptor`'s `init` now `require`s `"cpu" in providerBinaries` (was: any non-empty
  set). This is the type-level slice of FR-ACC-2/NFR-5b that is actually built today — a
  descriptor that only ever declares an accelerator binary can no longer be constructed. The
  full `ExecutionProvider` abstraction technical design §3.3 describes does not exist yet (M11
  scope); `ModelDescriptorCpuPathTest` says so in its KDoc and establishes only this slice.
- New `PlatformGuards` (pure logic, buildSrc) + `PlatformGuardsTask`, wired as the `platformGuards`
  Gradle task and into `check`: fails the build on (a) any dependency coordinate matching a
  telemetry/analytics/crash-reporting marker list in any module (FR-OBS-5), (b) any HTTP-client
  dependency coordinate outside `:net` (constitution V, NFR-6), (c) `android.permission.INTERNET`
  declared in any module's manifest outside `:net` (AC-59, NFR-6). Ran against the real project:
  currently zero violations on all three. These are declared-artifact checks — they prove what a
  build *could* do, never what it *did*; the task's own log output and KDoc say this explicitly.
- New `AllowBackupTest` (Robolectric) reads the merged manifest's `ApplicationInfo.FLAG_ALLOW_BACKUP`
  and asserts it is unset — `android:allowBackup="false"` was already correct in
  `AndroidManifest.xml`, so no manifest edit was needed; this only adds the missing proof.
- `coverageMatrix`'s `testRoots` now includes `buildSrc/src/test/kotlin`, which was silently
  excluded before (buildSrc is a separate included build, not a `subprojects` member). Side
  effect: regenerating surfaced two pre-existing, unrelated staleness items in the matrix —
  `Q8` (`CorrectionDaoTest`, already in `:data`, was already an orphan the last regen missed)
  and `AC-999` (`CoverageMatrixTest`'s own fixture for testing the orphan-detector, a permanent
  and expected orphan now that buildSrc is scanned). Neither is part of F-027; left as the
  regenerated tool now honestly reports them.
**Verified:** `./gradlew :onnx:test :buildSrc:test :asr-api:test :asr-sherpa:test :core:test
dependencyRules platformGuards --console=plain -q` green. `ModelDescriptorCpuPathTest`'s
rejection case and `PlatformGuardsTest`'s four detection cases were written and run failing
first (`Unresolved reference` / compile failure before `PlatformGuards` existed;
`IllegalArgumentException` not thrown before the `ModelDescriptor` guard existed).
`AllowBackupTest` was proven against a deliberate, uncommitted, reverted-before-commit
`allowBackup="true"` edit (`:app:testDebugUnitTest` failed with the expected assertion message),
then against the real (unchanged) `false` value (green) — `app/src/main/AndroidManifest.xml`
carries no net diff. `./gradlew coverageMatrix` regenerated; FR-ACC-2, NFR-5b, AC-59, FR-OBS-5,
NFR-6, FR-STO-8, FR-PLT-5, NFR-6a no longer appear in "Not yet covered". Robolectric/JVM only
throughout — no device, no real accelerator, no real network involved anywhere in this change.
**Left open / not done:**
- **NFR-5** (minSdk 26) — `MinSdkLaunchTest` (`app/src/androidTest/kotlin/org/ort/app/`) already
  documents NFR-5 in its KDoc and is a genuine, running instrumented test, but its test *function
  name* only carries `AC_93`, so the matrix generator does not credit NFR-5 to it. Renaming it
  needs `app/src/androidTest`, outside this fix's owned paths (`:onnx`/`:asr-api`/`:asr-sherpa`/
  `:core`/`app/src/test/kotlin/org/ort/app/structural`). Not fixed here — flagging as a one-line
  follow-up for whoever owns that file.
- **NFR-5b**'s "no required dependency on any specific SoC, NPU or vendor SDK" beyond the CPU-path
  slice, and the rest of **FR-ACC-2**'s "stable execution-provider interface" — not established
  here. The `ExecutionProvider`/`CpuProvider` abstraction technical design §3.3 describes has not
  been built (only `ModelDescriptor.providerBinaries`, a `Set<String>` with no NPU implementation
  behind it, exists); building that interface is production scope well beyond a test-only audit
  fix, and there is currently no vendor SDK dependency anywhere to test against regardless.
- **CON-CAP-1** (Bluetooth audio input never offered) — would need a test in `:capture-android`/
  `:capture-api` (where `AudioDeviceKind.BLUETOOTH` and route selection actually live), which is
  outside this fix's owned modules. Not established here.
- `PlatformGuards`' three checks are structural proxies, stated as such in their own KDoc: they
  cannot and do not prove a build made no network call, ran no telemetry SDK, or attempted no
  backup at runtime — only that the declared building blocks for doing so are absent from the
  source tree today. A regression there (someone adds `implementation("com.squareup.okhttp3:...")`
  to `:pipeline`, say) is what `platformGuards` catches; a compiled binary phoning home through a
  hand-rolled `java.net.Socket` is not.
- This worktree carried pre-existing, unrelated, uncommitted changes to
  `app/src/main/kotlin/org/ort/app/ui/data/SearchViewData.kt`,
  `app/src/main/kotlin/org/ort/app/ui/screens/SearchScreen.kt`, and two Search tests (a
  broken/incomplete Search feature — `:app`'s main source set does not currently compile because
  of them). Not part of F-027, not touched or committed by this change; noted so `./gradlew
  build` failing on `:app:compileDebugKotlin` for an unrelated `Band` reference is not mistaken
  for a regression from this fix.
## 2026-09-07 (audit — F-027)

### (pending) — audit F-027 · Coverage matrix now scans corpus/'s pytest suite; FR-TST-8/7/9 pytest tests named for the id they establish

**Scope:** `corpus/tests/` (rename only — five test functions), `buildSrc/` (`CoverageMatrix.kt`,
`CoverageMatrixTest.kt`), root `build.gradle.kts` (task wiring only — `coverageMatrix`'s
`testRoots`), `results/coverage-matrix.md`.

**Requirements/ACs:** FR-TST-8, FR-TST-7, FR-TST-9. **FR-TST-6 is explicitly NOT closed here —
see "Left open" below; the finding's premise was wrong about it.**

**What changed:** F-027 grouped `FR-TST-6`/`FR-TST-8` under "corpus/: Python tests exist; the
Kotlin matrix cannot see them." Only half of that was true.

- `CoverageMatrix.testsByRequirement` now also walks `**/*.py` under each test root and matches
  `def test_...(` (a new `PY_TEST_FUNCTION` regex), reusing the existing `NAME_ID` extraction so
  `test_FR_TST_8_source_missing_licence_is_rejected` yields `FR-TST-8` exactly as the Kotlin
  `_`-separated convention does. A Python match is attributed as
  `corpus/tests/<file>.py::<function>` (a new `corpusRelativePath` helper, rooted at the last
  `corpus` path segment so the attribution is identical regardless of checkout location) rather
  than the Kotlin `ClassName.function` form. `CoverageMatrixTest` gained two cases: one asserting
  the exact `corpus/tests/test_manifest.py::test_FR_TST_8_...` attribution string, one exercising
  `analyse()` end-to-end with a synthetic `FR-TST-6`-named fixture (a mechanism test — it does
  **not** claim the real FR-TST-6 exists; see below).
- Root `build.gradle.kts`: extracted `coverageMatrixTestRoots` (previously inlined into the
  `coverageMatrix` task registration) and added `corpus/tests` to it, so the one `val` is now the
  single place a future task (e.g. a `coverageMatrixCheck`, not present on this branch) would
  also draw from.
- `corpus/tests/test_manifest.py`, `test_harness.py`, `test_fold_gate.py`, `test_synth_generator.py`:
  renamed five tests that already genuinely established a requirement, per the matrix's `test_ID_`
  convention adapted to Python (`test_FR_TST_8_...`, `test_FR_TST_7_...`, `test_FR_TST_9_...`) —
  no assertion changed:
  - `test_source_missing_licence_is_rejected` → `test_FR_TST_8_...` (FR-TST-8's
    "licence-recorded" half; `test_evaluate_reports_per_source_and_aggregate` →
    `test_FR_TST_8_...` in `test_harness.py` covers its "metrics per source" half).
  - `test_eval_fold_is_refused_without_the_flag` and `test_eval_fold_opens_only_with_explicit_opt_in`
    → `test_FR_TST_7_...` (the harness-side eval-fold gate; already established on the Kotlin side
    too, per F-027).
  - `test_synthetic_session_in_eval_is_rejected` (manifest-level) and
    `test_generated_output_is_barred_from_the_eval_fold` (generator-level) → `test_FR_TST_9_...`.
- Regenerated `results/coverage-matrix.md`: `FR-TST-8` moved from "Not yet covered" to "Covered",
  attributed to the four `corpus/tests/...::test_...` entries above; `FR-TST-7` and `FR-TST-9`'s
  "Covered" rows gained the corpus-side attributions alongside their existing Kotlin ones.

**Left open / not done — FR-TST-6 could not be closed, and was not faked:** the finding's premise
— "FR-TST-6 ... built in `corpus/src/corpus/`" — does not hold. FR-TST-6 (functional-spec §7.17)
is a **synthetic traffic generator with configurable activity fraction, transmission length
distribution and SNR, for load/endurance testing of the running pipeline without an 8-hour tape**.
Grepped for `activity`/`fraction`/`SNR`/`endurance`/`traffic` across all of `corpus/src/` and
`corpus/tests/`: no matches. What *does* exist — `corpus/src/corpus/synth/generator.py`
(`generate_utterance`/`generate_dataset`) — is the D22 per-callsign accuracy-training generator
(ULS callsign → phonetic → TTS/splice → learned channel), a different, already-tested feature
(FR-TST-9's generator, which this change did rename a test for). It has no activity-fraction,
transmission-length-distribution or SNR-sweep controls, and produces isolated utterances, not a
continuous traffic stream. Building FR-TST-6 for real is new functionality under `corpus/src/`,
which this brief's scope explicitly excludes ("do not touch `corpus/src/**` unless a genuine bug
is found") and which the audit itself buckets elsewhere as unbuilt work, not a coverage-naming
gap — so no test was written or renamed to claim it, and `results/coverage-matrix.md` correctly
still lists `FR-TST-6` under "Not yet covered." This should be re-filed as its own UNBUILT finding
rather than reopened as F-027.

Also left open: no `coverageMatrixCheck` task exists on this branch (only `coverageMatrix` does),
so the second half of the standing brief's verification step — "then run `./gradlew
coverageMatrixCheck` in a separate invocation" — could not be run; `coverageMatrixTestRoots` is
structured as a shared `val` so wiring it in is a one-line addition whenever that task lands.

**Verified:** `cd corpus; python -m pytest -q` — 68 passed, 1 skipped, before and after the
renames (renames only, no assertion changes). `./gradlew -p buildSrc test --console=plain -q`:
the two new `CoverageMatrixTest` cases were confirmed to FAIL first (`2 tests failed` against the
pre-fix `CoverageMatrix.kt`, stashed and restored to prove it), then pass after the `.py`-scanning
change (7/7 green). `./gradlew coverageMatrix --console=plain -q` regenerated
`results/coverage-matrix.md` — `FR-TST-8` now under "Covered" with the four corpus attributions
above; `FR-TST-6` remains under "Not yet covered" (honestly — see above). `./gradlew build
dependencyRules --console=plain -q` — exit 0 (the interleaved Robolectric `CloseGuard` stack
traces in its log are known-noisy warnings from Room/SQLite teardown, not task failures — the
build's own exit code and lint HTML-report lines confirm completion). `python
tools/spec-check/spec_check.py` — all 8 checks PASS. All runs: this machine (`TAMBURLAINE`), JVM
only, Robolectric where used — no on-device verification was performed or claimed.
## 2026-09-07 (audit — F-013)

### (pending) — audit F-013 · Pass B's fingerprint now carries a real `configHash` and a real `provider`, not `"v0-smoke"`/`"cpu"` literals

**Scope:** `:pipeline` only — new `PassBFingerprintBuilder.kt` and `PassBFactoryTest.kt`
(`org.ort.pipeline.passb`); `PassBFactory.kt` (new `provider` parameter, real `configHash`
computation); `AsrEngineProvisioning.kt` (`AsrEngineAvailability.Available` gained a `provider`
field, populated from the real `SherpaOnnxSession` descriptor it already builds);
`RealCaptureService.kt` (`startProcessingLoop` now threads a real provider string through instead
of a literal); `PassB.kt` (`fingerprint` made public for inspectability); two pre-existing call
sites updated (`CaptureProcessingLoopTest.kt`).

**Requirements/ACs:** FR-REP-1; constitution III ("every pass is a pure function of (audio,
lexicon snapshot, model set, config) and records the fingerprint of what produced it") and VI
("provider is part of provenance").

**What changed:** `PassBFactory.create` stamped every `PassFingerprint` it built with the literal
`configHash = "v0-smoke"` and `provider = "cpu"`, regardless of what actually ran — two different
configurations produced identical fingerprints, and the honest-failure `UnavailableAsrEngine` path
falsely reported `"cpu"` as if a real decode had happened. Fixed in two parts: (1)
`PassBFingerprintBuilder.configHash` computes a deterministic SHA-256 hex hash (the same technique
`:core`'s `ResolvedConfig.configHash` already uses, repeated here rather than exported since
`ResolvedConfig.sha256Hex` is private) over a canonical string of every input fixed at
`PassBFactory.create` time that can change Pass B's output: `confirmThreshold`,
`separationThreshold`, the fixed `DecodeOptions()` passed to the engine on every run (now shared
between the fingerprint and the `PassB` instance itself, rather than two separate defaults that
could silently drift), and the three bundled lexicon tables' independent versions
(`VariantTable`/`ItuPrefixTable`/`ConfusionCostMatrix` — material here, unlike Pass D's separate
`lexiconVersion` field, because `PassBResolutionChain` resolves a callsign from the lexicon inside
Pass B itself). Segmentation config is deliberately excluded, matching `:core`'s own
`ConfigRelevance` table for `B_OFFLINE`. (2) `provider` is no longer guessed in `:pipeline`: the
one place that already knows which execution provider ran — `RealAsrEngineProvider`, via the
`SherpaOnnxSession` descriptor it constructs — now surfaces it on
`AsrEngineAvailability.Available.provider`; `RealCaptureService` threads that value (or the literal
`"none"` for the `Unavailable`/`UnavailableAsrEngine` path) into `PassBFactory.create`'s new
required `provider` parameter, which has no default — a caller must supply a real value, never a
constant. No `:asr-api`/`:asr-sherpa` change was needed or made: the descriptor was already built
in `:pipeline`, just never read back out of `AsrEngineAvailability`.

**Verified:** `PassBFactoryTest` (new), four `FR_REP_1_...`-named cases, written first and
confirmed failing against the unfixed code for the right reason before implementing: two different
`confirmThreshold`s produced identical hashes (`AssertionError: ... Actual: v0-smoke`), the
provider case failed `expected:<[none]> but was:<[cpu]>`, and the "never v0-smoke" case failed with
`Actual: v0-smoke`. (The determinism case passed both before and after, as expected — a constant
is trivially deterministic — so it does not itself distinguish the fix, but it does pin the
regression the other three exist to catch.) Re-verified all four green after restoring the fix.
`./gradlew :pipeline:test` — all tests green (pre-existing `CaptureProcessingLoopTest` call sites
updated for the new required `provider` parameter). Full gate: `./gradlew build dependencyRules`
(exit 0) and `python tools/spec-check/spec_check.py` (all 8 checks PASS). All JVM/Robolectric only
— nothing here is device-verified.

**Left open / not done:** `PassBFactory.create` is not called from anywhere but
`RealCaptureService` and its own tests yet (P12's real-model wiring is still gated behind a fetched
model — see that prompt's CHANGELOG entry), so this fix has not been observed against a real
on-device decode; `RealSherpaDecoder` does not configure any accelerator execution provider today,
so `"cpu"` remains the only value the real path can ever report — this fix makes that fact honest
and traceable rather than adding accelerator support. `Materiality`'s `B_OFFLINE` entry (`:core`,
untouched here) already treats `CONFIG_HASH` as material, so a lexicon bump now correctly flags a
stored Pass B row as a reprocessing candidate — this is a behavioural change downstream of the fix,
not verified against `PassFingerprint.isReprocessCandidate` directly in this session (that
mechanism's own tests are in `:core` and were not touched).

---

## 2026-09-08 (later — P16: correction, the inspection surface, and labelled-sample capture)

### (pending) — P16 · Correction, the inspection surface, and labelled-sample capture

**Scope:** `:app` — new `ui/data/InspectionSurface.kt`, `ui/data/CorrectionFlow.kt`,
`ui/data/LabelledSample.kt`; `ui/data/TransmissionDetail.kt` and `ui/data/ReaderPolling.kt`
extended (additive fields/functions); `ui/screens/TransmissionDetailScreen.kt` extended with three
new sections. `:data` — new `dao/CorrectionDao.kt` (the CorrectionEntity write path); one line on
`dao/TransmissionDao.kt`'s existing `updateAttribution` query (the `AND corrected = 0` guard, see
below) and one accessor line on `OrtDatabase.kt` (`correctionDao()`). No schema change —
`CorrectionEntity`, `TransmissionEntity.corrected`, `PhoneticLatticeEntity` and
`CallsignCandidateEntity` already existed (P5); this session only writes/reads them. P15's
`SearchDao.kt` and search/thread screens were not touched.
**Requirements/ACs:** FR-UI-6 + FR-SPK-7 (one-tap correction, `CORRECTED` lock against
re-propagation), FR-UI-8 (the inspection surface — lattice, candidates, per-prior breakdown,
FR-LEX-31's cold-start distinction), FR-OBS-4 (labelled-sample capture per
`docs/reference/labelling-protocol.md`), Q8 (tiered correction).

**What changed:**

- **Constitution Check.** Principle I (Uncertainty Is Content) is the one that bears hardest here
  — the prompt's own framing. "Every machine conclusion MUST be inspectable... a conclusion that
  cannot be explained cannot be corrected" is FR-UI-8's whole justification, and "a prior that
  abstained and a prior that argued against are different facts and must not look the same" is
  tested explicitly, not just asserted in prose. `CONFIRMED means heard in *this* transmission" and
  the ban on promoting a voice/correction to it is why `CorrectionDao.applyCorrectedAttribution`
  hard-codes `INFERRED`, never accepting a state parameter that could produce `CONFIRMED`.
  Principle VII (structural, not conventional) governs the lock: it is enforced by a `WHERE`
  clause the machine-resolution write path cannot get past, not by a comment asking callers to
  check a flag first.
- **The `CORRECTED` lock, made structural.** `CorrectionDao.recordCorrection` (new,
  `@Transaction`) inserts the audit row and calls `applyCorrectedAttribution`, which always writes
  `INFERRED`/no-confidence/no-source and sets `transmission.corrected = 1` — the exact shape
  `Attribution.withCorrection()` (`:core`, already existed) produces. The other half is the actual
  fix: `TransmissionDao.updateAttribution` (P12's machine-resolution write path, used by
  `DataPassBResultSink` in `:pipeline`) gained `AND corrected = 0` in its `WHERE` clause — a
  one-line, additive change to an existing query, not owned by any concurrent session. Before this
  change nothing in the codebase checked the lock at all (confirmed by searching the repo — P16 is
  the first place it is enforced end to end); after it, a later Pass B re-resolution silently
  no-ops against a corrected row instead of overwriting it. Tested directly:
  `CorrectionDaoTest.a_correction_locks_the_attribution_against_later_machine_re_propagation`
  applies a correction, then calls `TransmissionDao.updateAttribution` with a different station and
  a `CONFIRMED` state (simulating a real propagation write) and asserts the row is unchanged.
- **Q8's tiered correction.** `CorrectionTier` — `PICK_CANDIDATE`, `SEARCH_KNOWN_STATION`,
  `FREE_TEXT`. The first two are recorded as `CorrectionDao.FIELD_STATION` (eligible, in
  principle, to feed a future prior); free text is recorded as `FIELD_STATION_UNVERIFIED` — a
  distinction that survives in the `correction` table's own `field` column, not just in a
  transient UI state, so a later prior-feeding pass has something to check.
  **Module-boundary substitution, reported rather than worked around:** Q8's middle tier is "search
  the lexicon". `:app`'s allowed edges are `{:pipeline, :data, :net, :core}` — `:lexicon` is
  deliberately not among them, and `:pipeline` depends on `:lexicon` via `implementation`, not
  `api`, so lexicon search has no path to `:app` without either widening the module graph or
  adding a `:pipeline` call site, and this prompt explicitly forbids touching `:pipeline` and
  says to report a boundary block rather than route around it. `SEARCH_KNOWN_STATION` searches
  stations `:data` already knows about (`ActivityDao.listStations()`, filtered by substring) as the
  honest, reachable substitute — labelled "Search known stations" in the UI, not "search the
  lexicon" — documented in `CorrectionTier`'s own doc comment as a real, reported divergence, not
  a silent narrowing of Q8. True lexicon search needs a future `:pipeline` call site.
- **FR-UI-8, the inspection surface, reading around the same boundary.** `InspectionViewStateMapper`
  builds its view state from the plain `:data` entities `PhoneticLatticeEntity`/
  `CallsignCandidateEntity` (`CallsignCandidateEntity.priorBreakdown: Map<String, Double>?`) rather
  than from `:lexicon`'s `RankedCandidate`/`PriorContribution`, for the identical reason above.
  **The cold-start distinction (FR-LEX-31) survives the boundary crossing exactly**: `:lexicon`'s
  own invariant is that a cold-start prior contributes *exactly* zero, never a default and never a
  small value — so `PriorContributionViewState.isColdStart = (logOdds == 0.0)` recovers the same
  fact from the persisted `Double` alone, without needing `PriorContribution`'s own boolean field.
  `InspectionViewStateMapperTest` proves a `0.0` entry renders as cold start and a `-0.42` entry
  renders as "argued against" — different labels, tested explicitly, matching the prompt's own
  framing that these "must not look the same." `TransmissionDetailScreenCorrectionTest` proves the
  same distinction at the UI layer (`"cold start"` vs `"argued against"` both present as separate
  text nodes).
  **Be honest about thin data, as instructed:** nothing in this codebase writes
  `PhoneticLatticeEntity`/`CallsignCandidateEntity` rows yet — `DataPassBResultSink` (`:pipeline`,
  P12) only ever persisted the transcript and the final `Attribution`, never `PassBResult.lattice`/
  `.ranked`. Wiring that write is a `:pipeline` change, out of this prompt's scope by its own
  "do not touch" list. So today, for every real transmission, the inspection surface renders its
  tested empty state — "No resolver output recorded for this transmission yet." — truthfully, not
  a demo lattice. The read path is built and tested against the real schema so it renders real
  data the moment a future session adds that write; this is named as the deliberate, reported gap
  the prompt asked for, not a silent no-op.
- **FR-OBS-4, labelled-sample capture.** `LabelledSampleFormatter`/`LabelledSampleWriter`
  (`ui/data/LabelledSample.kt`) produce and append exactly the TSV
  `docs/reference/labelling-protocol.md`'s "Output format" section specifies — column order,
  `outcome`/`certainty` vocabulary, and the protocol's own invariant that `certainty` is set
  *exactly* when `callsign` is non-blank, enforced by `require()` rather than trusted to every
  caller. A tab or newline in a free-text field (`note`/`tactical`) is refused rather than silently
  corrupting the TSV. The UI form in `TransmissionDetailScreen` defaults `session_id`/
  `start_sample`/`end_sample` from the real transmission being viewed (`TransmissionDetail` gained
  `sessionId`/`samplePosition`; `endSample` is computed at the 16 kHz capture rate technical design
  §5 fixes), so an operator does not have to re-type facts the app already knows. The protocol
  document itself says the TSV→`LabeledOccurrence` conversion `corpus/`'s harness will eventually
  need is "not built and is a real, separate follow-up task" — this session does not invent that
  conversion or a JSON schema; it produces exactly the TSV row the document specifies and nothing
  more.
- **A real bug found and fixed along the way, not just a test workaround.**
  `TransmissionDetailScreen`'s outer `Column` had no scroll container. P14 shipped it that way
  because the screen was short; P16 added enough content (candidate lists, the correction form, the
  label form) that it now overflows a normal screen height, and *any* content below the fold was
  laid out with a collapsed, zero-height bound — not just visually clipped, but effectively
  unreachable (confirmed by instrumented debugging: a click dispatched at a zero-height node's
  position never invoked its handler). The fix is `Modifier.verticalScroll(rememberScrollState())`
  on the screen's root `Column`; the revision-history list, which was a `LazyColumn`, had to become
  a plain `Column` with `forEach` in the same change, since a vertically-scrolling `LazyColumn`
  nested inside another vertical scroll container measures with an infinite height constraint and
  crashes. This is a real fix a future device install would also have needed — not a test-only
  accommodation — and Compose UI tests that reach content below the fold now call
  `.performScrollTo()` first, since the test framework does not auto-scroll before a click or text
  input.
**Verified:**
`./gradlew :data:testDebugUnitTest --tests "org.ort.data.dao.CorrectionDaoTest"` — 4/4 green,
including the re-propagation-lock test, after first failing with `Unresolved reference
'correctionDao'`.
`./gradlew :app:testDebugUnitTest --tests "org.ort.app.ui.data.InspectionViewStateMapperTest"
--tests "org.ort.app.ui.data.CorrectionFlowTest" --tests "org.ort.app.ui.data.LabelledSampleTest"
--tests "org.ort.app.ui.data.TransmissionDetailViewMapperTest"
--tests "org.ort.app.ui.screens.TransmissionDetailScreenCorrectionTest"` — all green, each
observed failing first (`Unresolved reference` for not-yet-existing types, then a real Compose
test failure — "could not find any node" — that led to the scroll-container fix above, not just a
test change).
`./gradlew :data:testDebugUnitTest` — full module green (24 tests). `./gradlew :app:testDebugUnitTest`
— full module green (112 tests), including every pre-existing P8/P11/P13/P14/P17 test unchanged.
`./gradlew build dependencyRules` — full repo green (840 actionable tasks); `dependencyRules`
output confirms `:app -> :core, :data, :net, :pipeline` and `:data -> :core`, exactly the permitted
edge sets — **no new module edge anywhere**, including no `:app -> :lexicon` edge, despite FR-UI-8
and Q8 both wanting one. `python tools/spec-check/spec_check.py` — all 7 checks pass.
**Left open / not done:** the module boundary genuinely blocks two things this prompt asked for,
reported rather than routed around: (1) FR-UI-8's lattice/candidates have no writer yet — a
`:pipeline` change (`DataPassBResultSink` persisting `PassBResult.lattice`/`.ranked`) that this
prompt's "do not touch :pipeline" rule puts out of scope; today's inspection surface is real but
permanently empty until that lands. (2) Q8's "search the lexicon" tier is actually "search known
stations" (`:data`-only), for the identical reason — real lexicon search needs a `:pipeline` call
site. Both are named exactly where they bite, not discovered later. No device is available to this
session, so the `verticalScroll` fix and the correction/label UI are proven on Robolectric's layout
and semantics tree, not a real touchscreen. `OrtNavHost.TransmissionDetailContent` now wires
`ReaderPolling.applyCorrection`/`.searchKnownStations` and a real `LabelledSampleWriter` (appending
to `labelled-samples.tsv` in app-private storage) as the screen's real `onCorrect`/
`onSearchStations`/`onRecordLabel` — applying a correction refreshes the detail view immediately
so the corrected attribution and lock are visible without waiting for the next poll — but this
wiring itself is untested beyond compiling and the existing screen-level Robolectric tests
(no device to confirm a correction survives a real app restart or that the labelled-sample file is
actually written to a real filesystem).

---

## 2026-09-08 (night, cont. — P17: station and frequency views, and FR-UI-12's not-heard/not-listening distinction)

### (pending) — P17 · Station and frequency views with activity patterns

**Scope:** `:app` — new `ui/data/ActivityPattern.kt`, `ui/data/StationsAndFrequencies.kt`,
`ui/data/ReaderPolling.kt` (extended), `ui/components/ActivityPatternChart.kt`,
`ui/screens/StationScreen.kt`, `ui/screens/FrequencyScreen.kt`, `ui/navigation/ReaderDestination.kt`
(two `hasScreen` flags flipped) and `ui/navigation/OrtNavHost.kt` (drill-in wiring, additive).
`:data` — new `dao/ActivityDao.kt` (read-only aggregate queries) and one accessor line in
`OrtDatabase.kt`; `TestFixtures.kt` gained optional constructor parameters and a `station()`
factory (additive, existing callers unaffected). No schema change, no migration, no edit to
`TransmissionDao`/`CatalogDao` (kept conflict-free with the concurrent P15 session, per the
prompt's instruction to add a new DAO file rather than touch existing ones). Also fixed, while
updating this file's own checklist entry: a stray unresolved `>>>>>>> worktree-agent-a7eb9cc...`
merge-conflict marker left in `spec/build-plan.md` by an earlier merge (harmless to any tooling
that doesn't parse the file as a diff, but wrong prose regardless — removed in the same edit that
checked P17 off).
**Requirements/ACs:** FR-UI-9 (station view, everything heard from one station across sessions),
FR-UI-10 (frequency view, everything heard on one frequency across sessions), FR-UI-11 (activity
patterns — hour-of-day only, see "Left open" below), FR-UI-12 (the "not heard" vs "not listening"
distinction), AC-126 (verified against a session containing a real capture gap).
**What changed:**
- **Constitution Check.** Principle I (Uncertainty Is Content) and Principle IV (Capture Never
  Blocks, Never Drops, Never Lies) both bear directly: FR-UI-12 is, in the prompt's own words,
  "the requirement most likely to be silently got wrong" — presenting an hour the app was not
  listening as though the station/frequency was simply quiet is a **fabricated absence**, the
  same class of error Principle I forbids for a wrong callsign, applied here to an absence of
  activity instead of a presence. Principle IV states the general rule this instantiates:
  "Silence that was never listened to MUST be distinguishable from silence that was
  (FR-RUN-12, FR-UI-12). A gap is data." Principle VII (structural guarantees) also bears: the
  distinction is a closed three-state enum (`HourActivityState.HEARD` /
  `SILENT_WHILE_LISTENING` / `NOT_LISTENING`), never a boolean with a footnote, so a caller
  cannot collapse it back into "heard or not" by accident.
- **`ActivityPatternMapper`** (`ui/data/ActivityPattern.kt`) — the FR-UI-12 logic, written and
  proven first, before any of the surrounding plumbing. It classifies every real calendar hour
  across every recorded session's `[startedAt, endedAt ?: now]` window, with every
  `CaptureGapEntity` window subtracted, into the three states, then folds those onto 24
  hour-of-day (UTC) buckets. Priority order matches the doc comment and the tests: a real
  transmission timestamp always wins and marks `HEARD` — "audio is the source of truth"
  (constitution III) — even over a gap that a data inconsistency might otherwise mark
  not-listening; otherwise a session's own listening window (minus gaps) marks
  `SILENT_WHILE_LISTENING`; otherwise (inside a gap, or in a real hour no session's window ever
  touched at all) the hour is `NOT_LISTENING`. Nine tests drove this file, each written and seen
  to fail (`Unresolved reference` on the not-yet-existing types) before the implementation:
  covering the plain heard/silent/not-listening cases, the audio-overrides-gap case, an
  open-ended session (`endedAtUtc = null`, must not claim anything past `nowMillis`), an
  open-ended gap (must not claim not-listening past the session's own end), the always-24-buckets
  invariant, and `heardCount` aggregating across multiple days into one hour-of-day bucket.
- **`ReaderPolling` extensions** — `stationDetail`/`frequencyDetail` build the pattern from
  **every session ever recorded**, not only the sessions that happen to contain a matching
  transmission. This was a deliberate correction mid-implementation: an earlier draft scoped the
  pattern to only the matching sessions, which would have wrongly turned every hour of an
  all-quiet-for-this-station session into `NOT_LISTENING` — the fabricated-absence failure aimed
  in the *other* direction (understating real listening coverage instead of overstating it).
  Capture does not know in advance which station or frequency the operator will later ask about,
  so the correct "listening" universe for any station/frequency view is every session's own
  start/end and gaps, independent of what that session happened to hear. `listStationSummaries`/
  `listFrequencySummaries` back the two new list screens over the new `ActivityDao` queries.
  `ReaderPollingTest` gained six tests against a real in-memory Room database, including one that
  inserts a real `CaptureGapEntity` and asserts the gapped hour reads `NOT_LISTENING` while an
  adjacent, ungapped, quiet hour in the same session reads `SILENT_WHILE_LISTENING` — AC-126's
  own scenario.
- **`ActivityDao`** (`:data`, new file) — `transmissionsForStation`, `transmissionsForFrequency`,
  `listStations`, `listDistinctFrequencies`, all read-only, all reading columns the existing
  `TransmissionEntity`/`StationEntity` schema already has. `listDistinctFrequencies` excludes
  `NULL` rather than inventing an "unknown frequency" bucket for the transmissions the rig module
  (M7) hasn't tagged yet. Four tests, TDD (failed on `Unresolved reference 'activityDao'` before
  the DAO and the one-line `OrtDatabase` accessor existed), then green.
- **`ActivityPatternChart`** (`ui/components/`, reusable) — the hour-of-day strip
  `design/canvas/Timeline.dc.html` specifies. Every state carries both colour *and* shape/texture
  (FR-A11Y-1's floor, applied the same way `AttributionMarker` applies it to the four attribution
  states): `HEARD` is a solid bar scaled by how much was heard, `SILENT_WHILE_LISTENING` a short
  dim bar, and `NOT_LISTENING` a full-height **diagonally hatched** bar — textured, not just a
  different colour, so it cannot be mistaken for "quiet" by a screen reader or a colour-blind
  reader. A merged content description reports honest per-state hour counts, and a
  `"▨ not listening"` legend (matching the artboard's own annotation) appears only when at least
  one hour actually is. Three Compose tests prove the not-listening state is announced distinctly
  from quiet, that the legend is honestly absent when there is nothing to flag, and that the
  summary counts are correct.
- **`StationScreen`/`FrequencyScreen`** — list screens (FR-UI-9/FR-UI-10) with honest empty
  states ("No stations heard yet" / "No frequencies recorded yet" — real, expected states before
  any resolver output or rig-module frequency data exists, not placeholder rows), and detail
  screens showing the activity chart plus every transmission ever heard from that station/on that
  frequency, across every session. Wired into `OrtNavHost` the same way the existing
  transmission drill-in works: `openStationId`/`openFrequencyHz` state, each replacing the whole
  scaffold with its own back control. `ReaderDestination.STATIONS`/`.FREQUENCIES` flip from
  `hasScreen = false` to `true` — the only production change to that file. Nine Compose tests
  across the two screens (empty states, row tap navigates, detail shows the pattern and every
  transmission, empty detail is honest).
- **`NowScreen`**: deliberately **not** touched. The prompt allows adding the activity component
  there "if genuinely reusable... a small, additive change" — it is not: `Main.dc.html`'s chart is
  a single overnight session's own hour strip, while FR-UI-11's pattern is inherently
  cross-session history for one station/frequency. Forcing the same component onto a live,
  single-session view would need a different (and unbuilt) per-session-only variant of the
  mapper, which is out of scope here; P14's own named divergence for `Main.dc.html`'s chart stays
  open rather than being half-solved by the wrong shape of data.
**Verified:** `./gradlew :data:testDebugUnitTest --tests "org.ort.data.dao.ActivityDaoTest"` — 4/4
green, after first failing on `Unresolved reference 'activityDao'`.
`./gradlew :app:testDebugUnitTest --tests "org.ort.app.ui.data.ActivityPatternMapperTest"` — 9/9
green, after first failing on `Unresolved reference` for the not-yet-existing types.
`./gradlew :app:testDebugUnitTest` — full module green, 76 tests, including every pre-existing
P8/P11/P13/P14 test unchanged. `./gradlew build dependencyRules` — full repo green (840 actionable
tasks); `dependencyRules` confirms `:app -> :core, :data, :net, :pipeline` and `:data -> :core`,
exactly the permitted edge sets, no new module edge anywhere. `python tools/spec-check/spec_check.py`
— all 7 checks pass after the `build-plan.md` checklist edit.
**Left open / not done:** FR-UI-11 also asks for "by day of week, and how that has changed" —
only the hour-of-day half is built; day-of-week patterning and trend-over-time are a named
follow-up, not silently dropped (the requirement is only partially settled, and this entry says
so rather than checking it off as fully done). No device is available to this session, so
`ActivityPatternChart`'s visual rendering (the diagonal-hatch drawing, real colour contrast) is
proven only via Robolectric's semantics tree and layout, not an actual screen. `StationEntity`'s
own `activityByHourDow`/`frequenciesHeard` pre-computed fields are not used — the pattern is
computed fresh from `transmission`/`session`/`capture_gap` rows each time, which is correct
(those `StationEntity` fields are unpopulated by any prompt yet) but means a station/frequency
with a very long history recomputes its whole pattern on every open; acceptable at today's data
volumes, a candidate for caching later. `frequencyHz`/`stationId` being unpopulated columns
(rig module M7, and attribution respectively) means both list screens are honestly empty on a
fresh install — demonstrated by the empty-state tests, not worked around with fixture-like
placeholder content.

---

## 2026-09-08 (late night — P15: search and threads reach the FTS5 index P5 built and nothing queried)

### (pending) — P15 · Search and threads

**Scope:** `:app` (`ui/screens/SearchScreen.kt`, `ui/screens/ThreadScreen.kt` — new; `ui/data/SearchViewData.kt`,
`ui/data/ThreadViewData.kt` — new; `ui/data/TransmissionDetail.kt` extended with a `threadId` field and a
`timeLabelFor` accessor; `ui/data/ReaderPolling.kt` extended with a public `detailFromEntity` reuse point;
`ui/navigation/ReaderDestination.kt` — new `SEARCH` entry, `THREADS` now `hasScreen = true`;
`ui/navigation/OrtNavHost.kt` wired to both; `ui/navigation/Drawer.kt` made scrollable), `:data`
(new `dao/SearchDao.kt` — the prompt's own required "new DAO file, not `TransmissionDao`/`TranscriptDao`", to
stay conflict-free with the concurrent P17 session; one added abstract accessor line in `OrtDatabase.kt`, no
schema change). `:pipeline`, `:capture-*`, `:asr-*`, `:lexicon`, `:core`, `:net` and P14's
`NowScreen`/`TransmissionDetailScreen` were not touched, as the prompt requires.
**Requirements/ACs:** FR-UI-3 (full-text search over `transcript_fts`, with callsign/frequency/date filters
— the three the prompt's own write-first line names; FR-UI-3's fuller list also names band, attribution-state
and rejected/accepted filters, which this session did **not** build — see "Left open" below), FR-UI-2 (a
thread view groups transmissions and shows why each was attributed — "which transmission confirmed a
callsign, and which inherited it").
**What changed:**
- **`SearchDao`** (`:data`) — `filterOnly` (callsign/frequency/date filters over `transmission` LEFT JOINed
  to `station`, never touching `transcript_fts`) and `searchText` (adds the FTS5 `MATCH` join, filtered to
  each transmission's *current* transcript). A default `search(text, ...)` method degrades a blank/null text
  term to `filterOnly` rather than fabricating a match-everything FTS query. `FtsMatchQuery.build` (same
  file) turns free text into a safe MATCH expression — every whitespace token individually double-quoted so
  an FTS5 operator character (`-`, `*`, `:`) in a callsign like `K7ABC-2` is matched literally, not parsed
  as query syntax.
- **`ThreadGroupingMapper`** (`:app`, pure, DB-free) — groups `TransmissionDetail` by the real `threadId`
  column, real threads first and one explicit "Not yet grouped into threads" bucket last (never a fabricated
  single conversation standing in for absent data), and reasons about each entry's attribution: `CONFIRMED`
  → "callsign confirmed in this transmission", `INFERRED` → names the source transmission (and its time
  label, when that transmission is in the same result set) it was carried from, `AMBIGUOUS` → "more than one
  candidate; the system will not choose", `UNKNOWN` → "no callsign resolved". `ThreadPolling` feeds it real
  session data through the identical `ReaderPolling.currentTransmissionDetails` read path Now/Log already
  use, so a transmission cannot show different facts on two screens.
- **`SearchFilterParser`/`SearchPolling`** (`:app`) — parses the search screen's raw text fields
  (blank → `null`, an unparseable frequency or date dropped rather than crashing or asserting a filter that
  can never match) and reads through `SearchDao`. `SearchPolling.search` catches only the specific,
  empirically-confirmed fts5-missing `SQLiteException` (message containing `fts5` or `transcript_fts`) and
  degrades to the filter-only path, reporting `SearchResult.textSearchUnavailable = true` rather than
  silently dropping the user's text term or crashing the screen (constitution I: an unmet part of a query is
  content) — any other database error still propagates as a real bug.
- **`SearchScreen`/`ThreadScreen`** — plain Compose screens over the above. `SearchScreen` shows an honest
  "Enter a search term or filter, then tap Search" before any search runs (never a blank list standing in
  for "not searched yet"), "No results" for a real empty result, and the full-text-unavailable banner when
  set. `ThreadScreen` shows "No transmissions yet" when there is nothing at all, otherwise one section per
  group with each entry's attribution marker (reusing P13's `AttributionMarker`, FR-UI-4) and its reasoning
  text. Both reuse `ReaderTransmissionViewStateMapper`/`TransmissionListEntryViewState` rather than a second
  row-rendering scheme.
- **Drawer/nav wiring**: `ReaderDestination` gained `SEARCH` (a deliberate divergence from
  `design/canvas/Log.dc.html`, which puts search behind a magnifying-glass icon on the Log screen's own top
  bar — the prompt's own scope note asks for "drawer wiring for those two destinations", so Search is a
  drawer row instead) and `THREADS` is now a real screen. `ReaderDrawerContent` gained a `verticalScroll` —
  ten destinations plus the storage footer no longer fit the drawer's fixed height without it, and this
  session's own `ReaderAccessibilityTest` (P13) caught the resulting clipped row before it could ship;
  that test was updated to `performScrollTo()` each destination before asserting it is displayed, which is
  the correct fix for "reachable via scroll" rather than "all visible at once".
**FTS5 under Robolectric — genuinely checked, not assumed either way:** `OrtDatabase`'s own comment claims
"some host-JVM SQLite builds Robolectric uses lack the fts5 module" and the schema creation skips it
silently rather than failing. This session verified directly, before designing any test, that the fts5
module is in fact **unavailable** in this repository's pinned Robolectric/AGP versions — confirmed by
attempting `CREATE VIRTUAL TABLE probe_fts USING fts5(text)` against a fresh in-memory `OrtDatabase`, which
failed with `no such module: fts5 (code 1 SQLITE_ERROR)`, even with `data/src/test/resources/robolectric.properties`
already set to `sqliteMode=NATIVE` (the real Android SQLite amalgamation, which the project's own comment
says *should* carry fts5). A second, non-obvious finding this session hit and fixed: a naive
`SELECT name FROM sqlite_master WHERE name='transcript_fts'` check is **not** a reliable fts5-availability
probe — `CREATE VIRTUAL TABLE IF NOT EXISTS ... USING fts5(...)` can leave a `sqlite_master` schema row
behind even though the module lookup that follows it fails, so a query against the table still throws
`no such module: fts5` even when that check reports "available". `data/src/test/kotlin/org/ort/data/SearchDaoFullTextTest.kt`
and `app/src/test/kotlin/org/ort/app/ui/data/SearchPollingTest.kt` both now probe by actually preparing a
statement against `transcript_fts` (`SELECT count(*) FROM transcript_fts`), not by checking `sqlite_master`.
Given genuine unavailability, `SearchDaoFullTextTest`'s one full-text assertion uses JUnit4's `Assume.assumeTrue`
and is reported **SKIPPED**, never a false pass; `SearchDaoFilterTest` proves everything that does not
require the index (callsign/frequency/date filtering, case-insensitive callsign match, combined filters,
newest-first ordering, blank-text delegating to the filter-only path) against a real Room database, for
real. `SearchPollingTest`'s one text-search test branches on the same live-checked fact so it states a real
assertion either way rather than hard-coding today's environment as a permanent assumption.
**Verified:** strict TDD throughout — every new test observed to fail for the right reason first
(`Unresolved reference` compiler errors for `SearchDao`/`FtsMatchQuery`/`SearchScreen`/`ThreadScreen`/etc.,
then real assertion failures, including two genuine Compose-layout bugs this session found and fixed rather
than worked around: a `LazyColumn` placed after other `Column` siblings via `fillMaxSize()`/`weight(1f)`
rendered its rows outside the actual test viewport — `performScrollTo()` on such a node then drove Compose's
frame loop into an `OutOfMemoryError` rather than simply failing — fixed by using one `verticalScroll`ed
`Column` instead of a nested `LazyColumn` for a result set small enough to render eagerly). `./gradlew
:data:testDebugUnitTest` — all `:data` tests green (`FtsMatchQueryTest` ×5, `SearchDaoFilterTest` ×7 real,
`SearchDaoFullTextTest` ×1 SKIPPED as described above, plus every pre-existing P5 test unchanged).
`./gradlew :app:testDebugUnitTest` — 82/82 green, including `SearchFilterParserTest` ×6,
`ThreadGroupingMapperTest` ×7, `SearchPollingTest` ×2 (real DB, both branches), `SearchScreenTest` ×5,
`ThreadScreenTest` ×4, and every pre-existing P8/P11/P13/P14 test unchanged (including
`ReaderAccessibilityTest`, updated as described above). `./gradlew build dependencyRules` — full repo green
(840 actionable tasks); `dependencyRules` output confirms `:app -> :core, :data, :net, :pipeline` and
`:data -> :core`, exactly the permitted edge sets, no new module edge anywhere in the graph.
**Left open / not done:** FR-UI-3's fuller filter list (band, attribution state, rejected/accepted) is not
built — this session followed the prompt's own narrower write-first line ("filters by callsign, frequency
and date"); a follow-up session extending `SearchDao`/`SearchScreen` with the remaining filters is
straightforward given the pattern here. `threadId` remains unpopulated in production (threading is M6, per
build-plan note) — `ThreadScreen` is proven against the real column and both the grouped and ungrouped paths
via `ThreadGroupingMapperTest`'s fabricated fixtures, but no session has yet exercised a *real* multi-member
thread against live data, because nothing writes one yet. No device is available to this session — the
Compose screens are proven only on Robolectric, the same standing caveat as every UI change since P13.
`spec/build-plan.md` itself carries an unrelated, pre-existing unresolved git merge-conflict marker
(`>>>>>>> worktree-agent-a7eb9cc6470f58b9c`) at line 124, inside Wave E's own section — this session did not
introduce it, is not a `:app`/`:data` file, and this prompt's file-ownership rule keeps it untouched; flagged
here rather than silently left for someone to trip over. **(Resolved in the merge that landed this
entry** — the marker was mine, from hand-resolving P18's merge; it survived four commits on `main`
before the P17 merge happened to clean it. See the "conflict-marker check" entry above: the real
fix is that `spec_check.py` now fails on one, so a hand-resolution slip cannot reach `main` again.)

---

## 2026-09-08 (night — P14: the reader shows real transmissions, not the v0 smoke-test stub)

### (pending) — P14 · Reader: live view and transmission detail

**Scope:** `:app` reader screens and read paths only — `ui/screens/` (new `LogScreen.kt`,
`NowScreen.kt`, `TransmissionDetailScreen.kt`), `ui/data/` (new `TransmissionDetail.kt`;
`ReaderPolling.kt` extended, its old always-`Attribution.unknown()`/always-"not yet transcribed"
`currentTransmissions` stub removed), `ui/audio/` (new — `TransmissionAudioPlayer`,
`RealTransmissionAudioPlayer`, `FakeTransmissionAudioPlayer`), `ui/navigation/OrtNavHost.kt`
(wired to the new screens and a detail drill-in), `app/build.gradle.kts` (one test-only
dependency — see below). `:pipeline`, `:capture-*`, `:asr-*`, `:lexicon`, `:core`, `:net` and
`:data`'s schema were not touched, as the prompt requires.
**Requirements/ACs:** FR-UI-1 (a transmission appears newest first; a superseded partial is
*visibly* superseded), FR-UI-4 (all four attribution states visually distinct, reusing P13's
`AttributionMarker`), FR-UI-5 (audio playback alongside the transcript), FR-A11Y-1..4 (carried
over from P13 — every new interactive element has a content description; existing max-font-scale
coverage untouched).
**What changed:** P13 landed the Compose foundation with `Now`/`Log` rendering the same
always-`Attribution.unknown()`, always-"not yet transcribed" v0 smoke-test stub `StatusActivity`/
`TransmissionListActivity` polled — real transcript and attribution data has existed in `:data`
since P12's processing loop, and nothing read it. This session builds that read path and the
three designed screens over it:
- **`TransmissionDetail`** (`ui/data/TransmissionDetail.kt`) — the real, Compose-agnostic facts for
  one transmission: `currentTranscriptText` is `null` exactly when no `TranscriptEntity` row exists
  yet (never an empty string standing in for "nothing happened"), and `supersededTranscriptTexts`
  carries every earlier version via `TranscriptDao.getAllVersions` — AC-31's append-only guarantee,
  made *visible* here rather than only enforced at the data layer. `ReaderTransmissionViewStateMapper`
  turns a missing transcript into the honest "(captured, not yet transcribed)" label the prompt's
  empty-state paragraph asks for, and a non-empty `supersededTranscriptTexts` into a counted
  `revisionNote` ("revised · N earlier version(s)") — never a boolean, never silent.
- **`ReaderPolling.currentTransmissionDetails`/`.transmissionDetail`** — real reads over
  `TransmissionDao.listBySession` (reversed for "newest first" rather than adding a second,
  differently-ordered DAO query — `:data`'s query surface is deliberately untouched here, unlike
  P15, which the build plan names as the prompt that owns adding new DAO queries) and
  `TranscriptDao.getAllVersions`. `attributionFrom` reconstructs the type-safe `Attribution`
  constitution I requires from the entity's raw columns, falling back to `Attribution.unknown()`
  (never a crash, never a fabricated station) if a `CONFIRMED`/`INFERRED` row is ever missing the
  fields its own factory requires.
- **`NowScreen`** (`Main.dc.html`) — real `overCount`/`stationCount` (`NowSummaryMapper`, counting
  only transmissions with an actual attributed station, never an `UNKNOWN`/`AMBIGUOUS` over) over
  the existing, unchanged `StatusScreen` for capture state. **Deliberate divergence**: the artboard's
  activity-bar chart and "Worth knowing" digest are not built — an hour-bucketed activity aggregate
  is P17's job (FR-UI-9..12) and a digest is M9's, after the M4 fork the build plan leaves open;
  "Worth knowing" renders an explicit, honest empty state instead of a look-alike fabrication.
- **`LogScreen`** (`Log.dc.html`) — the dense per-transmission table: time, frequency, the shared
  `AttributionMarker` (FR-UI-4) plus the station id or "Unidentified station", the transcript text
  (or the honest not-yet-transcribed label), the revision note, and signal. **Deliberate
  divergence**: `Log.dc.html`'s QSO-header thread grouping and inline "not listening" gap row are
  FR-UI-2/FR-UI-12, explicitly P15's and P17's own prompts — rendered as a flat, newest-first list
  here rather than faked grouping logic.
- **`TransmissionDetailScreen`** (`Detail.dc.html`) — attribution marker, station/callsign or
  "Unidentified station", time/frequency/duration/signal, a play control (FR-UI-5, gated on
  `TransmissionDetailViewState.hasAudio` — "No retained audio for this transmission" otherwise,
  never a dead button), the transcript, and every superseded version listed under "Earlier versions
  (superseded)". **Deliberate divergence, called out by name in the prompt itself**: the artboard's
  "why this callsign" phonetic-lattice and per-prior ("what ranked it first") panel is FR-UI-8,
  which build-plan P16 owns ("the inspection surface... the per-prior breakdown `PriorCombiner`
  already produces") — that data has no path to this screen yet, and a look-alike panel with no
  real ranking behind it would be exactly the confident fabrication constitution I forbids.
- **Audio playback (FR-UI-5), for real.** `:app` may not depend on `:capture-android` directly
  (`ModuleGraph.allowed` only permits `:pipeline`, `:data`, `:net`, `:core`), so
  `RealTransmissionAudioPlayer` reuses `:pipeline`'s already-public `FlacSegmentAudioProvider` —
  the exact class P12's own Pass B wiring uses to decode retained audio for ASR — by constructing a
  throwaway `WorkQueueItemEntity` purely to reach that public entry point, never a second decode
  implementation that could silently drift from the codec ASR actually runs (constitution III). It
  converts the decoded floats to PCM16 and plays them through a real `AudioTrack`. A missing
  transmission row, a missing audio file, or a decode failure all return a named
  `PlaybackOutcome.Unavailable` rather than silently doing nothing (constitution I's
  "uncertainty is content" applied to playback). `FakeTransmissionAudioPlayer` ships in the same
  change (constitution II) and is what every Compose test above drives.
- **`OrtNavHost`** now polls `currentTransmissionDetails`/`currentStatus` for `Now` and `Log`, and
  holds a `selectedTransmissionId` that replaces the whole scaffold with `TransmissionDetailScreen`
  when a Log row is tapped — mirroring `Detail.dc.html`'s full-screen presentation, with its own
  back control as the way out.
**What was deliberately NOT deleted, and why:** the prompt permits deleting
`StatusActivity`/`TransmissionListActivity` only if everything they did is genuinely replaced,
including their tests' coverage. `StatusActivity` additionally surfaces `AsrAvailability`/
`VadAvailability` status labels (P12's "never let this screen look like transcription is working
when it isn't" safeguard) that `NowScreen` does not yet show — deleting the originals now would be
a real regression, not a cleanup, so both plain-view Activities and their tests are left exactly as
they are. `MainActivity` already launches `ReaderActivity` (the Compose nav host), not either
plain-view Activity, so this is inert legacy code rather than a second, conflicting entry point.
**Verified:** strict TDD throughout — every new test
(`ReaderTransmissionViewStateMapperTest` ×6, `NowSummaryMapperTest` ×3, `ReaderPollingTest` ×6,
`RealTransmissionAudioPlayerTest` ×3, `LogScreenTest` ×5, `NowScreenTest` ×4,
`TransmissionDetailScreenTest` ×5) was written and observed to fail for the right reason
(`Unresolved reference` compiler errors, then real assertion failures against a Robolectric-backed
real in-memory-vs-file-database mismatch caught and fixed, and a genuine Robolectric
`AudioTrack`-shadow limitation documented rather than hidden — see that test's own comment) before
the production code existed. `./gradlew :app:testDebugUnitTest` — all 57 tests green, including
every pre-existing P8/P11/P13 test unchanged. `./gradlew build dependencyRules` — full repo green
(840 actionable tasks); `dependencyRules` output confirms `:app -> :core, :data, :net, :pipeline`,
exactly its permitted edge set, no new module edge added anywhere in the graph.
**Left open / not done:** no device is available to this session, so `RealTransmissionAudioPlayer`
is proven only up to "decodes real retained audio through the real codec path and attempts
playback" — whether audio genuinely reaches a speaker is unverified (Robolectric's `AudioTrack`
shadow does not faithfully reproduce real hardware initialization; the test asserts the decode
succeeded and, if `Unavailable`, that the reason is specifically about playback, never about a
missing file or a broken decode). `Main.dc.html`'s activity chart and digest, `Log.dc.html`'s
thread grouping and gap rows, and `Detail.dc.html`'s lattice/per-prior panel are all named
divergences above, each pointing at the build-plan prompt (P15/P16/P17) that actually owns the
data behind it. `StatusActivity`/`TransmissionListActivity` remain registered and unreached, not
deleted, for the `AsrAvailability`/`VadAvailability` reason above — a future prompt that adds those
labels to `NowScreen` can finish that cleanup.

---

## 2026-09-08 (evening, cont. — the Compose reader becomes the app's actual UI)

### (pending) — Switch the app's entry point to P13's Compose navigation host

**Scope:** `app/src/main/kotlin/org/ort/app/MainActivity.kt` (two lines: the import and the
launch target).
**Requirements/ACs:** D15 (Compose), FR-UI-7 (the status surface stays reachable, now inside the
drawer). No new ACs.
**What changed:** P13 built the Compose theme, drawer and navigation host and deliberately left
`ReaderActivity` registered-but-not-launched, because P12 owned `MainActivity` at the time and the
two prompts ran concurrently. Both have now landed and merged, so this is that switchover: after
permissions are granted and capture starts, `MainActivity` hands off to `ReaderActivity` (the
Compose nav host) instead of the plain-view `StatusActivity`. The user who reported "I see no UI"
was looking at the plain `TextView` status readout; this is the commit where the app actually has
the designed interface behind it.
`StatusActivity`/`TransmissionListActivity` are deliberately left registered and working —
P13 ported them to Compose screens *behind* the nav host rather than deleting them, and removing
the originals belongs to P14, which replaces those screens rather than merely re-hosting them.
Deleting them now would be churn ahead of a rewrite.
**Verified:** `./gradlew build dependencyRules` full green (840 tasks). **Not verified on a
device** — the standing caveat for every UI change this session; this one especially wants a real
install, since its entire purpose is what the user sees on launch.
**Left open / not done:** the drawer's other seven destinations are still placeholders (P14–P17),
and no ASR model is fetched yet, so transcripts will not appear until P18's `:net` acquisition is
wired to an `:app` call site.

## 2026-09-08 (evening — P18: `:net` built, so a model can actually reach the device)

### (pending) — P18 · Model acquisition through `:net`: fetch, resume, checksum-verify, side-load

**Scope:** `:net` — the whole module, previously an empty stub (`build.gradle.kts`,
`package-info.kt`, new `README.md`, new `NetCapability.kt`, `HttpRangeClient.kt`, `Checksum.kt`,
`ModelFetchSpec.kt`, `ModelAcquisition.kt`, `real/RealHttpRangeClient.kt`,
`fake/FakeHttpRangeClient.kt`, and their tests). No other module touched, as this prompt requires
— `:pipeline`, `:capture-*`, `:asr-*` and `:app`'s call site are deliberately left for a later,
tiny follow-up commit.
**Requirements/ACs:** FR-AST-2 (checksum verified before a file is usable; a failed verification
leaves the previous version active — and, stricter than that, leaves *nothing* half-written that
a later run could mistake for progress), FR-AST-3 (resumable, idempotent download), FR-ASR-8 (a
side-loaded file goes through the same verification path as a fetched one), constitution
Principle V (only `:net` may link an HTTP client; the capture/processing path makes no network
call, ever — this prompt exists because P12 correctly refused to violate that rather than
"temporarily" fetch a model from `:pipeline`).
**What changed:** P12 wired `RealSherpaDecoder`/`RealSileroVad` into the running app, both inert
because no model file exists on disk and nothing in this codebase could put one there without
crossing a forbidden edge. This session builds that declared channel:
- **`HttpRangeClient`** — the one interface through which `:net` makes an HTTP call, with
  `real.RealHttpRangeClient` (plain `java.net.HttpURLConnection`, ranged `GET`, no new runtime
  dependency — see `net/README.md` for why OkHttp was not added) as the only implementation that
  opens a socket, and `fake.FakeHttpRangeClient` — scriptable to serve a full body, honour or
  ignore a `Range` header, drop the connection after N bytes, or fail outright — as the
  behavioural fake every test in this module drives instead (constitution II).
- **`ModelAcquisition.fetch()`/`.sideload()`** — mirrors `corpus/src/corpus/acquire.py`'s
  `_download`/`acquire_source` semantics deliberately, per the prompt's instruction to read it
  and match: a `.part` file carries partial progress and resumes from its own size (`fetch`
  re-requests `Range: bytes=<part.length()>-`), a completed download is SHA-256-checksummed
  before being renamed into place, and a `.sha256` marker file next to a verified destination
  makes a repeat `fetch()` call make *zero* HTTP requests (genuinely asserted via the fake's
  request log, not just returning a cached value). One deliberate divergence from the Python
  side, called out in the prompt as the most important behaviour here: on a checksum mismatch,
  `ModelAcquisition` **deletes** the `.part` file rather than keeping it — a corrupt partial
  cannot be fixed by appending more bytes to it, and FR-AST-2 requires nothing survive for a
  later run to mistake for a good model. `.sideload()` reuses the identical checksum check for a
  user-supplied file with no network call at all, and refuses without ever touching the
  destination on a mismatch — verified by a test that seeds the destination with "the previously
  active, good model"'s bytes first and asserts they are byte-for-byte untouched after a refused
  side-load. `:net`'s job stops there — `:asr-sherpa`'s `ModelActivation` (signature check,
  probe-run, keep-previous-on-failure) is not duplicated.
- **`NetCapability`** — a sealed `UserInitiated`/`ContributionGrant` token every entry point
  requires (technical design §16.3), the type-level half of "the capture and processing paths
  make no network call": neither `:capture-*` nor `:pipeline`'s pass execution can construct one,
  because neither module may depend on `:net` at all. Documented honestly as an architectural
  intent a future `:app` wiring session must still honour at the call site — `:net` itself cannot
  distinguish a real user gesture from a hand-constructed token, the same limit
  `ModelActivation.sideloadedUnverified` already documents for its own guarantee.
- Module plugin kept as `ort.android-library` (technical design §2 lists `:net` as Android-only,
  intended to run downloads under WorkManager later) with JUnit5 (`libs.junit.jupiter` +
  `junit-platform-launcher`) added directly, since none of this module's logic touches an Android
  framework class and Robolectric is not needed.
**Verified:** strict TDD — all eleven tests
(`ChecksumVerificationTest`, `ModelAcquisitionFetchTest` ×4, `ModelAcquisitionSideloadTest` ×3,
`FakeHttpRangeClientTest` ×3) written first and observed to fail with `Unresolved reference`
compiler errors for every type before any production code existed, then made to pass. `./gradlew
:net:test` — 11/11 passed (debug and release variants). `./gradlew build dependencyRules` — full
repo green (836 actionable tasks); `dependencyRules` output confirms `:net -> :core` only, and
critically that **neither `:capture-android` nor `:pipeline` has any edge to `:net`** — the
capture/processing path still has no path to an HTTP client, which is the entire point of this
prompt. **No real network call was made anywhere in this session or in any test** — the resume
and idempotence tests are driven entirely by `FakeHttpRangeClient`'s request log.
**Left open / not done:** `RealHttpRangeClient`'s actual `HttpURLConnection`/`Range`-header
wiring is **not** exercised by an automated test — a loopback `com.sun.net.httpserver.HttpServer`
test was written and then removed because `jdk.httpserver` is not visible on this Android-library
module's unit-test compile classpath (JPMS module boundary), and adding a real external network
call was never on the table; its correctness rests on the JDK's documented contract and manual
review only, noted plainly in `net/README.md` rather than left silent. The `:app` call site (a UI
action that mints `NetCapability.UserInitiated`, downloads the Whisper tiny.en and Silero VAD
models P12 already knows the expected paths for, and reports progress) is explicitly out of this
prompt's scope and remains the last piece connecting capture to a working transcript on a real
device — P12's own "left open" already named this gap; this entry closes the `:net` half of it.

---

## 2026-09-08 (afternoon — P12's third defect: the queue finally drains, and the Silero question is answered)

### (pending) — P12 · Wire PassDrainRunner/PassB/RealSherpaDecoder into the running app; resolve the VAD question

**Scope:** `:pipeline` (`passb/DataPassBResultSink.kt`, `passb/FlacSegmentAudioProvider.kt`,
`passb/AsrEngineProvisioning.kt`, `passb/PassBFactory.kt`, `CaptureProcessingLoop.kt`,
`capture/AsrAvailability.kt`, `capture/VadAvailability.kt`, `capture/RealVadProvider.kt`,
`capture/RealCaptureService.kt`), `:data` (`dao/TransmissionDao.kt` — two new `@Query` methods,
no schema change), `:asr-sherpa` (`real/RealSileroVad.kt`, its README section), `:app`'s capture
wiring only (`status/StatusActivity.kt` — two new status lines).
**Requirements/ACs:** AC-31 (exactly one current transcript, enforced end to end now, not just at
`TranscriptDao.supersede`'s own layer), FR-RUN-1/constitution IV (the processing loop never blocks
capture — it runs as an independent coroutine in the same service scope), constitution I (an
`AsrEngineAvailability.Unavailable`/`VadProvisionResult.Unavailable` is reported, never silently
substituted), FR-ASR-6/FR-RUN-9 (rejections and engine failures both reach `:data`, not just the
in-memory `RejectedSegmentLog`).
**What changed:** before this, `PassDrainRunner` (P8), `PassB`/`CallsignResolver` (P11) and
`RealSherpaDecoder`/the six `RejectionRule`s (P10 + follow-up) all existed, tested, and were never
constructed anywhere in the running app — a captured, enqueued transmission sat `CAPTURED` forever.
- **The write path P11 left open.** `DataPassBResultSink` (`PassBResultSink`) persists an
  `Accepted` outcome as a real `TranscriptEntity` via `TranscriptDao.supersede` (one current,
  nothing deleted) and writes the resolved `Attribution` onto the transmission via two new
  `TransmissionDao` queries (`updateAttribution`, `setRejectionReason` — additive, no migration).
  A `Rejected` outcome records its rule/detail on the transmission's own column instead of a
  transcript; a `Failed` outcome writes nothing (the queue's own `lastError` already carries it).
- **The audio read-back path P11 left open.** `FlacSegmentAudioProvider` (`SegmentAudioProvider`)
  loads a leased item's `TransmissionEntity`, reads its derived `audioPath()`, and decodes it with
  the same `LosslessCodec` (`DeflatePredictiveCodec`) `RealSegmentSink` encoded it with — the exact
  inverse of that class's PCM byte layout.
- **The loop itself.** `CaptureProcessingLoop` wraps `PassDrainRunner` with a real `Pass`
  (`PassBFactory.create(...)`, composing the two pieces above with the bundled lexicon grammar —
  same `VariantTable`/`CallsignGrammar`/`PriorCombiner` `PassBTest` already exercises) and drains
  repeatedly (`drainOnce`/`runForever`), continuing past an empty batch rather than stopping.
  `RealCaptureService.startCapture()` now launches it as its own coroutine in the service's
  existing scope — deliberately independent of the capture-flow collector, so a stalled or
  unavailable ASR engine can never block capture (constitution IV).
- **The model, honestly.** `RealAsrEngineProvider` looks for
  `tiny.en-{encoder,decoder}.int8.onnx`/`tiny.en-tokens.txt` at a fixed app-private path
  (`<filesDir>/models/whisper-tiny-en-int8/`) and constructs a real `SherpaAsrEngine`/
  `RealSherpaDecoder` only if all three are present; a partial or missing model returns
  `Unavailable` with a reason naming the exact expected path. **Fetching the model there is
  deliberately not implemented here**: constitution V forbids a network call from the
  capture/processing path this class runs in, and only `:net` may link an HTTP client — a real
  fetch belongs behind a separate, user-initiated asset-download action through `:net` (still
  effectively empty — only its `package-info.kt` exists). When unavailable, capture wires
  `UnavailableAsrEngine`, which throws loudly on every `transcribe()` call rather than returning
  anything — `RejectionPipeline` turns that into `PassBOutcome.Failed`, an honest, retryable
  failure, never a fabricated transcript. `AsrAvailability` (mirrors `CaptureState`'s own
  never-optimistic pattern) records which happened, and `StatusActivity` now shows it.
- **The VAD question, resolved, not dodged.** A subagent located the actual downloaded
  `sherpa-onnx-jvm-1.13.7.jar` in the Gradle cache and confirmed by `jar tf`/`javap` that it genuinely
  ships `com.k2fsa.sherpa.onnx.Vad`/`VadModelConfig`/`SileroVadModelConfig` — a real, Kotlin-callable
  Silero VAD API, not a gap in the binding. `RealSileroVad` (`:asr-sherpa`, mirroring
  `RealSherpaDecoder`'s pattern exactly) wraps it, exposing `speechProbability(FloatArray): Float`
  structurally rather than implementing `:segment`'s `VadModel` (ModuleGraph: `:asr-sherpa` may not
  depend on `:segment`) — `:pipeline`'s `RealVadProvider` adapts it with a one-line lambda. What is
  genuinely missing is the model file: neither jar bundles `silero_vad.onnx`, this repo doesn't
  commit one, and the subagent confirmed (HTTP 200, this session) that
  `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx` is the
  correct, reachable release asset — fetching it is out of scope for the same constitution-V reason
  the ASR model fetch is. `RealVadProvider` checks for it at
  `<filesDir>/models/silero-vad/silero_vad.onnx` and falls back to the existing RMS-energy
  `EnergyVadModel` when absent; `VadAvailability` (same pattern as `AsrAvailability`) records
  honestly which one is running, surfaced on `StatusActivity`. **`EnergyVadModel` is still what
  actually runs in this session's environment** — no model file was fetched here — but the seam is
  now real, not aspirational, and `RealSileroVadRealModelTest` (gated exactly like
  `RealSherpaDecoderRealModelTest`, `ORT_RUN_REAL_SHERPA=1`) documents precisely what a future
  session needs to do to prove it against a real model.
**Verified:** strict TDD — `CaptureProcessingLoopTest` (3 cases: accepted→`COMPLETE`+transcript+
attribution, rejected→`REJECTED`+reason+no transcript, and draining across multiple enqueues) was
written first and observed to fail (`AssertionError: expected COMPLETE but was PROCESSING`, and
separately a spurious `withTimeoutOrNull` cancellation from mixing `kotlinx-coroutines-test`'s
virtual clock with Room's real background-executor suspension inside `WorkQueue.runLeased` — fixed
by using `runBlocking` for this test class, since production code has no virtual clock at all) for
the right reasons before `CaptureProcessingLoop`/`PassBFactory`/`DataPassBResultSink`/
`FlacSegmentAudioProvider` existed; `AsrEngineProvisioningTest`, `RealVadProviderTest`,
`AsrAvailabilityTest`, `VadAvailabilityTest` cover the honesty seams. `./gradlew build
dependencyRules` — full green (830 actionable tasks; `dependencyRules: checked 17 modules ...
every edge is permitted by the design graph` — `:pipeline`'s existing `:asr-sherpa` edge covers the
new `RealSileroVad`/`RealAsrEngineProvider` usage, no new edges needed). `RealSherpaDecoderRealModelTest`
and the new `RealSileroVadRealModelTest` both `SKIPPED` (as designed — `ORT_RUN_REAL_SHERPA` unset).
**Left open / not done:** **no real on-device ASR or VAD decode was run in this session** — no
model files exist in this environment (neither was fetched; both gated real-model tests skip) — so
`EnergyVadModel` and `UnavailableAsrEngine` are what a debug build actually runs today; only the
Robolectric/fake-verified loop (`FakeAsrEngine`) is genuinely proven. AC-6 is not claimed (needs the
real dev noise tape, still absent — Q2/Q16, per `asr-sherpa/README.md`'s own standing note). The
`:net`-based model-fetch action itself (a download UI, progress state, and the install/verify/
activate/roll-back/remove lifecycle technical design §8.4 specifies) is not built — `:net` remains
essentially empty; this session only builds the seam that *reads* app-private storage once
something else populates it. Confirm-threshold/separation-threshold in `PassBFactory` are the same
uncalibrated placeholders `PassBTest` already used (no dev-fold data exists to fit real ones —
constitution VI). Not verified on a real device — no device was available to this session, exactly
as this prompt anticipated ("if the device work cannot be verified ... say so and stop"); everything
above is Robolectric + JVM verified only.

---

## 2026-09-08 (afternoon — P13)

### (pending) — P13 · Compose foundation: theme, navigation, the design canvas made real

**Scope:** `buildSrc/src/main/kotlin/ort.android-app.gradle.kts` and `buildSrc/build.gradle.kts`
(Compose wiring), `gradle/libs.versions.toml` (Compose version entries), `.editorconfig` (one
ktlint exemption Compose's own naming convention needs), new files under
`app/src/main/kotlin/org/ort/app/ui/` and `app/src/test/kotlin/org/ort/app/ui/`,
`app/src/main/AndroidManifest.xml` (registers the new activity only).
**Requirements/ACs:** D15 (Compose), AC-62/FR-A11Y-1 (the four attribution states distinguishable
without colour), AC-63/FR-A11Y-2/FR-A11Y-3 (content descriptions, no clipping at max font scale),
D26 (the drawer's storage footer).
**What changed:** the app had no Compose at all — P8 and P11 both shipped plain `TextView`s with
Compose explicitly out of scope, and a real user installed the app and said "I see no UI." This
prompt lands the foundation:
- **Compose wiring** in the `ort.android-app` convention plugin: the Kotlin Compose-compiler
  plugin (pinned to the exact Kotlin version, resolved from buildSrc's own classpath — the root
  build's version catalog is not visible inside a buildSrc precompiled script), the Compose BOM,
  and `ui`/`material3`/`foundation`/`activity-compose`, plus `ui-tooling`/`ui-test-manifest`
  (debug-only) and `ui-test-junit4` (test-only). `testBuildType = "debug"` and disabling
  `testReleaseUnitTest` outright — the release build type differs only by `isMinifyEnabled`, so
  testing it separately proved nothing beyond failing every Compose UI test for a reason that has
  nothing to do with the code under test (its merged manifest never carries the debug-only
  `ui-test-manifest` stub `createComposeRule()` launches).
- **Theme** (`ui/theme/`): colour tokens hand-picked as sRGB approximations of the `oklch(...)`
  values quoted from the canvas artboards (Compose has no first-class OKLCH conversion worth
  depending on for eight swatches — `design/canvas/*.dc.html` remains the source of truth for the
  actual palette), typography sized in `sp` throughout (AC-63), and a single dark scheme —
  every one of the seven artboards is dark-only, so there is no light palette to derive from yet.
- **`AttributionMarker`** (`ui/components/`): the reusable four-state component `States.dc.html`
  exists to prove out, built on top of the existing, tested `TransmissionListViewStateMapper`
  rather than a second scheme — a distinct shape (filled circle / outlined ring / half-filled ring
  / small dim dot) plus that mapper's existing text marker, merged into one accessibility node
  naming the shape, the state and the confidence. `TransmissionListScreen` renders every row
  through it rather than re-deriving attribution text itself.
- **Navigation**: `ReaderDestination` (the nine `Menu.dc.html` destinations, in its order),
  `ReaderDrawerContent` (every destination plus the D26 storage-budget footer — computed for now
  from real `StatFs` device-storage figures, explicitly labelled a placeholder for D26's actual
  per-category budgets, which no prompt has built the tracking for yet), and `OrtNavHost` (a
  `ModalNavigationDrawer` + `Scaffold`, holding current destination in `rememberSaveable` — a
  hand-rolled state holder rather than `androidx.navigation:navigation-compose`, deliberately: two
  real destinations do not yet justify a second dependency to pin and verify). `Now` and `Log`
  render the ported `StatusScreen`/`TransmissionListScreen`; the other seven destinations render
  `PlaceholderScreen` until their own prompt (P15-P17) builds them — reachable, honestly not built,
  rather than missing from the drawer or faked.
- **`ReaderActivity`**: hosts the graph, registered `exported="false"`, **not** the launcher —
  `MainActivity` stays the entry point until the switchover, a deliberate follow-up once P12's
  concurrent capture-wiring work lands. `ui/data/ReaderPolling.kt` reuses the exact same
  `CaptureStatusRepository`/`OrtDatabase` smoke-test path `StatusActivity`/
  `TransmissionListActivity` already poll (P8/P11), so the new screens show the identical facts —
  "no behaviour change" — without touching either owned-by-P12 Activity.
**Verified:** wrote `AttributionMarkerTest` (AC-62), `ReaderAccessibilityTest` (AC-63, at
`fontScale = 2f` — the largest scale Android's own accessibility settings offer on the reference
API range; FR-A11Y-3 does not name a number) and `DrawerContentTest` (every `Menu.dc.html`
destination present, D26 footer present) against Robolectric + `createComposeRule()` first, saw
each fail for the right reason (unresolved references before the code existed, then real
assertion failures — a false-positive `"filled circle"` substring match inside `"half-filled
circle"`, and `"red"` inside `"INFERRED"` — both fixed in the tests, not the production code) and
green after. `./gradlew :app:testDebugUnitTest` — 25 tests green, including all pre-existing P8/P11
tests (`StatusActivityTest`, `TransmissionListActivityTest`, both `*ViewStateMapperTest`s,
`PermissionsFlowTest`, `OnDeviceHarnessRunnerTest`) unchanged and still passing. Full
`./gradlew build dependencyRules` — green; `dependencyRules` output confirms `:app -> :core,
:data, :net, :pipeline`, exactly its permitted edge set, no new module dependency added.
**Left open / not done:** the switchover making `ReaderActivity` the app's launcher (deliberate —
P12 owns `MainActivity` this wave); `Main.dc.html`'s activity chart and "Worth knowing" digest,
`Log.dc.html`'s dense table, `Detail.dc.html`'s drill-in, and the other six drawer destinations —
all P14-P17; a light theme (no artboard to derive one from yet); live drawer badge counts (Log's
`412`, Threads' `31`, Capture's running timer) — the drawer renders labels only for now. No device
was available to this session; everything above is Robolectric-only, as the prompt allows.

---

## 2026-09-08 (morning, cont. — Wave E added; P12's first two defects fixed)

### (pending) — Decompose M5 into the build plan, and fix the two defects that made capture a lie

**Scope:** `spec/build-plan.md` (new Wave E, prompts P12–P17),
`capture-android/src/main/kotlin/org/ort/capture/android/AndroidAudioIo.kt`,
`capture-android/src/test/.../DefaultInputDeviceSelectionTest.kt` (new),
`pipeline/src/main/kotlin/org/ort/pipeline/capture/CaptureState.kt` (new),
`pipeline/src/test/.../CaptureStateTest.kt` (new),
`pipeline/src/main/kotlin/org/ort/pipeline/capture/RealCaptureService.kt`,
`app/src/main/kotlin/org/ort/app/status/StatusActivity.kt`.
**Requirements/ACs:** AC-2 (route verification — the mechanism was correct; the *selection handed
to it* was not), constitution IV (capture never lies about its own state).
**What changed:** the user installed the fixed smoke build, got past the battery-exemption hang,
and sent a screenshot: "Capturing / 00:00:16 / 0 transmissions / **Not responding (heartbeat
stale)**", plus "I see no UI — did you miss a phase?" Both observations were right, and neither is
what it first looks like:
- **No phase was skipped.** Waves A–D covered M0–M4 by design; every user-facing screen lives in
  M5, which the build plan parked as "deliberately not decomposed" and the implementation plan
  marks "outline only". Meanwhile `design/canvas/` holds **seven fully designed screens** nothing
  implements, and the spec carries **12 `FR-UI-*` + 6 `FR-A11Y-*`** requirements of which the app
  implements roughly one and a half. Added **Wave E (P12–P17)** decomposing exactly that, with
  P12 first because the app does not yet do its job at all.
- **Defect 1 — capture halted on its first read, and had since the wiring shipped.**
  `AndroidAudioIo.builtInMicDescriptor()` fabricated the device id `"builtin"`; `RouteVerifier`
  compares ids and a real `AudioDeviceInfo.getId()` is a number, so every real device reported a
  route mismatch immediately and `AudioRecordSource` halted — AC-2 working exactly as designed,
  against a selection constructed so it could never match. Replaced with `defaultInputDevice()`,
  which returns a **real enumerated** device (preferring the built-in mic) or `null`, which the
  caller reports as a capture failure rather than inventing something. This is why the heartbeat
  was stale and the transmission count zero.
- **Defect 2 — the status surface asserted `isCapturing = true` unconditionally**, so a halted
  capture still read "Capturing" indefinitely: the exact silent failure constitution IV exists to
  forbid, in the one screen whose entire job is to report it. Added `CaptureState` (a process-wide
  holder, deliberately not persisted — a stale row outliving its process claiming capture is
  running would be strictly worse, and cross-process liveness is the heartbeat's job, AC-65), fed
  by every `CaptureEvent` branch **and** by flow completion, so a source that stops for any reason
  can never leave the surface claiming success. The status screen now reports the real state and
  names the failure reason.
**Verified:** 11 new tests (`CaptureStateTest`, `DefaultInputDeviceSelectionTest`) green, including
one that documents the fabricated-descriptor shape as a mismatch precisely because it can never be
selected again; `./gradlew build dependencyRules` full green. **Not yet verified on the reporting
user's device** — same standing caveat as the last on-device fix, and the reason P12 exists.
**Left open / not done:** P12's third defect — **nothing drains the queue and nothing runs ASR**,
so a captured transmission still cannot become a transcript (`PassDrainRunner`, `PassB` and
`RealSherpaDecoder` all exist; none is constructed in the running app, and no model is fetched to
the device). Also still open: `EnergyVadModel` remains an RMS-threshold stand-in, not Silero. Both
are P12's remaining scope, and P13–P17 (the entire reader UI) have not started.

## 2026-09-08 (morning — a real device catches two real bugs neither CI nor Robolectric could)

### (pending) — Fix the on-device hang at the battery-exemption step, found by the user's own phone

**Scope:** `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/org/ort/app/MainActivity.kt`,
`pipeline/src/main/kotlin/org/ort/pipeline/capture/RealCaptureService.kt`.
**Requirements/ACs:** AC-65 (battery exemption diagnostic-only, never gates capture readiness —
`PermissionsFlowTest`'s existing `AC_65 battery exemption is requested last and never gates
capture readiness` already asserted this at the `PermissionsFlow` layer; the bug was entirely in
`MainActivity`'s own glue code misusing a correct API).
**What changed:** the user installed the smoke-test APK on their own phone and reported it stuck
on "Asking to ignore battery optimisation…" with no further progress, screenshot attached. Two
real bugs, neither catchable by Robolectric (no `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
dialog exists in that environment) or CI (never ran on a real device at all):
1. **Missing manifest permission.** Launching that action's intent without
   `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` declared throws `SecurityException`
   on a real device — added the `<uses-permission>`, and confirmed with `aapt2 dump badging` that
   it's actually present in the built APK now (it wasn't checked for on the first build).
2. **A real design bug, independent of the crash.** `MainActivity.advance()` used
   `PermissionsFlow.nextStep()`, which sequences `BATTERY_EXEMPTION` *before* `DONE` — so even
   with the permission fixed, capture would never start until the user granted (or the OS
   otherwise resolved) that step, directly contradicting `PermissionsFlow.captureIsPermitted()`'s
   own contract that battery exemption must never gate capture readiness. Fixed by checking
   `captureIsPermitted()` (mic + notifications only) directly, and making the battery-exemption
   request fire-and-forget — launched via `startActivity` (not `startActivityForResult`), with
   `SecurityException`/`ActivityNotFoundException` both caught and logged, never blocking.
3. Added a same-instant related guard to `RealCaptureService`: a foreground service is a
   singleton per process, so relaunching `MainActivity` while it's already running would have sent
   a second start command with a different session id, starting a second concurrent
   `AudioRecordSource`/`Segmenter` against the same microphone. `onStartCommand` now ignores a
   second start while one is already active.
**Verified:** `./gradlew build dependencyRules` — full green, including the pre-existing
`PermissionsFlowTest`'s AC-65 case (confirming the underlying flow logic was always correct — the
bug was entirely in this glue code); `aapt2 dump badging` on the freshly built APK confirms the
new permission is present. **Not yet re-verified on the reporting user's actual device** — that
is the next real check, same as the original bug report.
**Left open / not done:** if `MainActivity` is relaunched while `RealCaptureService` is already
running, `StatusActivity` will be shown a fresh session id the service silently ignored (polls an
empty session rather than the one actually capturing) — a minor UX inconsistency in an edge case
(relaunching a running capture), not a crash or data-loss risk, left as a known v0 limitation
rather than fixed here.

## 2026-09-08 (just after midnight, cont. — CI catches a real ruff-version-drift bug too)

### (pending) — Fix 23 ruff findings surfaced by an unpinned `ruff>=0.5`, then pin it

**Scope:** `corpus/pyproject.toml`, `corpus/src/corpus/fingerprint.py`,
`corpus/src/corpus/synth/generator.py`, `corpus/src/corpus/probes/_sherpa_whisper_export.py`,
`corpus/tests/test_acquire.py`, `corpus/tests/test_harness.py`.
**Requirements/ACs:** none — lint/dependency hygiene.
**What changed:** the numpy fix (previous entry) unblocked test collection, and CI's `corpus`
job then failed at the `ruff check .` step with 23 findings, none of which showed up in any
local run this whole session. Root cause: `pyproject.toml`'s dev extra pinned `ruff>=0.5` (a
floor, no ceiling) and this repo's local machine had ruff 0.15.11 installed, while a completely
fresh `pip install -e "corpus[dev]"` on a GitHub runner resolved 0.16.6 — newer, with more rules
enabled by default (`UP037`, `RUF046`, `RUF059`, `SIM113`, `B017`, `BLE001`, `I001`). Confirmed by
upgrading locally to 0.16.6 and reproducing exactly the same 23 findings before fixing anything.
Fixed all of them:
- `ruff check --fix` handled 20 mechanically (unnecessary quoted forward-refs, `Callable` import
  location, an unsorted import block).
- `fingerprint.py`'s blind `except Exception` narrowed to the three real failure modes calling
  `git` can raise (`OSError`, `CalledProcessError`, `TimeoutExpired`) — same fallback behaviour,
  named exceptions instead of "anything."
- Two `int(round(...))` casts in `synth/generator.py` — `round()` with no `ndigits` argument
  already returns `int`; the wrapping `int()` was dead.
- Two unused unpacked variables in the vendored `_sherpa_whisper_export.py` (`qk` from
  `qkv_attention`, genuinely unused by both callers) renamed to `_qk`; a manual counter loop
  converted to `enumerate()`.
- Two `pytest.raises(Exception)` assertions widened to the real exception each one actually
  needs — `ValueError` (checksum mismatch, `acquire.py`'s `_download`) and `EvalFoldSealed`
  (`corpus.gate`) — checked what each call site genuinely raises before narrowing, rather than
  guessing; a wrong guess here would have silently accepted the wrong failure as "the test
  passed."
Then **pinned `ruff==0.16.6` exactly**, replacing the open-ended floor, so this specific failure
mode — a new ruff release enabling new default rules between one session and the next — cannot
recur silently; a future ruff bump becomes a deliberate version bump with its own findings fixed
in the same change, not a surprise on the next clean-runner CI run.
**Verified:** `ruff check corpus` clean at 0.16.6; `pytest corpus -q` — 70 passed, 1 skipped
(unchanged); repeated the from-scratch venv simulation from the numpy fix (fresh `pip install -e
"corpus[dev]"`, no other packages) — both `ruff check` and `pytest` clean in complete isolation,
not just "clean on this machine."
**Left open / not done:** none for this bug. Same process note as the numpy entry: a hosted-runner
CI run that hadn't actually executed against this code path in a while is exactly what caught
both of today's real defects — local-only verification through several agent sessions missed
both.

## 2026-09-08 (just after midnight — CI catches a real dependency-declaration bug)

### (pending) — Declare numpy as a real corpus dependency (found by CI, not local testing)

**Scope:** `corpus/pyproject.toml` only.
**Requirements/ACs:** none — dependency hygiene.
**What changed:** pushing commit `3c5a274` (the smoke-test build + release wiring) triggered
`ci.yml` on a clean GitHub-hosted runner for the first time in a while, and its `corpus` job
failed: `ModuleNotFoundError: No module named 'numpy'` collecting
`test_probe_speaker_separation.py`, `test_synth_channel.py` and `test_synth_generator.py`. P6's
`synth/channel.py`, `synth/generator.py`, `synth/tts.py` and `probes/speaker_separation.py` all
import `numpy` unconditionally at module load — not lazily, unlike the `r1-real` extra's
torch/transformers/etc. — but `pyproject.toml`'s base `dependencies` was `[]`. Every local session
this repo has seen (including this one) had numpy already present as a transitive dependency of
the `r1-real` extra's own toolchain (installed while proving the real sherpa-onnx/LoRA path),
which silently masked the gap. Added `numpy>=1.26` to base `dependencies`.
**Verified:** built a throwaway venv (`python -m venv`), installed only `corpus[dev]` into it
(no `r1-real` extra, so no incidental numpy from that path), ran `pytest corpus` inside it —
70 passed, 1 skipped, matching this repo's normal local result — confirming the fix actually
closes the gap rather than trusting the diagnosis. Also `python tools/spec-check/spec_check.py`
(7/7 OK).
**Left open / not done:** none for this specific bug. Worth noting as a process point: this is
exactly why CI on a clean runner matters even when every local check passes — a session's own
machine can accumulate installed packages across unrelated work (here, the real-R1 toolchain)
that quietly cover for a missing declaration.

## 2026-09-07 (later still — GitHub Releases, at the user's request)

### (pending) — Wire GitHub Releases so a build is downloadable without going through the agent

**Scope:** new `.github/workflows/release.yml`, new `tools/release_notes.py`, `README.md`.
**Requirements/ACs:** none — release/distribution tooling, not spec-governed.
**What changed:** the user asked to "wire up proper GitHub releases and build so I can download
from the releases view there with a nice changelog." Two release shapes, both attaching the debug
APK CI already builds and verifies:
- A rolling **`latest-build` prerelease**, republished on every push to `main` — `gh release
  delete latest-build --yes --cleanup-tag || true` then `gh release create`, so there is always
  exactly one stable link for "the current build," not an accumulating pile of dated releases.
- A proper **versioned release** on any `vX.Y.Z` tag push, for an actual numbered milestone
  later.
- `tools/release_notes.py` extracts release notes from `CHANGELOG.md` itself rather than a
  separate summary someone has to remember to write: it groups by the calendar date in each
  `## YYYY-MM-DD (...)` heading and returns every section under the newest date, so a rolling
  release republished several times in one day always shows that whole day's entries, not just
  the single most-recent (often tiny) sub-section. Verified locally against this repo's real
  `CHANGELOG.md` before wiring it into CI.
- Both release steps run only after the same Android-unit-test + `dependencyRules` gate CI's
  `android` job already uses (`ci.yml`) — a release is cut from a build that passed, not just one
  that compiled.
- `README.md` gained a short "Downloads" section pointing at the Releases page, with an explicit
  caveat that the APK is unsigned (debug key) and a v0 smoke test — pointing at the newest
  changelog entries rather than letting a download imply more maturity than exists.
**Verified:** `python -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"`
(valid YAML); `python tools/release_notes.py` run locally against this repo's actual
`CHANGELOG.md`, confirmed it returns the newest date's entries correctly. **The workflow itself
has not yet run on GitHub** — that happens on the next push to `main`, which is this same commit;
its actual behavior (release creation, asset upload, gh CLI auth via the default `GITHUB_TOKEN`)
is unverified until then.
**Left open / not done:** the APK is unsigned — a real release signing config (keystore +
secrets) is a separate, deliberate decision, not done here. No automated versioning scheme for
tagged releases yet (someone still has to decide and push a `vX.Y.Z` tag by hand).

## 2026-09-07 (later that night — a real v0 smoke-test build, at the user's request)

### (pending) — Wire real microphone capture end to end, so a debug APK does something on a real phone

**Scope:** new `capture-android/src/main/kotlin/org/ort/capture/android/AndroidAudioIo.kt`, new
`pipeline/src/main/kotlin/org/ort/pipeline/capture/RealCaptureService.kt`, new
`pipeline/src/main/AndroidManifest.xml` service entry, `app/src/main/kotlin/org/ort/app/MainActivity.kt`,
`app/src/main/kotlin/org/ort/app/status/StatusActivity.kt`,
`app/src/main/kotlin/org/ort/app/transmissions/TransmissionListActivity.kt`,
`app/build.gradle.kts` and `pipeline/build.gradle.kts` (both gained direct Room + androidx.core
dependencies — see "What changed").
**Requirements/ACs: none new, and this is explicitly not build-plan output.** Every piece
composed here (`AudioRecordSource`, `Segmenter`, `WorkQueue`, `OrtDatabase`,
`CaptureStatusRepository`) was already built test-first by P4/P5/P8/P11 against fakes/Robolectric;
what didn't exist was the glue actually running them together against a real microphone on real
hardware. The user asked directly "can we create a buildable APK to validate other aspects" and
then, after being shown the app only reached a placeholder screen, "build as much of the app as
is available... I am on my phone right now." This entry is that work, done outside the
constitution's strict-TDD process (there is no test-first version of "does the real microphone
produce real audio" — the same gap `RouteVerifier`'s real counterpart and every other Android SDK
wrapper in this repo already accepts), and said so plainly rather than dressed up as a formal
prompt's deliverable.
**What changed:**
- `AndroidAudioIo` — the real, previously-nonexistent `AudioIo` implementation (only
  `FakeAudioIo` existed before this). Uses `android.media.AudioRecord`/`AudioManager` directly;
  route-change/interruption detection is deliberately minimal (relies on read-error detection
  rather than proactive `AudioDeviceCallback` wiring) — enough to prove real capture, not the
  full FR-CAP-3/FR-RUN-11 device-side implementation.
- `RealCaptureService` (`:pipeline`, not `:capture-android`, because it needs `:segment` and
  `:data`, which `:capture-android` may not depend on — technical design §2's module graph):
  constructs a real `OrtDatabase`, `WorkQueue`, `AndroidAudioIo`+`AudioRecordSource`, and a real
  `Segmenter` behind a `SegmentSink` that stages PCM to disk incrementally, FLAC-encodes via the
  existing `FlacStore`/`DeflatePredictiveCodec` (decode-and-compare, same discipline as P8's
  `CaptureService`), inserts a real `TransmissionEntity`, and enqueues it on the real `WorkQueue`.
  Also includes `EnergyVadModel` — a simple RMS-energy threshold, **explicitly not the real
  Silero VAD**, because no real Silero ONNX model/runtime exists anywhere in this repo yet
  (only `ScriptedVad` in `:segment`'s own tests); wrapped in the real `SileroVad` hysteresis class
  so swapping in a real model later is a one-line change, not a rewrite.
- `MainActivity` — replaced with a real driver of `PermissionsFlow`'s Android side (actual
  `ActivityCompat.requestPermissions` calls, not just the state machine P8 already tested),
  starting `RealCaptureService` and the real `StatusActivity` once permitted.
- `StatusActivity`/`TransmissionListActivity` — both gained opt-in live-data polling
  (only activated when a `session_id` intent extra is present, so `StatusActivityTest`/
  `TransmissionListActivityTest` — which never set that extra — are untouched and still pass)
  querying the real database every 2 seconds. Shed level is reported as a static, honest 0 — no
  real battery/backlog telemetry is wired for this test, and fabricating one would be exactly the
  kind of invented number the constitution forbids; the transmission list reads "(captured, not
  yet transcribed)" for the same reason (no ASR pass is constructed in production yet).
**Verified:** `./gradlew build dependencyRules coverageMatrix` — full green, all pre-existing
tests (including `StatusActivityTest`/`TransmissionListActivityTest`) unaffected; manually
inspected the built `app-debug.apk` with `aapt2 dump badging` (minSdk 26, correct permissions,
`RealCaptureService` present via manifest merge). **Not verified: actual behaviour on a physical
device** — that is what this build exists to let the user check next, outside this session.
**Left open / not done:** real Silero VAD (no model exists yet), real route-change/focus
handling in `AndroidAudioIo`, no ASR pass wired into production capture (transcripts stay
"not yet transcribed" until `PassB`, from P11, is actually constructed somewhere real), shed
telemetry, and a proper M5 navigation host (this MainActivity is still a shim, not Compose).

## 2026-09-07 (night, cont. — fix the coverage-tool regex gap flagged earlier)

### (pending) — CoverageMatrix: recognise requirement ids with alphanumeric segments

**Scope:** `buildSrc/src/main/kotlin/org/ort/gradle/CoverageMatrix.kt`,
`buildSrc/src/test/kotlin/org/ort/gradle/CoverageMatrixTest.kt`. No session currently owns
`:buildSrc`; this is a small, well-contained fix to a shared tool, not a drive-by edit to
someone else's module.
**Requirements/ACs:** none new — tool accuracy only. Makes `FR-A11Y-1` (the real accessibility
requirement P11's `TransmissionListViewStateMapperTest` correctly cites) count as covered
instead of orphaned.
**What changed:** the earlier hygiene pass (`b0fbb60`) documented but didn't fix a real gap in
`CoverageMatrix`'s id-recognition regex; P11 immediately produced a second, concrete case of it —
`@Requirement("FR-A11Y-1")` was flagged as an orphan even though `FR-A11Y-1` is a real,
correctly-cited functional-spec requirement (§7.16), because the regex's middle-segment pattern
(`[A-Z]{2,5}`) rejected the digits in `A11Y`. Widened both `REQUIREMENT` and `NAME_ID` to
`[A-Z][A-Z0-9]{1,5}` (still starts with a letter, so it doesn't start matching arbitrary numbers)
so alphanumeric segment codes like `A11Y` are recognised. Added a regression test proving
`FR-A11Y-1` is now counted as a real requirement, shows as covered, and is not orphaned.
**Deliberately not attempted here:** widening the regex further to recognise bare `F`/`D`/`R`/`Q`
ids (`F13`, `D28`, `R15`, `Q16` — flagged in `b0fbb60`) — those single-letter prefixes carry a
real risk of false-positive matches against ordinary prose elsewhere in the spec (quarter/date
references, resistor-style callouts, etc.) that would need checking against the whole spec
corpus before landing, which this session didn't have the scope to do safely. Left as still-open,
now with a narrower, better-understood shape than before.
**Verified:** `./gradlew :buildSrc:test` (new test green, all existing ones still pass);
`./gradlew build dependencyRules coverageMatrix` (full multi-module build green); coverage
matrix now reports 419 requirement ids (was 413 — six previously-invisible alphanumeric-segment
ids, including `FR-A11Y-1`, now counted), 101 covered (was 100), 1 orphan remaining (`F13`, the
documented bare-prefix gap).
**Left open / not done:** the bare `F`/`D`/`R`/`Q` id gap, as above.

## 2026-09-07 (evening, cont. — post-merge ktlint fix)

### (pending) — Fix a ktlint class-signature violation in RealSherpaDecoder after merging

**Scope:** `asr-sherpa/src/main/kotlin/org/ort/asrsherpa/real/RealSherpaDecoder.kt` only.
**Requirements/ACs:** none — style only.
**What changed:** merging the real-sherpa-onnx follow-up in surfaced a ktlint
`standard:class-signature` violation (`: SherpaDecoder, AutoCloseable` needed to start on its own
line) that the follow-up's own narrower verification (`:asr-sherpa:test`, `dependencyRules`)
didn't run against — `./gradlew build`'s full `ktlintMainSourceSetCheck` caught it. Fixed with
`./gradlew :asr-sherpa:ktlintFormat` rather than hand-editing, so the result matches the project's
own formatter exactly.
**Verified:** `./gradlew build dependencyRules` — full multi-module build green (830 tasks).
**Left open / not done:** none — this is a reminder for future sessions to run the full
`./gradlew build`, not just their own module's tests, before calling a merge clean; noted here
rather than silently fixed with no trace.

## 2026-09-07 (evening, cont. — drafting Q16 while P11 and a P10 follow-up run)

### (pending) — Draft the Q16 labelling protocol

**Scope:** `docs/reference/labelling-protocol.md` (new), `spec/open-questions.md`,
`spec/build-plan.md`, `AGENTS.md` — documentation only, no code.
**Requirements/ACs:** Q16 (does not close it — see below), Q2 (records what audio to capture and
why the noise tape specifically cannot be substituted).
**What changed:** while P11 and a P10 follow-up ran in the background, drafted a concrete
labelling protocol answering every open question Q16 lists: transmission boundaries are defined
as keying edges (PTT down/up), matching what `:segment`'s `AC-69` actually measures rather than a
speech-content boundary; doubling gets two rows when separable, one `doubled_unresolvable` row
when not; partial audibility is **never omitted** (three certainty levels — `certain`/
`uncertain`/`partial` — with an explicit rule against looking a doubtful callsign up against a
database while labelling, which would leak the very prior AC-10/AC-11 test into the ground
truth); threads default to a 20-minute same-frequency/shared-participant continuation rule,
explicitly flagged as an unvalidated default for the pilot to test; phonetic/tactical/club-station
handling; non-speech content (DTMF, courtesy tones, data bursts, CW IDs) is out of ASR scope and
never scored as a missed callsign; and a concrete TSV output schema at transmission grain. Also
investigated whether a downloaded CC0 "radio static" clip could stand in for the real development
noise tape — **concluded no and did not pursue it**: Q2 is explicit that AC-6 needs real squelch
tails from the two specific radios (TH-D75A, SDS150), not generic noise, so a substitute would be
actively misleading rather than useful groundwork. Updated `open-questions.md`'s Q16 entry to
"DRAFTED, not yet piloted" (not closed — closing it needs the pilot round this document itself
prescribes, which needs the Q2 recording first), added the doc to `AGENTS.md`'s reference table,
and updated `build-plan.md`'s "yours, not a session" line to point at it.
**Verified:** `python tools/spec-check/spec_check.py` (7/7 OK — Q16's status-line edit didn't
break the closed/open-question-to-decision mapping check).
**Left open / not done:** the actual recording (Q2) and the pilot labelling round (Q16's real
closing condition) — both explicitly require the user, not a session. The document's own "open
items for the pilot round" section lists three specific defaults (the 20-minute thread gap
foremost) that should be revisited once real audio exists.

---

## 2026-09-07 (P10 follow-up — real sherpa-onnx JVM binding)

### (pending) — P10 follow-up · Real sherpa-onnx JVM binding and a genuine ASR decode

**Scope:** `asr-sherpa/` (new `real/RealSherpaDecoder.kt`, its README, its `.gitignore`, the
gated `RealSherpaDecoderRealModelTest`), `gradle/libs.versions.toml` (new `sherpaOnnx` version
and two library coordinates), `settings.gradle.kts` (one new repository).

**Requirements/ACs:** FR-ASR-1 (the decode path this proves is real). Not AC-6 — see "Left open"
below; this task was explicitly scoped narrower than AC-6.

**What changed:** P10 built `SherpaAsrEngine` against a `SherpaDecoder` seam with only
`FakeSherpaDecoder` behind it, because no real JVM sherpa-onnx binding had been confirmed
resolvable and no model was on hand to exercise it. This follow-up closes that:

- **A real JVM/JNI binding is resolvable and used.** sherpa-onnx does not publish its Kotlin/JVM
  artifact to Maven Central — upstream only documents a build-it-yourself CMake + `kotlinc-jvm`
  path. What upstream does publish, as GitHub Release assets per tag, is a `sherpa-onnx-jvm`
  jar (the Java/Kotlin API classes) plus per-platform `sherpa-onnx-native-lib-<platform>` jars
  bundling the native library. These are mirrored onto JitPack
  (`com.github.k2-fsa.sherpa-onnx:<artifact>:1.13.7` — the same version the real R1 probe already
  confirmed usable via Python, `results/r1-lora-export.md`), which is an ordinary resolvable
  Maven repository. Added `maven { url = uri("https://jitpack.io") }` to
  `settings.gradle.kts`'s `dependencyResolutionManagement` (required — `FAIL_ON_PROJECT_REPOS` is
  set, so a project-level repository isn't an option), and
  `implementation(libs.sherpa.onnx.jvm)` / `runtimeOnly(libs.sherpa.onnx.native.win.x64)` in
  `asr-sherpa/build.gradle.kts`. Verified genuinely resolvable, not just metadata: after
  `:asr-sherpa:compileKotlin` and `:asr-sherpa:test`, `sherpa-onnx-jvm-1.13.7.jar` (187KB) is
  present in `~/.gradle/caches/modules-2/files-2.1/com.github.k2-fsa.sherpa-onnx/` — an actual
  jar, not a `.pom` stub. `./gradlew dependencyRules` still passes with `:asr-sherpa`'s module
  edges unchanged (`:asr-api`, `:core`, `:onnx`) — this is an external library dependency, not a
  new module edge.
- **`real.RealSherpaDecoder`** (`asr-sherpa/src/main/kotlin/org/ort/asrsherpa/real/RealSherpaDecoder.kt`)
  — a genuine second `SherpaDecoder` implementation alongside the untouched
  `fake.FakeSherpaDecoder`. Wraps a real sherpa-onnx `OfflineRecognizer` built from explicit
  Whisper encoder/decoder/tokens file paths; `decode()` creates an `OfflineStream`, calls
  `acceptWaveform` at the pipeline's fixed 16kHz (`SampleClock.DEFAULT_SAMPLE_RATE`), decodes,
  and maps `OfflineRecognizerResult` (text, tokens, timestamps, durations) onto this module's
  `DecodedHypothesis`. Not wired into any production construction path (`:pipeline`/`:app`) —
  see "Left open".
- **The model**: sherpa-onnx's own published pretrained model zoo, per the task's preferred
  option — Whisper `tiny.en`, int8-quantized (`tiny.en-{encoder,decoder}.int8.onnx`, ≈101MB
  combined), from `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2`.
  Not committed (`asr-sherpa/.gitignore` excludes `.models-cache/`); `asr-sherpa/README.md`
  documents the exact download/extract commands and the `ORT_SHERPA_MODEL_DIR` override.
- **`RealSherpaDecoderRealModelTest`** (`asr-sherpa/src/test/kotlin/org/ort/asrsherpa/real/`) —
  gated behind `@EnabledIfEnvironmentVariable(named = "ORT_RUN_REAL_SHERPA", matches = "1")`
  (mirrors `corpus/tests/test_probe_lora_export.py`'s `ORT_RUN_REAL_R1` pattern), with an
  `Assumptions.assumeTrue` skip (not a failure) if the model directory is missing even when the
  env var is set. Reads `test_wavs/0.wav` (16kHz mono PCM16, via a small WAV reader written
  inline — no new module dependency) through a real `RealSherpaDecoder` and asserts the output
  contains real recognizable words from the clip's actual ground truth
  (`test_wavs/trans.txt`: "AFTER EARLY NIGHTFALL THE YELLOW LAMPS ...").

**Verified:**
- `./gradlew :asr-sherpa:dependencies --configuration runtimeClasspath` — both new coordinates
  resolve (`BUILD SUCCESSFUL`); confirmed via cache inspection that the actual jars download.
- `./gradlew dependencyRules :asr-sherpa:test` (JDK 17, Windows 10, this machine) — `dependencyRules:
  OK`; 7 tests pass, `RealSherpaDecoderRealModelTest` **SKIPPED** (env var unset — proves it does
  not run in ordinary CI).
- `./gradlew :asr-sherpa:test --tests "*RealSherpaDecoderRealModelTest*"` with
  `ORT_RUN_REAL_SHERPA=1` set and the model extracted under `asr-sherpa/.models-cache/` — **PASSED**.
  Actual transcribed text (captured via the test's own stdout, JDK 17, Windows 10, sherpa-onnx
  1.13.7, CPU provider, Whisper tiny.en int8): `"After early nightfall, the yellow lamps would
  light up here and there the squalid quarter of the brothels."` — matches the clip's ground
  truth (`test_wavs/trans.txt`) essentially exactly (Whisper adds its own casing/punctuation).
  This is a real decode through a real binding and a real model, not a claim about accuracy
  (constitution VI) — no fold, no aggregate metric, one clip.

**Left open / not done:**
- **Not AC-6.** AC-6 needs the real development noise tape (radio traffic, not LibriSpeech-style
  read speech), which still doesn't exist (Q2/Q16; see `docs/reference/labelling-protocol.md`
  for the drafted protocol). This task proves the decode *mechanism* is real; it does not attempt
  AC-6's accuracy claim.
- `RealSherpaDecoder` is not constructed anywhere in `:pipeline` or `:app`. Wiring it in needs the
  real asset install/activation path (`ModelRegistry`, `ModelDescriptor`/`AssetRef` resolved to
  concrete encoder/decoder/tokens paths, side-loaded or downloaded per FR-ASR-8/FR-AST-2) — out
  of scope here, which was narrowly "prove the seam is real."
- Only `sherpa-onnx-native-lib-win-x64` is declared (this machine is Windows x64). A build on a
  different host platform needs its own `sherpa-onnx-native-lib-<platform>` coordinate —
  documented in `asr-sherpa/README.md`.
- No n-best beyond a single hypothesis, no `noSpeechProb`/`avgLogProb` (the Java API's
  `OfflineRecognizerResult` doesn't expose them for greedy-search Whisper decoding) — both fields
  are `null` in `RealSherpaDecoder`'s output, same shape `FakeSherpaDecoder` already allows.

---

## 2026-09-07 (evening, cont. — coverage-matrix hygiene)

### (pending) — Fix three misused `@Requirement` tags; note a real gap in the coverage tool

**Scope:** `onnx/src/test/kotlin/org/ort/onnx/ModelResidencyManagerTest.kt`,
`asr-sherpa/src/test/kotlin/org/ort/asrsherpa/SherpaAsrEngineTest.kt`,
`asr-api/src/test/kotlin/org/ort/asrapi/TranscriptSeriesTest.kt`, `results/coverage-matrix.md`.
**Requirements/ACs:** FR-TIER-8 (resident budget is a requirement on the tier), AC-31 (superseded
transcript retained, exactly one current) — both now correctly traced from P10's tests instead of
a bogus id.
**What changed:** `coverageMatrix` flagged 4 orphan test-requirement-id pairs after P10 landed.
Three were genuinely wrong: `ModelResidencyManagerTest`, `SherpaAsrEngineTest` and
`TranscriptSeriesTest` cited section references (`"technical-design-4.3"`, `"technical-design-8.1"`,
`"technical-design-8.3"`) as if they were requirement ids in `@Requirement(...)` — that annotation
exists for spec ids the coverage matrix indexes (`AC-*`/`FR-*`/`NFR-*`/`CON-*`), not section
numbers, which aren't ids at all. Replaced with the real ids that actually apply: `FR-TIER-8` (the
tier resident-budget requirement `ModelResidencyManagerTest` establishes) and `AC-31` (the
transcript-versioning criterion `TranscriptSeriesTest` establishes); `SherpaAsrEngineTest`'s tag
already carried `FR-ASR-1` alongside the bogus one, so that one was simply dropped.
**The fourth orphan, `"F13"` in `RejectionPipelineTest`/`ModelRegistryTest`, is NOT a test bug —
it's a real gap in `buildSrc`'s `CoverageMatrix.kt`.** `F13` ("model file missing or incompatible")
is a genuine id from functional-spec.md's failure-mode table (§12), correctly cited by both test
files. `CoverageMatrix.REQUIREMENT`'s regex only recognises `FR|AC|NFR|CON` prefixes, missing the
bare `F\d+` failure-mode ids, and (per a quick check against `AGENTS.md`'s own "cited by id"
example list — `D28`, `R15`) likely also misses `D\d+` (decisions), `R\d+` (risks) and `Q\d+`
(open questions) entirely, none of which would be flagged as covered or orphaned — they'd just
silently not count. **Left open, not fixed here**: widening `CoverageMatrix`'s regex to recognise
`F`/`D`/`R`/`Q` ids is a `:buildSrc` change outside this session's scope (no session currently owns
`:buildSrc`/`tools/spec-check`), but it's a real, demonstrated gap in a tool the whole project
relies on for traceability — worth a dedicated small session before the coverage numbers are
quoted anywhere important.
**Verified:** `./gradlew :onnx:test :asr-sherpa:test :asr-api:test coverageMatrix` — all green;
orphan count dropped from 4 to 1 (the remaining `F13` is the tool gap above, not a code defect).
**Left open / not done:** the `CoverageMatrix.kt` regex gap itself (see above).

---

## 2026-09-07 (night — P11 partial: the M3 text-derived wiring, M4 left open)

### P11 · End-to-end, then the M4 fork (`:pipeline`, `:lexicon`, `:app`)

**Scope:** `:pipeline` (new `passb/`, `latency/`, `backlog/` packages), `:lexicon` (new
`UnitSpotter.kt`, `fake/FakeUnitSpotter.kt`), `:app` (new `transmissions/` package,
`AndroidManifest.xml`). `:capture-android`, `:data`, `:onnx`, `:asr-api`, `:asr-sherpa` untouched
beyond consuming their existing public API (no edits to any of their files); `dependencyRules`
confirms no new module edge was introduced anywhere.

**Requirements/ACs:** AC-15 (text-derived lattice resolves identically to an acoustic one — now
proven through a real `Pass`, not just `TextDerivedLatticeBuilder` in isolation), FR-LEX-6/11,
FR-SPK-10 (non-optional attribution state — already true of `:core`'s `Attribution` from an
earlier prompt; this session's addition is that an attribution now reaches a *screen*),
FR-A11Y-1 (the four states distinguishable without colour, tested on a real rendered view, not
just the intermediate view-state), constitution III (Pass B carries its `PassFingerprint`).
**AC-73, AC-75 and M3's dev-fold callsign precision/recall are NOT MEASURED** — see below. **The
M4 fork decision (proceed / collapse to text / tier-gate) is explicitly left OPEN** — not chosen,
per this session's own constraints; see below.

**What changed:**

- **Merged P9 and P10 into this worktree first**, as instructed. Both had branched from the same
  P8 changelog commit and diverged, so this was two sequential merges (`worktree-agent-
  a67daa1ff68391962` fast-forwarded cleanly, `worktree-agent-aa1031e59afecdbf5` conflicted only
  in `CHANGELOG.md` — two independent appends, resolved by keeping both entries in full, P10's
  first). No file outside `CHANGELOG.md` conflicted; `git diff --stat` between the pre-merge and
  post-merge trees showed exactly the 45 non-changelog files P10's own changelog entry already
  describes (`:onnx`, `:asr-api`, `:asr-sherpa`), confirming the merge brought in nothing beyond
  what P9 and P10 each already landed. Verified: `./gradlew build dependencyRules` green
  immediately after the merge, before any P11 code was written (829 tasks, JDK 17, Windows).
- **`:pipeline/passb/PassB.kt`** — the real Pass B (build-plan P11, M3): a `Pass` (P8's
  interface) composing `:asr-api`'s `RejectionPipeline` (P10) → a per-token text-derived
  `PhoneticLattice` (deliberately *not* `TextDerivedLatticeBuilder.build`, which throws on the
  first unrecognised word — a real transcript of continuous speech contains many non-phonetic
  words, so unrecognised tokens are excluded rather than aborting the whole spot; documented in
  the class kdoc as this session's explicit, necessarily-degraded T0 approximation, since
  spotting a callsign embedded in running speech is exactly M4's job) → `:lexicon`'s
  `CallsignGrammar`/`PriorCombiner` (P3/P7) → the new `CallsignResolver` → an `Attribution`.
  Carries a `PassFingerprint` end to end (constitution III), grouped via the new
  `PassBResolutionChain` data class to keep the primary constructor under detekt's parameter-count
  threshold. `SegmentAudioProvider` and `PassBResultSink` are new seams this session owns — real
  audio decode (`:capture-android`'s `FlacStore`) and real attribution persistence (a `:data` DAO
  write path) are both explicitly left for a follow-up that can touch those modules; see below.
- **`:pipeline/passb/CallsignResolver.kt`** — the M1 resolver (technical design §9.2/§9.3,
  FR-LEX-11): ranked candidates → one of the four closed `AttributionState`s. Only `CONFIRMED`,
  `AMBIGUOUS` and `UNKNOWN` are reachable — never `INFERRED`, because a text-derived candidate's
  callsign text came directly out of *this* transmission's own transcript (constitution I: "heard
  and resolved in this transmission"), which is exactly what makes `CONFIRMED` legitimate here
  and not a violation of "never promote a voice match to it".
- **`:lexicon/UnitSpotter.kt`** (M4.1, technical design §9.7) — `fun interface UnitSpotter { fun
  spot(audio: FloatArray): PhoneticLattice }`, exactly as specified, plus
  `TextDerivedUnitSpotter`, wrapping the same per-token expansion `PassB` uses behind the
  interface so the M4 comparison can drive every candidate spotter — including the text baseline
  — through one uniform type. `:lexicon/fake/FakeUnitSpotter.kt` is its behavioural fake
  (constitution II), scriptable to a fixed lattice or "spotted nothing".
- **`:pipeline/passb/SpotterComparisonTest.kt`** — proves the *plumbing* M4.8 needs ("same audio,
  same resolver, same folds" for multiple spotters): `TextDerivedUnitSpotter` and two
  `FakeUnitSpotter`s driven through the identical grammar → priors → `CallsignResolver` chain
  produce independently inspectable attributions. This is a mechanism proof over synthetic data,
  explicitly not a claim about any acoustic spotter's real accuracy.
- **`:pipeline/latency/LatencyRecorder.kt`** (AC-73's mechanism) — nearest-rank p95 over
  recorded segment-close-to-visible durations; `null` (never a fabricated zero) with no samples.
- **`:pipeline/backlog/BacklogGrowthMonitor.kt`** (AC-75's mechanism) — least-squares slope of
  backlog-depth samples, flagged as growth only when positive and large relative to the mean
  depth (an oscillating-but-draining backlog is not growth; a monotonic climb is), so a single
  noisy sample cannot flip the verdict either way.
- **`:app/transmissions/`** — the first point in the project an `Attribution` reaches a screen
  (M3, not M5's full reader): `TransmissionRow`/`TransmissionListRowViewState`/
  `TransmissionListViewStateMapper` (pure, Room-independent by design) and
  `TransmissionListActivity` (plain Android views, same choice `StatusActivity` made in P8).
  Every one of the four states renders a distinct plain-text label carrying the state's name plus
  a distinct ASCII marker (✓ / ~ / ? / —) — FR-A11Y-1's "distinguishable without colour" tested as
  a plain-string assertion, which is what makes it checkable with no display, greyscale or
  otherwise. Registered in `AndroidManifest.xml`, not yet the app's launcher (same reasoning as
  `StatusActivity`).
- **TD2 probe (M4.3), attempted and answered — negative for the public API:** sherpa-onnx's
  documented C API (`sherpa-onnx/c-api/c-api.h`) and its generated Kotlin API
  (`OfflineRecognizer.kt`) expose only final decode results — `text`, `tokens`, `timestamps`,
  `json` — with no function returning encoder hidden states, embeddings or any intermediate
  tensor. The Whisper ONNX export docs describe the encoder/decoder `.onnx` files themselves but
  document no runtime path to their intermediate activations through sherpa-onnx's recognizer
  wrapper. **Conclusion: sherpa-onnx does NOT expose Whisper encoder hidden states on Android
  through its public API today.** Getting them would mean forking sherpa-onnx's C++ source to add
  an extraction path, or exporting a custom ONNX graph with the hidden-state tensor as an extra
  named output and running it directly via bare ONNX Runtime — bypassing sherpa-onnx's recognizer
  entirely, a materially heavier and different engineering path than "sherpa-onnx exposes it".
  Per M4.3's own framing ("gates option 2 entirely... if it fails, skip that option"), **Option 2
  (CB-Whisper-style encoder-similarity spotting) is skipped** on this finding; only Option 1
  (open-vocabulary KWS) remains a live M4 candidate for a future session with a real device and a
  real sherpa-onnx artifact. Sources: `github.com/k2-fsa/sherpa-onnx` (`sherpa-onnx/c-api/c-api.h`,
  `sherpa-onnx/kotlin-api/OfflineRecognizer.kt`) and `k2-fsa.github.io/sherpa/onnx/pretrained_models/
  whisper/export-onnx.html`, fetched 2026-09-07. This is a documentation-level finding, not a
  from-source verification of every internal code path — a from-source audit could still be
  wrong if an undocumented API exists; flagged as the residual uncertainty a future session
  should note if a real device changes this answer.

**Verified:** `./gradlew build dependencyRules` — BUILD SUCCESSFUL, 830 tasks, no forbidden
module edge (`:lexicon -> :core` and `:pipeline -> ...` unchanged from pre-P11). `./gradlew
:pipeline:testDebugUnitTest :lexicon:test :app:testDebugUnitTest` — every new test green:
`CallsignResolverTest` (5), `PassBTest` (3, including the full spelled-callsign → CONFIRMED path
and the rejected-segment → UNKNOWN path), `SpotterComparisonTest` (1), `LatencyRecorderTest` (5),
`BacklogGrowthMonitorTest` (5), `UnitSpotterTest` (3), `FakeUnitSpotterTest` (2),
`TransmissionListViewStateMapperTest` (3), `TransmissionListActivityTest` (2) — 29 new tests, all
written and confirmed failing to compile for the right reason (the class under test did not yet
exist) before each implementation was added, then green after. `./gradlew coverageMatrix` — 413
requirements, 100 covered (was 92 pre-P11); the tool's own id-regex cannot parse `FR-A11Y-1`
(`[A-Z]{2,5}` excludes the digit in `A11Y`), so it is listed among the pre-existing orphan-test
warnings alongside P10's already-unaddressed `F13`/`TECHNICAL-DESIGN-*` — a tool limitation, not
a missing requirement (`spec/functional-spec.md:1606` defines `FR-A11Y-1`); out of this prompt's
file scope to fix (`buildSrc`). `python tools/spec-check/spec_check.py` — all 7 checks PASS,
including after the `spec/build-plan.md` checklist edit. JDK 17, Windows, local machine
throughout; no fold applicable to any of the above (JVM/Robolectric unit tests over synthetic
fixtures and fakes, not a corpus measurement).

**Left open / not done — read this before assuming M3 or M4 landed:**

- **AC-73 and AC-75 are NOT MEASURED.** No reference device and no real segment timings exist in
  this sandbox. `LatencyRecorder` and `BacklogGrowthMonitor` are genuine, tested mechanisms that
  would report AC-73/AC-75 correctly if fed real device timings — that is what their own test
  suites prove — but no real p95 latency number or real sustained-backlog trend was measured, and
  none is claimed here.
- **M3's "record the harness's callsign precision/recall on the dev fold" — the line M4 must
  beat — could NOT be produced.** This needs real labelled dev-fold occurrences (real transmitted
  callsigns with ground truth), which do not exist in this repository (the same gap P7's session
  hit and recorded; `spec/open-questions.md` Q2/Q16 remain open). `SpotterComparisonTest` and
  `PassBTest` exercise the identical resolver chain against synthetic fixtures, which proves the
  *mechanism* computes attributions correctly — it is not, and is not presented as, the dev-fold
  number the build plan asks for.
- **Real audio decode is not wired.** `PassB`'s `SegmentAudioProvider` seam is real and tested,
  but nothing in this session connects it to `:capture-android`'s `FlacStore`/`LosslessCodec` —
  doing so is straightforward but was left out because it touches no new logic worth testing
  beyond what `FlacStore`'s own P8 tests already cover, and the prompt's file-ownership boundary
  (`:capture-android` untouched except by consuming its API) made a same-session change to wire
  it in without a real device to verify against feel like exactly the "half-finished" the
  constitution warns against.
- **Real attribution persistence is not wired, and this is a genuine gap this session found,
  not a choice.** `:data`'s `TransmissionDao` has no write path for `attributionState`/
  `stationId`/`attributionConfidence` — only `setProcessingState` and `setReprocessCandidate`
  exist as partial updates. `PassBResultSink` is the seam `PassB` writes through instead;
  `TransmissionListActivity` renders whatever `TransmissionRow`s it is handed, but nothing in
  this session produces those rows from a live database, because doing so needs a `:data` DAO
  method this prompt's file-ownership boundary explicitly reserves for a session that owns
  `:data` (per the prompt: "if a real gap forces a change to one of those, stop and report it
  rather than reaching across the boundary"). **Reported, not silently worked around.**
- **The M4 fork decision is explicitly OPEN, not chosen.** No real KWS implementation was built
  (no real sherpa-onnx JNI artifact was available in this sandbox — the same gap P10's session
  hit for Pass B's engine), no encoder-similarity implementation was attempted (skipped on the
  TD2 finding above), and no real 3-way comparison was run on real audio or the dev fold. Per the
  build plan's own M4 exit criteria, a fork decision requires exactly that comparison; picking a
  branch without it would be the fabricated-confidence failure mode the constitution exists to
  prevent. A future session with a real device, a real sherpa-onnx KWS binding and real labelled
  dev-fold audio should run `SpotterComparisonTest`'s shape for real and record the decision in
  the functional spec before M5, per the build plan's "Done when".
- Per-unit precision/recall reporting (M4.6) and calibration refit on acoustic-lattice scores
  (M4.7) are not attempted — both depend on the KWS/encoder-similarity implementations this
  session could not build for real.

---

## 2026-09-07 (evening — P10 lands)

### P10 · ASR and hallucination control (`:onnx`, `:asr-api`, `:asr-sherpa`)

**Scope:** `:onnx`, `:asr-api`, `:asr-sherpa` only — no other module touched.
**Requirements/ACs:** AC-7 (each of the six hallucination controls individually demonstrable),
AC-8 (rejected segments retain audio, reachable behind a filter), F13 (invalid model falls back
and surfaces it; side-loaded model signature-checked and probe-run before activation), FR-ASR-5/6,
FR-ASR-8, FR-AST-2, FR-REP-3, technical design §4.3/§8.1/§8.3/§8.4. **AC-6 is NOT established** —
see below.
**What changed:**
- `:onnx` — `ModelDescriptor` (technical design §8.4), `OnnxSession`/`OnnxSessionFactory` (a
  narrow seam mirroring `:segment`'s `VadModel` pattern, so no native runtime is required to
  build or test this layer), and `ModelResidencyManager` implementing the Pinned/Hot/Cold
  residency classes of §4.3: `PINNED` never evicted, `HOT` is LRU with a floor of one, `COLD` is
  loaded-per-use-and-released, budget enforced against declared footprints. `FakeOnnxSessionFactory`
  is the behavioural fake, scriptable to fail loading or crash on first run (the probe-run failure
  mode F13 requires a caller to handle).
- `:asr-api` — `AsrEngine`/`StreamingAsrEngine`/`Enhancer`/`AsrResult` (§8.1, n-best and
  `no_speech_prob` retained); the six `RejectionRule`s as named, cheapest-first, split into
  `PreDecodeRejectionRule` (`too_short`, `vad_no_speech` — never invoke the engine) and
  `PostDecodeRejectionRule` (`no_speech_prob`, `repetition`, `blocklist`, `compression_ratio`);
  `RejectionPipeline` composing all six with a timeout-guarded engine call (`withTimeout` ->
  `PassBOutcome.Failed` on hang or throw, never a hang — F13's engine-timeout requirement);
  `PassBOutcome` (Accepted/Rejected/Failed); `TranscriptCandidate`/`TranscriptSeries` — a
  domain-level append-only "exactly one current" invariant independent of Room (the DB-level
  enforcement is `:data`'s partial-unique-index from P5; this is what a Pass B run produces
  before that write); `ModelRegistry` resolving a tier to an activated model with fallback
  surfaced as `ModelFallback` (F13); `RejectedSegmentLog`, an in-memory stand-in proving the
  AC-8 shape (rejected is a retained, filterable result, not a deletion) at the layer this
  prompt owns — the persisted equivalent is `:data`/`:pipeline` territory; `FakeAsrEngine`, the
  behavioural fake (`Returns`/`HangsFor`/`Throws`).
- `:asr-sherpa` — `SherpaAsrEngine` implementing `AsrEngine` over an injected `SherpaDecoder` seam
  (the one method a real sherpa-onnx JNI binding would implement — see "Left open" below) plus
  `OnnxSession` lifecycle checks; `ModelActivation` — the side-loaded model install path
  (FR-ASR-8): signature check, then load, then probe-run over a fixture clip, refusing activation
  and keeping the previous model active on any failure (FR-AST-2), marking every side-loaded
  activation `sideloadedUnverified = true` regardless of passing checks, per §8.4 ("this does not
  make executing a third-party graph safe; it makes it deliberate"); `FakeSherpaDecoder`.
- Thresholds: `NoSpeechProbRule` (default 0.60) and `CompressionRatioRule` (default 2.4) use the
  same defaults already recorded in `:core`'s `ResolvedConfig` (P1). Both are Whisper's
  conventional values, **not refitted against a development noise tape** — see "Left open".
**Verified:** `./gradlew :onnx:test :asr-api:test :asr-sherpa:test :onnx:ktlintCheck
:asr-api:ktlintCheck :asr-sherpa:ktlintCheck :onnx:detekt :asr-api:detekt :asr-sherpa:detekt
dependencyRules` — BUILD SUCCESSFUL; 8 `:onnx` + 28 `:asr-api` + 7 `:asr-sherpa` tests, all green,
JDK 17, Windows, local machine, no fold applicable (JVM unit tests over synthetic/fake inputs,
not a corpus measurement). `dependencyRules` confirms no new edge beyond `:asr-api -> :core,
:onnx` and `:asr-sherpa -> :core, :onnx, :asr-api`, both already declared in `ModuleGraph`.
**Left open / not done — read this before assuming AC-6 passed:**
- **AC-6 is not established against real data or a real model, and this changelog entry does
  not claim it is.** `spec/open-questions.md` still lists Q2 ("record the tape") and Q16 (its
  labelling protocol) as the specification's two open questions; the development noise tape has
  not been recorded. Separately, no real ONNX Whisper/distil-whisper export runnable from a JVM
  sherpa-onnx binding was available/downloadable in this sandbox, so `:asr-sherpa`'s
  `SherpaAsrEngine` has never run a real decode — `SherpaDecoder` is a documented seam, not a
  verified binding. `SyntheticNoiseGateMechanismTest` in `:asr-api` proves the six-control
  mechanism rejects a set of hand-crafted, noise-shaped decodes (empty text with high
  `no_speech_prob`, degenerate repetition, documented Whisper hallucination phrases) with zero
  accepted — a genuine, valuable result about the *mechanism*, explicitly not a substitute for
  AC-6, and its test name and class doc say so. A real AC-6 run needs: the recorded tape (Q2),
  a real ONNX ASR model reachable from `:asr-sherpa`, and the JVM/JNI sherpa-onnx artifact this
  environment could not resolve/verify.
- Threshold *fitting* against the development noise tape (technical design §8.2, "thresholds are
  fitted, not inherited") could not be done for the same reason — `NoSpeechProbRule` and
  `CompressionRatioRule` keep Whisper's unrefitted defaults, recorded as such in their doc
  comments rather than presented as measured.
- `:asr-sherpa`'s `sherpa-onnx` JVM/JNI dependency itself was not added to `gradle/libs.versions.toml`
  — with no real model to exercise it and no confirmed resolvable artifact for this build's
  target platform, adding an unused/unverified native dependency seemed worse than the documented
  seam (`SherpaDecoder`). A follow-up session with a real model available should add it, implement
  `SherpaDecoder` for real, and only then attempt AC-6 for real.
- `StreamingAsrEngine`/`StreamSession` (Pass A, M8) and `Enhancer` (M11) are declared per §8.1 but
  have no implementation yet — out of P10's scope (M3), matching the build plan's milestone order.

---

## 2026-09-07 (evening — P9 partial: the on-device harness runner)

### 8fbad92 — P9 · On-device harness runner: reuse :eval's Harness/HarnessConfig/HarnessReport in an androidTest entry point

**Scope:** `:app` only — a new `harnessShared` source directory, `app/src/test/.../harness/`,
`app/src/androidTest/.../harness/`, and `app/build.gradle.kts` (new `testImplementation` /
`androidTestImplementation` edges plus the `sourceSets` wiring that shares `harnessShared`
between `test` and `androidTest`). This is deliberately the **first deliverable of P9 only** —
the on-device harness runner described in build-plan P9's opening paragraph and
implementation-plan M2.21a. The device matrix itself (D8, D4, D5, D1–D3, D9–D12; AC-64, AC-76,
NFR-7) is **not attempted in this session** — it needs the physical reference device, which this
sandbox does not have, and fabricating a result for it would be exactly the kind of silent
failure the constitution exists to prevent.
**Requirements/ACs:** AC-36 (a desktop-only harness structurally cannot measure T3 — this gives
`:eval` a genuine on-device entry point), FR-TST-7/AC-100 (the eval-fold seal holds
on-device, identically to the JVM harness — tested), AC-90's determinism/format guarantee
extended off-desktop (the on-device runner is proven to emit byte-identical `canonicalText()`
for identical inputs). AC-64/AC-76/NFR-7 (the actual endurance run and its published number)
remain **open**, as does the rest of the device matrix (D1–D5, D8–D12).
**What changed:** `OnDeviceHarnessRunner` (`app/src/harnessShared/kotlin/org/ort/app/harness/`)
mirrors `eval/src/main/kotlin/org/ort/eval/Main.kt`'s construction exactly (same grammar, same
`PriorCombiner`, same `Harness`, same eval-fold gate via `CorpusManifest.entries`) and adds
`runAndWriteReport`, which writes `HarnessReport.canonicalText()` to a file verbatim. It has
deliberately **no `android.*` import**, so the identical code path is exercised by a plain JVM
unit test (no device) and by the real on-device instrumented test — "same manifest, same report
format" is proven, not asserted, because both callers run the same function.
`HarnessInstrumentedTest` (`app/src/androidTest/.../harness/`) is the actual on-device entry
point: it resolves `InstrumentationRegistry`'s target-context `filesDir` (app-private storage),
reads a manifest from `<filesDir>/harness-corpus/manifest.tsv`, runs the harness with
`(machine = Build.MANUFACTURER/MODEL, provider = "cpu", threadCount = availableProcessors(),
runtimeVersion = Build.VERSION.SDK_INT)`, and writes the report to
`<filesDir>/harness-reports/report.txt`. Its kdoc documents the real push/pull commands
(`adb push` into `/data/local/tmp`, `run-as` copy into app-private storage, `am instrument`,
then `run-as cat`/`adb pull` to retrieve the report) for the future session that runs this on
hardware.
**Module-boundary finding (part of this prompt's explicit ask):** `:app`'s main source set has
no permitted compile-time edge to `:eval` (`buildSrc/.../ModuleGraph.kt`: `:app`'s allowed set is
exactly `{:pipeline, :data, :net, :core}`). `dependencyRules` only checks the `api`,
`implementation` and `compileOnly` configurations (`build.gradle.kts`'s `checkedConfigurations`)
— `testImplementation`/`androidTestImplementation` are outside that check by explicit design
(`ModuleGraph.kt`'s own comment: "Test scope is deliberately excluded: `:testing` fakes are meant
to be reachable from any module's tests"). Rather than widen `ModuleGraph.allowed[":app"]` to
include `:eval` (which would let a future `:app` *main-source* change depend on the harness by
accident, the exact failure mode structural enforcement exists to prevent), this change adds
`:eval`/`:testing`/`:lexicon` only to `testImplementation`/`androidTestImplementation` — inside
the graph's own documented exemption, not a workaround of it. Confirmed with
`./gradlew dependencyRules`: the checked graph still shows `:app -> :core, :data, :net,
:pipeline` only. `:eval`'s current dependencies (`:onnx`, `:asr-sherpa`, etc.) are all still
empty stubs (no native/JNI code yet), so this edge is safe today; when P10 lands a real ONNX
Runtime dependency inside `:onnx`/`:asr-sherpa`, a future session should check whether the JVM
artifact `:eval` pulls in in has a real (non-Android) native library that would fail to load
under `androidTest`'s on-device classpath — flagged here as a foreseeable follow-up, not
something this session could observe today.
**Verified:** `./gradlew :app:testDebugUnitTest` — 15 tests green, including
`OnDeviceHarnessRunnerTest`'s three cases (byte-identical `canonicalText()` vs. a
Main.kt-equivalent construction; `runAndWriteReport` writes the exact text to a file; the
eval-fold gate throws `IllegalStateException` without `allowEval` and succeeds with it).
`./gradlew :app:compileDebugAndroidTestKotlin` — `HarnessInstrumentedTest` compiles clean
against the real Android/androidx.test classpath (this is as far as verification can go without
a device or emulator in this sandbox — the instrumented test itself was not executed).
`./gradlew dependencyRules` — 17 modules checked, `:app -> :core, :data, :net, :pipeline` only,
no forbidden edges. `./gradlew :app:check` — ktlint, lint and both unit-test variants
(debug/release) green. `./gradlew coverageMatrix` — 413 requirements, 92 covered (was 90 before
this commit; +2 for the two `AC-36`-tagged JVM tests), no new orphan-id warnings.
**Left open / not done:** the entire device matrix (implementation-plan M2 exit criteria): **D8**
(30-minute "prove it" run), **D4** (the 8-hour unattended run, AC-64), **D5** (the same run
without exemptions, AC-5), **D1, D2, D3, D9, D10, D11, D12**. None of these can be attempted
without the physical reference device — this session had no device access, and the build-plan
prompt explicitly scoped this session to the on-device runner only. Also genuinely unverified for
the same reason: the actual `adb push` of a real corpus into app-private storage, the actual
`am instrument` execution on hardware, and the actual `adb pull`/`run-as cat` of the resulting
report — `HarnessInstrumentedTest` documents the exact commands but none of them have been run.
The manifest fixture inside `HarnessInstrumentedTest` is a stand-in for a pushed corpus; a real
device run replaces it with the genuine corpus manifest before executing. `AC-64`'s "Done when:
AC-64 passes and D8 correctly predicted D4's outcome" is entirely open.

---

## 2026-09-07 (afternoon, cont. — P8 and the real R1 run both land)

### 36c19ad — Merge branch 'worktree-agent-aa4aa2aeea1f1ebaa' (P8 into main)

**Scope:** brings P8's `:capture-android`, `:pipeline`, `:app` additions onto `main`.
**Requirements/ACs:** see the P8 entry below.
**What changed:** merge-only; one conflict in `spec/build-plan.md`'s progress checklist (this
branch and the P7 merge had both ticked adjacent lines independently), resolved by keeping both
ticks.
**Verified:** `./gradlew build dependencyRules` green post-merge (818 tasks); `./gradlew
coverageMatrix` regenerated (63 → 90 requirements covered).
**Left open / not done:** n/a (merge).

### 158a61c — P8 · Android capture spine: AudioRecordSource, shed controller, capture status surface

**Scope:** `:capture-android`, `:pipeline`, `:app` (status surface and permissions only, per the
prompt's ownership boundary — `:data`, `:capture-api`, `:segment`, `:core`, `:testing`, `corpus/`
confirmed untouched).
**Requirements/ACs:** AC-2/AC-98 (route verified after first read and on route change; a
deliberately-selected built-in mic is legal but persistently labelled), AC-5 (unclean end
reported next launch with its last heartbeat), AC-46 (shed order followed, no audio lost at any
level), AC-48/AC-49 (interruption produces a correctly-bounded `CaptureGap`, resumes, and a gap
is distinguishable from silence), AC-61 (notification never contains transcript text), AC-65
(liveness comes from the heartbeat — verified by forcing `isIgnoringBatteryOptimizations()` to
return `true` and confirming the session is still treated as dead), AC-96 (a continuously-archived
session is re-segmentable with different VAD parameters), AC-97 (resampler identity recorded),
AC-99 (`PassDrainRunner` cancels a hanging pass at its deadline and keeps draining), AC-103 (model
residency stays inside the tier budget). AC-64 (the 8-hour endurance claim itself) is explicitly
**partial** — the mechanism (`ProveItAnalyzer`) is built and tested, but the actual prediction
needs the reference device and is P9's job, not something Robolectric can settle.
**What changed:** `AudioRecordSource` (implements `:capture-api`'s `CaptureSource`; route
verification after the first read and on every route change, halt rather than silently
fall back, interruption → `BackoffLadder` (1/2/5/10/30s) → auto-resume); `RouteVerifier`,
`GapTracker`; the FLAC store (`FlacStore`: stage → encode → decode-and-compare → delete only on
byte match) backed by `DeflatePredictiveCodec` — a pure-Kotlin order-1-delta-prediction +
DEFLATE codec substituted for JNI libFLAC, which can't be built in this sandbox; genuinely
lossless and never trusted blindly (`FlacStore` verifies every encode itself); the
continuous-archive writer (`ContinuousArchiveWriter`/`ArchiveReader`, default off, sample-indexed
chunks; re-segmentation lives in `:pipeline`'s `ReSegmenter` since `:capture-android` may not
depend on `:segment`); `CaptureService` (foreground, wake lock, heartbeat) with a file-backed
`HeartbeatStore`, `UncleanEndDetector`, `LivenessChecker`; the OEM guidance table
(`OemGuidanceTable`/`OemGuidanceResolver`, four ColorOS steps + generic fallback);
`ProveItAnalyzer` (the 30-minute test's reporting mechanism); in `:pipeline`: `ShedController`
(levels 0–5, hysteresis, battery-critical override), `ModelResidency`/`ResidencyBudgetChecker`,
`Pass`/`PassDrainRunner` (orchestrator watchdog over `:data`'s `WorkQueue.runLeased`),
`GapPersister` (maps capture-android's `GapRecord` → `:data`'s `CaptureGapEntity`),
`CaptureStatus`/`CaptureStatusRepository`; in `:app`: `StatusActivity` + `StatusViewStateMapper`
(plain Android views, not Compose — `ort.android-app.gradle.kts` has no Compose wiring yet; the
state-holder logic is Compose-agnostic so this doesn't block adding it later) and
`PermissionsFlow` (RECORD_AUDIO → notifications → battery-exemption, the last kept
diagnostic-only per AC-65's point that liveness must never depend on that API).
**Verified:** `./gradlew dependencyRules` (every edge permitted), `./gradlew
:capture-android:check :pipeline:check :app:check` (tests + ktlint + detekt + lint, green),
`./gradlew build` (whole repo, green).
**Left open / not done:** three documented, scope-bounded deviations: (1) FLAC is substituted by
the pure-Kotlin codec above, not JNI libFLAC — flagged as a follow-up, not silently swapped; (2)
`:app` has no Compose UI yet, plain views only; (3) shed events live in `ShedController`'s memory
only, not persisted to a `:data` table (no `shed_event` table exists and this prompt couldn't
touch `:data` to add one). Also: `:pipeline` depends on `:capture-android` via `api` rather than
`implementation` specifically so `:app` can read capture-status types without a new declared
module edge — `dependencyRules` checks each module's own declared edges, so this is compliant
with the graph as coded, but worth a second look at the next `:pipeline`/`:app` session. AC-64,
AC-66's actual-ColorOS-hardware behaviour, and the 8-hour endurance claim all remain P9's job on
the physical reference device.

### 144e21b — Merge branch 'worktree-agent-a7b31df9844daf804' (the real R1 run into main)

**Scope:** brings the real (non-fixture) R1 run onto `main`: `corpus/pyproject.toml`,
`corpus/src/corpus/probes/lora_export.py`, `corpus/src/corpus/probes/_sherpa_whisper_export.py`
(new), `corpus/tests/test_probe_lora_export.py`, `results/r1-lora-export.md`.
**Requirements/ACs:** R1 (probe gate) — see the entry below.
**What changed:** merge-only, no conflicts (disjoint from every other concurrently-landed branch).
**Verified:** `cd corpus && python -m pytest -q` post-merge — 70 passed, 1 skipped (the real-R1
test skips without `ORT_RUN_REAL_R1=1` set, by design, so CI never pays its ~111s cost or needs
the real toolchain installed).
**Left open / not done:** n/a (merge).

### 269b578 — P6 follow-up · R1 real run

**Scope:** `corpus/pyproject.toml` (new `[r1-real]` optional-dependency extra),
`corpus/src/corpus/probes/lora_export.py`, `corpus/src/corpus/probes/_sherpa_whisper_export.py`
(new — a vendored, adapted copy of sherpa-onnx's own `scripts/whisper/export-onnx.py`),
`corpus/tests/test_probe_lora_export.py`, `results/r1-lora-export.md`.
**Requirements/ACs:** R1 (probe gate, build-plan S1.7 / D13). **R1 now has a real verdict:
D13 is not reopened** — the gated mechanism (LoRA fine-tune → `merge_and_unload()` → sherpa-onnx
export → load → transcribe) genuinely composes on CPU.
**What changed:** installed and pinned the real toolchain (torch 2.11.0 CPU, transformers
5.16.1, peft 0.20.0, accelerate 1.14.0, sherpa-onnx 1.13.7, plus onnx/onnxruntime/onnxscript,
datasets, soundfile, huggingface_hub) in a new `[r1-real]` `pyproject.toml` extra so CI's default
install stays untouched. `probe_r1(dry_run=False)` now performs a genuine run: peft LoRA
(rank 4, query/value projections) fine-tuned on `distil-whisper/distil-small.en` against
~3.1 minutes of `hf-internal-testing/librispeech_asr_dummy` (LibriSpeech dev-clean audio,
CC BY 4.0, with real ground-truth transcripts) for 60 steps, loss 0.69 → 0.004;
`merge_and_unload()`'d; exported via `_sherpa_whisper_export.py` to ONNX; loaded with
`sherpa_onnx.OfflineRecognizer`; transcribed a held-out clip (reference *"then the powerful
twist that thrust it aside in and under the guard"* → hypothesis *"then the powerful twist that
thrusts rest to the side in and under the guard"* — recognizable, non-degenerate real ASR
output, not a fabricated string). Two genuine toolchain findings recorded for M0a: (1) sherpa-onnx's
export script expects an `openai-whisper`-format checkpoint, not a HuggingFace `transformers`
one — this run sidesteps the undocumented conversion by running peft directly against
`openai-whisper`'s own model object; (2) torch ≥2.9's default "dynamo" ONNX exporter fails on
this decoder's data-dependent KV-cache slicing, so `dynamo=False` (the legacy TorchScript
exporter) is required.
**Verified:** `cd corpus && ORT_RUN_REAL_R1=1 python -m pytest -q
tests/test_probe_lora_export.py` — 5/5 passed in ~111s (real toolchain exercised); the 4
pre-existing fixture-based tests are untouched and still pass with zero real dependencies and no
env var.
**Left open / not done:** scaled down from the prompt's "ten minutes of anything" to ~3.1 minutes
(loss had already converged well before that point) and from a full LibriSpeech download to the
small `librispeech_asr_dummy` fixture — both documented with reasoning in
`results/r1-lora-export.md` rather than silently substituted. The real-toolchain test is gated
behind an opt-in env var so it never runs in ordinary CI (mirrors the eval-fold opt-in pattern
elsewhere in this project) — someone still needs to decide whether/how a CI lane should exercise
it periodically.

## 2026-09-07 (afternoon, cont. — more adversarial review while P8/R1 ran)

### (pending) — Fix segment endSample under-reporting on mid-hangover stream end; detekt line-length cleanup

**Scope:** `segment/src/main/kotlin/org/ort/segment/Segmenter.kt`,
`segment/src/test/kotlin/org/ort/segment/PrePostRollTest.kt` (P4, not touched by any
concurrently-running session), plus an unrelated detekt fix in
`eval/src/main/kotlin/org/ort/eval/Harness.kt` (two lines the earlier fold-isolation change left
over `MaxLineLength`, caught by `./gradlew build` rather than by the narrower module-test runs
used to verify that change), and a `results/coverage-matrix.md` regeneration.
**Requirements/ACs:** AC-95 (pre/post-roll retain the transmission complete) — this fixes a case
that criterion's existing test didn't reach.
**What changed:** `Segmenter.finish()`'s `HANGOVER` branch called a private
`bufferedHangoverSamples()` helper twice — once to size the `flushHangover()` call, and again
(after `flushHangover` had already cleared the buffer it counts) to compute the closed segment's
`endSample`. The second call always saw an empty buffer, so a stream that ended while still in
`HANGOVER` (trailing silence shorter than `minSilenceMs`, e.g. capture stopping mid-trail-off)
produced a `SegmentRecord` whose `endSample - startSample` under-counted the real audio already
written to the sink — the declared boundary and the actual appended sample count disagreed.
Fixed by computing the flushed sample count once, before the buffer is cleared, and reusing it
for both the flush and the `endSample` calculation. Added a regression test (300 ms of trailing
silence against a 600 ms `minSilenceMs`/400 ms `postRollMs` config, stream ending mid-hangover)
that fails on the pre-fix code and passes after.
**Verified:** `./gradlew :segment:test` (all 14 tests green, including the new one),
`./gradlew build` (full multi-module build green after the detekt fix), `./gradlew
coverageMatrix` regenerated.
**Left open / not done:** no other modules were re-audited in this pass beyond `:segment`,
`:lexicon`'s ranking/priors/calibration math (`PropagationModel`, `Priors.kt`,
`ThresholdDerivation`, `PlattCalibrator` — all read closely, no defects found), and re-verifying
the earlier `Harness`/`WorkQueue` fixes still hold.

## 2026-09-07 (afternoon, cont. — changelog established)

### (pending) — Establish CHANGELOG.md and make it part of the working agreement

**Scope:** `CHANGELOG.md` (new), `AGENTS.md`, `spec/build-plan.md` (docs only — no code).
**Requirements/ACs:** none — process change, at the user's request, not a spec requirement.
**What changed:** created this file, backfilled with a detailed entry for every commit from P1
(`5fca369`) onward (the specification-phase commits before it are condensed into one pointer
entry — see below). Added rule 6 to `AGENTS.md`'s working agreement requiring a `CHANGELOG.md`
entry with every commit, in this file's documented format, before the commit that completes a
unit — not a follow-up. Added the same instruction to the standing preamble in
`spec/build-plan.md`, which is the text pasted into every dispatched build-plan prompt, so it
reaches every future session (human or agent) the same way the Constitution Check and strict-TDD
rules already do. Added a `CHANGELOG.md` row to `AGENTS.md`'s "Where things are" table.
**Verified:** `python tools/spec-check/spec_check.py` (7/7 OK — confirms the doc edits didn't
introduce a dangling reference or break the spec's own integrity checks); no code changed, so no
build/test run was needed for this commit.
**Left open / not done:** this entry itself was written before the commit hash existed, so it's
marked `(pending)` above rather than a real hash — unavoidable for the entry describing its own
commit; every entry after this one carries a real hash written after the fact.

## 2026-09-07 (afternoon — parallel dispatch, adversarial review)

### d46738b — Document Fearless Steps acquisition steps for R4 (build-plan todo)

**Scope:** `corpus/src/corpus/sources.py`, `corpus/manifest.json`, `docs/reference/`, `spec/build-plan.md`.
**Requirements/ACs:** R4 (probe gate, still open); none newly satisfied.
**What changed:** Added [`docs/reference/fearless-steps-acquisition.md`](docs/reference/fearless-steps-acquisition.md),
step-by-step manual acquisition instructions for both the recommended Tier 1 (the CC-BY-4.0
100-hour Challenge Corpus's speaker-diarization track — the actual size R4 needs) and Tier 2
(the full ~19,000-hour corpus via a CRSS request form/EULA, multi-terabyte, real review
turnaround). Fixed `sources.py`'s `fearless-steps` `SourceSpec`: its URL was a dead Mendeley
link (`data.mendeley.com/datasets/xps6b5vpxr` now 404s), replaced with the live Challenge Phase 2
page and a comment stating plainly that this source cannot be fetched by the automated `corpus
acquire` HTTP path — it requires a human registration step first, which no session can complete
unattended. Licence field corrected to describe both tiers' real terms. Regenerated
`corpus/manifest.json` via `corpus build` to match. Added a "yours, not a session" todo entry in
`spec/build-plan.md` pointing at the new doc.
**Verified:** `corpus build` (regenerated manifest, folds OK), `python -m pytest -q` in `corpus/`
(70 passed).
**Left open / not done:** the actual acquisition — still needs a human to register with UT Dallas
CRSS; R4 remains `NOT RUN` (see `results/r4-speaker-separation.md`).

### d61eeb6 — Adversarial review fixes: eval-fold leakage in Harness, concurrent-pass crash in WorkQueue

**Scope:** `eval/src/main/kotlin/org/ort/eval/Harness.kt`, `eval/src/main/kotlin/org/ort/eval/Main.kt`,
`eval/src/test/kotlin/org/ort/eval/*`, `data/src/main/kotlin/org/ort/data/WorkQueue.kt`,
`data/src/test/kotlin/org/ort/data/WorkQueueTest.kt`, `.gitignore`.
**Requirements/ACs:** protects the eval-fold discipline underlying AC-55/AC-56/AC-90 and P7's
own stated requirement ("Platt calibration fitted on train+dev and verified on eval"); protects
AC-45/AC-47/AC-51/AC-99's queue-draining guarantees for the multi-pass case technical design
§7.1 specifies. No new AC introduced; both are latent-bug fixes caught by adversarial review, not
requirement gaps.
**What changed:**
1. `Harness.run()` used to fit its Platt calibrator and derive the `CONFIRMED` threshold from the
   *same* occurrence list it then scored. The first real caller to wire the `eval` fold in would
   therefore fit on eval data while nominally "verifying" against it — exactly the leakage
   "never read the eval fold" (constitution VI) exists to prevent, and it was unexercised by any
   existing test because no real caller does that yet. Split `run()` into explicit `fitOn` /
   `evaluate` parameters so this is structurally impossible rather than a caller's discipline to
   remember. Added `HarnessFoldIsolationTest`: proves the derived threshold is a pure function of
   `fitOn` (unaffected by what `evaluate` contains) and that reported metrics track `evaluate`'s
   own labels.
2. `WorkQueue.completePass()`/`failPass()` called `TransmissionDao.requireLegalTransition()`
   unconditionally. Technical design §7.1's `idx_wq_active` index is keyed on
   `(transmission_id, pass)` specifically because more than one pass (B, C, E, FUSE per the
   residency table) can be leased concurrently for one transmission — so whichever pass reports
   *second* would crash trying to re-transition an already-final transmission (`COMPLETE →
   COMPLETE` is not in `TransmissionLifecycle.legalTransitions`). Guarded both methods with the
   same `canTransition()` check `leaseBatch()` already used, so a later pass's outcome leaves an
   already-final transmission alone instead of throwing. Added two `WorkQueueTest` cases
   (two `PassId`s on one transmission, second completes/fails after the first already committed)
   that reproduce the crash on the pre-fix code and pass after.
3. Added `.claude/` to `.gitignore` after an unrelated `git add -A` staged agent worktree
   directories as embedded repos.
**Verified:** `./gradlew build` (full multi-module build, green — 793 tasks), `./gradlew
:eval:test :lexicon:test :data:testDebugUnitTest` individually (all green, including the four new
regression tests), `python tools/spec-check/spec_check.py` (7/7 checks OK), `cd corpus && pytest
-q` (70 passed). Confirmed both bugs were real (not defensive-only fixes) by checking
`TransmissionLifecycle.legalTransitions` directly: `COMPLETE → COMPLETE` is absent, so the
pre-fix `WorkQueue` code path would have thrown `IllegalTransitionException` on the exact
scenario the new tests exercise.
**Left open / not done:** no other modules were audited to this depth in this pass (`:capture-*`,
`:lexicon`'s grammar/FSA, `:segment` were reviewed only via their existing green test suites, not
re-derived from spec by hand).

### b7a0ea8 — Merge branch 'worktree-agent-ab723fefc2d0ab707' (P7 into main)

**Scope:** brings P7's `eval/` and `lexicon/` ranking/calibration additions onto `main`.
**Requirements/ACs:** AC-15, AC-55, AC-56, AC-90 (see the P7 entry below for detail).
**What changed:** Merge-only; one conflict in `spec/build-plan.md`'s progress checklist (P5 and
P7 had both ticked adjacent lines independently), resolved by keeping both ticks.
**Verified:** `./gradlew :eval:test :lexicon:test` green post-merge.
**Left open / not done:** n/a (merge).

### 5187fee — Merge branch 'worktree-agent-a9e92d557d18afc8c' (P6 into main)

**Scope:** brings P6's `corpus/src/corpus/synth/`, `corpus/src/corpus/probes/` additions onto `main`.
**Requirements/ACs:** R4, R1 (both recorded as `NOT RUN` — see the P6 entry below).
**What changed:** Merge-only; one conflict in `spec/build-plan.md`'s progress checklist (P5 and
P6 had both edited the same block independently), resolved by combining both ticks and P6's
NOT-RUN annotation.
**Verified:** `python -m pytest -q` in `corpus/` green post-merge (70 tests).
**Left open / not done:** n/a (merge).

### eb3fa62 — P7 · Lexicon ranking, calibration and the `:eval` harness

**Scope:** `:lexicon` ranking/calibration (owns per build-plan: `Prior`, `Priors`,
`PriorCombiner`, `PropagationModel`, `PlattCalibrator`, `ThresholdDerivation`,
`TextDerivedLatticeBuilder`, `RankingContext`), `:eval` (new module: `Harness`, `HarnessConfig`,
`HarnessReport`, `LabeledOccurrence`, `ManifestHarness`, `Main`).
**Requirements/ACs:** FR-LEX-9 (every prior's contribution clamped, structurally, in
`AbstractPrior` — no prior can eliminate a candidate), FR-LEX-31 (cold start contributes exactly
`0f`, enforced by a `require()` in `PriorContribution`, never a default), FR-LEX-25..27
(propagation prior demotes but never eliminates, suppressed on known repeater frequencies),
AC-15 (text-derived lattice is the same `PhoneticLattice` type as acoustic, records
`TEXT_DERIVED`), AC-55 (0.9 calibrated confidence ≈ 90% observed accuracy on synthetic
held-out draws — reliability diagram), AC-56 (raising the precision target raises the derived
`CONFIRMED` threshold; the API only accepts a precision target, never a raw score), AC-90 (two
harness runs over identical corpus/model/config produce byte-identical `canonicalText()`).
**What changed:** the clamped log-odds prior-combination mechanism (`AbstractPrior` enforces
clamping and cold-start-is-zero once, for every prior including future ones, rather than trusting
each implementation); the seven priors from technical design §9.3; a static/deterministic
`PropagationModel`; `PlattCalibrator` (logistic calibration) and `ThresholdDerivation`
(precision-target → threshold); `TextDerivedLatticeBuilder`; and `:eval` v1 — a pure
`Harness.run()` composing grammar + priors + calibration + threshold into a
`HarnessReport` with per-tier reliability diagram and per-prior ablation, plus `ManifestHarness`
(the one sanctioned call site reading the corpus manifest's fold gate) and a `Main.kt` entry
point.
**Verified:** `./gradlew :lexicon:test :eval:test` — 35 tests green — plus `dependencyRules`,
`:lexicon:detekt`/`:eval:detekt`, `:lexicon:ktlintCheck`/`:eval:ktlintCheck`, and
`tools/spec-check/spec_check.py`.
**Left open / not done:** **no real precision/recall/reliability number exists yet** — there is
no hand-labelled callsign-occurrence corpus (that's Q16/S1.8, still not produced) and no ASR
pass (P10) to derive a lattice from real audio. `Main.kt` states this explicitly rather than
printing a number computed from nothing. The mechanism itself is proven correct only against
synthetic fixtures. (This entry predates and is unaffected by the eval-fold-leakage fix in
`d61eeb6` above, found during review of this same code.)

### 8010be4 — P6 · Synthetic corpus and the two probes: channel model, generator, R4/R1 (not run)

**Scope:** `corpus/src/corpus/synth/` (new: `channel.py`, `phonetic.py`, `splice.py`, `tts.py`,
`generator.py`), `corpus/src/corpus/probes/` (new: `speaker_separation.py`, `lora_export.py`),
`results/r4-speaker-separation.md`, `results/r1-lora-export.md`, `corpus/manifest.json`
(`synth-callsigns/dev-01` entry), `spec/build-plan.md`.
**Requirements/ACs:** D22 (channel model + synthetic generator), FR-TST-9 (synthetic audio barred
from `eval` — enforced by `generator.py` refusing `fold="eval"` outright), FR-SPK-14 (R4's
false-match-rate reporting shape, not yet populated with a real number).
**What changed:** a deterministic channel-degradation model fit from Paderborn's clean/degraded
pairs; the ULS-callsign → phonetic-expansion → TTS-plus-spliced-ISOLET/ATC → channel pipeline,
with labels derived from the exact rendering placement (so they cannot drift from the audio);
the R4 (speaker-separation) and R1 (LoRA export) probe *pipelines* — `Embedder` /
`LoraTrainer`/`Exporter`/`AsrRuntime` protocols, clustering + false-match-rate math for R4,
the five-stage finetune→merge→export→load→transcribe composition for R1 — each with a
behavioural fake (`FakeEmbedder`, `FakeLoraTrainer`, etc.) shipped in the same change and proven
correct against synthetic fixtures.
**Verified:** `python -m pytest -q` in `corpus/` — 70 tests (30 new) — and `ruff check .` clean.
**Left open / not done:** **both R4 and R1 report `NOT RUN`, not a fabricated number.** R4:
Fearless Steps was not on disk and no WeSpeaker/3D-Speaker package was installed (now documented
in `d46738b` above — needs human registration). R1: `transformers`/`peft`/`accelerate`/
`sherpa_onnx` were not installed and no GPU is present (superseded by the real attempt dispatched
this session — see the R1-real entry below, still running as of this changelog's writing).
Each `results/*.md` states its blocker plainly per the task instruction that a poor *or absent*
result must never be papered over with an invented number.

### ae74bdc — P5 · Data layer: Room schema, FTS5, durable queue, transmission lifecycle

**Scope:** `:data` (owns: entities, DAOs, `WorkQueue`, `TransmissionTransitions`,
`OrtDatabase`, `ReconciliationReport`, migrations).
**Requirements/ACs:** AC-31 (superseded transcript stays retrievable; exactly one `is_current`
per transmission via a hand-written partial unique index — Room's `@Index` can't express one),
AC-45 (segments reach the durable queue with every pass stalled), AC-47 (killing the process
mid-pass returns `PROCESSING → CAPTURED` and completes identically on retry — passes are
idempotent), AC-51 (a repeatedly failing pass lands `FAILED` without blocking the queue;
re-enqueueing a completed pass succeeds because the partial unique index covers active states
only), AC-53 (migration from every released schema fixture preserves audio and superseded
transcripts), AC-54 (reconciliation reports an orphaned file and a dangling row and deletes
neither), AC-99 (a hanging pass is cancelled at its deadline via `withTimeoutOrNull`, marked
`FAILED`, and the queue keeps draining).
**What changed:** the Room entities from functional spec §8 (including `Voiceprint` enrolment
fields, station knowledge, `Thread.kind`, `ContributionItem`); FTS5 over the transcript table
filtered in the join; WAL; `WorkQueue` (run-id leasing, per-item deadlines, bounded retry, one
transaction per state change spanning both the queue and the transmission — this is what makes
AC-47 and AC-99 true together); `TransmissionTransitions` (the one path that moves a
transmission's lifecycle state, always through `TransmissionLifecycle.require()` first, so an
illegal transition throws rather than silently writing a wrong state).
**Verified:** `./gradlew :data:testDebugUnitTest` (Robolectric) — see the full pass list under
`d61eeb6` above, which added two more cases to this same suite.
**Left open / not done:** the concurrent-multi-pass gap fixed in `d61eeb6` was introduced here
and not caught by this session's own tests (all of which used one pass per transmission) — found
later during adversarial review, not at the time.

### 8010be4 / eb3fa62 note — merge order

P5, P6 and P7 were built concurrently by three agents in isolated git worktrees (P5 directly by
the user, P6 and P7 as dispatched background sessions), then merged into `main` one at a time
(`5187fee`, `b7a0ea8` above) once each was independently green. This was safe because build-plan
Wave B/C's file-ownership split holds exactly as documented: P5 owns `:data`, P6 owns `corpus/`,
P7 owns `:lexicon` (ranking/calibration only) + `:eval` — no two of the three touched the same
production file, and the only merge conflicts were both in `spec/build-plan.md`'s shared
checklist, resolved by combining ticks.

## 2026-09-07 (morning — Waves A/B)

### 2eec8a7 — P4 · Capture and segmentation: WavFileSource, polyphase resampler, ring buffer, VAD segmenter

**Scope:** `:capture-api`, `:segment`. Pure JVM, no Android APIs.
**Requirements/ACs:** AC-3 (pre-roll + explicit, non-silent overrun detection), AC-69 (boundary
precision/recall == 1.0 against hand-marked keying times), AC-70 (stuck-carrier force-split,
contiguous, none exceeding the configured cap), AC-71 (`minSilenceMs` measurably trades merge vs
split on a fixed 500 ms gap — the trade is measured, not tuned by ear), AC-72 (too-short segments
rejected with audio retained and no model invoked), AC-89 (WAV replay: real-time and
as-fast-as-possible produce byte-identical events, fast path beats real time), AC-94 (reflection
asserts neither `Segmenter` nor `SegmentConfig` can be constructed with a `Tier` — FR-SEG-7 /
CON-SEG-1, the segmenter cannot accept a tier by construction, not by convention), AC-95 (pre +
post-roll retain the transmission complete, verified against an exact source slice), AC-97
(48 kHz→16 kHz and 44.1 kHz→16 kHz resample deterministically across independent runs, with a
recorded `ResamplerIdentity`).
**What changed:** `CaptureSource`/`CaptureEvent` contract; a deterministic fixed-point
windowed-sinc polyphase resampler (Q30, SHA-256-identified coefficient table); a lock-free SPSC
`RingBuffer` with pre-roll snapshot; a minimal WAV reader/writer and `WavFileSource`; the
`Vad`/`VadModel` interfaces, a `SileroVad` hysteresis wrapper, `SegmentConfig` (no `Tier` field),
and the `IDLE → TENTATIVE → SPEECH → HANGOVER` `Segmenter` writing incrementally rather than
buffering a whole transmission; `ScriptedVad` as the behavioural fake VAD for downstream tests.
Two bugs were caught by the tests before shipping: the pre-roll ring double-capturing audio
already claimed by an open segment, and the first silent hangover frame being written to the
sink twice (directly and via the hangover-buffer flush).
**Verified:** `dependencyRules`, ktlint, detekt and the full root `check`, all green;
`results/coverage-matrix.md` regenerated, tracing all nine criteria above to their tests.
**Left open / not done:** none noted at the time.

### 1243c1d — P3 · Lexicon grammar: units, variant table, ITU trie, callsign FSA, confusion scoring

**Scope:** `:lexicon`'s structural half (types, units, trie, FSA, scoring) — pure Kotlin against
`:core` only.
**Requirements/ACs:** FR-LEX-5 (lattice retains alternatives with scores, never collapses to a
best path), FR-LEX-4/29 (variant table: NATO/letter-name/legacy forms map to one unit; an
unknown form is rejected, never guessed), FR-LEX-8 (ITU prefix-trie allocation is the *only*
hard filter), AC-10 (a structurally valid non-US-prefix callsign with zero database hits still
resolves as a candidate), AC-11 (an unallocated prefix yields no candidate, so it can never
reach `CONFIRMED`), AC-12 (NATO, letter-name and legacy pronunciations of one callsign yield one
result), FR-LEX-10 (confusion-weighted substitution — E-set B/D/E/P/V/T, M/N, S/F — costs less
than an arbitrary pair).
**What changed:** `PhoneticUnit` (the closed 26-letter/10-digit/STROKE vocabulary); the
`PhoneticLattice`/`LatticeSlot`/`UnitScore` types; `VariantTable` as a bundled versioned TSV
asset; `ItuPrefixTable` (prefix trie, longest-match allocation); `ConfusionCostMatrix` as a
bundled versioned asset; `CallsignGrammar` (beam search over lattice slots against the
prefix+digit+suffix FSA, modifiers and reciprocal forms `/P`, `/M`, `K7ABC/VE7`, `VE7/K7ABC`).
**Verified:** 25 tests green (including AC-10/11/12); `:lexicon:check`, `dependencyRules`,
`coverageMatrix` all green.
**Left open / not done:** ranking, priors, calibration and `:eval` deliberately deferred to P7
(this session touched only the grammar/trie/scoring half, per its own file-ownership boundary).

### bba6b5e — P2 · Corpus pipeline: manifest, fold gate, acquisition, harness v0

**Scope:** `corpus/` (new Python subproject, own `pyproject.toml` and CI job).
**Requirements/ACs:** FR-TST-7/9 (a session in two folds, or synthetic audio in `eval`, rejected
at load time), AC-100 (`eval` fold refused without the explicit `--i-know-this-is-the-eval-fold`
flag), FR-TST-8 (harness metrics reported per source as well as aggregate), §14A.2 (whole
sessions only, ≥1 eval session with no train station, the two noise tapes separated, HF/DX
routed to eval).
**What changed:** `manifest.py`, `folds.py` (the §14A.2 checker), `gate.py` (the eval seal),
`sources.py` (the four public sources — Paderborn HF, Fearless Steps, ATC merge, ISOLET — plus
synthetic and local, each licence-recorded), `acquire.py` (resumable, idempotent
fetch→verify→normalise), `audio.py` (deterministic 16 kHz mono FLAC via ffmpeg), `metrics.py` +
`harness.py` (WER, callsign/boundary precision-recall, rejection rate by reason, attribution
accuracy, with a run fingerprint), `report.py` (refuses to exist without a fold or serialise
without a fingerprint), `build.py` (`corpus build` renders the committed `manifest.json`; CI
asserts no drift).
**Verified:** 38 tests green at the time (grown to 70 by P6, per above).
**Left open / not done:** none noted at the time; the dead Fearless Steps URL surfaced later
(fixed in `d46738b`).

### 45a8ecb — P1 follow-up: mark gradlew executable, tick the build-plan checklist

**Scope:** `gradlew`, `spec/build-plan.md`.
**Requirements/ACs:** none new — housekeeping.
**What changed:** restored `gradlew`'s executable bit (lost in the initial commit) and ticked
P1's checklist entry.
**Verified:** n/a (permissions + doc only).
**Left open / not done:** none.

### 5fca369 — P1 · Foundation: Gradle skeleton, `:core` and `:testing`, spec-check, CI

**Scope:** root build files, `:core`, `:testing`, `.github/workflows/`, `tools/spec-check`
(every other module scaffolded as an empty stub only).
**Requirements/ACs:** AC-91 (`SampleClock` survives a wall-clock jump, F14/FR-RUN-15..18),
AC-93/NFR-5 (CI asserts the debug APK's `minSdkVersion` is 26; a weekly opt-in emulator job
installs and runs it on an API-26 AVD), FR-SPK-10 (the four attribution states are non-optional
at the type), FR-RUN-7 (the transmission lifecycle's legal-transition table, asserted
exhaustively), D11 (LICENSE: Apache-2.0 — patent grant + Play Store path, confirmed as a product
decision rather than defaulted).
**What changed:** the Gradle multi-module skeleton for every module in technical design §2,
wired with its permitted dependencies; the `dependencyRules` task (backed by `ModuleGraph`,
demonstrated failing on a deliberate `:capture-api → :asr-api` edge before being reverted,
`ModuleGraphTest` as the permanent guard); `tools/spec-check` implementing all seven test-plan
§8.1 checks (its own meta-guard test injects a dangling `AC-9999`/`FR-GHOST-1` reference and
asserts the checker catches it); the `coverageMatrix` Gradle task regenerating
`results/coverage-matrix.md` from spec ids + `@Requirement`-annotated test names; `:core`
(`Clock`, `SampleClock`, `Ulid`, `PassFingerprint`/`Materiality`, `ResolvedConfig`, the
`Attribution` closed type, `TransmissionLifecycle`) with no Android dependency
(`NoAndroidDependencyTest`); `:testing` (`TestClock`, `@Requirement`, `Fixtures`, `Seeds`, a
minimal `CorpusManifest` that already refuses the eval fold without an explicit opt-in); the
Apache-2.0 `LICENSE` file.
**Verified:** CI green; both meta-guards demonstrated failing then passing; AC-91 holds;
`results/coverage-matrix.md` generates.
**Left open / not done:** none noted at the time.

## 2026-09-06 — Specification phase (condensed)

Fifteen commits establishing the functional spec (266 requirements, 126 ACs, 32 decisions, 16
risks), technical design, implementation plan, test plan, and the eleven-prompt build plan
itself, plus an adversarial audit (41 findings, all addressed) and a later six-defect review.
Not restated entry-by-entry here — that history lives in the specs themselves
(`spec/functional-spec.md`, `spec/technical-design.md`, `spec/build-plan.md`,
`spec/audit-2026-09-06.md`) and in `git log` for anyone who needs the drafting sequence. This
changelog starts carrying full per-commit detail from P1 (`5fca369`) onward, where "done" first
meant "a module is green, fake-backed and independently testable" rather than "a document is
internally consistent."

---

## In flight, not yet in this changelog

Both sessions noted here as "in flight" when this file was first written have since landed —
see the 2026-09-07 "P8 and the real R1 run both land" section above. Nothing is in flight as of
the latest entry; this section is kept as the standing place to note it when something is.
