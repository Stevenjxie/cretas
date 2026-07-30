<#
.SYNOPSIS
    Publish a GitHub release artifact to Shanghai OSS without routing its bytes
    through Windows.

.DESCRIPTION
    Windows issues control commands only. The artifact travels:

        GitHub -> Tokyo Lightsail (download + verify)
              -> presigned PUT      -> Shanghai OSS
              -> internal endpoint  -> Shanghai ECS (download + re-hash)

    Neither the GitHub download URL nor the OSS PUT URL is ever placed in argv,
    a log line, an exception message or a process list. Both are held in memory
    only and handed to the remote host over SSH stdin as raw bytes.

    This script deliberately does NOT decide whether the artifact is safe to
    deploy. Transport integrity and build trust are different claims; it reports
    transport_verified and defers deployable_trust_verified to the release
    manifest checked by the ECS verifier.

.NOTES
    The 64/96/128/192-way sharded Windows downloader is NOT used here and must
    not become the default path again. It survives only as a manual fallback.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Repository,
    # Exactly one source. A release asset is a raw file whose SHA-256 is known
    # up front; a CI artifact is a ZIP whose publishable identity is the SHA-256
    # of the JAR inside it, discoverable only after Tokyo opens the archive.
    [string]$AssetId,
    [string]$ArtifactId,
    [string]$ExpectedSize,
    [string]$ExpectedSha256,
    [Parameter(Mandatory = $true)][string]$TreeSha,
    [Parameter(Mandatory = $true)][string]$DestinationPrefix,
    [string]$JarName = 'cretas-backend-system-1.0.0.jar',
    [string]$ManifestPath,
    [switch]$PurgeAcceptanceObject,
    # Publish the verified jar into the server-side SHA-256 cache, where
    # deploy-backend.sh's claim_remote_sha256_artifact will find it and skip the
    # upload race entirely. Refused for the acceptance prefix by the verifier.
    [switch]$StageToCache
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# No command echo: a traced line could surface a URL.
Set-PSDebug -Off

$LightsailHost = 'ubuntu@10.66.66.1'
$LightsailKey = Join-Path $env:USERPROFILE '.ssh\ai-egress-tokyo-windows_ed25519'
$EcsHost = 'aliyun-new'
$ApprovedPrefixes = @('deploy/backend/', 'codex-network-test/')

# ---------------------------------------------------------------- validation --

$useArtifact = -not [string]::IsNullOrWhiteSpace($ArtifactId)
$useAsset = -not [string]::IsNullOrWhiteSpace($AssetId)
if ($useArtifact -eq $useAsset) {
    throw 'Supply exactly one of -AssetId or -ArtifactId.'
}
if ($useArtifact) {
    if ($ArtifactId -notmatch '^[0-9]+$') { throw 'ArtifactId must be numeric.' }
    if ($JarName -notmatch '^[A-Za-z0-9._-]+\.jar$') {
        throw 'JarName must be a bare .jar filename with no path separators.'
    }
    # Both are optional here and act as assertions against what Tokyo finds
    # inside the ZIP; the ZIP size itself comes from the artifact API.
    if ($ExpectedSha256 -and $ExpectedSha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'ExpectedSha256 must be 64 lowercase hex characters.'
    }
    if ($ExpectedSize -and $ExpectedSize -notmatch '^[1-9][0-9]*$') {
        throw 'ExpectedSize must be a positive integer.'
    }
}
else {
    if ($AssetId -notmatch '^[0-9]+$') { throw 'AssetId must be numeric.' }
    if ($ExpectedSha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'ExpectedSha256 must be 64 lowercase hex characters.'
    }
    if ($ExpectedSize -notmatch '^[1-9][0-9]*$') {
        throw 'ExpectedSize must be a positive integer.'
    }
}
if ($TreeSha -cnotmatch '^[0-9a-f]{7,40}$') {
    throw 'TreeSha must be a lowercase hex Git SHA.'
}
if ($Repository -notmatch '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$') {
    throw 'Repository must be <owner>/<name> with no shell metacharacters.'
}
$normalizedPrefix = if ($DestinationPrefix.EndsWith('/')) { $DestinationPrefix } else { "$DestinationPrefix/" }
if ($ApprovedPrefixes -cnotcontains $normalizedPrefix) {
    throw "DestinationPrefix is not on the approved list."
}
if (-not (Test-Path -LiteralPath $LightsailKey)) {
    throw 'Tokyo Lightsail private key not found.'
}

