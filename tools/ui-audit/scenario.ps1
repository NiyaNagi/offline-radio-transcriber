<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP0, register R-111/R-179 - force-stops the app (unless -NoRestart),
  fires the debug scenario broadcast (register R-110), and waits for ScenarioReceiver's confirming
  logcat line.

.DESCRIPTION
  Prints the loaded scenario's primary session id (from `ScenarioReceiver`'s own
  "scenario <name>: session=<id>" log line) on its own line as `session=<id>`, so a caller can
  launch the reader against it:

    adb -s emulator-<port> shell am start -n org.ort.app/.ui.ReaderActivity --es session_id <id>

  R-179: the default force-stop makes a *running UI* an impossible witness to a scenario's own
  effect — force-stop kills the process the broadcast would otherwise land in, so a validator can
  never watch a screen already on-screen transition live (a degraded banner clearing, a recovery
  toast, a poll picking up a session that just started capturing). Pass -NoRestart to send the same
  broadcast to whatever process is already running instead: `ScenarioReceiver` is a plain manifest-
  registered receiver in the app's own process (`app/src/debug/AndroidManifest.xml`), so when the
  app is already running the broadcast is delivered into that same process and
  `Scenarios.load(...)` updates the same process-wide holders (`CaptureState`, `ThermalStatus`,
  `RigStatus`, `LevelStatus`, `InputStatus`, ...) the running UI's own poll loops are already
  reading — nothing about the mechanism differs, only whether the process reading the result is the
  one already on screen. With -NoRestart the app must already be running and in the foreground (or
  at least not force-stopped) for the broadcast to be delivered at all — Android does not wake a
  genuinely stopped app for an explicit broadcast either, once it has actually stopped.

.EXAMPLE
  .\scenario.ps1 -Port 5554 -Name overnight

.EXAMPLE
  # Load a first scenario normally, launch the reader, then broadcast a second scenario without
  # restarting so the already-open UI can be watched transitioning live.
  .\scenario.ps1 -Port 5554 -Name thermal
  adb -s emulator-5554 shell am start -n org.ort.app/.ui.ReaderActivity
  .\scenario.ps1 -Port 5554 -Name empty -NoRestart

.NOTES
  Hygiene (round-nine tooling): a *real* capture session an earlier mis-tap started (or the OS
  killed mid-capture) is never touched by `Scenarios.clearPriorScenarioData` — it only ever deletes
  rows whose id starts with `scenario-` — so it persists in the app's own database across every
  scenario switch from then on, and `Now` can read it (real, `endedAt = null`, possibly more
  recently started) over whatever this scenario itself just seeded. After loading, this script
  makes a best-effort attempt to check for exactly that on the device's own database, via
  `adb shell run-as` (debug builds are debuggable, so `run-as` reaches this package's private app
  data) and the device's own `sqlite3` binary; if either is missing this degrades to a printed
  reminder instead of failing the script — not every system image ships `sqlite3`, and this check
  was never run against a live device to confirm it works on `ort_audit`'s own image (see this
  package's own report). Either way, `install.ps1 -Clear` is the reliable fix: it wipes the app's
  entire on-device state, this stray real session included.

  R-810/R-811/R-820/R-830: `install.ps1` grants `BLUETOOTH_CONNECT` alongside `RECORD_AUDIO`/
  `POST_NOTIFICATIONS` by default, so every Bluetooth-mode setup scenario except `setup-bt-permission`
  itself (`setup-rig-transport`, `setup-rig-bluetooth`, and any `S04`/`S09b`/`S10b`/`S11` step under a
  Bluetooth-mode scenario) reaches its own board rather than clamping back to S02c. `setup-bt-permission`
  is the one scenario that needs `BLUETOOTH_CONNECT` explicitly revoked first -- see
  `results/ui-audit/README.md`'s "Reaching S02c" section for the exact recipe.
#>
param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Name,
    [int]$TimeoutSeconds = 60,
    [switch]$NoRestart
)

$ErrorActionPreference = "Stop"

$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $androidHome "platform-tools\adb.exe"
$serial = "emulator-$Port"
$packageId = "org.ort.app"

if ($NoRestart) {
    Write-Output "Skipping force-stop of $packageId on $serial (-NoRestart) - broadcasting into whatever is already running."
}
else {
    Write-Output "Force-stopping $packageId on $serial..."
    & $adb -s $serial shell am force-stop $packageId
}

