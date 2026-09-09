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

$filtered = [PSCustomObject]@{ steps = $steps }
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
        $lastLine = ($manifestText -split "`n" | Where-Object { $_.Trim() -ne "" } | Select-Object -Last 1)
        if ($lastLine -match '"done"\s*:\s*true') { $done = $true; break }
    }
    Start-Sleep -Seconds 3
}
if (-not $done) {
    throw "Timed out after ${TimeoutSeconds}s waiting for the tour's manifest 'done' marker on $serial " +
        "(files/tour/manifest.json under run-as $packageId). Check 'adb -s $serial logcat -d' for a crash."
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
$stepLines = $lines | Where-Object { $_ -notmatch '"done"\s*:\s*true' } | ForEach-Object { $_ | ConvertFrom-Json }
$okCount = @($stepLines | Where-Object { $_.ok -eq $true }).Count
$errorCount = @($stepLines | Where-Object { $_.ok -eq $false }).Count

$okSteps = @($stepLines | Where-Object { $_.ok -eq $true })
Write-Output "Pulling $($okSteps.Count) screenshot(s) via run-as + base64..."
foreach ($step in $okSteps) {
    $relativePath = "$($step.id).png"
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
        Write-Warning "could not pull '$($step.id)' (base64 exit $pullExitCode) - skipping, not failing the whole run"
        continue
    }
    $bytes = [Convert]::FromBase64String(($encoded -join ""))
    [System.IO.File]::WriteAllBytes($localFile, $bytes)
}

Write-Output ""
Write-Output "steps: $($stepLines.Count)  ok: $okCount  errors: $errorCount  elapsed: $([Math]::Round($elapsed.TotalSeconds, 1))s"
if ($errorCount -gt 0) {
    Write-Output "failed steps:"
    $stepLines | Where-Object { $_.ok -eq $false } | ForEach-Object {
        Write-Output ("  - {0}: {1}" -f $_.id, $_.errorMessage)
    }
}
Write-Output "screenshots + tour-manifest.json written under $outDir"
