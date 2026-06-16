param(
  [string]$ApkPath = "frontend/CretasFoodTrace/android/app/build/outputs/apk/release/app-release.apk",
  [string]$DeviceId = "",
  [string]$OutDir = "docs/audits/liushanmen/device-evidence/2026-06-16-rn-device-smoke"
)

$ErrorActionPreference = "Stop"

function Resolve-Adb {
  $fromPath = Get-Command adb -ErrorAction SilentlyContinue
  if ($fromPath) {
    return $fromPath.Source
  }

  $sdkAdb = Join-Path $env:LOCALAPPDATA "Android/Sdk/platform-tools/adb.exe"
  if (Test-Path $sdkAdb) {
    return $sdkAdb
  }

  throw "adb not found. Install Android SDK platform-tools or add adb to PATH."
}

function Run-Adb {
  param([string[]]$Args)
  if ($DeviceId) {
    & $adb -s $DeviceId @Args
  } else {
    & $adb @Args
  }
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

$adb = Resolve-Adb
$resolvedApk = Resolve-Path $ApkPath
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$devicesRaw = & $adb devices -l
$onlineDevices = $devicesRaw | Select-String -Pattern "device " | ForEach-Object { ($_ -split "\s+")[0] }

if (-not $onlineDevices -or $onlineDevices.Count -eq 0) {
  $report = @(
    "# RN Device Smoke Result",
    "",
    "Status: BLOCKED",
    "Reason: No Android device is online in adb.",
    "",
    "Checked at: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")",
    "ADB: $adb",
    "APK: $resolvedApk",
    "",
    "Next action:",
    "1. Connect Xiaomi phone by USB.",
    "2. Enable Developer options and USB debugging.",
    "3. Choose file transfer mode if prompted.",
    "4. Accept the RSA authorization prompt on the phone.",
    "5. Re-run:",
    "   powershell -ExecutionPolicy Bypass -File scripts/rn-device-smoke.ps1",
    "",
    "adb devices -l:",
    '```',
    ($devicesRaw | Out-String),
    '```'
  ) -join [Environment]::NewLine
  $reportPath = Join-Path $OutDir "result.md"
  Set-Content -Path $reportPath -Value $report -Encoding UTF8
  Write-Host "BLOCKED: no adb device online. Report: $reportPath"
  exit 2
}

if (-not $DeviceId) {
  $DeviceId = $onlineDevices[0]
}

$deviceInfo = Run-Adb @("shell", "getprop", "ro.product.manufacturer")
$model = Run-Adb @("shell", "getprop", "ro.product.model")
$androidVersion = Run-Adb @("shell", "getprop", "ro.build.version.release")

Write-Host "Using device: $DeviceId $deviceInfo $model Android $androidVersion"
Write-Host "Installing APK: $resolvedApk"
Run-Adb @("install", "-r", "$resolvedApk")

Write-Host "Launching app"
Run-Adb @("shell", "monkey", "-p", "com.cretas.foodtrace", "-c", "android.intent.category.LAUNCHER", "1")
Start-Sleep -Seconds 8

$screenshotDevice = "/sdcard/cretas-rn-smoke-home.png"
$screenshotLocal = Join-Path $OutDir "rn-smoke-home.png"
$hierarchyDevice = "/sdcard/cretas-rn-smoke-home.xml"
$hierarchyLocal = Join-Path $OutDir "rn-smoke-home.xml"

Run-Adb @("shell", "screencap", "-p", $screenshotDevice)
Run-Adb @("pull", $screenshotDevice, $screenshotLocal)
Run-Adb @("shell", "uiautomator", "dump", $hierarchyDevice)
Run-Adb @("pull", $hierarchyDevice, $hierarchyLocal)

$packageDump = Run-Adb @("shell", "dumpsys", "package", "com.cretas.foodtrace")
$topActivity = Run-Adb @("shell", "dumpsys", "activity", "activities")

$report = @(
  "# RN Device Smoke Result",
  "",
  "Status: PASS_DEVICE_LAUNCHED",
  "Checked at: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")",
  "",
  "Device:",
  "- id: $DeviceId",
  "- manufacturer: $deviceInfo",
  "- model: $model",
  "- android: $androidVersion",
  "",
  "APK:",
  "- path: $resolvedApk",
  "- package: com.cretas.foodtrace",
  "",
  "Evidence:",
  "- screenshot: $screenshotLocal",
  "- ui hierarchy: $hierarchyLocal",
  "",
  "Scope:",
  "- Installed APK",
  "- Launched app",
  "- Captured first-screen screenshot and UI hierarchy",
  "",
  "Not covered:",
  "- Login",
  "- Role switching",
  "- Sales/procurement/finance/warehouse business loop",
  "",
  "Next manual deep check:",
  "1. Login as sales, create sales order, submit finance review.",
  "2. Login as finance, approve sales todo.",
  "3. Login as procurement, create PO and payment request path.",
  "4. Login as warehouse, confirm purchase receive and transit receipt.",
  "5. Login as cashier, confirm payment.",
  "",
  "Package dump excerpt:",
  '```',
  ($packageDump | Select-Object -First 60 | Out-String),
  '```',
  "",
  "Top activity excerpt:",
  '```',
  ($topActivity | Select-Object -First 80 | Out-String),
  '```'
) -join [Environment]::NewLine

$reportPath = Join-Path $OutDir "result.md"
Set-Content -Path $reportPath -Value $report -Encoding UTF8
Write-Host "PASS_DEVICE_LAUNCHED. Report: $reportPath"
