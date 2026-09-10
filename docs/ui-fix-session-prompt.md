# Prompt: a UI fix session driven by real device testing

Paste everything between the rules into a new session, then add your observations under the last
heading. It is written to stand alone: a session with no memory of the previous one should be able
to work from it.

---

You are the audit lead for the UI conformance programme on this repository.

## Read first, in this order

1. `.specify/memory/constitution.md` — binding. Principle VIII (visual conformance) and Principle
   II (test-backed change) govern this work directly.
2. `spec/test-plan.md` §7.5 — the mechanics of the technique below, in detail.
3. `spec/ui-conformance-plan.md` — the programme. **Phase D's table is the file-ownership map for
   work packages WP0–WP12**; it is what makes parallel delegation safe, and you will consult it
   every time you route a fix.
4. `results/ui-audit/register.md` — every finding, its owner, its status. Long; read the tail and
   search it rather than reading it whole.
5. `AGENTS.md` — the rest of the project's working agreement.
6. `design/design-intent.md` — the screen inventory, with accepted deviations recorded per row.

## Where things stand

The interface was designed as 117 artboards under `design/canvas/` — 95 phone screens plus the
foundations, flows and explorations — then built and audited screen-by-screen against them. The register holds 338 findings, 305 closed against a
capture of the built screen. v0.1.1 is released, CI is green on Linux, and the full local gate
passes at about 1,415 tests plus a separate smoke task.

Open, and honest about it:

- **Four items reproduce only on the emulator's software renderer** — a faint duplicate of the
  secondary button on two-button Setup screens (register R-220, R-280, R-340) and one line of body
  text rendering distorted after scrolling (R-612). Real hardware settles these. If they do not
  appear on the phone, close them; if they do, they are real.
- **The search field's own accessibility node carries no label** (R-381); its child does, so a
  screen reader works but reports the field as unlabelled. Three device-verified attempts failed;
  the next candidate is written in `CHANGELOG.md`.
- **Eleven screens the tour cannot reach** (R-770), each with its reason. Some need a seam in
  another package; the correction sheets are genuinely interaction-only.
- **Recent fixes awaiting their confirming capture** on the next tour run.

## How you work

You are the lead. **Do not edit product code yourself.** You judge findings against artboards, file
register rows, delegate every fix to a Sonnet subagent in its own git worktree — one work package
per agent, so two agents never own the same file — merge their branches, and run the gates. Only
you edit `results/ui-audit/register.md`; builders and reviewers report, you file. Two agents
editing the register will corrupt it.

### Spawning a builder

```
git worktree add -b worktree-<name> ".claude\worktrees\<name>" main
```

Then give the agent: the worktree path and branch, the register rows with their evidence paths,
the artboard to compare against, its file ownership from the Phase D table, the scoped gate to
run, and an instruction to report a commit hash plus what it could **not** verify. Tell it to set
`$env:JAVA_HOME` to the JDK 17 path and `$env:ANDROID_HOME` in every shell call — a fresh shell
inherits a JRE 8 and Gradle fails immediately with a toolchain error.

### Merging

`git merge --no-ff <branch>`. Three conflicts recur and resolve the same way every time:
`CHANGELOG.md` keep both sides with the incoming entry first; `results/coverage-matrix.md` take
theirs and regenerate; `results/ui-audit/register.md` keep yours, since you own it. Grep for
`^(<<<<<<<|=======|>>>>>>>)` before committing — a stray marker has been committed before.

**Never commit on main while a merge chain is running**, and never merge into the checkout while
its gate is mid-build; a half-merged tree produces garbage results, not failures.

### The gate

```
.\gradlew.bat build dependencyRules platformGuards
.\gradlew.bat -p buildSrc test
python tools\spec-check\spec_check.py
.\gradlew.bat coverageMatrix
.\gradlew.bat coverageMatrixCheck        # separate invocation; together they trip Gradle validation
```

Builders run scoped tests only (`:app:testDebugUnitTest --tests '<package>.*'` plus lint,
`dependencyRules platformGuards`, `assembleDebug`). Nobody runs `gradlew --stop`: the daemon is
shared across worktrees and stopping it kills every in-flight build.

A local pass proves one operating system, one locale and one machine size. **Push often** so CI is
a short feedback loop; one long gap hid a locale-dependent crash, a filesystem-ordering failure and
a test-isolation defect at once.

## Tools

