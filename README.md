# Offline Radio Transcriber

An Android application that listens to amateur and scanner radio traffic, transcribes it
entirely offline, resolves callsigns by matching a lexicon **against the audio itself**,
groups transmissions into conversations, and presents a searchable log plus a digest.

**Status: build wave A started.** The specification set is complete and adversarially audited.
The Gradle multi-module skeleton (technical design §2) is now in place — every module wired
with its permitted dependencies, `:core` and `:testing` implemented in full under strict TDD,
CI green. Everything else is an empty-but-wired stub awaiting its build-plan wave.

**Licence: Apache-2.0** ([`LICENSE`](LICENSE)). Permissive, with an explicit patent grant and
a clear Play Store path — recorded against D11 and the constitution's governance section.

## Status

| Item | State | Location |
|---|---|---|
| **TH-D75A CAT reference** | Verified from community documentation | [`docs/reference/th-d75a-cat.md`](docs/reference/th-d75a-cat.md) |
| Hardware / SBC feasibility research | Complete | [`research/01-hardware-sbc-study.md`](research/01-hardware-sbc-study.md) |
| Phone platform feasibility research | Complete | [`research/02-phone-platform-study.md`](research/02-phone-platform-study.md) |
| Accuracy, lexicon and identity research | Complete | [`research/03-accuracy-lexicon-identity.md`](research/03-accuracy-lexicon-identity.md) |
| Flagship capability research | Complete | [`research/04-flagship-capability.md`](research/04-flagship-capability.md) |
| **Functional specification** | **Draft 3.2 — adversarially audited, ready for implementation** | [`spec/functional-spec.md`](spec/functional-spec.md) |
| Open decisions register | Active — Q16 (labelling protocol) and Q17 (may contributed audio be published?) are the live ones | [`spec/open-questions.md`](spec/open-questions.md) |
| **Technical design specification** | **Draft 1.1 — M0–M4 in detail, M5–M11 interfaces only** | [`spec/technical-design.md`](spec/technical-design.md) |
| **Implementation plan** | **Draft 1.1 — M0–M4 detailed, M5–M11 outlined** | [`spec/implementation-plan.md`](spec/implementation-plan.md) |
| Adversarial audit | Complete — 41 findings, all addressed | [`spec/audit-2026-09-06.md`](spec/audit-2026-09-06.md) |
| **Constitution** | **v1.0.0 — binding principles, read first** | [`.specify/memory/constitution.md`](.specify/memory/constitution.md) |
| **Agent guidance** | Cross-tool, [AGENTS.md](https://agents.md) standard | [`AGENTS.md`](AGENTS.md) |
| **Test plan** | **Draft 1 — strict TDD, criteria tiered by release line** | [`spec/test-plan.md`](spec/test-plan.md) |
| **Build plan** | **Draft 1 — the working session-by-session todo list** | [`spec/build-plan.md`](spec/build-plan.md) |
| UI design | Direction set — drawer navigation, dark-first, four-state markers | [`design/canvas/`](design/canvas/) |
| Visual / UX design guide | Not started — the canvas covers layout, not the component inventory | — |

Section 14 (Acceptance Criteria) feeds the test plan; section 13 (Interaction Principles)
feeds the UX guide, which is still the one document that does not exist; sections 6–11 fed
the technical design.

**M5–M11 are outlined rather than designed on purpose.** M4 is an architectural fork — if
audio-level resolution does not beat text-level resolution on the M0 tape (risk R3), Pass C
is deleted and the lexicon layer collapses to the text path. Detailed plans for what follows
that decision would be detailed plans for the wrong thing.

## Building

Requires JDK 17 and (for the Android modules) the Android SDK with `platforms;android-34` and
`build-tools;34.0.0`. `ANDROID_HOME` must point at the SDK.

```bash
./gradlew build                 # everything: compile, ktlint, detekt, tests
./gradlew dependencyRules       # module-boundary enforcement (technical design §2) — must pass
./gradlew :core:test            # one module
./gradlew coverageMatrix        # regenerates results/coverage-matrix.md (test-plan §9)
./gradlew -p buildSrc test      # the dependencyRules / coverageMatrix meta-guards
python tools/spec-check/spec_check.py   # the seven spec-integrity checks (test-plan §8.1)
cd tools/spec-check && python -m pytest # its meta-guard
```

Module layout is technical design §2: `:core` and the `-api` / `:lexicon` / `:eval` modules
are pure JVM with no Android dependency; `:capture-android`, `:rig-usb`, `:data`, `:pipeline`,
`:net` and `:app` are Android. The `dependencyRules` task fails the build on any forbidden
edge — it is not a convention, it is enforced (constitution VII).

## Reading order

**If you are implementing:** functional spec, then `spec/technical-design.md`, then
`spec/implementation-plan.md`, then `research/03` for the reasoning behind the accuracy
architecture. The hardware study is historical context and is not required.

**Start at [`spec/build-plan.md`](spec/build-plan.md) session S0.1** and work down the
checklist. Sessions S1.6 and S1.7 are decision gates that are cheap and answerable early —
whether speaker embeddings separate on degraded narrowband audio, and whether a fine-tuned
checkpoint exports into the runtime. Both can invalidate later work, so both come before Phase 2.

The reasoning behind the corpus strategy, which is what makes M0 small:

**M0.A–M0.D — the corpus pipeline.** The project's data strategy was rebuilt in
September 2026 around public corpora (§14A.3): 176 h of real off-air amateur HF audio, 19,000 h
of degraded analog comms with speaker labels, and free ATC data all exist under permissive
licences. What does not exist publicly is amateur conversational callsign traffic — so that is
the only thing left to record, about an hour of it, as validation rather than training. Also
worth doing on day one: M0a.1, the ONNX export round trip, which is half a day and retires the
project's largest remaining technical risk.

**If you are reviewing the decision:** read `research/02` (why a phone at all), then the
functional spec's sections 1–5.

**If you are revisiting the hardware path:** `research/01` is preserved complete and
stands on its own.

## The two-paragraph summary

**Compute is not the constraint, and the lexicon layer is.** A 2019 Galaxy S10 runs Whisper
small at 2.4x real time on plain CPU, and a scanner is busy roughly 15% of the time, so the
bar to clear is 0.15x. Meanwhile raw ASR resolves roughly 40% of callsigns, and matching a
phonetic expansion against the FCC ULS dump, POTA park list and local repeater data takes
that past 90%. The design therefore spends its complexity budget on running that lexicon
**against the audio signal** rather than against the transcribed text — decode-time
contextual biasing plus acoustic scoring of the ~36 phonetic alphabet units, parsed against
the ITU callsign grammar — because CB-Whisper measures that approach at 79.9% → 96.9% entity
recall.

**The single largest lever is not on the phone at all.** Domain fine-tuning takes Whisper
from 55.2% to 6.8% WER on air-traffic-control audio, the closest published analogue, and one
study reached a 54.8% relative reduction from **55 hand-transcribed clips**. Because a
fine-tuned model is just a file, this improves every device tier equally — and the labelled
tape needed to measure the product is the same tape needed to train it. On top of that, a
flagship with an NPU runs `large-v3-turbo` at roughly 22x real time, which is nine times
faster than Whisper small on a 2019 phone at five points higher callsign accuracy. Design
for that ceiling; let weaker devices produce *provisional* records that improve when
reprocessed later.

## Key decisions already made

These were settled with the product owner and are not open for re-litigation without new
information. Full rationale in the functional spec, section 3.

1. **Android, not iOS.** The `microphone` foreground service type has no time limit;
   iOS background recording is technically permitted but commercially fragile.
2. **On-device only.** No cloud, no desktop dependency. Reprocessing is designed in as an
   extensibility point but is not required for the product to work.
3. **Design for the best hardware first.** The reference experience is defined on a modern
   flagship and is not capped by what a weak device can do.
4. **Lexicon runs against audio, not text.** This is the core accuracy thesis.
5. **Deterministic callsign extraction.** An LLM may *rerank a closed candidate set*; it may
   never generate a callsign.
6. **Lower tiers stay fully functional and reprocessable.** Four tiers, one data shape, and
   any record can be reprocessed at a higher tier later — because every pass is a pure
   function of retained audio, tier is a property of processing, not of the record.
7. **Domain fine-tuning is first-class**, not a research aside. It is the biggest lever and
   it lifts every tier.
8. **NPU acceleration is optional**, behind a stable interface, used to run a *better model*
   rather than the same model faster. A CPU path always exists.
9. **Audio-only first, then a modular rig interface.** Kenwood TH-D75A is the first module;
   the interface extends to any radio, and for ASCII CAT radios that means a data file
   rather than code.
10. **Attribution confidence is always visible.** Confirmed, inferred, ambiguous and unknown
    are distinct states in the data model and in the UI.
11. **Open source, self-build now; Play Store later.** No decision may foreclose the store
    path.
12. **Stack chosen (D15):** Kotlin, Compose, Room/SQLite+FTS5, Coroutines/Flow, Hilt,
    WorkManager, sherpa-onnx behind interfaces — with the lexicon and evaluation modules
    deliberately Android-free so the accuracy work runs on a desktop JVM.
13. **Reference device (D19): OPPO Find X9 Ultra.** The exact chipset Qualcomm benchmarked
    `large-v3-turbo` on, which makes T3's headline number a measurement rather than an
    extrapolation — and it runs ColorOS, which is why background-killing is the top risk.
14. **Fine-tune a base already in sherpa-onnx's export enum (D20).** Converts the project's
    biggest risk into a naming detail.

Two more, added by the September audit and load-bearing enough to belong here:

15. **Segmentation is permanent.** It is the one pass reprocessing cannot redo, so its
    parameters never vary by tier and its pre-roll is generous by policy.
16. **Audio is retained losslessly until a lossy codec is proven harmless to Pass C** — a
    storage decision that is really an accuracy decision, and one made two milestones before
    the pass that cares about it can be measured.

## A note on the RTF convention

This project uses the **speed convention**: real-time factor is audio-seconds transcribed
per wall-clock second, so higher is faster. Much of the ASR literature uses the
reciprocal (processing time divided by audio duration, lower is better). Every figure
carried in from an external source in these documents has been converted, and the
conversion is flagged where it happens. Check which way any new source runs before
comparing.