# ------------------------------------------------------------------ helpers --

function Invoke-RemoteWithSecretStdin {
    <#
        Runs a remote command over SSH and feeds it one secret line on stdin.
        The secret is written as raw LF-terminated bytes: PowerShell's pipeline
        would emit CRLF, and the remote `IFS= read -r` would keep the CR inside
        the URL, which then fails validation for a reason that looks unrelated.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$SshTarget,
        [Parameter(Mandatory = $true)][string[]]$SshOptions,
        [Parameter(Mandatory = $true)][string]$RemoteCommand,
        [Parameter(Mandatory = $true)][string]$Secret
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'ssh'
    foreach ($option in $SshOptions) { $startInfo.ArgumentList.Add($option) }
    $startInfo.ArgumentList.Add($SshTarget)
    $startInfo.ArgumentList.Add($RemoteCommand)
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.UseShellExecute = $false

    $process = [System.Diagnostics.Process]::Start($startInfo)
    try {
        # UTF-8 without BOM, LF-terminated. The payload is a URL or a manifest;
        # either way a BOM or CRLF would be carried into the remote's variable.
        $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($Secret + "`n")
        $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $process.StandardInput.BaseStream.Flush()
        $process.StandardInput.Close()
        [System.Array]::Clear($bytes, 0, $bytes.Length)

        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StdOut   = $stdout
            StdErr   = $stderr
        }
    }
    finally {
        $process.Dispose()
    }
}

function Invoke-Remote {
    param(
        [Parameter(Mandatory = $true)][string]$SshTarget,
        [Parameter(Mandatory = $true)][string[]]$SshOptions,
        [Parameter(Mandatory = $true)][string]$RemoteCommand
    )
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'ssh'
    foreach ($option in $SshOptions) { $startInfo.ArgumentList.Add($option) }
    $startInfo.ArgumentList.Add($SshTarget)
    $startInfo.ArgumentList.Add($RemoteCommand)
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.UseShellExecute = $false

    $process = [System.Diagnostics.Process]::Start($startInfo)
    try {
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StdOut   = $stdout
            StdErr   = $stderr
        }
    }
    finally {
        $process.Dispose()
    }
}

function Get-Field {
    param([string]$Text, [string]$Name)
    foreach ($line in ($Text -split "`n")) {
        if ($line -match "(?:^|\s)$([regex]::Escape($Name))=([^\s]+)") { return $Matches[1] }
    }
    return $null
}

# --------------------------------------------------------------------- main --

$mutex = [System.Threading.Mutex]::new($false, 'Global\Cretas-Publish-GitHubArtifact')
if (-not $mutex.WaitOne(0)) {
    throw 'Another publish run holds the lock; refusing to run two at once.'
}

$assetUrl = $null
$putUrl = $null
$lightsailOptions = @('-i', $LightsailKey, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
    '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=15')
$ecsOptions = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=15')

