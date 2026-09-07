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
| **Functional specification** | **Draft 1 — ready for review** | [`spec/functional-spec.md`](spec/functional-spec.md) |
| Open decisions register | Active | [`spec/open-questions.md`](spec/open-questions.md) |
| Technical design specification | Not started | — |
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

## The one-paragraph summary

Compute is not the constraint. A 2019 Galaxy S10 runs Whisper small at 2.4x real time on
plain CPU, and a scanner is busy roughly 15% of the time, so the bar to clear is 0.15x —
a 16x margin before any NPU is involved. What actually determines whether this product is
good is the **lexicon layer**: raw ASR resolves roughly 40% of callsigns, and matching a
phonetic expansion against the FCC ULS dump, POTA park list and local repeater data takes
that past 90%. The design therefore spends its complexity budget on running that lexicon
against the audio signal rather than against the transcribed text — decode-time
contextual biasing plus acoustic scoring of phonetic units — because the published
evidence says that is worth 17 points of entity recall over text matching alone.

## Key decisions already made

These were settled with the product owner and are not open for re-litigation without new
information. Full rationale in the functional spec, section 3.

1. **Android, not iOS.** The `microphone` foreground service type has no time limit;
   iOS background recording is technically permitted but commercially fragile.
2. **On-device only.** No cloud, no desktop dependency. Reprocessing is designed in as an
   extensibility point but is not required for the product to work.
3. **Lexicon runs against audio, not text.** This is the core accuracy thesis.
4. **Deterministic callsign extraction.** An LLM is optional, post-hoc, and never in the
   path that produces a callsign.
5. **Must degrade to run on any device.** Four capability tiers, same data shape at every
   tier.
6. **Audio-only first, then a modular rig interface.** Kenwood TH-D75A is the first
   module; the interface is designed to be extended to any radio.
7. **Attribution confidence is always visible.** Confirmed, inferred and unknown are
   distinct states in the data model and in the UI.
8. **Open source, self-build now; Play Store later.** No decision may foreclose the
   store path.

## A note on the RTF convention

This project uses the **speed convention**: real-time factor is audio-seconds transcribed
per wall-clock second, so higher is faster. Much of the ASR literature uses the
reciprocal (processing time divided by audio duration, lower is better). Every figure
carried in from an external source in these documents has been converted, and the
conversion is flagged where it happens. Check which way any new source runs before
comparing.
