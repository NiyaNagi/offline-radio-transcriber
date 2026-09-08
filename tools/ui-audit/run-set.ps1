<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP0, register R-111 - runs the scenarios and screens of one
  spec/ui-conformance-plan.md sect. E set end to end: for each (scenario, screen) pair, loads the
  scenario, launches the reader against its primary session, navigates to the screen, and shoots
  it.

.DESCRIPTION
  The scenario/screen pairing for each set lives in tools/ui-audit/sets.json, not in this script.
  Only sets whose scenarios and screens this package (WP0) actually made reachable are populated
  today - V3 and V5. A set with no entry is reported as not-yet-reachable rather than silently
  skipped or faked (constitution I) - the remaining sets (V1, V2, V4, V6, V7) depend on
  screens/flows other work packages have not landed yet (setup, detail, settings...), and their
  scenario/screen pairs belong there once those packages land.

.EXAMPLE
  .\run-set.ps1 -Port 5554 -Set V3
#>
param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Set
)

$ErrorActionPreference = "Stop"

$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $androidHome "platform-tools\adb.exe"
$serial = "emulator-$Port"

$setsPath = Join-Path $PSScriptRoot "sets.json"
if (-not (Test-Path $setsPath)) { throw "sets.json not found at $setsPath" }
$sets = Get-Content -Path $setsPath -Raw | ConvertFrom-Json

if (-not ($sets.PSObject.Properties.Name -contains $Set)) {
    Write-Warning "Set '$Set' has no scenario/screen pairing in sets.json yet - nothing reachable to run."
    Write-Warning "Known sets today: $($sets.PSObject.Properties.Name -join ', ')"
    return
}

$entries = $sets.$Set
$failures = New-Object System.Collections.Generic.List[string]

foreach ($entry in $entries) {
    $scenarioName = $entry.scenario
    Write-Output "== $Set : scenario '$scenarioName' =="
    try {
        $scenarioOutput = & (Join-Path $PSScriptRoot "scenario.ps1") -Port $Port -Name $scenarioName
        $scenarioOutput | ForEach-Object { Write-Output $_ }
    }
    catch {
        $failures.Add("scenario '$scenarioName': $($_.Exception.Message)")
        continue
    }

    $sessionLine = $scenarioOutput | Where-Object { $_ -match "^session=(.+)$" } | Select-Object -Last 1
    $sessionId = $null
    if ($sessionLine -and $sessionLine -notmatch "^session=<none>$") {
        $sessionId = ([Regex]::Match($sessionLine, "^session=(.+)$")).Groups[1].Value
    }
    if (-not $sessionId) {
        $failures.Add("scenario '$scenarioName': no session id returned - cannot launch the reader")
        continue
    }

    # ReaderActivity is exported="false" (register R-007) - reached through the debug-only forward
    # alias ScenarioReaderActivity.kt adds, exactly as its own doc comment documents.
    & $adb -s $serial shell am start -n "org.ort.app/.debug.ScenarioReaderActivity" --es session_id $sessionId
    Start-Sleep -Milliseconds 800

    foreach ($screen in $entry.screens) {
        try {
            & (Join-Path $PSScriptRoot "nav.ps1") -Port $Port -Screen $screen
            & (Join-Path $PSScriptRoot "shoot.ps1") -Port $Port -Scenario $scenarioName -Screen $screen
        }
        catch {
            $failures.Add("scenario '$scenarioName' screen '$screen': $($_.Exception.Message)")
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Output ""
    Write-Output "Set '$Set' finished with $($failures.Count) failure(s):"
    $failures | ForEach-Object { Write-Output "  - $_" }
    throw "run-set '$Set' had $($failures.Count) failure(s) - see above."
}

Write-Output "Set '$Set' completed: $($entries.Count) scenario(s) shot on emulator-$Port."