# Clear logcat first so the confirming line below is unambiguous - a prior scenario's run cannot
# be mistaken for this one's.
& $adb -s $serial logcat -c

Write-Output "Broadcasting scenario '$Name'..."
# `-n <package>/<receiver>` (an explicit component target), not just `-a <action>`: a just-
# force-stopped app is in Android's "stopped" state, where an *implicit* broadcast is not
# delivered at all until the app is otherwise launched - only an explicit target bypasses that and
# actually starts the receiver's process.
& $adb -s $serial shell am broadcast -n "$packageId/.debug.ScenarioReceiver" --es name $Name

$escapedName = [Regex]::Escape($Name)
$confirmPattern = "scenario ${escapedName}: \d+ transmissions, \d+ sessions"
$sessionPattern = "scenario ${escapedName}: session=(.+)$"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$confirmed = $false
$sessionId = $null
while ((Get-Date) -lt $deadline) {
    # A single filterspec arg - `adb logcat -s TAG1:L1 TAG2:L2` (two separate args) reliably
    # returns nothing on this adb version; `V` (verbose) shows every level for the one tag.
    $log = & $adb -s $serial logcat -d -s "ScenarioReceiver:V" 2>$null
    if ($log) {
        if (-not $confirmed) {
            $confirmed = [bool]($log | Select-String -Pattern $confirmPattern -Quiet)
        }
        $sessionLine = $log | Select-String -Pattern $sessionPattern | Select-Object -Last 1
        if ($sessionLine) {
            $sessionId = $sessionLine.Matches[0].Groups[1].Value.Trim()
        }
        $failed = $log | Select-String -Pattern "scenario ${escapedName}: (unknown scenario name|failed to load)" -Quiet
        if ($failed) {
            throw "scenario '$Name' failed to load on $serial - see logcat tag ScenarioReceiver for the exception."
        }
    }
    if ($confirmed) { break }
    Start-Sleep -Milliseconds 800
}

if (-not $confirmed) {
    throw "Timed out after ${TimeoutSeconds}s waiting for ScenarioReceiver's confirming logcat line for '$Name' on $serial."
}

Write-Output "scenario '$Name' loaded on $serial."
if ($sessionId) {
    Write-Output "session=$sessionId"
}
else {
    Write-Output "session=<none>"
}

# Hygiene (see this script's own .NOTES): best-effort check for a real, non-scenario session with
# endedAt = null still sitting in the app's own database from an earlier mis-tap or an unclean OS
# kill -- never blocks or fails this script either way, since neither `run-as` nor `sqlite3` on the
# device is guaranteed to exist. The SQL text must reach the device's own shell as ONE argument
# (adb shell joins its own argv with spaces before the device shell ever sees it, so an unquoted
# multi-word string would otherwise be split apart) -- wrapped in embedded double quotes here, in
# the single command-line string handed to `adb shell`, for exactly that reason.
$strayQuery = "SELECT id FROM session WHERE endedAt IS NULL AND id NOT LIKE 'scenario-%';"
$remoteCommand = "run-as $packageId sqlite3 databases/ort.db `"$strayQuery`""
$strayOutput = & $adb -s $serial shell $remoteCommand 2>$null
$strayExitCode = $LASTEXITCODE
$strayIds = @($strayOutput | Where-Object { $_ -and $_.Trim() -ne "" })
if ($strayExitCode -eq 0 -and $strayIds.Count -gt 0) {
    Write-Warning (
        "Real (non-scenario) session(s) with endedAt = null are still in $serial's own database: " +
        "$($strayIds -join ', '). An earlier mis-tap or unclean kill likely started one of these, " +
        "and it can outrank this scenario's own fixture session on Now. Re-run " +
        "'.\install.ps1 -Port $Port -Clear' to wipe the app's on-device data and start clean."
    )
}
elseif ($strayExitCode -ne 0) {
    Write-Output (
        "(could not check $serial's own database for a stray real session -- run-as/sqlite3 may " +
        "not be available on this device image. If Now looks wrong (a session you did not expect, " +
        "or one that outranks this scenario's own), run '.\install.ps1 -Port $Port -Clear' to wipe " +
        "the app's on-device data and start clean; see this repo's results/ui-audit/README.md, " +
        "'Known gaps / hygiene'.)"
    )
}
