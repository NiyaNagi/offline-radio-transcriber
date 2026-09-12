# Design guide

The visual and interaction system for the reader. Binding for anything that renders.

This guide is **derived from the artboards**, not invented alongside them. Every value below
was lifted from `design/canvas/*.dc.html`; where an artboard and this file disagree, the
artboard wins and this file is wrong. Principles come from functional spec §13 (P1–P12) and
the constitution.

Companion documents:

- [`design-intent.md`](design-intent.md) — every screen, every state, every interaction, cited
  to requirement ids. The inventory this guide styles.
- [`canvas/`](canvas/) — the artboards themselves. Source of truth for the palette.
- [`.specify/memory/constitution.md`](../.specify/memory/constitution.md) — binding principles.

---

## 1. What this product looks like, in one paragraph

An instrument, not an app. Dark, dense, monospaced where the data is numeric, and quiet
everywhere it is not carrying information. It shows an operator what their radio heard
overnight, and it is scrupulously honest about which parts of that it is sure of. Nothing is
decorative. Colour is never load-bearing. The interface earns trust by being legible about its
own uncertainty, so the visual system's first job is to make four confidence states
distinguishable at a glance, in greyscale, at 4 mm.

---

## 2. Principles that constrain the visuals

From functional spec §13. These are product rules; this section says what each one *costs* in
pixels.

| Principle | What the design must do |
|---|---|
| **P1** Uncertainty is content | Every attribution renders a state marker. There is no "no marker" case. A design that omits it is a bug, not a simplification. |
| **P2** One tap to inspect | Every machine conclusion has a visible affordance leading to its evidence. "Why this callsign" is a named, findable destination. |
| **P3** Correction is cheap | Correction is reachable from the row *and* the detail, never buried in a menu. Its propagation is shown, not implied. |
| **P4** Capture state never in doubt | Live state is visible on every screen — the persistent bar — not only on a status page. |
| **P5** Live vs record differ | Pass A partials are visually provisional (italic, dimmed, no state marker). The swap to Pass B is legible. |
| **P6** Radio vocabulary | Copy uses over, QSO, net, repeater, simplex, band, mode, callsign. Never "message", "channel number", "recording". |
| **P7** The log is scanned | Tabular alignment, monospace for time/freq/signal, fixed column widths, dense rows. |
| **P8** Setup is guided, once | A numbered sequence with a verify step per stage. No stage advances unverified. |
| **P9** Nothing deleted quietly | Rejected, superseded and overwritten records stay reachable and visibly present, dimmed rather than absent. |
| **P10** Degradation is announced | Tier drops, thermal and backlog get a banner at the moment they happen. |
| **P11** Weaker device knows less | Reduced-capability states say what is *not* known. Never show a shakier number. |
| **P12** Provisional is improvable | Below-capability records carry a mark and a bulk action. Framing is "can get better", never "broken". |

---

## 3. Colour

OKLCH throughout, `oklch(L C H)`. Two hues carry meaning: **150** (green, confirmed/live/nominal)
and **75/60** (amber, ambiguous/gap/warning). Everything else is a near-neutral at hue **250**
with chroma ≤ 0.012 — a cool grey that reads as black without being black.

### Surfaces

| Token | Value | Use |
|---|---|---|
| `bg/page` | `oklch(0.11 0.006 250)` | The drawer's page ground, behind the scrim |
| `bg/screen` | `oklch(0.155 0.008 250)` | Every screen background |
| `bg/raised` | `oklch(0.185 0.009 250)` | Drawer sheet, live bar, log group header |
| `bg/card` | `oklch(0.195 0.009 250)` | Cards, state tiles |
| `bg/selected` | `oklch(0.26 0.012 250)` | Selected drawer row |
| `bg/chip` | `oklch(0.30 0.012 250)` | Selected filter chip |
| `bg/score` | `oklch(0.24 0.010 250)` | Score chip behind a confidence number |
| `bg/row-ambiguous` | `oklch(0.175 0.010 75)` | A log row whose attribution is AMBIGUOUS |
| `bg/row-gap` | `oklch(0.185 0.012 60)` | A "not listening" row |

### Lines