try {
    Write-Host 'step=auth'
    # Status only. gh prints a masked token, but a masked secret is still a
    # secret shape in a transcript, so the whole stream is discarded and only
    # the exit code is consulted.
    & gh auth status *> $null
    if ($LASTEXITCODE -ne 0) { throw 'gh auth status failed; run gh auth login.' }

    $manifestB64 = $null

    if ($useArtifact) {
        Write-Host 'step=artifact_metadata'
        $artifactJson = & gh api "repos/$Repository/actions/artifacts/$ArtifactId" `
            -H 'Accept: application/vnd.github+json' 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'gh api could not read the artifact metadata.' }
        $artifact = $artifactJson | ConvertFrom-Json
        if ($artifact.expired) { throw 'That artifact has expired and can no longer be downloaded.' }
        $zipSize = [string]$artifact.size_in_bytes
        if ($zipSize -notmatch '^[1-9][0-9]*$') { throw 'Artifact metadata has no usable size.' }
        Write-Host "artifact_zip_bytes=$zipSize"

        # The /zip endpoint answers 302 to a short-lived storage URL. Resolve it
        # without following, and never let either the token or the location land
        # in argv, a log line or an exception message.
        $token = & gh auth token
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($token)) {
            throw 'Could not obtain a GitHub token from gh.'
        }
        # HttpClient with redirects disabled: Invoke-WebRequest treats
        # -MaximumRedirection 0 as an error rather than handing back the 302,
        # and parsing the header out of `gh api --include` is known to break on
        # multi-byte output. This keeps the token in memory and out of argv.
        $handler = [System.Net.Http.HttpClientHandler]::new()
        $handler.AllowAutoRedirect = $false
        $client = [System.Net.Http.HttpClient]::new($handler)
        try {
            $request = [System.Net.Http.HttpRequestMessage]::new(
                [System.Net.Http.HttpMethod]::Get,
                "https://api.github.com/repos/$Repository/actions/artifacts/$ArtifactId/zip")
            $request.Headers.Authorization =
                [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
            $request.Headers.UserAgent.ParseAdd('cretas-release-transport')
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            if ([int]$response.StatusCode -notin 301, 302, 303, 307, 308) {
                throw "Artifact download returned $([int]$response.StatusCode) instead of a redirect."
            }
            $zipUrl = [string]$response.Headers.Location.AbsoluteUri
            $response.Dispose()
            $request.Dispose()
        }
        finally {
            $token = $null
            $client.Dispose()
            $handler.Dispose()
        }
        if ([string]::IsNullOrWhiteSpace($zipUrl)) {
            throw 'Artifact download did not return a redirect location.'
        }

        Write-Host 'step=lightsail_stage'
        $stage = Invoke-RemoteWithSecretStdin -SshTarget $LightsailHost -SshOptions $lightsailOptions `
            -RemoteCommand "sudo -n /usr/local/sbin/github-artifact-stage --zip-size $zipSize --jar-name $JarName" `
            -Secret $zipUrl
        $zipUrl = $null
        if ($stage.ExitCode -ne 0) { throw "github-artifact-stage failed: $($stage.StdErr.Trim())" }
        $cacheStatus = Get-Field -Text $stage.StdOut -Name 'cache_status'
        $jarSha256 = Get-Field -Text $stage.StdOut -Name 'jar_sha256'
        $jarSize = Get-Field -Text $stage.StdOut -Name 'jar_size'
        $shaSource = Get-Field -Text $stage.StdOut -Name 'sha_source'
        $manifestPresent = Get-Field -Text $stage.StdOut -Name 'manifest_present'
        if ($manifestPresent -eq 'true') { $manifestB64 = Get-Field -Text $stage.StdOut -Name 'manifest_b64' }
        if ($jarSha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Staging returned no usable JAR SHA-256.' }
        # Optional assertions: if the caller stated what it expects, the ZIP must agree.
        if ($ExpectedSha256 -and $jarSha256 -cne $ExpectedSha256) {
            throw 'JAR SHA-256 inside the artifact disagrees with ExpectedSha256.'
        }
        if ($ExpectedSize -and $jarSize -ne $ExpectedSize) {
            throw 'JAR size inside the artifact disagrees with ExpectedSize.'
        }
        Write-Host "cache_status=$cacheStatus jar_bytes=$jarSize sha_source=$shaSource manifest_present=$manifestPresent"
    }
    else {
        Write-Host 'step=asset_metadata'
        $assetJson = & gh api "repos/$Repository/releases/assets/$AssetId" `
            -H 'Accept: application/vnd.github+json' 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'gh api could not read the asset metadata.' }
        $asset = $assetJson | ConvertFrom-Json
        if ([string]$asset.size -ne $ExpectedSize) {
            throw "Asset size disagrees with ExpectedSize (got $($asset.size))."
        }
        # Memory only, from here until the finally block wipes it.
        $assetUrl = [string]$asset.browser_download_url
        if ([string]::IsNullOrWhiteSpace($assetUrl)) { throw 'Asset has no download URL.' }

        Write-Host 'step=lightsail_cache'
        $cache = Invoke-RemoteWithSecretStdin -SshTarget $LightsailHost -SshOptions $lightsailOptions `
            -RemoteCommand "sudo -n /usr/local/sbin/github-cache-put --sha256 $ExpectedSha256 --size $ExpectedSize" `
            -Secret $assetUrl
        $assetUrl = $null
        if ($cache.ExitCode -ne 0) { throw "github-cache-put failed: $($cache.StdErr.Trim())" }
        $cacheStatus = Get-Field -Text $cache.StdOut -Name 'cache_status'
        $cacheBytes = Get-Field -Text $cache.StdOut -Name 'bytes'
        $cacheSha = Get-Field -Text $cache.StdOut -Name 'sha256'
        if ($cacheStatus -notin @('hit', 'stored')) { throw "Unexpected cache_status: $cacheStatus" }
        if ($cacheBytes -ne $ExpectedSize) { throw 'Cached size disagrees with ExpectedSize.' }
        if ($cacheSha -cne $ExpectedSha256) { throw 'Cached SHA-256 disagrees with ExpectedSha256.' }
        $jarSha256 = $ExpectedSha256
        $jarSize = $ExpectedSize
        Write-Host "cache_status=$cacheStatus bytes=$cacheBytes"
    }

    Write-Host 'step=sign'
    $sign = Invoke-Remote -SshTarget $EcsHost -SshOptions $ecsOptions -RemoteCommand (
        "/usr/local/sbin/oss-sign-put.py --prefix $normalizedPrefix --tree-sha $TreeSha " +
        "--jar-sha256 $jarSha256 --size $jarSize --expires-seconds 900")
    if ($sign.ExitCode -ne 0) { throw "oss-sign-put failed: $($sign.StdErr.Trim())" }
    $artifactStatus = Get-Field -Text $sign.StdErr -Name 'artifact_status'

    if ($artifactStatus -eq 'hit') {
        Write-Host 'artifact_status=hit (object already present; skipping upload)'
        $uploadStatus = 'hit'
    }
    else {
        $putUrl = $sign.StdOut.Trim()
        if ([string]::IsNullOrWhiteSpace($putUrl)) { throw 'Signer returned no URL.' }

        Write-Host 'step=upload'
        $upload = Invoke-RemoteWithSecretStdin -SshTarget $LightsailHost -SshOptions $lightsailOptions `
            -RemoteCommand ("sudo -n /usr/local/sbin/oss-put-artifact " +
                "--prefix $normalizedPrefix --tree-sha $TreeSha " +
                "--sha256 $jarSha256 --size $jarSize") `
            -Secret $putUrl
        $putUrl = $null
        if ($upload.ExitCode -ne 0) { throw "oss-put-artifact failed: $($upload.StdErr.Trim())" }
        Write-Host $upload.StdOut.Trim()
        $uploadStatus = Get-Field -Text $upload.StdOut -Name 'artifact_status'
    }

    Write-Host 'step=verify'
    $verifyCommand = "/usr/local/sbin/oss-verify-artifact.sh --prefix $normalizedPrefix " +
    "--tree-sha $TreeSha --jar-sha256 $jarSha256 --size $jarSize"
    if ($ManifestPath) { $verifyCommand += " --manifest $ManifestPath" }
    if ($manifestB64) { $verifyCommand += ' --manifest-stdin' }
    if ($PurgeAcceptanceObject) { $verifyCommand += ' --purge-acceptance' }
    if ($StageToCache) { $verifyCommand += ' --stage-to-cache' }

    if ($manifestB64) {
        # The manifest came out of the ZIP on Tokyo and is piped straight to the
        # ECS, so it never touches the Windows filesystem.
        $manifestText = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($manifestB64))
        $verify = Invoke-RemoteWithSecretStdin -SshTarget $EcsHost -SshOptions $ecsOptions `
            -RemoteCommand $verifyCommand -Secret $manifestText
        $manifestText = $null
    }
    else {
        $verify = Invoke-Remote -SshTarget $EcsHost -SshOptions $ecsOptions -RemoteCommand $verifyCommand
    }
    if ($verify.ExitCode -ne 0) { throw "oss-verify-artifact failed: $($verify.StdErr.Trim())" }
    Write-Host $verify.StdOut.Trim()

    Write-Host "upload_status=$uploadStatus"
    Write-Host 'windows_carried_artifact_bytes=0'
}
finally {
    $assetUrl = $null
    $putUrl = $null
    [System.GC]::Collect()
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
