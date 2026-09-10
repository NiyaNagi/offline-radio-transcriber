# End-to-end checklist — capture modes, Bluetooth, bundled assets, LLM

The burn-down for [`spec/e2e-capture-modes-plan.md`](../../spec/e2e-capture-modes-plan.md). One
row per thing that must be true, each naming how it is proven. **Only the lead edits this file**;
builders, reviewers and validators report, and the lead moves rows.

**Verification** — `unit` (a named JVM/Robolectric test, cited in the row when it exists) ·
`tour` (a capture in `results/ui-audit/<scenario>/<screen>.png`, judged against the artboard) ·
`device` (a validator on an AVD, interactive, against fakes) · `hardware` (the operator, per
[`hardware-checklist.md`](hardware-checklist.md)) · `build` (a Gradle gate or CI outcome).

**Status** — `open` · `building` (a package owns it) · `fixed` (the builder reports it done,
unconfirmed) · `closed` (evidence exists: a test named in the row that was shown to discriminate,
a capture, a device dump, a build log) · `hardware` (waits on the operator) · `blocked` (reason in
the row).

Last updated 2026-09-10 by the lead at plan creation. Every row starts `open` or `building`.

## A — Specification and data

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-A01 | D33–D36, FR-CAP-8..13, FR-RIG-13..19, FR-AST-3a/3b, FR-DIG-3b, AC-127..140, R17, R18 | Recorded in `spec/functional-spec.md`; `spec_check.py` 8/8 | build | lead | closed — `4fed9de`, spec-check OK |
| E2-A02 | FR-CAP-13 | `SessionEntity` carries `captureMode`, `audioRouteKind`, `audioRouteLabel`, `bluetoothProfile`, `rigTransport`; migration 6→7 preserves every v6 fixture row | unit (`MigrationTest.migration_from_v6_to_v7_preserves_existing_rows_and_adds_the_capture_mode_columns`, `…every_prior_fixture_from_v1_to_v6_migrates_forward_to_v7…`) | WPC1 | closed — `50bfc07`, gate on main `2644c02` green |
| E2-A03 | FR-DIG-11 | `prose_summary` table stores each summary with the over ids it came from; migration 7→8 | unit | WPH | open |
| E2-A04 | constitution VII | `:llm-api`, `:llm-mediapipe`, `:rig-bluetooth` in `ModuleGraph`; `:capture-*` → `:llm-*` forbidden and shown to fail `dependencyRules` | build (WP0' report: `:capture-android -> :llm-api (explicitly forbidden…)` then OK; gate on main `d30cb02` green, 20 modules) | WP0' | closed |
| E2-A05 | technical design §2 | Allowed-edges table names the three modules and the new forbidden edges | review (lead read the merged §2) | WP0' | closed |

## B — Rig core (`:rig`)

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-B01 | FR-RIG-1 | `RigModule` contract exactly as §7.6; `RigState` timestamped with `sourceConfidence` | unit (`FakeRigModuleTest`, `FakeRigTransportTest`) | WPA | fixed — `fdbe146`/`3bed6da`, gate on main pending |
| E2-B02 | FR-RIG-2, FR-RIG-18 | Null module with `capabilities = {}` and manual frequency; always in the catalogue | unit (`NullRigModuleTest`, `RigCatalogueTest.AC_135_*`) | WPA | fixed — `3bed6da` |
| E2-B03 | FR-RIG-4, FR-RIG-11 | Descriptor loader + validator; an invalid descriptor falls back to null with a stated error, never blocks | unit (`DescriptorValidatorTest` ×8 incl. `FR_AST_7_*`, `DescriptorLoaderTest` ×4) | WPA | fixed — `3bed6da` |
| E2-B04 | FR-RIG-3, FR-RIG-14, D23 | TH-D75A descriptor: `FQ`/`BY`/`FO`/`AI`/`BL` per `docs/reference/th-d75a-cat.md`, both bands, `AI` push, **both transports with identical capabilities** | unit (`ThD75aDescriptorTest.AC_133_parity`, `D23 a BY change on band B…`) — **`MODE` deliberately not claimed**: `docs/reference/th-d75a-cat.md` marks the `FO` field position unverified, so the descriptor asserts FREQUENCY/SQUELCH_STATE/SUB_BAND only (Principle I). H1 verifies `FO` on the radio; then `MODE` is added and FR-RIG-3's minimum is met | WPA | fixed for what is verifiable — `3bed6da`; `MODE` waits on H1 |
| E2-B05 | FR-RIG-14 | Generic ASCII CAT descriptor: frequency and mode, both transports | unit (`BundledDescriptors`/`RigCatalogueTest`) | WPA | fixed — `3bed6da` |
| E2-B06 | FR-RIG-16, FR-RIG-17 | Catalogue generated from the descriptor set; adding a descriptor adds an entry with per-transport capabilities, no code change | unit (`RigCatalogueTest.AC_134_*`) | WPA | fixed — `3bed6da` |
| E2-B07 | FR-RIG-19 | Descriptor import from a file validates through the same validator | unit (`RigCatalogueTest.FR_RIG_19_*`) | WPA | fixed — `3bed6da` |
| E2-B08 | constitution II | `FakeRigTransport` can hang, fail to open, drop mid-stream, return garbage; `FakeRigModule` exists | unit (`FakeRigTransportTest` — hang, open failure, drop, garbage, push) | WPA | fixed — `3bed6da` |
| E2-B09 | FR-RIG-6 | Rig state correlated to transmissions by time; a mid-transmission change recorded at start and flagged | unit (`DescriptorRigModuleTest.FR_RIG_6_*`; the session-side correlation is WPC2) | WPA / WPC2 | fixed (rig half) — `3bed6da` |

## C — Transports (`:rig-usb`, `:rig-bluetooth`)

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-C01 | FR-RIG-3, FR-PLT-2, F16 | USB serial over CDC-ACM; a lost/absent USB permission is a transport state, never a crash | unit (Robolectric state machine) + hardware H1, H4 | WPB | open |
| E2-C02 | FR-RIG-14 | Bluetooth SPP over RFCOMM; absent `BLUETOOTH_CONNECT` is a state; paired-device listing filters to SPP-capable devices | unit + hardware H2 | WPB | open |
| E2-C03 | FR-RIG-15, FR-RIG-7 | A Bluetooth drop → reconnect with backoff, `Stale` reported, capture unaffected | unit (fake drop) + device (`rig-bt-lost`) + hardware H3 | WPB / WPC2 | open |
| E2-C04 | constitution V | Neither transport module links an HTTP client or declares `INTERNET`; `platformGuards` green | build | WPB | open |

## D — Mode plumbing (`:core`, `:capture-android`, `:pipeline`)

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-D01 | FR-CAP-8 | `CaptureMode` is a closed set in `:core`, no Android dependency | unit (`CaptureModeTest.FR_CAP_8_capture_mode_is_a_closed_set_of_exactly_three_values`, `NoAndroidDependencyTest`) | WPC1 | closed — `50bfc07`, gate on main `2644c02` green |
| E2-D02 | FR-CAP-11, D34 | `AndroidAudioIo` opens a `TYPE_BLUETOOTH_SCO` route, reports the negotiated profile/codec; `FakeAudioIo` can present one and drop it | unit (`AndroidAudioIoBluetoothTest`, `FakeAudioIoTest.FR_CAP_11_dropping_the_device_mid-read…`) + hardware H5 (the real codec: `detectNegotiatedBluetoothProfile` is a best-effort hint, `UNKNOWN` otherwise) | WPC1 | closed on the emulator side — `50bfc07`, gate `2644c02`; the real codec stays hardware H5 |
| E2-D03 | FR-CAP-3, FR-CAP-3a | Route verification treats Bluetooth and the built-in mic as ordinary selections: halt only on route ≠ selection | unit (`RouteVerifierTest.E2_D03_*` ×3 — shown to discriminate: verifier sabotaged → 4 failures, restored → green) | WPC1 | closed — `50bfc07`, gate on main `2644c02` green |
| E2-D04 | FR-CAP-13, AC-129 | `RealCaptureService` writes mode, route, profile, transport on the session at start | unit (`AC_129_session_records_mode`) | WPC2 | open |
| E2-D05 | FR-CAP-12, AC-131 | A mode change during a session is stored for the next session; the running session's row is unchanged; the next session uses the new mode | unit (`AC_131`) + device (`mode-change-pending`) + hardware H8 | WPC2 | open |
| E2-D06 | FR-RIG-8, FR-RIG-9 | Frequency provenance `rig` while connected, `manual` on override, stale on drop; every frequency carries provenance | unit | WPC2 | open |
| E2-D07 | FR-CAP-5, F23 | A Bluetooth audio drop → `InputStatus.Lost` + a `CaptureGap`, retry ladder, recovery announced; never captures nothing silently | unit (fake drop) + device (`bt-audio-dropped`) + hardware H7 | WPC2 | open |
| E2-D08 | FR-RIG-15 | A Bluetooth control drop → `RigStatus.Stale` with the transport named, **no** gap | unit + device (`rig-bt-lost`) | WPC2 | open |
| E2-D09 | constitution III | The segmenter still accepts no mode and no tier (a compile-time check: its signature is unchanged) | build | WPC2 | open |
| E2-D10 | constitution IV | `:capture-android` has no edge to `:rig*`, `:llm*`, `:asr*` after this wave | build (`dependencyRules`: `:capture-android -> :capture-api, :core`) | WP0' / WPC1 | closed at `2644c02` — re-checked at every later merge |

## E — Setup UI (`app/ui/setup`)

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-E01 | FR-CAP-8, S00 | S00 renders three modes with the artboard's copy; counter `1 of 8` | unit + tour (`setup-mode/S00-mode`, `@2x`) | WPD | open |
| E2-E02 | FR-CAP-9, AC-127 | Choosing each mode completes onboarding end to end on a device offering only that mode's hardware (fakes) | unit (`AC_127` ×3) + device V8 | WPD | open |
| E2-E03 | FR-CAP-9, FR-RIG-13, AC-130 | Bluetooth control with wired audio is reachable: mode Bluetooth, S04 wired route kept, S09b Bluetooth kept | unit (`AC_130`, written before the picker) + device V8 | WPD | open |
| E2-E04 | FR-CAP-2b, FR-CAP-10, AC-128 | S04 offers the built-in mic as a real choice, neutral tone, room-audio disclosure; `Verify` unlocks | unit (closed at `4fed9de`) + tour (`setup-mode/S04-input-mic`) | WPD | fixed — enumerator closed, screen capture pending |
| E2-E05 | FR-CAP-11, AC-132 | S04 lists the Bluetooth route with its narrowband disclosure; choosing it proceeds to verify | unit (closed at `4fed9de`) + tour | WPD | fixed — enumerator closed, screen capture pending |
| E2-E06 | FR-CAP-9 | S04 and S09 show the preset chip naming the mode; the preset is preselected; any other row is selectable | unit + tour | WPD | open |
| E2-E07 | S02c | Bluetooth mode inserts the Nearby-devices step; denying falls back to USB mode and says so; USB and mic modes never show it | unit (state machine) + tour (`setup-bt-permission/S02c`) + device V8 | WPD | open |
| E2-E08 | FR-RIG-16, AC-134, AC-135 | S09 is generated from the catalogue; null and generic reachable without scrolling; `Import it` present | unit (`AC_134`, `AC_135`) + tour | WPD | open |
| E2-E09 | FR-RIG-17, S09b | S09b shows both transports with per-transport capabilities, the mode's preset selected, the cost lines | unit + tour (`setup-rig-transport/S09b`) | WPD | open |
| E2-E10 | FR-RIG-14, S10b | S10b lists paired devices (SPP-capable selectable, headset-only not), `Pair in system settings`, the open→identify→verify checklist; `Continue` only after verify | unit + tour (`setup-rig-bluetooth/S10b-*` per state) + device V8 | WPD | open |
| E2-E11 | FR-RIG-15, S10b | A drop during S10b verification is shown, not a blank screen; `Use USB instead` works | unit + device V8 | WPD | open |
| E2-E12 | S11 | S11 names the transport in the subtitle | tour | WPD | open |
| E2-E13 | FR-CAP-12, S12 | S12 leads with the Mode row (`Change` → S00) and the Models row reads bundled/verified | unit + tour (`setup-verified/S12-ready`) | WPD / WPG | open |
| E2-E14 | design guide §6.10 | Every setup board reads `n of 8` with eight segments | tour (all S-ids) | WPD | open |
| E2-E15 | FR-CAP-12 | `MainActivity` re-entry at `EXTRA_STEP = MODE` from CF11 lands on S00 with the current mode marked | unit + device V9 | WPD / WPE | open |

## F — Settings UI (`app/ui/settings`, `ui/navigation`)

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-F01 | FR-CAP-12, CF02 | CF02 leads with the Capture-mode row; `Change` → CF11 | unit + tour (`overnight/CF02-settings-capture`) | WPE | open |
| E2-F02 | FR-CAP-12, AC-131, CF11 | CF11 shows three modes with the current marked, the two rows the mode set with `Change`, and the amber banner while a session is live | unit + tour (`mode-change-pending/CF11-settings-mode`, each mode) + device V9 | WPE | open |
| E2-F03 | FR-RIG-14, CF06 | CF06 names the transport and address; `Reconnect` and `Switch` are real actions | unit + tour (`rig-bt-connected/CF06-settings-rig`) + device V9 | WPE | open |
| E2-F04 | FR-AST-3, CF04 | CF04 rows read bundled · verified · size; Prose-digest section; Space row; no download offered anywhere | unit + tour (`assets-bundled/CF04-settings-assets`) | WPE / WPG | open |
| E2-F05 | FR-DIG-3b, AC-140 | The `Write prose summaries` toggle disables the engine and releases memory; the deterministic digest still generates | unit (`AC_140`) + device V9 | WPE / WPH | open |
| E2-F06 | FR-DIG-3b, CF05 | CF05 tier 3 names the prose capability; lower tiers say the model is stored, not loaded | tour | WPE | open |
| E2-F07 | FR-AST-3a, AC-139 | CF03 storage excludes bundled assets; CF04's Space row shows their real size | unit (`AC_139`) + tour | WPE / WPG | open |
| E2-F08 | nav | Routes to CF11, S00 re-entry, S09b re-entry and F23's actions exist in `OrtNavHost`; `NavSeed` can seed CF11 | unit + smoke test per destination | WPE | open |

## G — Status, Now, Session, Log, Digest, failures (`app/ui/*`)

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-G01 | FR-CAP-13, N04 | N04 Input sub-line names mode and route; Radio sub-line names the transport | unit + tour (`overnight-live/N04-capture-status`, `bt-audio-session/N04`) | WPF | open |
| E2-G02 | FR-CAP-3a, FR-CAP-10, AC-129, N01b | In local-mic mode the room-audio chip and the live-bar `room` mark appear on **every** destination that carries the live bar | unit + tour (`mode-local-mic/N01b-now`, `L01`, `T01`, `Q01`, `ST01`, `CF01` — one capture per destination) | WPF | open |
| E2-G03 | FR-CAP-13, DG04 | DG04 shows Mode, Input (with profile for Bluetooth) and Rig link fact rows from the session row; R-450's deviation retired | unit + tour (`overnight/DG04-session`, `bt-audio-session/DG04`, `mode-local-mic/DG04`) | WPF | open |
| E2-G04 | FR-CAP-13, F23 | Every over from a Bluetooth-audio session carries the `bt audio` mark in L01, D01–D04 headers | unit + tour (`bt-audio-session/L01-log`) | WPF | open |
| E2-G05 | FR-CAP-5, F23 | F23 banner from `InputStatus.Lost` on a Bluetooth route: gap counting, retry ladder, `Retry now`, `Switch to a wired input`; live bar reads `Input lost` | unit (`FailureMapperTest` F23) + tour (`bt-audio-dropped/F23-now`, `-log`) + device V10 | WPF | open |
| E2-G06 | FR-RIG-15, F9 | F9 names the Bluetooth transport when that is what dropped; `rig-bt-lost` → `rig-reconnected` fires the recovery toast | unit + device V10 (the `-NoRestart` recipe) | WPF | open |
| E2-G07 | FR-DIG-3, FR-DIG-6, FR-DIG-11, DG05 | DG05 renders stored summaries badged `generated`, italic, attributed to overs, `Read the overs` → L01 filtered; absent entirely when disabled | unit + tour (`llm-enabled-prose/DG05-digest-prose`, `llm-disabled/DG01-digest`) | WPF / WPH | open |
| E2-G08 | FR-DIG-4, D5 | No summary shown contains a callsign not present in the deterministic digest for that thread | unit (`CallsignShapeFilter` with the fake's deliberately invented callsign) | WPH | open |

## H — Bundled assets (`buildSrc`, `app`, `pipeline`)

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-H01 | FR-AST-3, D35 | `fetchBundledAssets` fetches every manifest entry once, verifies sha256, packages under `assets/bundled/`; one build variant | build (a clean build on this machine; CI) | WPG | open |
| E2-H02 | FR-AST-2 | A pinned-digest mismatch fails the build loudly, naming the file | build (deliberately corrupt the cache once; paste the failure) | WPG | open |
| E2-H03 | R9, D36 | Gemma is gated: absent `HF_TOKEN` fails the build with a one-line instruction, never skips the entry | build | WPG | open |
| E2-H04 | FR-AST-3b, AC-137 | `BundledAssetInstaller` verifies on first launch; a corrupted bundled file fails verification, leaves a stated recoverable state, never activates | unit (`AC_137`) + device (`asset-corrupt`) | WPG | open |
| E2-H05 | FR-AST-3, AC-136 | A fresh install with networking disabled reaches full capability for its tier: capture, Pass B, lexicon, deterministic digest | device (AVD with data off, `pm clear`, walk setup, capture the fake session) + hardware H9 | WPG / validators | open |
| E2-H06 | FR-AST-3a, AC-138 | On a T0/T1 device the LLM is on disk and never loaded; resident memory within the tier budget | unit (`AC_138`: gate refuses below T3) + device (`tier0-llm-stored`) + hardware H11 | WPG / WPH | open |
| E2-H07 | FR-AST-3a, AC-139 | Bundled storage excluded from the retention budget | unit (`AC_139`) | WPG | open |
| E2-H08 | FR-AST-1 | Side-load and replacement still work through `ModelAcquisition`; the bundled copy remains the fallback | unit (existing `ModelsControllerTest` green + a replace-then-roll-back case) | WPG | open |
| E2-H09 | R18 | Installed size measured and recorded with the device | `results/e2e-audit/installed-size.md` | WPG | open |
| E2-H10 | constitution V | `platformGuards` still green with the MediaPipe AAR; no new `INTERNET` declaration outside `:net` | build | WP0' / WPH | open |

## I — LLM (`:llm-api`, `:llm-mediapipe`, `:pipeline/digest`)

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-I01 | FR-DIG-3 | `LlmEngine` contract; `FakeLlmEngine` can hang, fail to load, over-run its budget, and invent a callsign | unit (one test per failure mode) | WPH | open |
| E2-I02 | FR-DIG-4, D5 | `CallsignShapeFilter` rejects any callsign-shaped token not in the supplied resolved set; the invented one from the fake is caught | unit (`FR_DIG_4_filter_rejects_invented_callsign`) | WPH | open |
| E2-I03 | FR-DIG-5, AC-87 | `ProseDigestGate` runs only idle ∧ charging ∧ not capturing ∧ tier 3 ∧ enabled | unit (each conjunct falsified in turn) | WPH | open |
| E2-I04 | FR-DIG-3b, AC-140 | Disabling releases the engine; `DigestPolling` output is unchanged with the engine disabled | unit (`AC_140`) | WPH | open |
| E2-I05 | FR-DIG-11 | Every summary stored with its source over ids; DG05 can navigate to them | unit | WPH | open |
| E2-I06 | D36 | `MediaPipeLlmEngine` loads the bundled `.task` and generates on a real device at T3 | hardware H11 | WPH / operator | hardware |
| E2-I07 | FR-DIG-12 | The prompt supplies resolved entities and transcripts only — no station knowledge, no names, no location | unit (prompt builder test asserts the field list) | WPH | open |

## J — Scenarios and tour (`app/src/debug`, `tools/ui-audit`)

| id | requirement | what must be true | verification | owner | status |
|---|---|---|---|---|---|
| E2-J01 | plan § WPI | Every scenario named in the plan exists, seeds real stores/holders, and is documented in `results/ui-audit/README.md` | unit (`ScenariosTest` per scenario) | WPI | open |
| E2-J02 | plan § WPI | `tour.json` has a step for every board in the plan's input list at 1.0 and 2.0, `-end` for long ones; `SetupStepIds` knows S00/S02c/S09b/S10b | tour run (manifest `ok` for each) | WPI | open |
| E2-J03 | constitution VIII | Steps the tour cannot reach are named in the README with the reason, never faked | review | WPI | open |

## K — Validation rounds

| id | what | evidence | owner | status |
|---|---|---|---|---|
| E2-K01 | Tour run 1 over the full step list on port 5558 | `results/ui-audit/tour-manifest.json`, commit | lead | open |
| E2-K02 | Reviewers A–D (bases R-810..R-840) compare every new capture to its artboard | register rows | lead | open |
| E2-K03 | V8 Setup, three lanes (5554) | register rows R-850+, captures | validator | open |
| E2-K04 | V9 Settings, assets, LLM (5556) | register rows R-860+ | validator | open |
| E2-K05 | V10 Status, session, digest, failures (5554) | register rows R-870+ | validator | open |
| E2-K06 | V11 Accessibility — TalkBack dumps, 2.0, greyscale (5556) | register rows R-880+, `window_dump` files (untracked) | validator | open |
| E2-K07 | Fix rounds until K02–K06 file zero halt/spec/design | register | lead + builders | open |
| E2-K08 | Full gate green on `main` after the last merge, CI green | gate log, CI run id | lead | open |

## L — Hardware (the operator)

Every row here is a `hardware` row; the protocol, prediction and result path are in
[`hardware-checklist.md`](hardware-checklist.md).

| id | H | requirement | status |
|---|---|---|---|
| E2-L01 | H1 | TH-D75A over USB serial: identify, verify, both bands, `AI` push (D15, AC-133 half) | hardware |
| E2-L02 | H2 | TH-D75A over Bluetooth SPP: same command set, same capabilities (AC-133) | hardware |
| E2-L03 | H3 | Bluetooth rig link drop → stale, capture continues, reconnects (FR-RIG-15) | hardware |
| E2-L04 | H4 | USB re-plug mid-session surfaces F16 (D10) | hardware |
| E2-L05 | H5 | Bluetooth audio from a headset-class device: route verifies, profile recorded, rows marked (AC-132) | hardware |
| E2-L06 | H6 | **TH-D75A as the phone's Bluetooth audio source — prediction: not offered by stock Android** (D34 note) | hardware |
| E2-L07 | H7 | Bluetooth audio drop mid-session → gap, F23, recovery (FR-CAP-5) | hardware |
| E2-L08 | H8 | Mode change while capturing applies at the next session (AC-131) | hardware |
| E2-L09 | H9 | Fresh install in airplane mode reaches full capability (AC-136) | hardware |
| E2-L10 | H10 | Corrupted bundled asset refused at launch (AC-137) — optional on hardware, required on the AVD | hardware |
| E2-L11 | H11 | LLM at tier 3: prose only while idle and charging; disable releases memory; no invented callsign (AC-87, AC-138, AC-140) | hardware |
| E2-L12 | H12 | Local-microphone overnight: the room-audio mark everywhere, session recorded as such (AC-128, AC-129) | hardware |
| E2-L13 | H13 | Eight-hour run in USB-radio and Bluetooth-radio modes on ColorOS (D4 equivalents, R5) | hardware |