| Token | Value | Use |
|---|---|---|
| `line/strong` | `oklch(0.28 0.010 250)` | Live bar top edge, drawer right edge |
| `line/default` | `oklch(0.26 0.010 250)` | Section dividers in the drawer |
| `line/section` | `oklch(0.24 0.010 250)` | Between page sections |
| `line/row` | `oklch(0.215 0.010 250)` | Between log rows |
| `line/faint` | `oklch(0.21 0.010 250)` | Between station rows |
| `line/chip` | `oklch(0.30 0.010 250)` | Unselected chip border |

### Text

Chroma is `0.006` at L ≥ 0.72 and `0.008` below — one rule, no exceptions. Pick by role, never
by "looks about right".

| Token | Value | Use |
|---|---|---|
| `text/bright` | `oklch(0.96 0.006 250)` | Selected drawer row only |
| `text/high` | `oklch(0.94 0.006 250)` | Titles, primary values |
| `text/icon` | `oklch(0.90 0.006 250)` | Header icons — drawer, back |
| `text/ambiguous` | `oklch(0.88 0.006 250)` | An AMBIGUOUS callsign, a shade under CONFIRMED |
| `text/body` | `oklch(0.86 0.006 250)` | Drawer rows, INFERRED callsigns, list copy |
| `text/prior` | `oklch(0.80 0.006 250)` | Prior names in the inspection surface |
| `text/secondary` | `oklch(0.76 0.006 250)` | Transcript text, banner body |
| `text/live` | `oklch(0.72 0.006 250)` | Partial text in the live bar |
| `text/chip-x` | `oklch(0.70 0.008 250)` | The dismiss glyph inside a chip |
| `text/icon-dim` | `oklch(0.68 0.008 250)` | Unselected drawer icons, device icons |
| `text/muted` | `oklch(0.66 0.008 250)` | Card body copy, unselected chip label |
| `text/dim` | `oklch(0.62 0.008 250)` | Subtitles, metadata, sub-lines |
| `text/time` | `oklch(0.60 0.008 250)` | Time and frequency columns in a log row |
| `text/faint` | `oklch(0.58 0.008 250)` | Section labels, secondary sub-lines |
| `text/figure` | `oklch(0.55 0.008 250)` | Score chips, counts beside a list row |
| `text/low` | `oklch(0.52 0.008 250)` | "unknown station", axis labels, artboard ids |
| `text/signal` | `oklch(0.50 0.008 250)` | Signal figures, chevrons, recent-search icon |
| `text/disabled` | `oklch(0.46 0.008 250)` | Column headers, disabled text actions |
| `marker/unknown` | `oklch(0.45 0.008 250)` | The UNKNOWN dot |
| `line/control` | `oklch(0.40 0.008 250)` | Unselected radio and checkbox border |
| `line/handle` | `oklch(0.34 0.010 250)` | The bottom-sheet drag handle |

Two more surfaces the boards use beyond §Surfaces: `bg/audio` `oklch(0.20 0.010 250)` (the
waveform card), `bg/live` `oklch(0.19 0.010 250)` (the live bar), `bg/group` `oklch(0.185 0.008
250)` (a log group header), `bg/current` `oklch(0.175 0.009 250)` (the row this screen is about),
`bg/pressed` `oklch(0.22 0.010 250)` (a text action while pressed), `bg/notification`
`oklch(0.17 0.008 250)` on a shade of `oklch(0.09 0.006 250)`. `bar/neutral` `oklch(0.48 0.05
250)` is the fill of a prior that barely argued.

### Accents

