<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP0, register R-111 - assembles the debug APK and installs it on the
  named emulator port, granting the runtime permissions the app needs to run without a manual
  prompt getting in a validator's way.

.DESCRIPTION
  Hygiene finding (round-nine tooling): validators on the shared AVDs keep inheriting a *real*
  capture session an earlier mis-tap started (`Now`'s "Start capture" pressed by accident while
  poking around a scenario, or a session the OS killed mid-capture) — `Scenarios.clearPriorScenarioData`
  only ever deletes rows whose id starts with `scenario-`, so a genuine session is never touched by
  a scenario reload and persists in the app's own database across every scenario switch from then
  on, `Now` reading it (real, more recent, `endedAt = null`) over whatever the scenario itself
  seeded. `-Clear` (`adb shell pm clear org.ort.app`) wipes the app's entire on-device state —
  database, files, granted permissions, everything — right after install, so every scenario run
  from a `-Clear` install starts from a genuinely empty app, not just an empty *scenario* fixture.
  `pm clear` also revokes every previously-granted runtime permission, so this script re-grants
  `RECORD_AUDIO`/`POST_NOTIFICATIONS` afterward the same way a plain (non-`-Clear`) install already
  does — `-Clear` changes nothing else about this script's own contract.

.EXAMPLE
  .\install.ps1 -Port 5554

.EXAMPLE
  # A shared AVD a previous session may have left a stray real capture session running on:
  .\install.ps1 -Port 5554 -Clear
#>
param(
    [Parameter(Mandatory = $true)][int]$Port,
    [switch]$Clear
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $androidHome "platform-tools\adb.exe"
$serial = "emulator-$Port"
$packageId = "org.ort.app"

Push-Location $repoRoot
try {
    & "$repoRoot\gradlew.bat" ":app:assembleDebug"
    if ($LASTEXITCODE -ne 0) { throw "gradlew :app:assembleDebug failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}

$apk = Get-ChildItem -Path (Join-Path $repoRoot "app\build\outputs\apk\debug") -Filter "*.apk" -ErrorAction Stop |
    Select-Object -First 1
if (-not $apk) { throw "no debug APK found under app\build\outputs\apk\debug after assembleDebug" }

Write-Output "Installing $($apk.Name) on $serial..."
& $adb -s $serial install -r -g $apk.FullName
if ($LASTEXITCODE -ne 0) { throw "adb install failed with exit code $LASTEXITCODE" }

if ($Clear) {
    # Hygiene (see this script's own .DESCRIPTION): wipes any real capture session, or any other
    # on-device state, a previous run left behind -- `pm clear` also revokes every runtime
    # permission this package was granted, which is why the grant step below is unconditional
    # rather than skipped for a -Clear run.
    Write-Output "Clearing $packageId's on-device data on $serial (-Clear)..."
    & $adb -s $serial shell pm clear $packageId
    if ($LASTEXITCODE -ne 0) { throw "adb shell pm clear failed with exit code $LASTEXITCODE" }
}

# RECORD_AUDIO/POST_NOTIFICATIONS are runtime permissions on API 34 (the ort_audit AVD); granting
# them here means a validator's scenario/screenshot run never blocks on an OS permission dialog.
& $adb -s $serial shell pm grant $packageId android.permission.RECORD_AUDIO
& $adb -s $serial shell pm grant $packageId android.permission.POST_NOTIFICATIONS

if ($Clear) {
    Write-Output "Installed $packageId on $serial, cleared its on-device data, and granted RECORD_AUDIO/POST_NOTIFICATIONS."
}
else {
    Write-Output "Installed $packageId on $serial and granted RECORD_AUDIO/POST_NOTIFICATIONS."
}
