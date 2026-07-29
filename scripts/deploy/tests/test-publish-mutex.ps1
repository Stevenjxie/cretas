$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# A Windows mutex is re-entrant for the SAME thread, so holding it in this
# process and then calling the script in-process proves nothing. The real
# scenario is two independent processes, so hold the lock in a separate one.
$script = 'C:\Users\Steve\cretas-plan1-transport\scripts\deploy\Publish-GitHubArtifactViaLightsailOss.ps1'
$name = 'Global\Cretas-Publish-GitHubArtifact'

Write-Host 'starting a separate process that holds the lock for 25s...'
$holder = Start-Process -FilePath 'pwsh' -PassThru -NoNewWindow -ArgumentList @(
    '-NoProfile', '-Command',
    "`$m=[System.Threading.Mutex]::new(`$false,'$name'); if(`$m.WaitOne(0)){Write-Host 'holder_acquired'; Start-Sleep -Seconds 25; `$m.ReleaseMutex()} else {Write-Host 'holder_failed'; exit 3}"
)
Start-Sleep -Seconds 4

$refused = $false
$reason = ''
try {
    & $script -Repository 'obsproject/obs-studio' -AssetId '488910689' `
        -ExpectedSize '167106178' `
        -ExpectedSha256 '53f6bca41dc59153f30a9fca69a2a9ae1be6086cc8c592dca178cd80c59c7ab9' `
        -TreeSha '05fc3ba1397faf12db80d333b6a42afdb9f6a828' `
        -DestinationPrefix 'codex-network-test/' *> $null
    $reason = 'proceeded despite another process holding the lock'
}
catch {
    $reason = $_.Exception.Message
    if ($reason -like '*holds the lock*') { $refused = $true }
}

Write-Host "cross_process_refused=$refused"
Write-Host "reason=$reason"
if (-not $holder.HasExited) { $holder.Kill() }
if (-not $refused) { exit 1 }