| Token | Value | Use |
|---|---|---|
| `accent/green` | `oklch(0.72 0.14 150)` | CONFIRMED marker, live dot, links, nominal, primary fill |
| `accent/green-hover` | `oklch(0.80 0.14 150)` | Link hover, pressed text action |
| `accent/green-dim` | `oklch(0.68 0.10 150)` | Elapsed time beside the live dot, "verified" labels |
| `accent/on-green` | `oklch(0.16 0.01 150)` | Text on a green fill |
| `wave/speech` | `oklch(0.68 0.13 150)` | Speech bars in a waveform card |
| `score/good` | `oklch(0.68 0.11 150)` | A lattice slot score that is fine |
| `meter/idle` | `oklch(0.60 0.10 150)` | The live-bar meter with nothing to hear |
| `highlight/green` | `oklch(0.22 0.04 150)` | The callsign span inside a transcript |
| `card/ok-border` | `oklch(0.34 0.06 150)` | A card border that means "this is the good one" |
| `accent/amber` | `oklch(0.78 0.13 75)` | AMBIGUOUS marker, alternate candidate, warning badge, live dot when degraded |
| `accent/on-amber` | `oklch(0.16 0.01 75)` | Text on an amber fill |
| `accent/amber-dim` | `oklch(0.74 0.07 60)` | Degraded live-bar text |
| `accent/gap` | `oklch(0.70 0.09 60)` | "not listening" text and icon |
| `accent/amber-text` | `oklch(0.62 0.05 60)` | Amber explanatory text, "revised" badge text |
| `meter/warn` | `oklch(0.62 0.10 60)` | Meter bars below the band |
| `accent/gap-dim` | `oklch(0.55 0.07 60)` | The hatch pattern in a gap bar, gap-row icon |
| `hatch/bar` | `oklch(0.40 0.055 60)` | The hatch stripe in a full-height not-listening bar |
| `badge/revised-border` | `oklch(0.40 0.04 60)` | The "revised" badge outline |
| `banner/amber-border` | `oklch(0.38 0.06 60)` | Border on a degradation banner |
| `highlight/amber` | `oklch(0.22 0.04 75)` | An uncertain span inside a transcript |
| `lattice/uncertain` | `oklch(0.42 0.07 75)` | Border on a lattice slot below threshold |
| `halt/text` | `oklch(0.72 0.15 25)` | Text and icon on a halting banner, the Stop action |
| `halt/fill` | `oklch(0.60 0.16 25)` | A destructive button, a failed-check dot |
| `halt/on-fill` | `oklch(0.16 0.01 25)` | Text on `halt/fill` |
| `halt/border` | `oklch(0.42 0.10 25)` | Border on a halting banner or dialog |
| `halt/bg` | `oklch(0.20 0.045 25)` | Ground of a halting banner |

**Red is for one thing:** capture has stopped and the operator must act. Every degradation —
tier drop, backlog, stale rig, storage floor, missing model — is amber. If a screen is red and
capture is still running, the screen is wrong.

**Chart ramps.** Green by intensity: `0.72 0.14 150` → `0.64 0.12` → `0.58 0.10` → `0.52 0.08` →
`0.46 0.055`. Amber by intensity (a departure from usual, a queue growing): `0.78 0.13 75` →
`0.74 0.11 75` → `0.70 0.09 60` → `0.62 0.10 60`. Neutral for low activity: `oklch(0.42 0.04
250)` → `0.42 0.03` → `0.40 0.03` → `0.38 0.02` → `0.36 0.02` → `0.34 0.02`, and `0.30 0.01`
for a listened-but-silent cell in a grid. Waveform quiet bars: `0.46 0.03` and `0.42 0.02`. A
**not-listening** bar is never a short bar — it is a full-height 45° hatch in `hatch/bar`. See §8.

### The rule that governs all of it

**Colour is reinforcement, never signal.** Any state distinguished by colour is also
distinguished by shape, fill, size, position or text. The greyscale strip in `States.dc.html`
is the proof, and it is part of the design, not documentation of it. Contrast meets WCAG 2.2 AA
for text and 3:1 for state markers (FR-A11Y-4).

---

## 4. Typography

**IBM Plex Sans** 400/450/500/600 for prose, **IBM Plex Mono** 400/500/600 for anything an
operator compares vertically: times, frequencies, callsigns, signal reports, scores, counts.

That split is the whole system. A callsign is monospace because callsigns get scanned down a
column; a transcript is proportional because it gets read.

