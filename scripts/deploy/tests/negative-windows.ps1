$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$script = 'C:\Users\Steve\cretas-plan1-transport\scripts\deploy\Publish-GitHubArtifactViaLightsailOss.ps1'
$goodSha = '53f6bca41dc59153f30a9fca69a2a9ae1be6086cc8c592dca178cd80c59c7ab9'
$goodTree = '05fc3ba1397faf12db80d333b6a42afdb9f6a828'
$pass = 0; $fail = 0

function Expect-Refusal {
    param([string]$Name, [string]$Fragment, [hashtable]$Overrides)
    $splat = @{
        Repository = 'obsproject/obs-studio'; AssetId = '488910689'
        ExpectedSize = '167106178'; ExpectedSha256 = $goodSha
        TreeSha = $goodTree; DestinationPrefix = 'codex-network-test/'
    }
    foreach ($k in $Overrides.Keys) { $splat[$k] = $Overrides[$k] }
    try {
        & $script @splat *> $null
        Write-Host "  FAIL  $Name -- accepted an input it must refuse"
        $script:fail++
    }
    catch {
        if ($_.Exception.Message -like "*$Fragment*") {
            Write-Host "  PASS  $Name"; $script:pass++
        }
        else {
            Write-Host "  FAIL  $Name -- wrong reason: $($_.Exception.Message)"
            $script:fail++
        }
    }
}

Write-Host '== parameter validation =='
Expect-Refusal 'uppercase sha refused'      'lowercase hex'  @{ ExpectedSha256 = $goodSha.ToUpper() }
Expect-Refusal 'short sha refused'          'lowercase hex'  @{ ExpectedSha256 = 'abc123' }
Expect-Refusal 'unapproved prefix refused'  'approved list'  @{ DestinationPrefix = 'deploy/evil/' }
Expect-Refusal 'traversal prefix refused'   'approved list'  @{ DestinationPrefix = '../deploy/backend/' }
Expect-Refusal 'bad tree sha refused'       'Git SHA'        @{ TreeSha = 'not-a-sha' }
Expect-Refusal 'shell metachar repo refused' 'metacharacters' @{ Repository = 'owner/name;rm -rf /' }
Expect-Refusal 'non-numeric asset refused'  'numeric'        @{ AssetId = '12a' }
Expect-Refusal 'zero size refused'          'positive integer' @{ ExpectedSize = '0' }

Write-Host ''
Write-Host "windows_negative_pass=$pass windows_negative_fail=$fail"
if ($fail -ne 0) { exit 1 }
