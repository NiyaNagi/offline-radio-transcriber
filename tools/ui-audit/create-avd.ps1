<#
.SYNOPSIS
  spec/ui-conformance-plan.md WP0, register R-111 - creates a second AVD identical to `ort_audit`
  (Pixel 6, API 34 x86_64 google_apis), for the concurrency and safety rule in
  spec/ui-conformance-plan.md ("at most two AVDs at once - ort_audit on 5554, ort_audit_2 on
  5556").

.EXAMPLE
  .\create-avd.ps1 -Name ort_audit_2
#>
param(
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Device = "pixel_6",
    [string]$Package = "system-images;android-34;google_apis;x86_64"
)

$ErrorActionPreference = "Stop"

$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

$avdmanager = Get-ChildItem -Path (Join-Path $androidHome "cmdline-tools") -Filter "avdmanager.bat" -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $avdmanager) { throw "avdmanager.bat not found under $androidHome\cmdline-tools - install the SDK command-line tools" }

$sdkmanager = Get-ChildItem -Path (Join-Path $androidHome "cmdline-tools") -Filter "sdkmanager.bat" -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if ($sdkmanager) {
    Write-Output "Ensuring system image '$Package' is installed..."
    "y" | & $sdkmanager $Package | Out-Null
}

Write-Output "Creating AVD '$Name' ($Package, device '$Device')..."
# `avdmanager create avd` asks "Do you wish to create a custom hardware profile [no]" on stdin;
# this session's stdin is the null device (PowerShell tool contract), so the answer is piped in.
"no" | & $avdmanager create avd --name $Name --package $Package --device $Device --force
if ($LASTEXITCODE -ne 0) { throw "avdmanager create avd failed with exit code $LASTEXITCODE" }

Write-Output "AVD '$Name' created."
