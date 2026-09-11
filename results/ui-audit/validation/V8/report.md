# V8 — Setup, three lanes (device validation)

Validator V8 (Sonnet), 2026-09-11, emulator-5554 (`ort_audit`, Pixel 6 / API 34), app built from
main at `9eede972` (`assembleDebug -PortAllowMissingBundledAssets=true`, installed by hand with the
three grants — `install.ps1` gained the property afterwards, R-868). 36 screenshots under
`lane1-local-mic/`, `lane2-usb/`, `lane3-bluetooth/`, `ac130/`, `mode-change/`. The validator's
harness refused the report file; the lead transcribed its findings here.

## Proven on device, by real taps

- **Lane 1, local microphone (fresh install):** S01, S00 (`1 of 8`, board copy, footer pointer),
  S04 with "preset by Local microphone", the mic pre-selected, the room-audio disclosure verbatim
  and `Verify` enabled; S05 listens for a real 30 s against the AVD's silent mic and times out
  honestly ("No signal heard in 30 s on sdk_gphone64_x86_64", `Try again` / `Choose another
  input`) — never a fabricated pass. N01b under `mode-local-mic`: "Room audio — the phone's
  microphone, not the radio", live bar `Live · room`, one station — matches at 1.0 and 2.0.
- **Lane 2, USB radio (no USB device on the AVD):** S04 chip "preset USB audio — none attached,
  choose a route" (R-816 holds); overriding with the built-in mic checks the row and enables
  `Verify` (but see R-852); S09 catalogue with the null entry's board copy and the inline
  `Import it` (R-813, R-814 hold).
- **Lane 3, Bluetooth radio:** S02/S02c skipped when the grant exists; with `BLUETOOTH_CONNECT`
  revoked, S02c matches its board, `Allow nearby devices` raises the real OS dialog, `Don't
  allow` returns with the consequence box, `Not now — use USB instead` advances to S04 with the
  chip now "preset USB audio" — the mode genuinely fell back (E2-E07, both halves). S04's preset
  under Bluetooth mode is the wired headset (FR-CAP-9, by design). S09b matches verbatim (unset
  state by the scenario's design). **S10b**: the paired list renders on cold entry (R-802), the
  headset-only device is dim and unselectable (R-811); tapping the TH-D75A drives the real open →
  identify → verify checklist to three green lines and `Continue` enabled; the `-connecting`,
  `-identified` and `-dropped` scenarios render each state (the drop banner: "The link dropped —
  … Pick the device again, or use USB instead — nothing captured is lost."); at 2.0 `Refresh`
  stays whole (R-805). S11 "Bluetooth SPP · identified and verified" (E2-E12).
- **AC-130 on device:** S12 reports "Bluetooth-connected radio · audio by cable", Input "Wired
  headset · verified", Radio "Kenwood TH-D75A · verified" — Bluetooth control with wired audio,
  both axes right; the footer counts its two amber rows (R-812). `ac130/S12-ac130-bluetooth-control-wired-audio.png`.
- **Change mode later:** `mode-change-pending` → Settings → Input and level → `Change` → CF11:
  the AC-131 banner verbatim, USB marked current, Bluetooth marked pending only — the radio
  reflects what is live; the queued choice appears only as "pending".
- Tap targets spot-checked from accessibility bounds all ≥ 122 px; no colour-only state seen.

## Findings (filed as R-850..R-853)

| id | severity | where | what |
|---|---|---|---|
| R-850 | design | S04, every lane | Subtitle "Which of these is the radio?" where the board reads "Which of these carries the radio's audio?" |
| R-851 | design | S00 | The "Bluetooth-connected radio" row has no leading icon (title at x=55 vs x=146 on the other two rows) |
| R-852 | spec | S04, USB lane | With no device matching the preset, the "none attached, choose a route" chip never changes after the operator picks a route — `modeOverriddenAudio` is only set when a preset route id exists |
| R-853 | halt | Now under `bt-audio-dropped` | The Now body renders idle ("Not capturing" / "Start capture" / "No input · No radio · tier —") while the same screen's live bar shows a live dot and 40:34 elapsed, and no F23 banner renders; `overnight-live` on the same install renders correctly; the process did not restart (same PID) |

## Not exercised, declared

- AC-127 end to end in any lane: S05's real listen cannot pass on the AVD's silent microphone —
  the operator's hardware pass covers it.
- The local-microphone lane's own S07–S12: no scenario reaches them under `LOCAL_MICROPHONE`
  (`setup-verified`/`setup-level`/`setup-radio` share a `USB_RADIO` base) — a scenario gap.
- S09b's preset-selected state: the only scenario reaching S09b deliberately leaves it unset.
- Font scale 2.0 swept on S10b and N01b only.

Incidents: `emulator-5554`'s `system_server` died mid-run (rebooted); PowerShell's `>` corrupts
`screencap` output (BOM) — use `screencap -p /sdcard/… ; adb pull` as the README says.
