<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP0, register R-111 - screenshots the emulator's current frame to
  results/ui-audit/<scenario>/<screen>.png.

.DESCRIPTION
  Uses `screencap -p` to a device-side file then `adb pull` - never `adb exec-out`, which
  PowerShell's `>` redirection corrupts for binary output (this package's brief, verbatim).

.EXAMPLE
  .\shoot.ps1 -Port 5554 -Scenario overnight -Screen log
#>
param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Scenario,
    [Parameter(Mandatory = $true)][string]$Screen
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $androidHome "platform-tools\adb.exe"
$serial = "emulator-$Port"

$outDir = Join-Path $repoRoot "results\ui-audit\$Scenario"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = Join-Path $outDir "$Screen.png"
$devicePath = "/sdcard/ui-audit-shot.png"

& $adb -s $serial shell screencap -p $devicePath
if ($LASTEXITCODE -ne 0) { throw "screencap failed on $serial (exit $LASTEXITCODE)" }

& $adb -s $serial pull $devicePath $outFile
if ($LASTEXITCODE -ne 0) { throw "adb pull failed for $devicePath (exit $LASTEXITCODE)" }

& $adb -s $serial shell rm $devicePath

if (-not (Test-Path $outFile)) { throw "screenshot was not saved at $outFile" }
$size = (Get-Item $outFile).Length
if ($size -lt 1024) { throw "$outFile is suspiciously small ($size bytes) - the pull likely failed silently" }

Write-Output "saved $outFile ($size bytes)"
