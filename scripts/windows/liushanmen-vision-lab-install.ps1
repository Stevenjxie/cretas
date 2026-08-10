param(
    [string]$Python = "B:\anaconda3\python.exe",
    [string]$Config = "D:\CretasVisionLab\config.json",
    [string]$RepoRoot = "C:\Users\Steve\my-prototype-logistics",
    [switch]$Apply,
    [switch]$DisableLegacyTasks
)

$ErrorActionPreference = "Stop"
$taskName = "Cretas-Liushanmen-VisionLab"
$wrapper = Join-Path $RepoRoot "scripts\windows\liushanmen-vision-lab.ps1"
foreach ($path in @($Python, $Config, $wrapper)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required file not found: $path"
    }
}

$arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$wrapper`" -Python `"$Python`" -Config `"$Config`" -RepoRoot `"$RepoRoot`" -Command cycle"
$preview = [ordered]@{
    TaskName = $taskName
    DailyAt = "10:00"
    Execute = "powershell.exe"
    Arguments = $arguments
    DisableLegacyTasks = [bool]$DisableLegacyTasks
}
$preview | ConvertTo-Json -Depth 4
if (-not $Apply) {
    Write-Host "Preview only. Re-run with -Apply to register the task."
    exit 0
}

$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $arguments
$trigger = New-ScheduledTaskTrigger -Daily -At "10:00"
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 8) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Description "LIUSHANMEN read-only collect, MARK, YOLO train/evaluate and gated deploy" -Force | Out-Null

if ($DisableLegacyTasks) {
    foreach ($legacy in @("Cretas-Liushanmen-LabelV5-Shadow", "Cretas-Liushanmen-Disagreement-Mining")) {
        $task = Get-ScheduledTask -TaskName $legacy -ErrorAction SilentlyContinue
        if ($null -ne $task) {
            Disable-ScheduledTask -TaskName $legacy | Out-Null
        }
    }
}

Get-ScheduledTask -TaskName $taskName | Select-Object TaskName, State