| Role | Size | Weight | Tracking | Family |
|---|---|---|---|---|
| Screen title | 27px | 600 | −0.022em | Sans |
| Callsign as title | 27px | 600 | −0.01em | **Mono** |
| Card / sheet title | 19px | 600 | −0.01em | Sans |
| Figure | 17px | 600 | — | **Mono** — a frequency or count as the point of a tile |
| Drawer title | 16px | 600 | −0.01em | Sans |
| Digest item, body prose, button label | 15px | 400 / 500 | — | Sans, `line-height: 1.4` |
| Row title | 14.5px | 400 (500 selected) | — | Sans — drawer rows, settings rows, list items |
| Control, field, dialog body | 14px | 400 / 500 | — | Sans; **Mono** when the value is a callsign or frequency |
| Subtitle, banner title | 13.5px | 400 / 500 | — | Sans |
| Transcript row, text action | 13px | 400 / 500 | — | Sans, `line-height: 1.35` |
| Card body, sub-line | 12.5px | 400 | — | Sans, `line-height: 1.5` |
| Chip label, metadata | 12px | 400 (500 selected) | — | Sans |
| Secondary sub-line | 11.5px | 400 | — | Sans |
| Section label | 11px | 600 | +0.08em, uppercase | Sans |
| Callsign (row) | 13.5px | 600 | — | **Mono** |
| Callsign (card) | 13px | 600 | — | **Mono** |
| Time / frequency | 12px | 400 | — | **Mono** |
| Signal, count, step counter | 11px | 400 | — | **Mono** |
| Score chip, small annotation | 10.5px | 400 | — | **Mono** |
| Badge | 10px | 600 | +0.05em, uppercase | Sans |
| Axis label | 10px | 400 | — | **Mono** |
| Column header, artboard id | 9.5px | 400 | +0.08–0.09em, uppercase | **Mono** |

Nothing else. A size not in this table is a defect, with one exception: the `Type` board's
"at maximum font scale" demonstration, which shows what 13.5 becomes at 1.35×.

Sizes are authored in px on the artboards and ship as **sp** — every one of them scales with the
system font setting (FR-A11Y-3, AC-63). No fixed-height container may clip a grown row: rows
grow, they do not truncate. `text-wrap: pretty` on every multi-line block.

---

## 5. Spacing, sizing, shape

- **Page margin: 20px.** Every screen. Nothing but a full-bleed divider crosses it.
- **Vertical rhythm:** 3 / 5 / 6 / 9 / 12 / 14 / 18 / 26px. Card padding 18px. Section gap 14px.
- **Log row:** `8px 20px 9px` — deliberately asymmetric, so the transcript's descenders sit
  clear of the divider.
- **Radii:** chip 14px (pill), card 10px, drawer row 8px, badge/score chip 3px.
- **Touch targets: 44px minimum**, always, including drawer rows, chips and inline actions. A
  13px text label with 5px padding does not qualify; give it a padded hit area. This is the rule
  most often broken by text-styled controls — see §6.
- **Frame:** 390×844, 44px top inset left clear for the real status bar. Never draw a fake one.

---

## 6. Components

### 6.1 Attribution marker

The most important component in the product. Four states, closed set (§4.1, FR-UI-4, AC-62).

| State | Shape | Fill | Size | Beside it |
|---|---|---|---|---|
| CONFIRMED | circle | solid `accent/green` | 9px | callsign, mono 600, `text/high` |
| INFERRED | circle | 1.5px ring, `accent/green`, hollow | 9px | callsign `text/body` + score chip |
| AMBIGUOUS | circle | half-filled `accent/amber` (left half), 1.5px ring | 9px | callsign + `or QRF` alternate in amber |
| UNKNOWN | circle | solid `oklch(0.45 0.008 250)` | **5px** | `unknown station`, italic, `text/low` |

UNKNOWN differs in **size**, not merely shade — that is what carries it in greyscale. The marker
never appears without its state; a marker with no adjacent identity treatment is incomplete.

`CORRECTED` is a flag, not a state: it renders as a `corrected` label beneath the header, and the
underlying state marker is unchanged.

At 12px (cards, `States.dc.html`) the ring weight goes to 2px. Everything else scales.

### 6.2 Score chip

Mono 10.5px on `bg/score`, 3px radius, `1px 5px` padding. Only ever beside an INFERRED callsign
or a candidate. Never on CONFIRMED — a confirmed callsign was heard, and a number would imply
doubt the data does not have.

### 6.3 Filter chip

Pill, 14px radius, `5px 11px`. Selected: `bg/chip` fill, `text/high`, weight 500. Unselected:
1px `line/chip` border, `text/muted`, weight 400. Chips sit in a horizontal `gap: 7px` row and
scroll horizontally if they overflow — they never wrap to a second line.

### 6.4 Badge

`NEW` — 10px, 600, +0.05em, uppercase, `accent/on-green` on `accent/green`, 3px radius.
Count badge — 10.5px, 600, `accent/on-amber` on `accent/amber`, 9px radius (pill).
Badges annotate; they are never the only way to learn something.

### 6.5 Row (log)

The densest thing in the product, and the one P7 is about.

