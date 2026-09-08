<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP0, register R-111 - drives the currently-installed build to a
  named screen by replaying `adb shell input` steps read from screens.json.

.DESCRIPTION
  Coordinates live in tools/ui-audit/screens.json, not in this script, per this package's brief -
  "The map will be re-pointed as navigation changes; keep the coordinates in the JSON, not the
  script." screens.json is keyed by screen name; each value is an ordered array of steps:

    { "action": "tap",  "x": 40, "y": 96 }
    { "action": "wait", "ms": 500 }
    { "action": "text", "value": "park activation" }
    { "action": "key",  "keycode": "KEYCODE_BACK" }

  Density 420dpi, 1080x2400 (the ort_audit AVD's own resolution) - coordinates in screens.json are
  raw pixel taps at that resolution.

.EXAMPLE
  .\nav.ps1 -Port 5554 -Screen log
#>
param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Screen
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $androidHome "platform-tools\adb.exe"
$serial = "emulator-$Port"
$mapPath = Join-Path $PSScriptRoot "screens.json"

if (-not (Test-Path $mapPath)) { throw "screens.json not found at $mapPath" }
$map = Get-Content -Path $mapPath -Raw | ConvertFrom-Json

$knownScreens = $map.PSObject.Properties.Name
if ($knownScreens -notcontains $Screen) {
    throw "no route for screen '$Screen' in screens.json. Known screens: $($knownScreens -join ', ')"
}

$steps = $map.$Screen
foreach ($step in $steps) {
    switch ($step.action) {
        "tap" {
            & $adb -s $serial shell input tap $step.x $step.y
        }
        "swipe" {
            & $adb -s $serial shell input swipe $step.x1 $step.y1 $step.x2 $step.y2 $step.durationMs
        }
        "text" {
            # `input text` cannot contain literal spaces unescaped - `%s` is its own escape for one.
            $escaped = $step.value -replace " ", "%s"
            & $adb -s $serial shell input text $escaped
        }
        "key" {
            & $adb -s $serial shell input keyevent $step.keycode
        }
        "wait" {
            Start-Sleep -Milliseconds $step.ms
        }
        default {
            throw "unknown nav step action '$($step.action)' for screen '$Screen'"
        }
    }
    Start-Sleep -Milliseconds 350
}

Write-Output "navigated to '$Screen' on $serial ($($steps.Count) step(s))."
