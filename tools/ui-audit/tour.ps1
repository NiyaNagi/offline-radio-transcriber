<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP12 - runs the whole screenshot tour (or a filtered subset) on one
  emulator and pulls every PNG plus the manifest into results/ui-audit/.

.DESCRIPTION
  Installs nothing (`install.ps1` already put the debug APK on the device). Writes the tour spec
  into the app's own sandbox, launches `ScreenshotTourActivity` (`app/src/debug/AndroidManifest.xml`),
  polls the on-device `<filesDir>/tour/manifest.json` for its trailing `{"done": true, ...}` line (a
  hard 15-minute cap either way), then pulls the whole on-device `tour/` directory and lays it out
  the same way `shoot.ps1` already does: `results/ui-audit/<scenario>/<screen>.png`, plus the
  manifest itself at `results/ui-audit/tour-manifest.json`.

  **Everything this activity reads or writes lives under this app's private storage**
  (`getFilesDir()`), never `/sdcard` or `/data/local/tmp` - API 34 scoped storage refuses this app
  read access to either (a real `FileNotFoundException ... EACCES`, found by actually running the
  first real-device tour, not by inspection). This script never redirects `adb exec-out` to a file
  (brief-common.md's own rule) either way - both directions go through `run-as`:

  - **Push**: `Get-Content -Raw` piped into `adb shell run-as org.ort.app sh -c 'mkdir -p files/tour
    && cat > files/tour/spec.json'` - stdin forwarding through a non-interactive `adb shell` is
    standard adb behaviour. The `-c` payload's grouping quotes are **single** quotes, embedded
    literally inside one PowerShell string handed to `adb` as a single argument. Two things had to be
    true at once to land on this, both confirmed by actually running each variant against a real
    device on `emulator-5558`, not by inspection: (1) `adb shell` joins multiple separate argv
    elements with spaces before the device's shell ever sees them, so quoting supplied only at the
    PowerShell-parser level (e.g. `shell run-as $pkg sh -c "mkdir -p files/tour"` as four/five
    separate arguments) is lost by the time the device shell parses the flattened line - `-c` ends up
    with only its first word (`mkdir: Needs 1 argument`, reproduced directly). (2) Even a single
    combined argument with embedded **double**-quote characters (`sh -c "mkdir -p files/tour"`, one
    PowerShell string) reaches the device shell with the double quotes already stripped - `adb.exe`'s
    own Windows-side argument handling consumes them - so `-c` again sees only `mkdir`, the identical
    failure. Embedded **single** quotes survive `adb.exe`'s own argument handling intact and are
    genuine POSIX shell grouping once they reach the device, which is what actually works.
  - **Pull**: `run-as ... cp -r ... /sdcard/...` (stage-then-`adb pull`, `scenario.ps1`'s own
    stray-session-check shape) and a direct `adb pull /data/data/<pkg>/...`/`/data/user/0/<pkg>/...`
    were both tried first and both fail `Permission denied` on this device/adb combination - reproduced
    directly, not by inspection (see the git history of this file for the staging attempt this
    replaced). What works: `run-as ... cat` already reads a *text* file out over `adb shell`'s own
    stdout (the manifest poll below) - the identical path reads a *binary* PNG once base64-encoded to
    text first (toybox's `base64`, present on this device) and decoded back to bytes locally, one file
    per step. Never `adb exec-out` redirected to a file either way (brief-common.md's own rule).

  Write the spec *after* any `install.ps1 -Clear` - `pm clear` wipes this app's `files/` directory,
  spec included.

.PARAMETER Only
  A `-like` glob against each step's `id` (e.g. `"overnight/*"`, `"setup-*"`). Steps not matching
  are dropped from the spec pushed to the device - the manifest and summary below only ever cover
  what was actually run.

.NOTES
  R-1083 (register): this script used to poll `files/tour/manifest.json` for a trailing `"done":
  true` line with no guard at all against reading a *stale* one - the previous invocation's own
  finished manifest, left on disk because nothing ever cleared it first. The lead reproduced this
  twice in one night: `-Only "vad-fallback/*"` printed `steps: 2  ok: 2  errors: 0  elapsed: 0.1s`
  and wrote no PNG at all (0.1s is not a real tour run - it is the time to read a file that was
  already there), and `-Only "vad-fallback/N01*"` (one step) reported two steps, both belonging to
  the prior `overnight/CF12*` run. A false green here is worse than a crash, because every visual
  claim in the project (constitution VIII) rests on this script's own report being true.

  Fixed two ways, deliberately redundant (belt-and-suspenders, not either/or):

  1. **Clear before push.** `files/tour` (manifest and every PNG) is deleted through `run-as`
     *before* the spec is written - the same directory `ScreenshotTourActivity`'s own
     `TourRunner.run` clears again itself once its coroutine actually starts (that class's own
     comment), but the gap between `am start` returning and that coroutine reaching its own
     `deleteRecursively()` call is exactly the race the two reproductions above fell into: a cold
     activity start is not instant, and the very first poll below runs immediately, with no prior
     sleep. Clearing here means there is nothing stale left to read during that gap at all.
  2. **Stamp and match a run id.** A fresh GUID is generated per invocation and written into the
     pushed spec's own `runId` field (`TourSpec.runId`, `app/src/debug/kotlin/org/ort/app/debug/tour/TourSpec.kt`) -
     `TourRunner` (`TourRunner.kt`) carries it onto every manifest line it writes for the run,
     including the trailing `done` marker (`TourManifest.kt`). The poll below only accepts a
     `done` line whose own `runId` matches the one this invocation generated; a `done` line for a
     *different* run (the clear step failed for some reason, or two invocations somehow overlap)
     is recognised, named in a loud failure message, and never mistaken for this run's own result.
     This is what makes a stale-or-foreign manifest structurally unable to satisfy the poll, not
     merely unlikely to occur in practice.

  The clear step and the run-id match are both exercised by `ScreenshotTourTest`'s own
  `R_1083_RUN_ID_STAMPED`/`R_1083_STALE_RUN_ID_REPLACED` cases (Robolectric, on-device manifest
  plumbing) - this script's own matching/failure logic below has no equivalent automated test (no
  Pester harness exists in this repository) and is covered only by manual reproduction: see this
  round's own session report for the exact before/after transcripts.

  Separately (same finding, third symptom): the script used to report success even when it pulled
  fewer screenshots than the spec asked for - the `-Only "vad-fallback/*"` run above is again the
  example, "ok: 2" while zero PNGs existed. The step-count and pulled-screenshot-count checks near
  the end of this script now throw rather than print a warning when either falls short.

.EXAMPLE
  .\tour.ps1 -Port 5554

.EXAMPLE
  .\tour.ps1 -Port 5554 -Only "overnight/*" -Out results\ui-audit
#>
param(
    [Parameter(Mandatory = $true)][int]$Port,
    [string]$Tour = "tools\ui-audit\tour.json",
    [string]$Only,
    [string]$Out = "results\ui-audit",
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $androidHome "platform-tools\adb.exe"
$serial = "emulator-$Port"
$packageId = "org.ort.app"
# R-1103 (register): the run-id-matching and fewer-results-than-requested decisions below used to
# be inline PowerShell with no automated test, because this repository has no PowerShell test
# harness. They now live in tools/ui-audit/tour_manifest.py, a plain Python module with no device
# dependency, covered by tools/ui-audit/tests/test_tour_manifest.py and the existing
# ui-audit-diff CI job - see that module's own docstring for why this follows diff.py's pattern
# (R-1116) rather than adding a second test framework.
$tourManifestPy = Join-Path $repoRoot "tools\ui-audit\tour_manifest.py"

# R-1149 (register, constitution VIII): a `wm size` override present on one emulator and absent
# on another produces screenshots that can land at the exact same pixel dimensions while every
# element inside them sits somewhere else (a constant ~48px vertical shift was the measured,
# reproduced symptom) - diff.py's own R-1149 guard catches the resulting manifest mismatch after
# the fact, but a run captured at the wrong geometry in the first place is the upstream half of
# the same defect and should never be allowed to start. Assert an override is actually set on
# this emulator, rather than assuming `wm size` reports one - `adb shell wm size` prints
# `Physical size: <w>x<h>` always and an additional `Override size: <w>x<h>` line only when one is
# set (spec/test-plan.md's own "Reproducing a real device's geometry" section is how one gets set;
# this script does not set it, only refuses to capture without it).
$wmSizeOutput = & $adb -s $serial shell wm size
if ($LASTEXITCODE -ne 0) { throw "adb shell wm size failed on $serial (exit $LASTEXITCODE)" }
$wmSizeText = ($wmSizeOutput -join "`n")
# `-match` (not `-notmatch`) so `$Matches` is actually populated in the success branch - PowerShell
# only fills `$Matches` on a positive `-match`, never on `-notmatch`.
if ($wmSizeText -match "Override size:\s*(\d+x\d+)") {
    Write-Output "wm size override confirmed on ${serial}: $($Matches[1])"
}
else {
    throw "emulator-$Port reports no 'wm size' override (only a physical size) - R-1149: a tour " +
        "captured at physical resolution is not comparable against the canonical reference, which " +
        "is captured at an override, because the mismatch can shift content within same-sized " +
        "screenshots rather than resizing them (diff.py's own geometry guard catches this after " +
        "the fact; this check exists so a scoped run never starts at the wrong geometry at all). " +
        "Set an override matching the reference geometry first, e.g. " +
        "'adb -s $serial shell wm size <width>x<height>' (see spec/test-plan.md's own " +
        "'Reproducing a real device's geometry' section for the override/density recipe), then " +
        "re-run. Full 'wm size' output was:`n$wmSizeText"
}

$tourPath = if ([System.IO.Path]::IsPathRooted($Tour)) { $Tour } else { Join-Path $repoRoot $Tour }
if (-not (Test-Path $tourPath)) { throw "tour spec not found at $tourPath" }

$tourSpec = Get-Content -Raw -Path $tourPath | ConvertFrom-Json
$allSteps = @($tourSpec.steps)
$steps = $allSteps
if ($Only) {
    $steps = @($allSteps | Where-Object { $_.id -like $Only })
    if ($steps.Count -eq 0) { throw "no step id matched -Only '$Only' (spec has $($allSteps.Count) steps)" }
}
Write-Output "Running $($steps.Count) of $($allSteps.Count) step(s) from $tourPath on $serial..."

# R-1083: a fresh id for *this* invocation, carried onto every manifest line the device writes
# (TourSpec.runId -> TourRunner -> TourManifestEntry/appendManifestDone) - see this script's own
# .NOTES for why matching this, not just a trailing "done" line's presence, is what the poll below
# actually needs.
$runId = [guid]::NewGuid().ToString()

$filtered = [PSCustomObject]@{ steps = $steps; runId = $runId }
$localTempJson = Join-Path $env:TEMP "ort-tour-$Port.json"
# Windows PowerShell 5.1's `Set-Content -Encoding utf8` writes a UTF-8 byte-order mark, which
# survives the run-as pipe onto the device and lands as the first character of spec.json - Kotlin's
# File.readText() does not strip a BOM, so org.json.JSONObject's tokener sees it before the opening
# `{` and fails to parse (found by actually piping a real spec through and reading it back, not by
# inspection). [System.IO.File]::WriteAllText with an explicit no-BOM UTF8Encoding avoids it.
$noBomUtf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($localTempJson, ($filtered | ConvertTo-Json -Depth 10), $noBomUtf8)

# Write the spec into the app's own sandbox (never /sdcard - see this script's own .DESCRIPTION for
# why) via run-as + a piped `sh -c`. The `-c` payload's grouping quotes must be single quotes: a
# double-quoted payload reaches the device with its quotes already stripped by adb.exe's own
# Windows-side argument handling, and separate (unquoted-at-the-adb-level) PowerShell arguments get
# flattened with spaces before the device shell ever sees them either way - both reproduced directly
# against a real device (`mkdir: Needs 1 argument` - see this script's own .DESCRIPTION). Wrapped so
# a transient native-stderr line cannot turn into a terminating error under
# $ErrorActionPreference = "Stop" (this file's own top-level setting).
# R-1083: delete any previous run's manifest/PNGs *before* pushing this run's own spec - closes
# the race this script's own .NOTES describes (ScreenshotTourActivity clears the same directory
# itself once its coroutine starts, but that is not instant, and this script's very first poll
# below runs with no prior sleep). Best-effort: `rm -rf` on a directory that does not exist yet
# (a fresh install) still exits 0, and any genuine problem here (e.g. the app is not installed at
# all) surfaces as a loud, specific failure at the spec-write step immediately below regardless.
$clearTourDirCmd = "run-as $packageId sh -c 'rm -rf files/tour'"
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $adb -s $serial shell $clearTourDirCmd | Out-Null
$ErrorActionPreference = $prevEap

$writeSpecCmd = "run-as $packageId sh -c 'mkdir -p files/tour && cat > files/tour/spec.json'"
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
Get-Content -Raw -Path $localTempJson | & $adb -s $serial shell $writeSpecCmd
$writeSpecExitCode = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($writeSpecExitCode -ne 0) {
    throw "failed to write the tour spec into $packageId's sandbox on $serial (exit $writeSpecExitCode) - " +
        "is the app installed and debuggable (run-as needs a debug build)?"
}

Write-Output "Launching ScreenshotTourActivity on $serial..."
& $adb -s $serial shell am start -n "$packageId/.debug.tour.ScreenshotTourActivity"
if ($LASTEXITCODE -ne 0) { throw "am start failed (exit $LASTEXITCODE)" }

$pollCmd = "run-as $packageId sh -c " +
    "'test -f files/tour/manifest.json && cat files/tour/manifest.json || true'"
$startedAt = Get-Date
$deadline = $startedAt.AddSeconds($TimeoutSeconds)
$done = $false
# R-1083: the id of whichever *other* run's own "done" line was seen while waiting for this run's
# own - kept so a timeout can name exactly what it found, rather than a generic "nothing ever
# appeared" message that would look identical to the app never having started at all.
$foreignRunId = $null
while ((Get-Date) -lt $deadline) {
    # Before manifest.json exists at all, a bare `run-as ... cat` exits non-zero and writes to
    # stderr - under $ErrorActionPreference = "Stop" that becomes a terminating NativeCommandError
    # even with 2>$null (found by actually running this against a fresh launch, not by inspection;
    # the same class of bug as boot.ps1's own getprop poll, fixed there too). `test -f ... && cat
    # ... || true` always exits 0 and prints nothing until the file is actually there; the
    # $ErrorActionPreference swap below is belt-and-braces for any other transient adb hiccup.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $manifestText = & $adb -s $serial shell $pollCmd 2>$null
    $ErrorActionPreference = $prevEap
    if ($manifestText) {
        # R-1103: the run-id-matching decision (is this *this* invocation's own "done" line, a
        # foreign/stale one, or not there yet) is tour_manifest.py's `check_done_marker`, not
        # inline PowerShell - see this script's own top-of-file note and that module's docstring.
        $pollResult = ($manifestText | & python $tourManifestPy poll --run-id $runId) | ConvertFrom-Json
        if ($pollResult.done) {
            $done = $true
            break
        } elseif ($pollResult.foreignRunId) {
            $foreignRunId = $pollResult.foreignRunId
        }
    }
    Start-Sleep -Seconds 3
}
if (-not $done) {
    if ($foreignRunId) {
        throw "Timed out after ${TimeoutSeconds}s: files/tour/manifest.json on $serial (under run-as " +
            "$packageId) carries a completed run for id '$foreignRunId', not the run this invocation " +
            "started ('$runId') - a stale or foreign manifest, never this run's own result. This should " +
            "no longer be possible after R-1083's fix (the on-device tour dir is cleared before the spec " +
            "is pushed); if it recurs, check for a concurrent tour.ps1 invocation against the same " +
            "emulator, or 'adb -s $serial logcat -d' for a crash right after 'am start'."
    }
    throw "Timed out after ${TimeoutSeconds}s waiting for the manifest 'done' marker for run '$runId' on " +
        "$serial (files/tour/manifest.json under run-as $packageId). Check 'adb -s $serial logcat -d' for " +
        "a crash."
}
$elapsed = (Get-Date) - $startedAt
Write-Output "Tour finished in $([Math]::Round($elapsed.TotalSeconds, 1))s. Pulling screenshots..."

# Neither `run-as ... cp -r ... /sdcard/...` nor a direct `adb pull /data/.../files/tour` can reach
# this app's private storage on this device/adb combination - API 30+ scoped storage refuses this
# app's own run-as'd process write access to /sdcard (and even to its own
# /sdcard/Android/data/<pkg>/files), and this adb (37.0.1) has no working run-as fallback for a
# direct `pull` of /data/data or /data/user/0 either - both reproduced directly with a real device on
# port 5558, `Permission denied` either way, not by inspection. What *does* work, proven the same
# way: `run-as ... cat` already reads text out over adb's own stdout (the manifest poll above) - the
# identical path works for binary PNGs once base64-encoded to text first (toybox's `base64`, present
# on this device), decoded back to bytes locally. Never `adb exec-out` redirected to a file
# (brief-common.md's own rule) - this uses only `adb shell`'s captured stdout, the same as the poll.
$outDir = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $repoRoot $Out }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$manifestDest = Join-Path $outDir "tour-manifest.json"
$lines = $manifestText -split "`n" | Where-Object { $_.Trim() -ne "" }
# `Set-Content -Value <array> -NoNewline` concatenates every element with no separator at all
# (found by actually reading the written file back and seeing one run-on line, not by inspection) -
# join explicitly instead, one JSONL line per manifest entry, exactly like the on-device file.
$noBomUtf8Manifest = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($manifestDest, (($lines -join "`n") + "`n"), $noBomUtf8Manifest)
# R-1103: the manifest-line parsing itself (step/ok/error counts, the ok-id list to pull) is
# tour_manifest.py's `summarize`, not a second inline parse here - the old shape re-implemented
# this same JSON parsing twice (once for this summary, once for the pull loop below), and neither
# copy had a test. `summarize` is called once and both this script's summary print and its pull
# loop read from its one result.
$summary = ($manifestText | & python $tourManifestPy summarize) | ConvertFrom-Json
$stepCount = $summary.stepCount
$okCount = $summary.okCount
$errorCount = $summary.errorCount
$okIds = @($summary.okIds)

Write-Output "Pulling $($okIds.Count) screenshot(s) via run-as + base64..."
$pulledCount = 0
foreach ($id in $okIds) {
    $relativePath = "$id.png"
    $localFile = Join-Path $outDir $relativePath.Replace("/", "\")
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $localFile) | Out-Null

    $devicePath = "files/tour/$relativePath"
    $base64Cmd = "run-as $packageId sh -c 'base64 $devicePath'"
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $encoded = & $adb -s $serial shell $base64Cmd 2>$null
    $pullExitCode = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($pullExitCode -ne 0 -or -not $encoded) {
        Write-Warning "could not pull '$id' (base64 exit $pullExitCode) - skipping, not failing the whole run"
        continue
    }
    $bytes = [Convert]::FromBase64String(($encoded -join ""))
    [System.IO.File]::WriteAllBytes($localFile, $bytes)
    $pulledCount++
}

Write-Output ""
Write-Output "steps: $stepCount  ok: $okCount  errors: $errorCount  elapsed: $([Math]::Round($elapsed.TotalSeconds, 1))s"
if ($errorCount -gt 0) {
    Write-Output "failed steps:"
    foreach ($e in $summary.errors) {
        Write-Output ("  - {0}: {1}" -f $e.id, $e.errorMessage)
    }
}
Write-Output "screenshots + tour-manifest.json written under $outDir"

# R-1083 (the third symptom of the same finding) / R-1103 (now tested off-device): a run that
# produced fewer screenshots than the spec asked for must fail, not print a quiet summary and
# exit 0 - `-Only "vad-fallback/*"`'s own false-green report was "steps: 2  ok: 2  errors: 0"
# while zero PNGs had actually been written. Two independent counts, either one enough to fail on
# its own - `tour_manifest.py verify` raises `StepCountMismatch`/`PulledCountMismatch` for exactly
# these, covered by `tools/ui-audit/tests/test_tour_manifest.py`'s `R_1103_...` cases:
#   - the manifest itself must report one result per step this invocation actually requested
#     (never fewer or more - a step the device silently dropped, or a stale/short manifest that
#     slipped past the runId check above some other way);
#   - every step the manifest reported ok must have actually been pulled to disk - a base64 pull
#     failure above only warns, by design (one bad pull should not lose every other screenshot),
#     so this is the one place that turns "some pulls failed" into a failed run overall.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& python $tourManifestPy verify --run-id $runId --out-dir $outDir `
    --requested-count $steps.Count --step-count $stepCount --ok-count $okCount --pulled-count $pulledCount `
    | Out-Null
$verifyExitCode = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($verifyExitCode -eq 2) {
    throw "run '$runId' reported $stepCount step result(s) in its manifest but $($steps.Count) " +
        "step(s) were requested from $tourPath - the device produced a different number of results " +
        "than asked for. Check 'adb -s $serial logcat -d' for a crash partway through the run."
} elseif ($verifyExitCode -eq 3) {
    throw "run '$runId' reported $okCount ok screenshot(s) but only $pulledCount were actually pulled to " +
        "$outDir - see the 'could not pull' warning(s) above for which step(s) and why."
} elseif ($verifyExitCode -ne 0) {
    throw "tools/ui-audit/tour_manifest.py verify exited $verifyExitCode unexpectedly for run '$runId' - " +
        "this should only ever be 0, 2 or 3."
}
