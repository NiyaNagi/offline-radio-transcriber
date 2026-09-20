# Privacy policy

**Applies to:** both build variants (`full` and `play`, FR-AST-13) of this product, from the
release that first carries a `play` variant onward. This document is the Play-Store-facing privacy
policy required by FR-ANL-14 and D42/D48 (constitution V). It states the three analytics tiers
exactly as functional spec §7.13d (FR-ANL-1..14) defines them, and the one permitted
single-sentence privacy claim verbatim, per FR-ANL-14.

## The one-sentence claim

Where a single-sentence summary is wanted, this product states, verbatim and only this:

> Your audio is processed only on your phone and is never uploaded unless you choose to share it.

A bare claim that no audio ever leaves the device is never made — it would be false the moment the
operator turns on contribution (FR-CON-1), a field report with audio (D37/D38), or analytics tier 3
(FR-ANL-4), each of which uploads audio only by the operator's own explicit choice.

## What stays on the device, always

Regardless of any setting below, the following **never leave the device through any channel this
product has** (FR-ANL-5, FR-SPK-25, FR-DIG-13, FR-LEX-24):

- User-supplied names (a station's callsign owner, a personal note attached to a station).
- Station knowledge accumulated from listening (FR-DIG-7..14).
- Location more precise than a grid square.

Captured audio, transcripts and callsigns are retained on the device and processed entirely on the
device (constitution V). They leave the device only through one of the three channels below, and
only when the operator has explicitly enabled that channel.

## The three ways data can leave this device

### 1. Analytics (on by default for tier 1; off by default for tiers 2 and 3)

Analytics exists in **exactly three tiers**, each a closed, fixed list of fields — never a schema
that can grow silently (FR-ANL-1):

- **Tier 1 — usage and quality (on by default, can be turned off).** Crash traces and ANRs; which
  screens and actions were used (not their content); per-pass processing speed; capture uptime and
  gaps; how setup went, including whether a model download succeeded; and aggregate accuracy
  statistics (how often a transcript needed correcting, how often a callsign could not be resolved).
  **Tier 1 never contains transcript text, a callsign, a name, station knowledge, or location of any
  precision** (FR-ANL-2).
- **Tier 2 — transcript and callsign content (opt-in, off by default).** Transcript text and
  callsigns, including the pairing of what the recognizer produced against what the operator
  corrected it to (FR-ANL-3).
- **Tier 3 — audio (opt-in, off by default).** The retained audio of an over, together with its
  corrected transcript (FR-ANL-4).

Turning a tier off takes effect immediately for every event not yet sent (FR-ANL-9). Every event
carries only what its tier's fixed list allows, computed fresh each time — never a raw copy of a
database row (FR-ANL-6). Analytics is queued on the device and only ever sent while no capture
session is active — it can never add latency or drop audio (FR-ANL-7, FR-ANL-13). The operator can
reset their installation's random id at any time, which erases every previously-sent row associated
with the old id at the destination (FR-ANL-11, D48).

### 2. Contribution (opt-in, off by default)

The operator may choose to contribute corrected transcripts to improve the lexicon and models.
Contributed audio is never published (FR-CON-6). Declining contribution leaves every other function
of the product fully working (FR-CON-1).

### 3. Field reports (opt-in, off by default)

The operator may send a diagnostic field report when something goes wrong. A field report may,
per-category and only by explicit operator choice, include audio and voice data — this is the one
narrow exception to "voiceprints and embeddings do not leave the device." Every field report names
its real file and size before upload and is refused against a public destination unless the
operator has explicitly turned off a visible Settings safeguard first (FR-SPK-20, FR-OBS-9,
FR-OBS-10, D38).

## Where analytics data goes

Analytics data is sent to a destination the person or organization operating this build configures
themselves (a self-hosted collector, D48) — never a third-party analytics vendor's SDK, and never
during capture. No telemetry or crash-reporting SDK is linked into this product; this is a
structural build guarantee, checked on every build (`platformGuards`, `PlatformGuards.kt`).

## Retention

Analytics data collected from real use forms its own fold, kept separate from development and
evaluation data (FR-ANL-12) — never mixed with the data used to build or test this product
(constitution VI). A field report, when the operator chooses to send one, is retained at the
destination for 90 days before deletion (D49, Q19) — this bounds how long any recording,
voiceprint or screen frame an operator chose to attach can survive there, independent of when the
issue it documents is resolved.

## Changes to this policy

Any change to what a tier collects, or to the one-sentence claim above, requires a corresponding
change to functional spec §7.13d (FR-ANL-1..14) first — this document is written from that spec,
not the other way around.