```
[time 52px mono] [freq 56px mono] [marker + callsign + chips / transcript] [sig mono, right]
gap: 10px · padding: 8px 20px 9px · border-top: 1px line/row
```

Time and frequency are **fixed-width columns** so they align vertically down the whole log. The
station block is `flex-grow: 1; min-width: 0`. Signal is right-aligned mono `text/low`.

Row variants: **group header** (`bg/raised`, 11px 500 `text/faint`, icon + "QSO · 4 overs ·
2 stations"), **gap** (`bg/row-gap`, amber, "not listening · 38 s · incoming call"),
**rejected** (`opacity: 0.45`, mono 10.5px uppercase "rejected · squelch tail"), **ambiguous**
(`bg/row-ambiguous`).

### 6.6 Live bar

Pinned to the bottom of any screen while a session runs. `bg/raised`, 1px `line/strong` top
edge, `10px 20px 14px`. Four-bar level meter (2px bars, 6/13/9/14px, `accent/green`), the live
partial transcript in italic `text/secondary` ellipsized to one line, and `Live` in
`accent/green` 13px 500. This is P4 and P5 in one component: it proves capture is running *and*
shows the provisional text looking provisional.

### 6.7 Buttons and actions

The existing artboards use **text actions** (`Filter`, `All 19`) in `accent/green` 13px, not
filled buttons — correct for a dense instrument. But a text action still needs a 44px hit area
and a pressed state. Destructive and primary setup actions get a real filled button:
`accent/green` fill, `accent/on-green` text, 8px radius, 44px tall.

Never style a tappable control as body text with no affordance. If it acts, it looks like it
acts — a chevron, a chip, a colour, or a button.

### 6.8 Empty, loading, failed

Three distinct treatments, never conflated:

- **Empty** — a plain sentence in `text/muted` at page margin, saying what would appear here and
  why it has not. Never a spinner, never an illustration.
- **Loading** — only when a real fetch is in flight, and only where the content will land.
- **Failed / unavailable** — amber, states the cause in operator terms, and carries the recovery
  action. Never a bare "error".

A screen that has nothing to show because a *capability is missing* (no ASR model) is **failed**,
not empty, and says so.

### 6.9 Sheet

A bottom sheet for any choice that belongs to the screen beneath it — filters, correction, a
confirmation with options. `bg/raised`, 18px top radius, 1px `line/strong` top edge, a 36×4
`line/handle` pill centred at the top, `10px 20px 20px` padding. Title is 19px/600. Dismiss by
dragging or by the scrim. The screen beneath dims to 22% opacity so it is still legible as
context. A sheet is never taller than the screen minus 120px — past that, it is a screen.

### 6.10 Step indicator

Setup only. A row of equal segments, `gap: 4px`, 3px tall, 2px radius, `accent/green` for done
and current, `line/default` for the rest, with a mono 12px `n of N` counter in the header. A
halted step's segment is `halt/text`. Always exactly one indicator per screen and always at the
top, so the operator can see how far through they are without reading.

### 6.11 Selection controls

**Radio** — 16px circle, 1.5px `line/control` border; selected fills an 8px `accent/green` dot
inside an `accent/green` border. **Checkbox** — 16px, 4px radius; on is an `accent/green` fill
with an `accent/on-green` check; off is a 1.5px `line/control` border. **Toggle** — 42×24 pill,
`accent/green` with an `accent/on-green` 18px knob when on, `bg/chip` with a `text/signal` knob
when off. Every option row is 44px minimum and the whole row is the target. A closed set is
always a visible list with counts — never a tap-to-cycle label (§6.7).

### 6.12 Progress

A 4px bar, 2px radius, `line/default` track, `accent/green` fill. Used for improve-in-progress
and the drawer's storage footer. Never indeterminate — if the length is unknown, show what is
known (`27 of 64`) and the pass that is running, not a spinner.

### 6.13 Marker variants beyond the four states

Two things sit in the marker slot that are not attributions, and both look unlike any of the
four states on purpose:

- **Hearing** — a 3-bar mini level meter (2px bars, 4/9/6px) in `meter/idle`, beside the italic
  word `hearing…`. A Pass A partial is streaming. No state has been claimed.
- **Resolving** — a 9px ring in `text/signal` with one quadrant open, beside `resolving…`. The
  text is final; the attribution is being computed, or is deferred behind a backlog.

Both are italic and dim. Neither ever coexists with a callsign.

### 6.14 Badges, complete set

`NEW` (green fill), `REVISED` (`accent/amber-text` on a `badge/revised-border` outline — Pass B
changed the text Pass A showed), `CORRECTED` (`text/dim` on a `line/chip` outline — a human
overrode the machine), the count badge (amber pill), and the tier chip (`text/dim` on `line/chip`,
`TIER 1`) on a session that was captured below current capability. All 10px/600/+0.04–0.05em
uppercase, 3px radius, `0 5px` padding. A badge never carries the only copy of a fact.

### 6.15 Stale

A frequency the rig last reported but has not confirmed since carries a superscript mono `?` in
`accent/amber-text` after the number, on every row from the disconnect onward. It is removed
when the rig reports again or the operator sets the frequency by hand, and the change is kept as
a revision on each affected over.

### 6.16 Waveform card

`bg/audio`, 10px radius, `13px 14px`. A 34px round play control in `accent/green`, a
`flex-grow` waveform of 1.5px-gapped bars (`wave/speech` for speech, `0.46 0.03 250` /
`0.42 0.02 250` for quiet), and the duration in mono 11.5px `text/faint`. Playing: played bars
turn `accent/green`, a 2px `text/high` cursor, position over duration in mono, speed chips
beneath. The word being spoken in the transcript above gets a 1px `accent/green` outline on its
highlight span.

### 6.17 Lattice slot and prior bar

A lattice slot is a `flex-grow` cell on `oklch(0.21 0.010 250)`, 5px radius, `6px 0`, centred:
the unit in mono 13px/600, the score in mono 9.5px `score/good`, and any kept alternate in mono
9.5px `text/low` beneath. A slot below threshold gets a 1px `lattice/uncertain` border and an
`accent/amber` score. A prior bar is a 118–122px name in 12.5px `text/prior`, a 5px track on
`bg/score` with an `accent/green` fill proportional to its log-odds, and the value in mono 11px
`text/dim` right-aligned at 30–34px. A prior that argued against fills leftward from a centre
tick in `meter/warn` with an amber value; one that abstained has no bar and reads `cold start`.

### 6.18 Tile

A `bg/card` 8–10px card holding one figure: 17px/600 mono, with a 10.5–11px `text/faint`
caption beneath. Used in grids of two or three for band readouts, digest numbers and diagnostics.
Never more than three in a row; never a tile for a number the operator would not act on.

### 6.19 Notification

The one persistent notification, drawn on `bg/notification` at 18px radius. Icon in a 34px
`oklch(0.22 0.010 250)` disc; title line `Capturing` 13.5px/500 + elapsed in mono
`accent/green-dim` + count in `text/dim`; second line 12.5px `text/muted` — or
`accent/amber-dim` when degraded. Expanded adds three key/value rows and the `Open` / `Stop`
actions. It is updated in place; there is never a second notification, and never a sound.

---

## 7. Icons

Stroke-based inline SVG on a 24px viewBox, `stroke-width: 1.9` at 18–21px, `1.8` at 19px, `2–2.2`
at 11–15px, `stroke-linecap: round`, `stroke-linejoin: round` where corners meet. Never filled,
never emoji, never a font glyph. Drawer icons 18px, header icons 19–21px, inline row icons 11–13px.

Icon colour follows text role: an unselected drawer icon is `oklch(0.68 0.008 250)`, the selected
one is `accent/green`.

---

## 8. Activity charts and the not-listening rule

FR-UI-11 and FR-UI-12 are one component and one hard rule.

Bars are hour buckets, `flex-grow: 1`, `gap: 1.5px`, 38px tall, bottom-aligned, height
proportional to activity. Intensity ramps through the green scale in §3; low-activity hours fall
to neutral.

**An hour the app was not listening is not a short bar.** It is a full-height 45° hatch
(`repeating-linear-gradient(45deg, oklch(0.40 0.055 60) 0 2px, transparent 2px 5px)`), and it
carries a legend: `not listening · 38 s`. Conflating "heard nothing" with "was not running" turns
the feature into a lie — the spec says so in those words. Any pattern display without the hatch
treatment is incomplete, not merely unstyled.

Axis labels are mono 10px `text/low` at each end.

---

## 9. Copy

- **Operator vocabulary** (P6). Over, transmission, QSO, net, thread, station, callsign, band,
  simplex, repeater, mode, signal, squelch, tier, pass. Not: message, channel number, recording,
  clip, user, contact.
- **Sentence case** everywhere except section labels (uppercase) and badges.
- **Say what is not known**, in the same voice as what is. `4 unidentified voices` is a result,
  not a failure. `not listening · 38 s` is a fact. `rejected · squelch tail` names the reason.
- **Never fabricate a number.** A prior that abstained reads `cold start — no prior data`, not
  `0.00`. An unknown confidence is absent, not zero.
- **No exclamation marks, no apology, no encouragement.** The operator is an expert.
- **Enum values are prose**: `Confirmed`, not `CONFIRMED`, in any operator-facing string.
- **Frame degradation as improvable** (P12): "can get better", never "broken" or "failed to".

---

## 10. Density and layout

Two densities, and screens pick one deliberately:

- **Instrument** — the Log, search results, station lists. Fixed columns, 8–9px row padding,
  mono alignment, dividers between every row. Optimised for scanning a hundred rows.
- **Editorial** — Now, digest, detail, settings. 20px margins, 14–18px section gaps, generous
  line-height, prose measure. Optimised for reading one thing.

Never mix them within a screen region. The Log's group header is instrument density; the digest
item above it is editorial. The divider between them is doing real work.

### 10.1 System insets — what the artboards do and do not model

*(Added 2026-09-12, register R-1003 / R-1003d. Written down because the build faithfully
reproduced a design omission and the operator could not reach the buttons on his own phone.)*

The artboards are authored at a fixed **390 × 844 dp** root and that root is the **whole window**,
not the content area — every board carries `padding-top: 44px` for the status bar and draws into
the space behind it. The boards were never given the matching statement at the bottom, and the
build did exactly what they showed: `SetupScaffold` applied `WindowInsets.statusBars` only and
pinned its action bar at the raw window bottom, so on a device with a navigation bar every setup
button rendered underneath it.

The rule, from now on:

- **A board's bottom padding is design spacing, never a system inset.** The 24px under a pinned
  action block is breathing room between the last control and the edge of the *safe* area.
- **The build adds the navigation-bar inset on top of that**, it does not absorb it. A pinned
  bottom surface — a setup action block, a failure action bar, the live bar — sits `24px +
  navigationBars` above the window bottom, and the same inset is added to the trailing scroll
  space so nothing hides behind the bar at any font scale (the R-613 / R-940 shape).
- **Boards with a pinned bottom surface draw the safe area explicitly**, as a 24px band below the
  content matching the 44px band above it, so the two ends of the board are stated the same way
  and a reviewer can see what is spacing and what is inset.
- The app opts into edge-to-edge itself (`enableEdgeToEdge` in all three activities, transparent
  system-bar colours in `themes.xml`). This is **not** a consequence of the target SDK and would
  not be fixed by raising it — it is a deliberate look, and the inset arithmetic is the price of it.

Boards updated to the new form are listed in their `design/design-intent.md` rows. A board that
has not been updated yet is judged against this section, not against its own silence.

---

## 11. Accessibility floor

Non-negotiable, from FR-A11Y-1..6 and constitution VII.

1. Four states distinguishable **without colour** — proven by the greyscale strip.
2. Every interactive element has a content description; the reader is fully navigable by
   TalkBack. A marker's description names the shape, the state and the confidence.
3. Text scales to the system maximum without clipping or overlap. Rows grow.
4. WCAG 2.2 AA contrast for text, 3:1 for markers and bar fills.
5. 44px minimum touch targets.
6. English (US) only in v1, but no user-visible string is hardcoded outside resources.
7. The phonetic alphabet variant set is **content, not localisation** — it never moves into a
   locale file.

---

## 12. What this design does not do

Stated so nobody has to guess:

- **No light theme.** Every artboard is dark. A light variant is a palette addition, not a
  per-screen change, and it does not exist yet.
- **No colour-only state.** Ever.
- **No motion beyond state transitions.** Nothing animates for delight. The level meter moves
  because it is data.
- **No illustration, no empty-state art, no mascot.**
- **No tab bar.** The drawer replaces it — the destination list is too long for tabs, and Capture,
  Improve records and Settings belong in the same place as the content destinations
  (`canvas.json`'s `integrated` note).
