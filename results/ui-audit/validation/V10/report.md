# V10 — Status, session, digest, failures (device validation)

Validator V10 (Sonnet), 2026-09-11, emulator-5554 (`ort_audit`, Pixel 6 / API 34), app built from
main at `7ae4ed67` via `install.ps1 -Port 5554`. 73 screenshots and `uiautomator` dumps under this
directory. The validator's harness refused the report file; the lead transcribed its findings.
`scenario.ps1`'s tail hygiene check aborted on this image (R-876); scenarios were driven with the
raw `adb` commands the script documents.

## Register rows, on device

| row | verdict | evidence |
|---|---|---|
| R-853 | reproduced, mechanism established | `bt-audio-dropped_F23-now_1` (first open: banner, live bar, body all correct) vs `…_2-after-relaunch` (after `am force-stop` + relaunch, new PID: idle body, no live bar, no banner, while Earlier nights still says "still running · 1 gap"). Also reproduced under `mode-local-mic`. |
| R-912 | not reproduced — fixed | `mode-local-mic_DG04-session.png`: Digest / Log / Export pills carry real text nodes |
| R-910, R-911 | not reproduced — fixed | nine-destination drawer sweep under `mode-local-mic`: exactly one live bar, at the bottom, `room` mark, no fragment at the top on any frame |
| R-913 | not reproduced — fixed | N01b chart 10:48–11:09 solid vs DG04 coverage 10:48–11:12 solid — agree |
| R-836 | F9 half still broken | see R-872 |
| R-824, R-825, R-831..R-834, R-837, R-838 (text), R-839 (N04), R-844 (1.0), R-914, R-916, R-920 (DG04), R-922 (1.0), R-931 | confirmed fixed | quoted in the validator's notes per screen |

## Findings (filed as R-870..R-876)

| id | severity | where | what |
|---|---|---|---|
| R-870 | polish | `rig-bt-lost`/F9 banner title | "Kenwood TH-D75A disconnected over the Bluetooth SPP transport at 04:12:59 — frequency is stale" — the manufacturer prefix survives in F9's title path only |
| R-871 | spec | rig facts reached from F9's "Set the frequency by hand" | "Stale since 1789125179230" — a raw epoch-millisecond timestamp where every other fact is a clock time or a duration |
| R-872 | halt | `rig-bt-lost`/F9 live bar | the meter is the same muted flat dots as F23's (crops identical: `rig-bt-lost_F9-livebar-crop.png` vs `bt-audio-dropped_F23-livebar-crop.png`) though only the control link is down — R-836's F9 half, now proven on device |
| R-873 | spec | R-853's mechanism | the seeded in-memory holders (`CaptureState`, `InputStatus`, `RigStatus`) do not survive a process restart while the session row and the seeded fresh heartbeat do — a combination production cannot produce (the heartbeat is written by the in-process capture service; after a crash it goes stale and the unclean-end path runs) |
| R-874 | spec | `llm-enabled-prose`/DG05 at 2.0 | "Read the overs" is clipped at the card's right edge ("Read th…") on both cards — R-863's unbounded-width fix trades the per-word wrap for clipping |
| R-875 | design | DG04 coverage bar at 2.0 | the caption ("not listening · 1 gap · …") collides with the right-edge axis time ("11:25") |
| R-876 | process | `tools/ui-audit/scenario.ps1` | the trailing stray-session check (`run-as … sqlite3`) fails with "incomplete input" on this image and, under `$ErrorActionPreference = "Stop"`, aborts the script after the scenario already loaded |

Not exhausted: the 2.0 sweep covered N01b, DG04, DG05 only; a fuller 2.0 / touch-target pass is
V11's. "Switch to a wired input" on F23 lands on S04 (per design intent); the S04 chip showed the
previous scenario's stale `SetupStore` — a test-ordering effect, not filed.
