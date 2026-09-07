# Offline Radio Transcriber

An Android application that listens to amateur and scanner radio traffic, transcribes it
entirely offline, resolves callsigns by matching a lexicon **against the audio itself**,
groups transmissions into conversations, and presents a searchable log plus a digest.

This directory holds the complete research record and the functional specification.

## Status

| Item | State | Location |
|---|---|---|
| Hardware / SBC feasibility research | Complete | [`research/01-hardware-sbc-study.md`](research/01-hardware-sbc-study.md) |
| Phone platform feasibility research | Complete | [`research/02-phone-platform-study.md`](research/02-phone-platform-study.md) |
| Accuracy, lexicon and identity research | Complete | [`research/03-accuracy-lexicon-identity.md`](research/03-accuracy-lexicon-identity.md) |
| Flagship capability research | Complete | [`research/04-flagship-capability.md`](research/04-flagship-capability.md) |
| **Functional specification** | **Draft 3 — ready for technical design** | [`spec/functional-spec.md`](spec/functional-spec.md) |
| Open decisions register | Active — 3 blocking | [`spec/open-questions.md`](spec/open-questions.md) |
| Technical design specification | Not started — baseline in spec §17 | — |
| Test plan | Not started | — |
| Visual / UX design guide | Not started | — |

The functional spec is written to be the direct input to the three documents that do not
yet exist. Section 14 (Acceptance Criteria) feeds the test plan; section 13 (Interaction
Principles) feeds the UX guide; sections 6–11 feed the technical design.

## Reading order

**If you are implementing:** read the functional spec first, then
`research/03` for the reasoning behind the accuracy architecture. The hardware study is
historical context and is not required.

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

## A note on the RTF convention

This project uses the **speed convention**: real-time factor is audio-seconds transcribed
per wall-clock second, so higher is faster. Much of the ASR literature uses the
reciprocal (processing time divided by audio duration, lower is better). Every figure
carried in from an external source in these documents has been converted, and the
conversion is flagged where it happens. Check which way any new source runs before
comparing.
