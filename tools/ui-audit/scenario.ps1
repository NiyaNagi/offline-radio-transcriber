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
