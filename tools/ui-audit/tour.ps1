<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP12 - runs the whole screenshot tour (or a filtered subset) on one
  emulator and pulls every PNG plus the manifest into results/ui-audit/.

.DESCRIPTION
  Installs nothing (`install.ps1` already put the debug APK on the device). Pushes the tour spec,
  launches `ScreenshotTourActivity` (`app/src/debug/AndroidManifest.xml`), polls the on-device
  `<filesDir>/tour/manifest.json` for its trailing `{"done": true, ...}` line (a hard 15-minute cap
  either way), then pulls the whole on-device `tour/` directory and lays it out the same way
  `shoot.ps1` already does: `results/ui-audit/<scenario>/<screen>.png`, plus the manifest itself at
  `results/ui-audit/tour-manifest.json`.

  `ScreenshotTourActivity`'s own output lives under this app's private storage
  (`getFilesDir()`), which plain `adb pull` cannot reach on a non-rooted device. This script never
  redirects `adb exec-out` to a file (brief-common.md's own rule) - it uses the same
  `run-as`-then-`adb pull`-from-`/sdcard` shape `scenario.ps1`'s own stray-session check already
  established: `run-as` copies the private directory out to a world-readable `/sdcard` path first,
  then a plain `adb pull` reads it from there.

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
$filtered | ConvertTo-Json -Depth 10 | Set-Content -Path $localTempJson -Encoding utf8

$devicePath = "/sdcard/ort-tour.json"
& $adb -s $serial push $localTempJson $devicePath
if ($LASTEXITCODE -ne 0) { throw "adb push failed for $localTempJson (exit $LASTEXITCODE)" }

# A fresh run must not read a previous run's leftover /sdcard staging copy or on-device tour dir -
# ScreenshotTourActivity itself clears <filesDir>/tour (TourRunner.run's own doc comment), but the
# /sdcard staging copy this script pulls from is this script's own responsibility to clear.
& $adb -s $serial shell rm -rf /sdcard/ort-tour-out | Out-Null

Write-Output "Launching ScreenshotTourActivity on $serial..."
& $adb -s $serial shell am start -n "$packageId/.debug.tour.ScreenshotTourActivity" --es tour_path $devicePath
if ($LASTEXITCODE -ne 0) { throw "am start failed (exit $LASTEXITCODE)" }

$startedAt = Get-Date
$deadline = $startedAt.AddSeconds($TimeoutSeconds)
$done = $false
while ((Get-Date) -lt $deadline) {
    $manifestText = & $adb -s $serial shell run-as $packageId cat files/tour/manifest.json 2>$null
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

# run-as -> world-readable /sdcard -> adb pull (never adb exec-out redirected to a file).
& $adb -s $serial shell run-as $packageId cp -r files/tour /sdcard/ort-tour-out
if ($LASTEXITCODE -ne 0) { throw "run-as cp failed to stage tour/ under /sdcard on $serial (exit $LASTEXITCODE)" }

$stagingDir = Join-Path $env:TEMP "ort-tour-pull-$Port"
if (Test-Path $stagingDir) { Remove-Item -Recurse -Force $stagingDir }
New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null

& $adb -s $serial pull /sdcard/ort-tour-out $stagingDir
if ($LASTEXITCODE -ne 0) { throw "adb pull failed for /sdcard/ort-tour-out (exit $LASTEXITCODE)" }
& $adb -s $serial shell rm -rf /sdcard/ort-tour-out | Out-Null

$pulledTourDir = Join-Path $stagingDir "ort-tour-out"
if (-not (Test-Path $pulledTourDir)) { throw "expected $pulledTourDir after pulling - adb pull's own layout may have changed" }

$outDir = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $repoRoot $Out }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Copy-Item -Path (Join-Path $pulledTourDir "*") -Destination $outDir -Recurse -Force

$manifestSource = Join-Path $outDir "manifest.json"
$manifestDest = Join-Path $outDir "tour-manifest.json"
if (Test-Path $manifestSource) {
    if (Test-Path $manifestDest) { Remove-Item -Force $manifestDest }
    Move-Item -Path $manifestSource -Destination $manifestDest
}
else {
    throw "expected manifest.json in the pulled tour/ directory but did not find it"
}

$lines = Get-Content -Path $manifestDest | Where-Object { $_.Trim() -ne "" }
$stepLines = $lines | Where-Object { $_ -notmatch '"done"\s*:\s*true' } | ForEach-Object { $_ | ConvertFrom-Json }
$okCount = @($stepLines | Where-Object { $_.ok -eq $true }).Count
$errorCount = @($stepLines | Where-Object { $_.ok -eq $false }).Count

Write-Output ""
Write-Output "steps: $($stepLines.Count)  ok: $okCount  errors: $errorCount  elapsed: $([Math]::Round($elapsed.TotalSeconds, 1))s"
if ($errorCount -gt 0) {
    Write-Output "failed steps:"
    $stepLines | Where-Object { $_.ok -eq $false } | ForEach-Object {
        Write-Output ("  - {0}: {1}" -f $_.id, $_.errorMessage)
    }
}
Write-Output "screenshots + tour-manifest.json written under $outDir"
