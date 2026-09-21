# Backlog — every open item, one view

**Generated** by `tools/backlog/backlog.py`. Do not edit by hand: file the item on the
surface that owns it (the register, a checklist, the build plan, the questions) and
regenerate. `--check` fails when this file is stale, so the view cannot drift.

Priority is derived, not assigned: a register row of severity `halt`, an unrun hardware
step and a beta-gate build unit are P0; `spec` rows and remaining units are P1; design,
process, polish, open questions and coverage debt are P2.

| Source | Open | P0 | P1 | P2 |
|---|---:|---:|---:|---:|
| UI / defect register | 110 | 22 | 49 | 39 |
| Hardware protocol | 15 | 15 | 0 | 0 |
| Build-plan units | 12 | 5 | 7 | 0 |
| Capture-modes checklist | 15 | 0 | 15 | 0 |
| Open questions | 10 | 0 | 0 | 10 |
| Coverage matrix | 2 | 0 | 0 | 2 |
| **Total** | **164** | **42** | **71** | **51** |

## P0 — blocks the beta, or is wrong in front of the operator

| id | what | source | state | refs / note |
|---|---|---|---|---|
| P33 | Pass B honesty — wire the evidence priors, stop asserting `CONFIRMED` uncalibrated | build-plan | not started | beta gate |
| P34 | Capture route and sample rate | build-plan | not started | beta gate |
| P35 | Durability — stale leases and the migration guard | build-plan | not started | beta gate |
| P37 | Evidence tooling — make `diff.py` able to fail | build-plan | not started | beta gate |
| P38 | Packaging and the model mirror | build-plan | not started | beta gate |
| H1 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H1.md |
| H2 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H2.md |
| H3 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H3.md |
| H4 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H4.md |
| H5 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H5.md |
| H6 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H6.md |
| H7 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H7.md |
| H8 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H8.md |
| H9 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H9.md |
| H10 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H10.md |
| H11 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H11.md |
| H12 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H12.md |
| H13 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H13.md |
| H14 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H14.md |
| H15 | protocol step never run | hardware | unrun | no results/e2e-audit/hardware/H15.md |
| R-011 | drawer | register | halt/fixed / awaiting capture | `Grid` |
| R-085 | setup › mic denied | register | halt/fixed / awaiting capture | `Setup-Mic-Denied` |
| R-180 | D01–D03 Why section, D05 | register | halt/fixed / awaiting capture | `Detail-Why`, FR-UI-8 |
| R-272 | ST04 Split | register | halt/fixed / awaiting capture | `Station-Identity`, `Fail-Cluster` |
| R-320 | D05 Detail-Why, section 1 | register | halt/fixed / awaiting capture | `Detail-Why`, FR-UI-8, cf. R-180 |
| R-760 | CF03 Settings-Storage usage bar with an empty store | register | halt/fixed / awaiting capture | `Settings-Storage`, guide §6.3, cf. R-441 |
| R-809 | The Release workflow's unit-test job with every asset present | register | halt/building | `.github/workflows/release.yml`, R-807, R-808 |
| R-865 | CF04 under `tier0-llm-stored` | register | halt/closed | `Settings-Assets.dc.html`, FR-AST-3a |
| R-882 | S12 Radio row under `rig-bt-connected` at 2.0 | register | halt/closed | `Setup-Done.dc.html`, R-874 |
| R-883 | Failure banner overlay at 2.0 (F9, F23 on Now) | register | halt/closed | `Fail-Rig.dc.html`, `Fail-Bluetooth-Audio.dc.html`, R-613 |
| R-911 | Live-bar fragment at the top of the frame | register | halt/closed | `Threads.dc.html`, `Session.dc.html`, `Capture-Status.dc.html` |
| R-1001 | Pass B, every transmission | register | halt/fixed / awaiting capture | FR-ASR-1, constitution VII |
| R-1002 | the nineteen already-failed overs | register | halt/fixed / awaiting capture | FR-ASR-6, P9 |
| R-1003 | every pinned bottom action bar, all screens | register | halt/fixed / awaiting capture | `Setup-Rig.dc.html`, `Setup-Rig-Transport.dc.html`, `Setup-Rig-Bluetooth.dc.html`, guide S8 |
| R-1013 | S10b link probe | register | halt/fixed / awaiting capture | FR-RIG-3, FR-RIG-14, FR-RIG-15, constitution I + IV |
| R-1014 | S10b `Verified` | register | halt/fixed / awaiting capture | FR-RIG-3, constitution I |
| R-1109 | Production Pass B runs with every evidence prior dead | register | halt/open | FR-LEX-9; FR-LEX-25..27, FR-LEX-31; constitution I |
| R-1110 | `CONFIRMED` is asserted on an uncalibrated score | register | halt/open | FR-LEX-17..21; FR-SPK-10; constitution I, VI |
| R-1111 | Improve is unreachable on every real session | register | halt/open | FR-REP-1..11; AC-39 |
| R-1112 | The field-report channel is absent from every signed build | register | halt/open | FR-OBS-6..12; D55 |
| R-1113 | Route-change and interruption events never fire in production | register | halt/open | FR-CAP-3; FR-RUN-11; constitution IV |
| R-1120 | An unguarded index build can brick launch permanently | register | halt/open | FR-STO-7; constitution III |