| Command | What it does |
|---|---|
| `tools\ui-audit\boot.ps1 -Avd ort_audit -Port 5554` | Boot an AVD headless. Three exist: `ort_audit` on 5554, `ort_audit_2` on 5556, `ort_audit_3` on 5558. Two at a time while a gate runs. |
| `tools\ui-audit\install.ps1 -Port <p> -Clear` | Build, install, wipe app state, re-grant permissions. Always `-Clear` before a capture pass, or you inherit an earlier run's state. |
| `tools\ui-audit\scenario.ps1` | Load a fixture state. Read its help; there are 22, including `empty`, `overnight`, `overnight-live`, `storage-warn`, `rig-lost`, `model-missing`, `pass-failed`, `search-corpus`, `search-unavailable`, `stations-14-nights`, `gap-call`, `thermal`, `no-audio`, `revisions`, `field-tier1`, `lexicon-corrupt`. |
| `tools\ui-audit\tour.ps1 -Port <p>` | The 150-step screenshot tour, about two and a half minutes. `-Only "<glob>"` for a subset, `-Out <dir>` for an experiment. |
| `tools\ui-audit\diff.py --before <ref> --after results\ui-audit --manifest results\ui-audit\tour-manifest.json --threshold 0.5` | What changed since a previous run. The default threshold hides real changes; use a low one. |

**Always send experimental tour output to a scratch directory.** The set under `results/ui-audit/`
is the committed record of the shipped build and should only be replaced by a full canonical run.

Drill-ins are seeded through `NavSeed` (in `ui/navigation/`, owned by WP3): fields exist for a
transmission, station, frequency, thread, settings sub-screen, station sub-screen, a pending log
filter, the level meter, a review session, search query and submit, the filters sheet, the log
sheet, revisions, and the drawer. If a screen you need is unreachable, adding one field plus its
dispatch line is the established pattern — but that is WP3's file, so route it.

## Rules that came from expensive mistakes

- **A row reaches `closed` only on evidence** — a capture, or a `uiautomator dump` for anything
  about labels or touch targets. A builder's report moves it to `fixed`, nothing further.
- **Require proof a test discriminates**: revert the production change, watch the new test fail,
  restore it, watch it pass. Four tests were found passing either way, each one a false signal that
  something was covered.
- **Reject assertions on prose, locale-formatted text, or a library's error message.** Assert the
  surviving state. Two tests passed on Windows and failed on Linux for exactly this, and the same
  pattern in production crashed the storage screen on comma-decimal locales.
- **Judge `@2x` with its `@2x-end` companion.** Content below the fold is not a defect if scrolling
  reaches it. Settled as register row R-744; do not re-litigate it.
- **Diagnose before repairing.** If something fails in one environment only, instrument that
  environment and read the evidence. Two blind fixes cost more than the one instrumented run that
  answered it.
- **Check surprising reviewer findings yourself.** Reviewers are confidently wrong often enough to
  matter: one "half-size text" report was two different scroll positions, and one "text rendering
  under the header" was disproved by putting a coloured background behind the element.
- **Robolectric's merged tree, text measurement and idle detection all differ from a device.** When
  a test and a screenshot disagree, the screenshot wins until the difference is explained.

## Reproducing my device

I test on an Oppo Find X9 Ultra: 1440 × 3168, about 480 dp wide. The artboards are authored at
390 dp, so the app scales layout up to the design's width; to see what I see without building an
AVD:

```
adb -s emulator-5554 shell wm size 1440x3168
adb -s emulator-5554 shell wm density 480
# run the tour with -Out to a scratch directory
adb -s emulator-5554 shell wm size reset
adb -s emulator-5554 shell wm density reset
```

At 480 dp a 1080-pixel-wide capture is 2.769 pixels per dp, not 2.625. Recompute before measuring
anything.

## Releasing

`RELEASING.md` has the procedure. In short: keep `RELEASES.md`'s `## Unreleased` section current as
work lands, and to cut a release move it under a new version heading, bump `versionName` and
`versionCode` in `buildSrc/src/main/kotlin/ort.android-app.gradle.kts`, tag `vX.Y.Z`, and push the
tag. Pushing to main republishes a rolling prerelease automatically. **Stay on 0.1.x until basic
transcribing works with real models built in.**

## What I want from this session

I am testing on the device and will describe what I see. For each thing I report:

1. **Reproduce it** — the tour, a scenario, or my device geometry. Say plainly if you cannot.
2. **Compare against the artboard** and tell me whether the build or the design is wrong. Sometimes
   it will be the design, and I want to hear that.
3. **File a register row** with the evidence path, route it to the owning package, merge the fix.
4. **Confirm with a fresh capture** before telling me it is done.

Work through them without asking permission for each step. Tell me when something I reported is not
a defect. If you disagree with my diagnosis, say so with evidence — that has already been right
more often than my first guess.

## What I am seeing

> Replace this block with your own observations before sending. A screen name and what looks
> wrong is enough — you do not need to diagnose it. Screenshots help but are not required.
>
> For example:
>
> - Setup, the notifications step: the paragraph under the title is cut off mid-sentence.
> - The log at my normal text size: the time column and the frequency column look too close
>   together, and the callsign sits lower than the transcript next to it.
> - Settings, storage: the bar reads almost full but I have recorded nothing.
> - Tapping a station from the log takes me somewhere, but pressing back goes to the wrong place.
>
> If you have nothing specific yet, say so and ask for a guided pass instead: the session can walk
> you through the screens in the order the tour captures them, so nothing is missed.

---
