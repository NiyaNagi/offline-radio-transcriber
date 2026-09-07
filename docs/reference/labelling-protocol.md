# Labelling protocol (Q16)

**Status: drafted, not yet piloted.** This answers Q16's question — "what exactly gets labelled,
by what rules" — so that when the validation-hour recording (Q2) happens, labelling can start
immediately rather than being decided ad hoc at 11pm with headphones on, which is exactly the
failure mode Q16 warns about. Per Q16's own recommendation, **treat session one as this
protocol's pilot**: label it under these rules, notice what was still ambiguous, amend this
document, then relabel session one under the finished version. That relabelling cost is cheap
insurance against the project's single most expensive rework — relabelling everything later
because disagreements were invisible until they showed up as unexplained accuracy variance.

This document does not close Q16 — closing it requires the pilot round above, which needs real
audio that does not exist yet. It exists so the pilot has rules to test rather than a blank page.

## What to record first (from Q2)

| Content | Duration | Radio | Labels needed |
|---|---:|---|---|
| Repeater, conversational (2 m/70 cm) | 90 min | TH-D75A | callsigns, speaker turns, thread bounds |
| HF SSB including DX | 60 min | TH-D75A | callsigns (incl. non-US), frequency |
| Scanning, public safety | 60 min | SDS150 | frequency changes, speaker turns |
| Squelch noise, no speech | 20 min | both, separately | **none — this is the hallucination control (AC-6)** |
| Weak/marginal signals | 30 min | either | callsigns where humanly possible, marked `uncertain` |

The noise tape is not optional and is not substitutable — AC-6 needs the real squelch
characteristics of *these two radios specifically*, not a generic recording of static (verified:
no downloaded stand-in was used or is proposed here for exactly this reason).

## Transmission boundaries

**A transmission's start and end are the keying edges — PTT down and PTT up, audible as the
squelch break — not the first or last phoneme of speech.** This matches what the segmenter
(`:segment`, AC-69) actually detects and is scored against; labelling to a speech-content boundary
instead would make the hand-marks measure something the segmenter was never built to measure.

- Mark the sample (or, by ear, the nearest ~100 ms) where the carrier opens and where it drops.
- Trailing dead air before squelch closes belongs to the transmission's tail, not the next gap.
- A carrier that keys with no speech at all (a stuck PTT, a mic bump) is still one transmission —
  label it `outcome: no_speech`, not omitted. Omitting it would hide exactly the kind of segment
  AC-72 (too-short/no-speech rejection) needs to be measured against.

## Doubling (two stations transmit at once)

- If both are separable by ear (distinct pitch, one clearly stronger): label **two
  transmissions**, same start/end window, `doubled: true` on both, each with its own callsign
  field (or `uncertain`/omitted per the audibility rule below, independently per station).
- If they are not separable — a single garbled mass — label **one transmission**,
  `outcome: doubled_unresolvable`, callsign field empty. Do not guess which station "won."

## Partial audibility — never omit, always mark certainty

**A callsign that is even partially audible is labelled, never omitted, and never silently
guessed complete.** Omission directly inflates the recall ceiling every measurement in this
project is checked against — an omitted-but-actually-present callsign looks, to the harness,
identical to a transmission that genuinely carried none. Use three certainty levels on the
callsign field:

- `certain` — every character heard clearly, no reasonable alternative reading.
- `uncertain` — heard, but with real doubt about one or more characters. Write the callsign with
  the doubtful character(s) marked, e.g. `K7A?C` for a garbled fourth character, not your best
  guess at what it "probably" is. **Do not look the callsign up against a database or the ULS
  allocation table while labelling** — that would leak the very prior (FR-LEX-8/AC-10/AC-11)
  the resolver is independently tested against into the ground truth itself, contaminating the
  measurement.
- `partial` — only a fragment was heard (e.g. "...seven alpha bravo"). Write exactly the
  fragment heard, anchored to its position if known (e.g. `...7AB` for a heard suffix, `K7?...`
  for a heard prefix with the rest lost). Do not complete it.

A negative example (audio with clearly no callsign, e.g. squelch noise or a transmission that
never identifies) gets `truthCallsign: (empty)`, distinct from `uncertain`/`partial` — this is
what lets the harness measure false positives (FR-TST-8) rather than only recall.

