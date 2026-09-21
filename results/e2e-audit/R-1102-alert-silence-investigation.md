# R-1102 investigation: AC-193's packet-capture proof for live alerts

AC-193: "No alert definition, match, or firing event is ever transmitted off the device, verified
by packet capture across a session that fires at least one alert of each kind (FR-ALR-2)." The
register row was filed assuming this needed the reference phone and the H1-H15 hardware protocol.
This session checked that assumption on an emulator instead, per its own brief: an emulator can be
packet-captured, and a structural guarantee that also carries an empirical demonstration is worth
more than one that does not.

**Result: the capture technique works on an emulator. The other half — driving a session in which
an alert genuinely fires — does not, on any emulator, without a code change. AC-193 is not closed
by this session; the row stays with the hardware protocol**, for a different and more specific
reason than the one it was filed under (not "needs a device to run tcpdump" — it demonstrably does
not — but "needs a device with a real microphone or a real rig, because nothing in the shipped or
debug-only code can make Pass B resolve a transmission without one").

## Device and method

`emulator-5560` (`ort_audit_a11y`, API 34, `sdk_gphone64_x86_64`, userdebug), already running with
`org.ort.app` installed — not `emulator-5554` (the lead's canonical captures) and not a relaunch of
any AVD, so nothing already running was disrupted.

Two capture routes were named in this unit's brief:

- **`-tcpdump <file>` at emulator launch** — would have required killing and relaunching
  `emulator-5560`'s existing qemu process, which is shared with whatever else is using that AVD.
  Not used, to avoid disrupting it.
- **`adb shell tcpdump` on a rooted device** — checked first, and it works:

```
$ adb -s emulator-5560 root
restarting adbd as root
$ adb -s emulator-5560 shell id
uid=0(root) gid=0(root) ... context=u:r:su:s0
$ adb -s emulator-5560 shell which tcpdump
/system/bin/tcpdump
$ adb -s emulator-5560 shell tcpdump --version
tcpdump version 4.99.3
libpcap version 1.10.3 (with TPACKET_V3)
```

`adb root` does not restart the emulator process — the running instance, and whatever else is
using it, was untouched. **This is the method used**: no relaunch, no `-tcpdump` flag needed.

## Positive control: the capture technique can see real traffic

A negative packet capture proves nothing by itself — an app that did nothing at all would also
produce none. Before trying to make an alert fire, the technique itself was validated with a
deliberate, unrelated network action in the same session shape (8 s tcpdump window, background via
`nohup ... &`, stopped with `pkill -INT tcpdump` so the pcap trailer is written cleanly):

**Idle baseline** (`emulator-5560`, nothing launched, 8 s):
```
$ adb -s emulator-5560 shell tcpdump -i any -w /data/local/tmp/idle-baseline.pcap
2 packets captured
```

**Positive control** (`emulator-5560`, `am start -a android.intent.action.VIEW -d https://example.com com.android.chrome`, 8 s):
```
$ adb -s emulator-5560 shell tcpdump -i any -w /data/local/tmp/positive-control.pcap
182 packets captured
```

2 packets of background chatter versus 182 packets for one real page load is a clean, discriminating
signal: this rig can tell "nothing happened" from "something happened." Both pcaps were pulled,
inspected for size/count, and deleted from the device afterward (`/data/local/tmp` cleanup) along
with the `deviceidle` whitelist change made below, so `emulator-5560` was left as found for whoever
uses it next (`pm clear org.ort.app` at the end, matching what `install.ps1 -Clear` would leave).

## Why an alert could not be made to fire

FR-ALR-3/AC-194: an alert fires only when **Pass B resolves a real transmission** — the coordinator
is invoked from exactly one production call site, `DataPassBResultSink.record`'s
`runCatching { alertTrigger.fireAndForget(...) }` (`pipeline/src/main/kotlin/org/ort/pipeline/passb/DataPassBResultSink.kt`),
which only runs inside a live `RealCaptureService` session (`RealCaptureService.startProcessingLoop`
builds the one real, session-scoped `AlertEvaluationCoordinator`, per the P31/`c1196a1d` CHANGELOG
entries). Nothing else in the shipped app calls it:

- **`ReprocessRunner` never fires a live alert, by design** — `PassBFactory.create`'s
  `alertTrigger` parameter defaults to `NoOpAlertEvaluationTrigger`, and `ReprocessRunner.realPassBFor`
  never supplies a real one (R-1097, decided in the CHANGELOG's own words: "reprocessing still
  never fires a live alert, only live capture can").
- **The debug scenario mechanism (`Scenarios.load`, `ScenarioReceiver`) writes historical rows
  straight into Room** (`app/src/debug/kotlin/org/ort/app/debug/Scenarios.kt`) — that is exactly why
  the UI-conformance tour can screenshot alert-bearing screens without a live session, and exactly
  why it cannot make an alert fire: it never touches `RealCaptureService` or the live coordinator.
  Searched the whole `app/src/debug` tree for an alert-specific scenario the task brief expected to
  find (`grep -ri alert app/src/debug`) — **none exists**; alert watches are added only through the
  real Settings screen (`SettingsAlertsScreen.kt`) into the real `FileBackedAlertWatchStore`.
  `RealCaptureService.Dependencies` (the one class with a seam for overriding audio I/O) is
  `internal` to `:pipeline`, with no debug/test override surfaced to `:app`.

So the only route to a genuine firing is a real, live capture session that gets a real transmission
through Pass B. That was attempted:

1. **Added a keyword/callsign/frequency watch is moot until a session can even start** — setup has
   to complete first. Used the existing `setup-verified` scenario (`tools\ui-audit\scenario.ps1
   -Port 5560 -Name setup-verified`, the documented recipe from `results/ui-audit/README.md`'s
   "Reaching S07/S09/S12" section) to seed a `USB-connected radio` / `USB Audio Device · verified`
   setup and reach S12 (Ready) without needing `S05`'s real 30 s microphone listen.
2. **S12's own `Overnight` row is amber** (`Battery exemption skipped`) and tapping `Start capture`
   routes back into the wizard at `SetupStep.OVERNIGHT` rather than starting anything.
   `SetupActivity.reconcileOvernightSurvival()` (`app/src/main/kotlin/org/ort/app/ui/setup/SetupActivity.kt:389`)
   resets `SetupStore.overnightStepSeen` to `false` on **every** entry to `SetupActivity` until
   `overnightSurvivalProven` is `true` — which itself requires a *real, already-completed* overnight
   session already in the database (`RealOvernightSurvivalChecker`). On a freshly cleared device this
   is a bootstrap-order problem, not something `Skip for now` can get past by design once the
   activity is re-entered; within one continuous activity instance `onSkipOvernight()` sets the flag
   and calls `refreshStep`, but no in-session tap sequence produced a visible step change in this
   attempt (`adb shell input tap`/`input touchscreen tap` on the verified UI-Automator bounds for
   both `Skip for now` and `Open the setting`; the latter did, on one attempt, reach
   `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations` per logcat, but returned
   without the exemption sticking — `dumpsys deviceidle whitelist +org.ort.app` added the package to
   the user whitelist but `SetupActivity` still read "not yet exempt" after a fresh relaunch).
3. Regardless of (2): the `setup-verified` scenario's own seeded state is **USB-connected radio**
   with **USB Audio Device** marked verified — a fixture flag, not a real attached device. This
   emulator has no physical or virtual USB audio peripheral, so even a successful `Start capture`
   would have nothing real for `AndroidAudioIo` to open.
4. **Local-microphone mode fares no better, and this is already independently established**, not
   just asserted here: `results/e2e-audit/checklist.md`'s own row **E2-E02** records that a previous
   validator (V8) found "S05's real 30 s listen cannot pass on the AVD's silent microphone... tried
   every lane." No emulator in this fleet has a working virtual microphone; without one, the
   segmenter's VAD never opens a segment, so no transmission is ever created, so
   `DataPassBResultSink.record` — the alert coordinator's only call site — is never reached.

Confirmed directly rather than just inferred: after the setup/overnight attempts above, the app's
own database had **zero sessions and zero transmissions**
(`adb -s emulator-5560 shell run-as org.ort.app sqlite3 databases/ort.db "SELECT count(*) FROM
transmission;"` → `0`), and no `RealCaptureService`/capture foreground service was ever running
(`dumpsys activity services org.ort.app` showed none). A capture session was never actually
started, which is the honest reason no alert could be observed firing — not "it fired and produced
no traffic," which would be a false pass of exactly the kind this project's own working agreement
warns against.

## What would need to change (out of this session's scope)

Either real audio input reaches one of these emulators (not available: every AVD in this fleet
boots `-no-audio`, and this session was told not to relaunch a shared AVD with a different audio
backend), or a debug-only seam is added that can hand a resolved `AlertMatchInput` to the live,
session-scoped `AlertEvaluationCoordinator` without a real transmission — the same class of thing
`Scenarios.load` already does for Room rows, but nothing today does it for the live coordinator.
Either is a product/code change. This unit's brief is explicit that such a change is out of scope
("If satisfying this needs a code change, STOP and report rather than making it"), so it was not
made. The structural guarantee itself was re-confirmed instead:

```
$ .\gradlew.bat dependencyRules
dependencyRules: checked 21 modules
  :pipeline -> :asr-api, :asr-sherpa, :capture-android, :capture-api, :core, :data, :identity,
               :lexicon, :llm-api, :llm-mediapipe, :onnx, :rig, :rig-bluetooth, :rig-usb, :segment,
               :telemetry
  :capture-android -> :capture-api, :core
dependencyRules: OK - every edge is permitted by the design graph.
BUILD SUCCESSFUL
```

Neither `:pipeline` (where the alert package lives) nor `:capture-android` has an edge to `:net` —
the structural half of AC-193 continues to hold, unchanged by this session.

## Disposition

R-1102/AC-193 is **not closed**. It stays with the hardware protocol
(`results/e2e-audit/hardware-checklist.md`), for the reason this investigation found rather than
the one the row was originally filed under: not because packet capture needs a physical device (it
does not — the technique above works on an emulator and would work equally well there), but because
making an alert genuinely fire needs either a real microphone/rig or a code change, and this session
had neither in scope. A future hardware session running H1/H2/H5/H9-style protocol already produces
exactly the "alerts fire" half this needs; pairing it with `adb root && adb shell tcpdump -i any -w`
on the reference phone (if it is rooted) or an emulator run once a debug injection seam exists would
complete AC-193 in one sitting.
