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

**Status: specification complete, implementation not started.** There is no code yet. The next
commits are the corpus pipeline and the Gradle skeleton.

## Where things are

| Path | What |
|---|---|
| `.specify/memory/constitution.md` | **Binding principles.** Read first |
| `spec/functional-spec.md` | 266 requirements, 126 acceptance criteria, 32 decisions, 16 risks |
| `spec/technical-design.md` | Architecture, module boundaries, subsystem design |
| `spec/build-plan.md` | **The working todo list** — 11 prompts in four waves |
| `CHANGELOG.md` | **The build log** — one detailed entry per commit; append to it, don't just tick the plan |
| `spec/test-plan.md` | Testing approach, fake inventory, device matrix, CI |
| `spec/open-questions.md` | Decision register — 15 of 17 closed |
| `spec/audit-2026-09-06.md` | Adversarial audit record |
| `research/01`–`04` | Background. `01` is historical and superseded |
| `docs/reference/th-d75a-cat.md` | Verified radio CAT command set |
| `design/canvas/` | UI direction — dark-first, drawer nav, four-state markers |

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

## Stack

Kotlin · Compose · Room + FTS5 · Coroutines/Flow · Hilt · WorkManager · sherpa-onnx behind
interfaces · `usb-serial-for-android` · Gradle Kotlin DSL · **minSdk 26**, target current.
Desktop tooling under `corpus/` is Python with its own `pyproject.toml` and CI job.

Tests: JUnit5, Turbine for Flow, Robolectric where a device is not required, MockK sparingly —
prefer the project's own fakes, which are behavioural.

## Commands

```bash
./gradlew build                 # everything
./gradlew :lexicon:test         # one module
./gradlew dependencyRules       # module boundary enforcement — must pass
./gradlew coverageMatrix        # regenerates results/coverage-matrix.md
python tools/spec_check.py      # spec integrity: ids, dangling refs, traceability
cd corpus && pytest             # the Python side
```

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
