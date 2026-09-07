# Kenwood TH-D75A — CAT interface reference

**Working notes for the rig module (FR-RIG-3). Verified September 2026.**

## Provenance and why this file exists rather than the PDF

The command set below was extracted from `TH_D75_Commands.pdf` by **Kevin Wnuk, KI4LAX**
(May 2024, 16 pp), a community reverse-engineering effort. Nothing in Kenwood's official
manuals documents the serial protocol.

That document is **not redistributed here.** It is a third party's copyrighted work, and this
repository is public. Command syntax and parameter layouts are facts about an interface and are
recorded below with attribution; the document itself stays out of the repo. If you need the
original, get it from its author.

**Treat everything here as community-verified, not vendor-guaranteed.** Confirm against a real
radio before relying on it (Q1).

## Transport

- The radio enumerates as a **USB CDC device** — Kenwood ships `USB_CDC_Driver_TH-D75_V100`.
  `usb-serial-for-android`'s CDC-ACM driver claims it with no vendor driver and no root.
- Two-letter ASCII commands, read and set forms, terminated per Kenwood convention.
- Error returns: **`N`** = cannot set value, **`?`** = syntax error.

## Commands the transcriber needs

| Cmd | Form | Returns | Use |
|---|---|---|---|
| `FQ` | `FQ A` | `FQ A,BBBBBBBBBB` | **RX frequency**, Hz, 10 digits zero-padded, per band |
| `BY` | `BY A` | `BY A,B` | **Squelch status** — `B`: 0 closed, 1 open |
| `FO` | `FO A` | `FO A,B…,C…,D,E,F,…` | Full band settings; **`F` is mode** (0–7) |
| `BC` | `BC` | `BC A` | Which band has CTRL/PTT |
| `MR` | `MR A` | `MR BBB` | Current memory location on band |
| `ME` | `ME AAA` | full channel record | Memory channel contents, incl. name |
| `AI` | `AI` / `AI A` | `AI A` | **Auto-information** — 0 off, 1 on. See below |
| `BL` | `BL` | battery level | Health reporting (FR-OBS-1) |

`A` is the band selector throughout: **0 = A band, 1 = B band.**

## Two findings that change the design

### 1. `AI` gives push, not poll — and the descriptor format must support it

With auto-information enabled the radio **emits state changes unsolicited**. The rig contract
already allows for this — `observe(): Flow<RigState>`, "push or polled, module's choice"
(FR-RIG-1) — but the **declarative descriptor format in functional spec §9.2 is poll-only**: it
declares a `poll` block of send/expect pairs and has no way to express "enable this mode, then
parse whatever arrives."

This matters beyond convenience. FR-RUN-17 requires rig state correlated to audio within
**250 ms** or provenance degrades to `inherited`. A 500 ms poll cycle makes that budget hard;
unsolicited push on state change makes it comfortable. It also answers Q1's open question about
safe poll rates by largely removing the need to poll.

**Consequence:** the descriptor schema needs an `unsolicited` section alongside `poll` —
an enable command, and a set of patterns matched against pushed lines. Cheap to add now,
awkward later, and the TH-D75A is the first radio to need it.

### 2. Every state command is band-scoped, and the audio is not

`FQ`, `BY`, `FO` and `MR` all take a band parameter. The radio receives on **both bands
simultaneously and mixes the audio** into one output. So a transmission's frequency is genuinely
ambiguous whenever both bands are active — and the spec's §9.2 descriptor sketch, modelled on
single-receiver HF radios (`FA;`, `MD;`), cannot express the situation at all.

**The resolution (D23):** poll or watch `BY 0` and `BY 1` together, and attribute each
transmission to whichever band's squelch opened.

| Squelch state at transmission start | Frequency provenance |
|---|---|
| Exactly one band open | `rig`, that band's `FQ` |
| Both open | `unknown` — record both candidates, never guess |
| Neither open (VAD fired anyway) | `inherited`, last known |

This is also the highest-value use of the rig connection generally: squelch fusion makes
transmission boundaries a **measurement instead of an inference** (FR-SEG-5), against
squelch-tail hallucination, which is the product's #1 practical failure mode.

## Draft descriptor

Illustrative, and it exercises the `unsolicited` extension the format does not yet have:

```jsonc
{
  "schemaVersion": 1,
  "id": "kenwood-thd75a",
  "displayName": "Kenwood TH-D75A",
  "transport": "usb_serial",
  "serial": { "baud": 9600, "dataBits": 8, "stopBits": 1, "parity": "none" },
  "capabilities": ["FREQUENCY", "MODE", "SQUELCH_STATE", "MEMORY_CHANNEL", "CHANNEL_NAME"],
  "bands": [0, 1],                       // band-scoped commands, see above
  "unsolicited": {
    "enable": "AI 1",
    "patterns": [
      { "expect": "^BY (\\d),(\\d)$",        "map": { "band": "$1", "squelchOpen": "$2" } },
      { "expect": "^FQ (\\d),(\\d{10})$",    "map": { "band": "$1", "frequencyHz": "$2" } }
    ]
  },
  "poll": {
    "intervalMs": 2000,                  // fallback / resync only when AI is active
    "perBand": [
      { "send": "FQ {band}", "expect": "^FQ (\\d),(\\d{10})$", "map": { "band": "$1", "frequencyHz": "$2" } },
      { "send": "BY {band}", "expect": "^BY (\\d),(\\d)$",     "map": { "band": "$1", "squelchOpen": "$2" } }
    ]
  },
  "errors": { "cannotSet": "N", "syntax": "?" }
}
```

## Still to verify with the radio in hand

1. **VID/PID**, and that Android claims it through CDC-ACM with no vendor driver.
2. **Command terminator** — Kenwood convention is `;`, unconfirmed for this radio.
3. **Whether `AI` actually pushes `BY` transitions**, or only some subset of state. The whole
   push design depends on this one answer.
4. **Poll rate limits** if `AI` proves insufficient — how fast can `BY` be polled without
   disturbing the radio?
5. **Mode table D** values (0–7), not captured in the extraction.

Items 1–3 are a single evening with the radio and a USB cable, and item 3 is the one that
decides how the descriptor format grows.
