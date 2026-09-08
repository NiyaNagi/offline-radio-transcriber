<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP0, register R-111 - boots a named AVD headless on a given port and
  waits for sys.boot_completed.

.DESCRIPTION
  `emulator-5554` (AVD `ort_audit`) is already booted per this package's brief and MUST NOT be
  killed or re-booted by this script. Use this to bring up `ort_audit_2` on port 5556 (or any
  other AVD/port pair a validator is told to use) - never to re-boot 5554.

.EXAMPLE
  .\boot.ps1 -Avd ort_audit_2 -Port 5556
#>
param(
    [Parameter(Mandatory = $true)][string]$Avd,
    [Parameter(Mandatory = $true)][int]$Port,
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $androidHome "platform-tools\adb.exe"
$emulatorExe = Join-Path $androidHome "emulator\emulator.exe"
$serial = "emulator-$Port"

if (-not (Test-Path $adb)) { throw "adb.exe not found at $adb - check ANDROID_HOME" }
if (-not (Test-Path $emulatorExe)) { throw "emulator.exe not found at $emulatorExe - check ANDROID_HOME" }

Write-Output "Booting AVD '$Avd' headless on port $Port ($serial)..."
Start-Process -FilePath $emulatorExe -ArgumentList @(
    "-avd", $Avd,
    "-port", $Port,
    "-no-window",
    "-no-audio",
    "-no-snapshot",
    "-gpu", "swiftshader_indirect"
) -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$booted = $false
while ((Get-Date) -lt $deadline) {
    $prop = & $adb -s $serial shell getprop sys.boot_completed 2>$null
    if ($prop -and ($prop | Select-Object -First 1).Trim() -eq "1") {
        $booted = $true
        break
    }
    Start-Sleep -Seconds 3
}

if (-not $booted) {
    throw "AVD '$Avd' on $serial did not report sys.boot_completed within $TimeoutSeconds seconds."
}

Write-Output "$serial ($Avd) booted."
