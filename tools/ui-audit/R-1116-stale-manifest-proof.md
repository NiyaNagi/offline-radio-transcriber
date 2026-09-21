# R-1116 done-when proof: `diff.py` refuses a deliberately stale/mixed manifest

P37's own "done when" (spec/build-plan.md) asks for "a live tour invocation against a deliberately
stale manifest exits non-zero, with the transcript committed." This session had no emulator
attached (three were already in use by the lead's own gate, and the working rules for this unit
say not to install to any of them), so per that same instruction this is the `diff.py` half of
the proof, run directly against the **real, committed captures** under `results/ui-audit/` — no
synthetic screenshots. The `tour.ps1` half (an actual on-device run reporting a foreign `runId`)
still needs the lead's own emulator and is not claimed here.

## How the stale manifest was built

There is no second real tour run's manifest sitting around in this offline session to splice in,
so the deliberately-stale manifest below is the real, committed
`results/ui-audit/tour-manifest.json` (see its own `git log`, e.g. commit `f26ab8a8`), truncated to
its first six non-`done` entries, with entry 3 (`storage-warn/N00-drawer`) given an extra
`"runId": "deliberately-stale-foreign-run"` field the other five entries do not carry. That is
exactly the shape R-1083/R-1116 describe: a manifest whose entries were not all written by the same
tour invocation. Every `apkHash` here is the real value (`686212f`) already committed in the file;
only the injected `runId` is synthetic.

```json
{"id":"overnight/N00-drawer","scenario":"overnight","fontScale":1,"width":1080,"height":2400,"capturedAt":"2026-09-11T17:13:39.235589Z","ok":true,"errorMessage":null,"apkHash":"686212f","note":null}
{"id":"overnight/N00-drawer@2x","scenario":"overnight","fontScale":2,"width":1080,"height":2400,"capturedAt":"2026-09-11T17:13:41.140925Z","ok":true,"errorMessage":null,"apkHash":"686212f","note":null}
{"id":"storage-warn/N00-drawer","scenario":"storage-warn","fontScale":1,"width":1080,"height":2400,"capturedAt":"2026-09-11T17:13:42.851289Z","ok":true,"errorMessage":null,"apkHash":"686212f","note":null,"runId":"deliberately-stale-foreign-run"}
{"id":"empty/N01-now-idle","scenario":"empty","fontScale":1,"width":1080,"height":2400,"capturedAt":"2026-09-11T17:13:44.286824Z","ok":true,"errorMessage":null,"apkHash":"686212f","note":null}
{"id":"first-session/N01-now-first","scenario":"first-session","fontScale":1,"width":1080,"height":2400,"capturedAt":"2026-09-11T17:13:45.900042Z","ok":true,"errorMessage":null,"apkHash":"686212f","note":null}
{"id":"overnight/N01-now","scenario":"overnight","fontScale":1,"width":1080,"height":2400,"capturedAt":"2026-09-11T17:13:47.674429Z","ok":true,"errorMessage":null,"apkHash":"686212f","note":null}
{"done":true}
```

## Invocation and transcript

`--after` is the real, committed `results/ui-audit/` directory (its own PNGs, untouched); `--before`
is `HEAD`, so every step's "before" image is the same commit's own committed PNG (before and after
are pixel-identical here — this proof is about the manifest gate, not the pixel comparison, so an
`unchanged` verdict was never going to be reached: the run-identity check must refuse before any
image is even opened).

```
$ python tools\ui-audit\diff.py --before HEAD --after results\ui-audit --manifest "<scratch>\stale-tour-manifest.json"
error: --manifest <scratch>\stale-tour-manifest.json: entries disagree on 'runId' - found ['deliberately-stale-foreign-run', 'unknown']. This manifest carries more than one tour run's worth of entries and cannot be used as evidence for a single run (R-1116).
exit code: 3
```

Exit code **3**, non-zero, and no `tour-diff.md` was written — the fixed `diff.py` refuses to
compare a single pixel once it has read a manifest that cannot be one coherent run. Run against the
pre-fix `diff.py` (`git show HEAD:tools/ui-audit/diff.py`, before this change), the identical
invocation exits **0** and writes a report, because that version never read `runId` at all — the
same discrimination this unit's test suite records in `tools/ui-audit/tests/test_diff.py` and
CHANGELOG.md's R-1116 entry.

## Still needed from the lead

A live `tour.ps1` invocation against a device/emulator whose on-device manifest is made stale (the
`tour.ps1`-side reproduction R-1083's own `.NOTES` describe) exercises the same `runId` mismatch
end to end, from a real device poll through to this script's refusal. That half needs one of the
attached emulators and is left to the lead, per this unit's own working rules (no install to any
attached emulator from this session).