## Threads

**Default rule: a thread continues across a gap on the same repeater/frequency if the gap is at
most 20 minutes and at least one participant (by callsign or, once voice enrolment exists, by
voiceprint) recurs.** Otherwise the next transmission starts a new thread. This 20-minute figure
is a protocol default chosen for this project's own callsign-ID window convention (FR-SPK-4's
configurable ID window uses a similar order of magnitude) — it is not independently derived from
data, and should be revisited once the pilot round shows how real nets and ragchews actually
behave; if the pilot disagrees with it, change it here and say why, rather than leaving the
mismatch implicit in the labelled data.

- A net or scheduled repeater activity period spanning check-ins from many stations is one
  thread, not one per checking-in station — thread ≠ conversation between two parties; see the
  domain vocabulary in `AGENTS.md` ("Thread — a QSO, net, or scanner activity period").
- A station that leaves and rejoins the *same* net within the gap window stays the same thread;
  outside it, treat the rejoin as a new thread even on the same frequency.

## Phonetic form, tactical callsigns, club stations

- **Label the resolved legal callsign text, never the pronunciation form spoken.** NATO
  ("Kilo Seven Alpha Bravo Charlie"), letter-name, and legacy/ARRL phonetics for the same
  callsign all get the same label, `K7ABC` — AC-12 requires the resolver to treat these as one
  result, so the ground truth must already agree they are one result.
- **Tactical callsigns** (non-FCC identifiers used operationally — "Net Control," "Mobile 3,"
  "Base") are not callsigns. Label them `tactical: "<literal text>"` in a separate field, leave
  the callsign field empty unless the station *also* IDs with a real callsign in the same or an
  adjacent transmission (common practice; label that occurrence normally).
- **Club stations** are labelled with whatever callsign is actually spoken (the club's own
  licensed call) — no special handling; they are structurally identical to any other station.

## Non-speech content

DTMF, courtesy tones, data bursts (packet, digital voice sync), and CW station IDs are not
speech and are not transcribed as callsigns:

- Mark the transmission `outcome: non_speech`, with a `note` naming what it was
  (`dtmf` / `courtesy_tone` / `data_burst` / `cw_id`) where identifiable by ear.
- A CW ID *is* an identification in the amateur-radio sense, but decoding Morse is out of this
  project's speech-recognition scope. Transcribing it manually into the `note` field is welcome
  when trivial (a short, clean ID) but is not required, and a missed/undecoded CW ID must not be
  scored as a missed callsign by the harness — it is a different modality entirely.

## Output format

The protocol produces one row per **transmission** (not per word or per session), matching the
grain `:segment`'s `Segmenter` already emits. A plain TSV keeps this reviewable by hand and
diffable in git, with columns:

```
session_id  start_sample  end_sample  outcome  doubled  callsign  certainty  tactical  thread_id  note
```

Where `outcome` is one of `speech | no_speech | doubled_unresolvable | non_speech`, `certainty`
is one of `certain | uncertain | partial` (empty when `callsign` is empty), and `thread_id` is a
per-session sequence number assigned per the thread rule above.

**This raw format is not yet `:eval`'s `LabeledOccurrence`** (`eval/src/main/kotlin/org/ort/eval/LabeledOccurrence.kt`),
which expects a `PhoneticLattice` and `RankingContext` already built from real audio via passes
that don't exist yet in verified form (P10's `:asr-sherpa` has no real model wired — see
`CHANGELOG.md`'s P10 entries). **A conversion step from this TSV to `LabeledOccurrence` is not
built and is a real, separate follow-up task** once both the recording and a working ASR/lattice
pipeline exist — noting this now so it isn't assumed to already work.

## Open items for the pilot round to actually resolve

These are protocol defaults, not settled answers — the pilot labelling pass (session one, per
Q16's recommendation) should stress-test each and this document should be edited with what was
learned, not treated as final:

1. The 20-minute thread-continuation gap (chosen by convention above, not measured).
2. Whether `uncertain` needs a finer grade (e.g. "one character in doubt" vs. "half the
   callsign in doubt") once real marginal-signal audio shows how it actually fails.
3. Whether doubling is common enough on the recorded repeaters to need more than the two
   outcomes above.
