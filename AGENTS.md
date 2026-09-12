# AGENTS.md

Guidance for AI coding agents working in this repository. Follows the
[AGENTS.md](https://agents.md) open standard, read natively by Claude Code, Copilot, Cursor,
Codex, Gemini CLI, Windsurf, Aider and others.

**Read [`.specify/memory/constitution.md`](.specify/memory/constitution.md) first.** It is
binding, it is short, and it exists because this product's characteristic failures are silent.

## What this is

An Android application that transcribes amateur and scanner radio traffic **entirely offline**,
resolves callsigns by matching a lexicon **against the audio signal** rather than the transcript,
groups transmissions into conversations, and presents a searchable log and a digest.

**Status (2026-09-12): build plan P1–P21 landed; `v0.1.1` released; every push to `main`
republishes the rolling `latest-build` pre-release with every model bundled.** The interface was
drawn as artboards, built, and audited screen by screen against them; CI and the Release
workflow are green on `main`. Remaining work is driven by what the operator reports from the
device — start such a session from `docs/debug-fix-session-prompt.md`.

## Where things are

| Path | What |
|---|---|
| `.specify/memory/constitution.md` | **Binding principles.** Read first |
| `spec/functional-spec.md` | 277 requirement ids, 140 acceptance criteria, 36 decisions, 18 risks |
| `spec/technical-design.md` | Architecture, module boundaries, subsystem design |
| `spec/build-plan.md` | **The working todo list** — P1–P21, all landed |
| `CHANGELOG.md` | **The build log** — one detailed entry per commit; append to it, don't just tick the plan |
| `RELEASES.md`, `RELEASING.md` | Plain-language release notes (`## Unreleased` first) and the release procedure |
| `spec/test-plan.md` | Testing approach, fake inventory, device matrix, CI; **§7.5 the screenshot tour** |
| `spec/ui-conformance-plan.md`, `spec/e2e-capture-modes-plan.md` | The two programs and their **file-ownership maps** (Phase D, Phase C) — consult before routing any fix |
| `spec/open-questions.md` | Decision register |
| `design/design-intent.md`, `design/canvas/`, `design/design-guide.md` | The screen inventory, the artboards (390 × 844 dp), the tokens |
| `results/ui-audit/register.md` | **Every UI finding**, its owner, status and evidence; only the session lead edits it |
| `results/ui-audit/README.md`, `tools/ui-audit/` | The scenario catalogue and the capture tooling (boot, install, scenario, tour, shoot, diff) |
| `results/e2e-audit/` | The capture-modes checklist, the hardware protocol (H1–H15), the installed-size measurement |
| `docs/debug-fix-session-prompt.md` | **How to start a debugging and fix session** — runs the visual re-verification automatically when a fix touches a screen |
| `docs/ui-fix-session-prompt.md` | The capture technique in depth (superseded as an entry point) |
| `docs/reference/audit-and-remediate-prompt.md` | The spec-audit session (Fable finds, Sonnet fixes) |
| `docs/reference/th-d75a-cat.md` | Verified radio CAT command set |
| `docs/reference/labelling-protocol.md` | Q16's draft labelling protocol — ready to use, not yet piloted |
| `research/01`–`04` | Background. `01` is historical and superseded |

**Requirements are cited by id** — `FR-LEX-8`, `AC-47`, `NFR-1a`, `D28`, `R15`. When you touch
something, cite the id. When you write a test, name it for the id it establishes.

## Working agreement

1. **Start with a Constitution Check.** Name the principles that bear on the work.
2. **Work from the build plan.** Take a whole prompt; do not improvise scope. Within a wave,
   only touch the files your prompt says it owns.
3. **Strict TDD, everywhere.** Test written and failing for the right reason, then the code.
   Ship the behavioural fake in the same change.
4. **Stop rather than half-finish.** If the exit criteria cannot be met, say so and explain why.
5. **Update the plan's checklist** when a unit is done.
6. **Append a [`CHANGELOG.md`](CHANGELOG.md) entry with every commit**, in the format documented
   at that file's top — scope, requirements/ACs, what changed, how it was verified, and what was
   left open. This is not optional and not a summary of the commit message: the changelog is
   read on its own, without `git log`, so write it to stand alone. Do this before the commit that
   completes a unit, not as a separate follow-up.
7. **A change that touches a screen re-runs the visual verification — automatically, triggered
   by the diff.** If your change touches `app/src/main/kotlin/org/ort/app/ui/**`,
   `app/src/main/res/**`, `design/**`, a debug scenario or tour step, or any `*Content`,
   `*Screen`, `*ViewState`, `*ViewData` or `*Mapper` file under `:app`, it is not done until:
   the affected screens are captured again by the tour at font scale 1.0 and 2.0 (and scrolled
   to the end where they scroll) and compared against their artboards; any 2.0 or row/column
   layout change has a Robolectric test at `w390dp-h844dp-420dpi` with native graphics asserting
   bounds; anything about labels or touch targets has a `uiautomator dump`; and the register row
   carries the capture path. Builders capture to a scratch directory and report; the lead judges
   and files. Constitution VIII says why.
8. **Debugging is one loop.** Reproduce in the environment that fails → classify → file the row
   with evidence → route to the owning package (the ownership maps) → discriminating test → gate
   → merge → push → CI **and** Release green → close on evidence. Never close on a report.

## Roles in a session

The lead (Fable) reproduces, judges, files, routes, merges and gates, and never edits product
code; builders (Sonnet, one package each, own git worktree, strict TDD) build and report;
validators drive one scenario set on one emulator and file findings; reviewers compare captures
to artboards with no device. A builder that needs a file outside its package stops and reports.
Never `git stash`; never `gradlew --stop`; never merge while a gate is mid-build.

## Stack

Kotlin · Compose · Room + FTS5 · Coroutines/Flow · Hilt · WorkManager · sherpa-onnx behind
interfaces · `usb-serial-for-android` · Gradle Kotlin DSL · **minSdk 26**, target current.
Desktop tooling under `corpus/` is Python with its own `pyproject.toml` and CI job.

Tests: JUnit5, Turbine for Flow, Robolectric where a device is not required, MockK sparingly —
prefer the project's own fakes, which are behavioural.

## Commands

```bash
./gradlew dependencyRules platformGuards build   # the gate; with HF_TOKEN set and no escape hatch before a push
./gradlew -p buildSrc test                       # a plain build does not run buildSrc's tests
./gradlew :lexicon:test                          # one module
./gradlew coverageMatrix                         # regenerates results/coverage-matrix.md
./gradlew coverageMatrixCheck                    # separate invocation — together they trip Gradle validation
python tools/spec-check/spec_check.py            # spec integrity: ids, dangling refs, traceability
cd corpus && pytest                              # the Python side

# the visual verification (Windows PowerShell; see spec/test-plan.md §7.5)
tools\ui-audit\install.ps1 -Port 5558 -Clear                          # build, install, wipe state
tools\ui-audit\tour.ps1 -Port 5558 -Only "<scenario>/<screen>*" -Out <scratch>   # scoped re-capture
tools\ui-audit\tour.ps1 -Port 5558                                    # the full canonical tour (only this replaces results/ui-audit/)
python tools\ui-audit\diff.py --before <ref> --after <dir> --manifest <manifest> --threshold 0.5
gh run watch <id> --exit-status                                       # the hosted proof: CI and Release
```

`-PortAllowMissingBundledAssets=true` is a local-only escape hatch for a no-token iteration; the
gate before a push, CI and the Release workflow all run without it. Never print `HF_TOKEN`.

## Rules that are not negotiable

These are the ones an agent is most likely to breach by being helpful. Each is in the
constitution with its reasoning.

- **`:capture-*` must never depend on `:asr-*`, `:lexicon` or `:identity`.** Capture cannot
  block on inference. The build enforces this; do not "temporarily" add the edge.
- **Only `:net` may link an HTTP client.** No network in the capture or processing path, ever.
- **Voiceprints, user-supplied names, station knowledge and precise location never leave the
  device** — not in a contribution, a diagnostic bundle or a backup.
- **An attribution without its confidence state is a bug**, at the data layer, not just the UI.
- **`CONFIRMED` means heard in *this* transmission.** Never promote a voice match to it.
- **The segmenter must not accept a tier.** Segmentation is the one decision reprocessing
  cannot undo.
- **Never read the `eval` fold.** Not to debug, not for a quick check. Use `dev`.
- **Never report a number without its fold, machine and provider.**
- **Never delete quietly** — rejected segments, superseded transcripts and gaps stay reachable.
- **A screen is not fixed until it has been captured again and compared with its artboard.** A
  test that passes is not that evidence; a builder's report is not that evidence. When a test and
  a screenshot disagree, the screenshot wins until explained.
- **No unit test reads a real bundled model.** Scenario and tour sweeps take the fixture-sized
  source; the hosted test worker cannot hold the real one.
- **Green means green on CI and on the Release workflow**, on the pushed commit.

## Domain vocabulary

Use these precisely; they are defined in functional spec §4 and belong in code and UI alike.

**Transmission** (one keying, the atomic unit) · **Over** (its UI synonym) · **Session** ·
**Thread** (a QSO, net, or scanner activity period) · **Station** · **Callsign** ·
**Voiceprint** · **Attribution** · **Lexicon** · **Phonetic lattice** · **Rig** ·
**Rig Module** · **Pass** · **Digest**.

The user is an expert operator. Do not translate their domain into generic language — a
frequency is not a "channel number", an over is not a "message".

## Things that will surprise you

- **The reference device runs ColorOS**, which kills background apps and whose battery-exemption
  API *lies*. Liveness is proven by heartbeat, never by `isIgnoringBatteryOptimizations()`.
- **The TH-D75A receives on two bands at once and mixes the audio.** Every CAT command is
  band-scoped; transmissions are attributed to a band by squelch state.
- **Most USB audio adapters do not offer 16 kHz.** Capture negotiates the device's native rate
  and resamples deterministically.
- **Audio is retained losslessly** until a lossy codec is proven harmless to Pass C — a storage
  decision that is really an accuracy decision.
- **M4 is an architectural fork.** If audio-level resolution does not beat text-level, Pass C is
  deleted. Do not build on the assumption that it survives.
