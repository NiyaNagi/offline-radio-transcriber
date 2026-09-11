# V11 — Accessibility (font scale 2.0, semantics, greyscale) on the device

Validator V11 (Sonnet), 2026-09-11, emulator-5556 (`ort_audit_2`, Pixel 6 / API 34), main
`873a44a1` (every fix of the program merged), installed with `install.ps1 -Port 5556 -Clear`
after a stale on-device database from earlier validators crashed the first launch (see R-885).
`font_scale` 2.0 for the whole pass, restored to 1.0. The AVD's software renderer ignores the
daltonizer setting for `screencap` (verified pixel-identical), so every `*-grey.png` is a local
luminosity desaturation (0.30/0.59/0.11) of the real capture — inspected as the greyscale
evidence, never a claimed pass. 154 screenshots and semantics dumps across 22 scenarios under
this directory. The validator's harness refused the report file; the lead transcribed it.

## Confirmed on the device at 2.0

R-805 (S10b `Refresh` whole), R-826 (CF02 clearance), R-863 (CF04 / DG05 actions whole),
R-866 (S12 amber `Install` under `asset-corrupt`: 132 px = 47.7 dp), R-874 (DG05 action inside
its card, word wrap), R-875 (no axis/caption collision), R-940 (S02c third bullet and denial box
reachable), R-942 (S10b actions adjacent), R-970 (CF04 badges whole), R-852 (tapping the built-in
mic under `mode-usb` clears the unmatched-preset chip and selects the row). R-744 and R-867 stand.

## Screens passing every check

S00, S02c, S04 (three lanes), S09, S10b (four states), S12 (`setup-verified`,
`setup-verified-local-mic`, `assets-bundled`, `asset-corrupt`), N01b, N04 (two scenarios), CF04
(four scenarios), CF05, DG04 (two scenarios), DG05. Every clickable node measured ≥ 122 px; no
clickable or state-bearing node lacked a description; radio rows carry real `checkable`/`checked`.

## Findings (filed as R-880..R-885)

| id | severity | where | what |
|---|---|---|---|
| R-880 | design | CF02 at 2.0 (`overnight`, `mode-bluetooth`) | "Log overs against" label vertically centred against a 5–9-line value caption — visually unassignable, though bounds do not intersect |
| R-881 | spec | CF06 at 2.0 (`rig-bt-connected`) | "Radio battery" row label truncated to "Radio battery, BL" with an empty value; content-desc "Radio battery, BL, , not reported by this rig module" — a malformed string, not scaling |
| R-882 | halt | S12 under `rig-bt-connected` at 2.0 | the Radio row's value collapses one letter per line ("Kenwood TH-D75A · 2 bands, verified" vertical) and the row's "Radio" label is invisible; reproduced twice after a settle |
| R-883 | halt | F9 and F23 on Now at 2.0 | the failure banner overlay has a fixed height: its last line bleeds over "Tonight", and F9's message is never fully readable ("…If you changed" — the rest exists only in the content-desc); no scroll affordance reaches it |
| R-884 | polish (hardware check) | S09b at 2.0 | a faint ghost "Back" at the top of the frame with no node in the tree — the software-renderer class already on record (R-220/R-280/R-340/R-612) |
| R-885 | halt | first launch over an older on-device database | `NotImplementedError: Migration … requires overriding migrate(SQLiteConnection)` — a migration implemented against the legacy signature crashes the real open path on upgrade; cleared only by `-Clear` |

Not a defect: CF11 is reached from CF02's Capture-mode `Change` action (V8/V9 walked it); V11 did
not tap it. S11 is a tour-only transient (a cold launch resolves to S12).
