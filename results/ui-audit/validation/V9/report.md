# V9 — Settings, assets, LLM (device validation)

Validator V9 (Sonnet), 2026-09-11, emulator-5556 (`ort_audit_2`, Pixel 6 / API 34), app built from
main at `04c31433` with `-PortAllowMissingBundledAssets=true` (`install.ps1` does not pass the
property and fails on the gated Gemma fetch — a tooling gap, see R-868). Screenshots per
scenario under this directory; `accessibility-2x/` holds the 2.0 captures. The validator's
harness refused the report file, so the lead transcribed its findings here verbatim in substance.

## Matches

- `mode-change-pending`/CF11: amber banner verbatim; the three-way pending picker updates the
  store live for every mode (AC-131); `Back` returns to CF02 with the current-mode row intact.
- CF11 and CF02 under `mode-local-mic`, `mode-usb`, `mode-bluetooth`: coherent Audio-route /
  Rig-link facts, correct route labels and transport names; `mode-bluetooth`'s CF02 copy verbatim.
- CF06 under `mode-usb`/`mode-bluetooth`: title, Rig module row, Link sub-line with the real
  address, AI toggle, honest "not reported by this rig module" counts, both buttons ≥ 44 dp
  (R-835, R-845 confirmed on device).
- `assets-bundled`/CF04: title and subtitle verbatim (R-841 confirmed); Gemma row honestly
  "not in this build".
- `asset-corrupt`: names the failing part ("Whisper tiny.en — encoder"), demotes to 3 of 5,
  offers a working action, claims nothing verified. Note: the copy matches F13's language more
  than F21's — a plan/board mapping note, not a defect.
- `llm-enabled-prose`/CF05: tier-3 text verbatim; DG05: `GENERATED` badge, italic prose, over
  citations, `Read the overs` filters the Log to exactly the cited overs. `llm-disabled`/DG01
  omits the prose section entirely.
- `tier0-llm-stored`: S12 "5 bundled · ready"; CF04 Gemma row verbatim R-842's copy; CF05 "2 of 3".
- No state anywhere carried by colour alone.

## Findings (filed in the register as R-860..R-867)

| id | severity | where | what |
|---|---|---|---|
| R-860 | halt | `mode-change-pending`/CF11 | Audio route "not yet selected" / Rig link "no radio configured" on a live, configured USB session — the rows read the live `InputStatus`/`RigStatus` holders the scenario never opens, not the configuration store |
| R-861 | halt | `mode-change-pending`/CF02 | "USB-connected radio" above "No input selected" — same root |
| R-862 | halt | `assets-bundled`, `asset-corrupt`/S12 | Models row "No transcription model yet" while Settings root shows 4 of 5 / 3 of 5 installed — R-843 reopened for the real-source install path; persists across relaunch |
| R-863 | design | CF04 "Install from a file", DG05 "Read the overs" | Trailing text action wraps one word per line — R-805's class on two more screens |
| R-864 | spec | `mode-bluetooth`/CF02, CF11 | The Bluetooth profile (`HFP_MSBC` seeded) is never named |
| R-865 | spec | `tier0-llm-stored`/CF04 | Every asset and the Space total read 0 KB — the fixture shortcut writes empty placeholders |
| R-866 | spec | S12 amber `Install` action | 95 px / 34.3 dp tall, under the 44 dp floor |
| R-867 | — | app-wide at 2.0 | Headings grew ~1.35–1.38× at font scale 2.0 — **rejected by the lead**: API 34 non-linear font scaling shrinks the step for large text by design; `Theme.kt` passes `fontScale` through unchanged |

Incidents: emulator-5556's `system_server` had died before the run (rebooted, no product
involvement); `scenario.ps1`'s stray-session check trips on this AVD as the README already notes.
