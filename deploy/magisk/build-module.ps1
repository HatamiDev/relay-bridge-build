param(
    [string]$Apk = "",
    [string]$Out = ""
)

# Package the Magisk module that makes RelayBridge a privileged app.
#
#   .\deploy\magisk\build-module.ps1 -Apk .\RelayBridge-2.4.0-arm64.apk
#
# Produces deploy/magisk/relay-priv.zip, which is flashed from Magisk ->
# Modules -> Install from storage. Nothing here modifies /system on disk: Magisk
# overlays the tree at boot, so uninstalling the module reverses it completely
# without a firmware flash. That matters because a malformed
# privapp-permissions file can stop a device booting, and a module can be
# removed from recovery while an edited /system cannot.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$module = Join-Path $root "relay-priv"

if (-not $Apk) {
    $newest = Get-ChildItem (Join-Path $root "..\..") -Filter "RelayBridge-*.apk" |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $newest) { throw "No RelayBridge-*.apk found; pass -Apk explicitly." }
    $Apk = $newest.FullName
}
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
if (-not $Out) { $Out = Join-Path $root "relay-priv.zip" }

$dest = Join-Path $module "system\priv-app\RelayBridge\RelayBridge.apk"
Copy-Item $Apk $dest -Force
Write-Output ("  apk  : " + (Split-Path $Apk -Leaf))

Remove-Item $Out -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $module "*") -DestinationPath $Out -CompressionLevel Optimal
Write-Output ("  built: " + $Out)

Write-Output ""
Write-Output "Flash from Magisk -> Modules -> Install from storage, then reboot."
Write-Output "Verify with:"
Write-Output "  adb shell dumpsys package com.relay.app | findstr CAPTURE_AUDIO_OUTPUT"
Write-Output "It must print granted=true. If it prints granted=false the allowlist"
Write-Output "did not load - check the package name in the XML against applicationId."
