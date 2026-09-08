<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP0, register R-111 - assembles the debug APK and installs it on the
  named emulator port, granting the runtime permissions the app needs to run without a manual
  prompt getting in a validator's way.

.EXAMPLE
  .\install.ps1 -Port 5554
#>
param(
    [Parameter(Mandatory = $true)][int]$Port
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

# RECORD_AUDIO/POST_NOTIFICATIONS are runtime permissions on API 34 (the ort_audit AVD); granting
# them here means a validator's scenario/screenshot run never blocks on an OS permission dialog.
& $adb -s $serial shell pm grant $packageId android.permission.RECORD_AUDIO
& $adb -s $serial shell pm grant $packageId android.permission.POST_NOTIFICATIONS

Write-Output "Installed $packageId on $serial and granted RECORD_AUDIO/POST_NOTIFICATIONS."