## P1 — must be true for 1.0

| id | what | source | state | refs / note |
|---|---|---|---|---|
| P9 | GATE: 8-hour run on the reference device | build-plan | not started |  |
| P11 | End-to-end, then the M4 fork | build-plan | not started |  |
| P12 | Make capture actually work end to end on a device | build-plan | not started |  |
| P25 | VAD fallback disclosure | build-plan | not started |  |
| P26 | Playback stops on navigation, plus five small defects | build-plan | not started |  |
| P31 | Live alerts | build-plan | not started |  |
| P32 | Accessibility pass — WCAG 2.2 AA, FR-A11Y | build-plan | not started |  |
| E2-A06 | FR-RIG-6, FR-CAP-5, FR-RIG-3 | e2e | **owed data/descriptor additions found while wiring** (wpc2 report): transmissionentity.rigstatechangedmidtransmission (v9), capturegapcause.bluetooth_audio_lost (v9), transportspec.usbvendorid/usbproductid/lineterminator on :rig descriptors (then defaultrigtransportfactory reads the descriptor before |  |
| E2-I06 | D36 | e2e | hardware |  |
| E2-L01 | H1 | e2e | hardware |  |
| E2-L02 | H2 | e2e | hardware |  |
| E2-L03 | H3 | e2e | hardware |  |
| E2-L04 | H4 | e2e | hardware |  |
| E2-L05 | H5 | e2e | hardware |  |
| E2-L06 | H6 | e2e | hardware |  |
| E2-L07 | H7 | e2e | hardware |  |
| E2-L08 | H8 | e2e | hardware |  |
| E2-L09 | H9 | e2e | hardware |  |
| E2-L10 | H10 | e2e | hardware |  |
| E2-L11 | H11 | e2e | hardware |  |
| E2-L12 | H12 | e2e | hardware |  |
| E2-L13 | H13 | e2e | hardware |  |
| R-014 | drawer | register | spec/fixed / awaiting capture | — |
| R-052 | detail › correct | register | spec/fixed / awaiting capture | `Detail-Correct-A/B/C`, `Detail-Propagated`, P3 |
| R-106 | log gap row | register | spec/fixed / awaiting capture | `Rows`, `Fail-Call`, FR-RUN-11, F15 |
| R-177 | Fail-Thermal banner | register | spec/fixed / awaiting capture | `Fail-Thermal` |
| R-188 | D04 Unknown | register | spec/fixed / awaiting capture | `Detail-Unknown`, P1, P11 |
| R-262 | CF04 Settings-Assets (and every scrolling destination) | register | spec/fixed / awaiting capture | `Settings-Assets`, guide §6.6 |
| R-282 | S06 Setup-Route-Mismatch @2x | register | spec/fixed / awaiting capture | `Setup-Route-Mismatch`, guide §5 |
| R-350 | R04 Improve-Done | register | spec/fixed / awaiting capture | `Improve-Done`, FR-REP, cf. R-143/R-290 |
| R-371 | Search, frequency query | register | spec/fixed / awaiting capture | `Search`, `Search-Results`, FR-UI-3 |
| R-381 | Every merged row: N04 status rows, L01/T02 rows, L02 chips, D03/D08 candidate rows, the Search field, the live bar | register | spec/partial | `Rows`, `Capture-Status`, guide §11, FR-A11Y-2 |
| R-543 | Applied filter chips (Search, Log filter) dismiss icon | register | spec/fixed / awaiting capture | `Search-Filters`, `Log-Filter`, guide §3, FR-UI-7 |
| R-550 | N04 Capture-Status @2x under `storage-warn`, scrolled to end | register | spec/fixed / awaiting capture | `Capture-Status`, `Fail-Storage`, FR-UI-7, cf. R-300 |
| R-613 | N04 Capture-Status @2x-end under `storage-warn` | register | spec/fixed / awaiting capture | `Capture-Status`, `Fail-Storage`, FR-UI-7, cf. R-300/R-550 |
| R-720 | D01/D02/D03 inline "Why this callsign" | register | spec/fixed / awaiting capture | `Detail`, `Detail-Confirmed`, `Detail-Why`, FR-UI-8, cf. R-051/R-320 |
| R-835 | CF06 under `rig-bt-connected` | register | spec/closed | `Settings-Rig.dc.html`, FR-RIG-14 |
| R-1004 | S04 input selection | register | spec/fixed / awaiting capture | `Setup-Input.dc.html`, FR-CAP-3 |
| R-1007 | live bar -> full live view | register | spec/artboard drawn | `Capture-Status.dc.html`, new `Live-Monitor.dc.html`, FR-UI-7 |
| R-1008 | app-wide | register | spec/open | P4, `Feedback.dc.html` |
| R-1010 | field-report channel | register | spec/wave 0 landed | FR-OBS-3/5/5a, FR-SPK-20, FR-CON-3, constitution V |
| R-1033 | live-captured overs | register | spec/open | constitution VI |
| R-1034 | `capture.log` | register | spec/open | FR-OBS-1 |
| R-1036 | archive default-on disclosure | register | spec/**specified** | D39, FR-STO-3d, constitution I |
| R-1037 | gated-audio pruning order | register | spec/**decided by the product owner** | FR-STO-3, FR-STO-3d |
| R-1054 | Capture with VAD unavailable | register | spec/fr-seg-10 merged, q22 open | constitution I, VI; FR-OBS-1; D41 |
| R-1062 | FR-SEG-5 squelch fusion was never built | register | spec/hosted green, hardware check outstanding | FR-SEG-5 (M), CON-SEG-1; failure mode F4 |
| R-1088 | Wave I and J: export completion and live alerts, captured | register | spec/built, merged, awaiting hosted run | P30, P31, FR-EXP-7, FR-STO-9, FR-ALR-1..6, AC-169..171, AC-191..197 |
| R-1089 | Four design tokens fail WCAG AA contrast | register | spec/open | constitution VII (the accessibility floor); FR-A11Y; design-guide |
| R-1091 | Setup-Mic-Denied's artboard draws a halt the code does not have | register | spec/open | constitution I, VIII; R-1087 |
| R-1093 | CF13 Settings-Analytics has an artboard and a capture, but no judgement | register | spec/open | NFR-6d-adjacent; D42, FR-ANL-11, AC-180 |
| R-1094 | Backup carries four tables, not the record | register | spec/open | FR-STO-9; AC-170; constitution III |
| R-1095 | Restore's conflict rule is all-or-nothing | register | spec/open | FR-STO-9; AC-170 |
| R-1096 | The share sheet always shares the most recent thing | register | spec/open | FR-EXP-7; AC-171 |
| R-1097 | Reprocessing never fires an alert, and a delivery failure is discarded | register | spec/open | FR-ALR-1..6; AC-191..197 |
| R-1098 | A thread records no reason for its own grouping | register | spec/open | FR-SPK-5; AC-163..165; constitution I |
| R-1099 | Two implementations still rebuild a corrected attribution | register | spec/open | FR-SPK-10; constitution I, VII |
| R-1100 | Analytics records screens, not actions | register | spec/open | FR-ANL-2; D42 |
| R-1101 | The analytics uploader's TLS path has never run | register | spec/open | FR-ANL-9; D48 |
| R-1102 | AC-193's network-silence proof for alerts has not been run | register | spec/open | AC-193; FR-ALR-3; constitution V |
| R-1104 | Setup only rechecks overnight survival when it is reopened | register | spec/open | AC-189; NFR-8; constitution IV |
| R-1105 | Two shipped artifacts are unproven: the play APK's native libraries and the model mirror | register | spec/open | FR-AST-13, FR-AST-14; D43, D44 |
| R-1106 | AndroidManifest has no dataExtractionRules | register | spec/open | technical-design §12.4; NFR-6a |
| R-1108 | Two more screens carry the confirmed empty-label defect | register | spec/open | FR-A11Y; R-1090 |
| R-1114 | Capture never negotiates the adapter's native sample rate | register | spec/open | FR-CAP-2, FR-CAP-2a |
| R-1115 | The heartbeat keeps no history, so the 30-minute prove-it test cannot run | register | spec/open | NFR-8; FR-SVC-5b; AC-189 |
| R-1118 | A thread's participant order drops every unattributed over | register | spec/open | FR-SPK-29, FR-SPK-30; AC-164 |
| R-1119 | Stale work-queue leases are never recovered | register | spec/open | FR-RUN-8; AC-47 |
| R-1121 | Hallucination control 3 is inert, and no acoustic confidence exists anywhere | register | spec/open | FR-ASR-5, FR-ASR-6; AC-6 |
| R-1122 | Every number the corpus harness emits lacks machine and provider | register | spec/open | constitution VI |
| R-1123 | Native aborts, ANRs and OOM are unreportable | register | spec/open | FR-ANL-3; R-1052 |

## P2 — after the beta, or polish

| id | what | source | state | refs / note |
|---|---|---|---|---|
| coverage-orphans | 12 tests name a requirement id the spec does not have | coverage | open | ./gradlew coverageMatrix |
| coverage-uncovered | 213 requirement ids have no test | coverage | open | ./gradlew coverageMatrix |
| T1 | Speaker embedding separation on narrowband off-air audio | questions | open |  |
| T2 | Phonetic-unit KWS accuracy on radio audio | questions | open |  |
| T3 | Whether CB-Whisper's encoder-hidden-state approach ports to sherpa-onnx on Android | questions | open |  |
| T4 | Hotword automaton cost at ~100 units on target hardware | questions | open |  |
| T5 | **ONNX export of a fine-tuned Whisper for sherpa-onnx** | questions | open |  |
| T6 | Real per-transmission latency on a mid-tier phone | questions | open |  |
| T7 | Whether a specific PD + USB-Audio-Class hub works with the reference device | questions | open |  |
| T8 | Whether ATC fine-tuning gains transfer to amateur radio audio | questions | open |  |
| T9 | End-to-end RTF for `large-v3-turbo` on a real Android app (vendor figures are component latencies) | questions | open |  |
| T10 | Whether enhancement helps or hurts *per pass* on this audio | questions | open |  |
| R-010 | drawer | register | design/fixed / awaiting capture | `Menu` |
| R-012 | drawer footer | register | design/fixed / awaiting capture | `Menu` footer, D26 |
| R-013 | drawer | register | design/fixed / awaiting capture | `Menu` |
| R-056 | detail › label | register | design/fixed / awaiting capture | `Controls` §6.11 |
| R-220 | S02b, S05 (timed out), S06 | register | design/open | `Setup-Mic-Denied`, `Setup-Verify`, `Setup-Route-Mismatch` |
| R-280 | S02b, S03, S05 timed out, S06 | register | design/open | `Setup-*` |
| R-340 | S02b, S03, S05, S06, S09, S11 bottom bar | register | design/open | `Setup-*`, cf. R-220/R-280 |
| R-431 | FQ02 typical-night chart caption | register | design/fixed / awaiting capture | `Frequency`, cf. R-216 |
| R-611 | ST04 Station-Identity Nearest-other row @2x | register | design/fixed / awaiting capture | `Station-Identity`, FR-SPK-10, cf. R-570 |
| R-612 | S03 Setup-Notify scrolled at 2.0 | register | design/open | `Setup-Notify`, guide §5 |
| R-721 | D04 Detail-Unknown "what was tried" | register | design/fixed / awaiting capture | `Detail-Unknown` |
| R-761 | CF04 Settings-Assets grouped model rows | register | design/fixed / awaiting capture | `Settings-Assets`, cf. R-443 |
| R-770 | Tour coverage of designed screens | register | process/open | `design/design-intent.md`, tour |
| R-771 | D02 Detail-Inferred lattice slots | register | design/fixed / awaiting capture | `Detail`, cf. R-720/R-320 |
| R-801 | Second look at the 2026-09-10 boards (read-only reviewer, no device) | register | process/fixed / awaiting capture | `design-guide.md` §3, §6.1, §6.5, §8 |
| R-838 | F23 Log gap row icon | register | design/closed | `Fail-Bluetooth-Audio.dc.html` |
| R-884 | Ghost "Back" at the top of S09b at 2.0 | register | polish/hardware | `Setup-Rig-Transport.dc.html` |
| R-933 | CF04 under `model-missing` (live) | register | design/closed | `Settings-Assets.dc.html` |
| R-943 | S05 under `setup-verified` | register | design/closed | `Setup-Verify.dc.html` |
| R-944 | S07 under `setup-level` | register | design/closed | `Setup-Level.dc.html` |
| R-946 | Ghost "Back" on S09b at 1.0 | register | polish/hardware | `Setup-Rig-Transport.dc.html`, R-884 |
| R-985 | CF02 "Re-verify the route now" row at 2.0 has no frame | register | process/open | `Settings-Capture.dc.html`, R-980 |
| R-1016 | S10b title | register | design/fixed / awaiting capture | `Setup-Rig-Bluetooth.dc.html` line 37, R-941 |
| R-1017 | S10b paired-device rows at 2.0 | register | design/fixed / awaiting capture | `Setup-Rig-Bluetooth.dc.html` lines 44-59, R-874/R-880 |
| R-1018 | S10b pinned block bottom padding | register | polish/fixed / awaiting capture | `Setup-Rig-Bluetooth.dc.html`, guide 10.1 |
| R-1021 | `overnight-live-monitor` tour steps | register | process/open | R-1007, constitution II |
| R-1023 | N07 timestamp column at 2.0 | register | design/**fixed, confirmed at 2.0** | `Live-Monitor.dc.html`, R-880/R-1017 family |
| R-1024 | `overnight-live-monitor` level meter | register | process/**fixed, confirmed at 2.0** | `Live-Monitor.dc.html` lines 44-77 |
| R-1025 | `overnight-live-monitor/N07-live-monitor` at 1.0 | register | process/**reopened | R-1021 |
| R-1026 | N07 level envelope colours | register | design/fixed / awaiting capture | `Live-Monitor.dc.html`, guide 8, R-944 |
| R-1027 | N07 level caption at 2.0 | register | design/fixed / awaiting capture | `Live-Monitor.dc.html` |
| R-1043 | `CaptureStatusContentTest` R_232 | register | process **2026-09-20 (overnight session):** this row cost two full gate runs. `transmissiondetailcontenttest > r_052` failed under load with `performscrollto() failed ... could not find any node ... contentdescription = 'typed callsign'` and passed in isolation on an unchanged tree. diagnosed rather than re-run: three flows in that file clicked *type a callsign* and then looked for the field with one implicit idle-wait instead of the file's own bounded `waituntildescriptionexists` poll, so a contended machine loses the race. fixed with the deterministic wait, not a longer timeout (merged this session). **two mechanisms remain unproven and are deliberately unfixed:** `settingscapturescreentest`/`bluetoothpermissionscreentest`'s `appnotidleexception` (suspected cross-class compose recomposer poisoning in a shared fork - neither class owns async state) and `:data`'s `sqlite_cantopen` when two on-disk databases open back to back in one robolectric jvm on windows (`forkevery` is set only in `app/build.gradle.kts`, nowhere else). neither could be forced red under synthetic cpu load across 5 runs each, so no fork-policy or timeout change was guessed at - the constitution's own rule after two blind fixes cost more than one instrumented run. next occurrence: capture `jstack` rather than re-running./partly fixed | constitution II |
| R-1080 | Failure banner bottom border after R-1077 | register | polish/open | constitution VIII |
| R-1083 | The tour reports a stale manifest as success | register | tooling/open | constitution II, VIII; tooling |
| R-1090 | The accessibility sweep is half done | register | spec **2026-09-20, second pass (`b9d916e8`):** the screens the first pass never reached were inventoried on a device and fixed - the correction sheet, search's recent row, threads (card and meanwhile row), thread detail, stations (`stationrow` dumped an empty `content-desc` **and** measured 33.5 dp, under the floor), station identity, digest, sessions, recordings (the budgets card had no semantics at all), capture's own back chevron (the r-1073 shape again, on a screen that draws its own header), settings export and backup, and `feedback.kt`'s `banneraction`, which every banner in the app uses. settings root, storage and licences confirmed clean. **still open:** `improvescreens.kt` and `frequencyscreen.kt` carry the identical defect and were outside the sweep's list; search's `widenrow`/`struckthroughqueryfield`/`unappliedtextcountline` use a different shape that was not device-verified; and no live talkback pass has been run, which is what would settle whether `uiautomator`'s split-node dump is ground truth for a small icon inside a clickable box./open | constitution VII, VIII; FR-A11Y |
| R-1103 | The tour script itself has no test | register | process/open | constitution II, VIII; R-1083 |
| R-1107 | The mic-denied setup screen has an artboard and no tour step | register | process/open | constitution VIII; R-770, R-1091 |
| R-1116 | `diff.py` cannot fail, and cannot see a changed line of text | register | process/open | constitution VIII; R-1083, R-1103 |
| R-1117 | The strongest built differentiator has never been captured | register | design/open | constitution VIII; FR-UI-8; R-770 |
