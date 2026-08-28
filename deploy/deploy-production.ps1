[CmdletBinding()]
param(
    [string]$ProductionHost = "207.57.122.109",
    [ValidateRange(1, 65535)]
    [int]$Port = 22,
    [string]$DeployUser = "api-deploy",
    [string]$IdentityFile = (Join-Path $HOME ".ssh\api-transit-deploy")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$Arguments
    )
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath exited with code $LASTEXITCODE"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
$stage = $null
try {
    $dirty = & git status --porcelain
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect Git working tree" }
    if ($dirty) { throw "Working tree must be clean before production deployment" }

    $branch = (& git branch --show-current).Trim()
    if ($branch -ne "master") { throw "Production deployment is only allowed from master" }
    Invoke-Native -FilePath git -Arguments @("fetch", "--quiet", "origin", "master")
    $head = (& git rev-parse HEAD).Trim()
    $originMaster = (& git rev-parse origin/master).Trim()
    if ($head -ne $originMaster) { throw "Local master must exactly match origin/master" }

    Invoke-Native -FilePath (Join-Path $repoRoot "mvnw.cmd") -Arguments @("--batch-mode", "--no-transfer-progress", "verify")
    Push-Location (Join-Path $repoRoot "web")
    try {
        Invoke-Native -FilePath npm -Arguments @("ci")
        Invoke-Native -FilePath npm -Arguments @("test")
        Invoke-Native -FilePath npm -Arguments @("run", "build")
    } finally {
        Pop-Location
    }

    $shortSha = $head.Substring(0, 7)
    $releaseId = "{0}-{1}" -f ([DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss")), $shortSha
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $stage = Join-Path $temporaryRoot "api-transit-deploy-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $stage | Out-Null

    Copy-Item -LiteralPath (Join-Path $repoRoot "target\API_transit_station-0.0.1-SNAPSHOT.jar") `
        -Destination (Join-Path $stage "api-transit.jar")
    Invoke-Native -FilePath tar -Arguments @("-C", (Join-Path $repoRoot "web\dist"), "-czf", (Join-Path $stage "frontend.tar.gz"), ".")
    $jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $stage "api-transit.jar")).Hash.ToLowerInvariant()
    $frontendHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $stage "frontend.tar.gz")).Hash.ToLowerInvariant()
    $manifest = @(
        "$jarHash  api-transit.jar"
        "$frontendHash  frontend.tar.gz"
    ) -join "`n"
    [IO.File]::WriteAllText((Join-Path $stage "SHA256SUMS"), "$manifest`n", [Text.Encoding]::ASCII)

    $identity = (Resolve-Path -LiteralPath $IdentityFile).Path
    $sshArgs = @("-i", $identity, "-p", "$Port", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes")
    Invoke-Native -FilePath ssh -Arguments (@($sshArgs) + @(
        "$DeployUser@$ProductionHost",
        "install -d -m 0700 '/var/lib/api-transit-deploy/incoming/$releaseId'"
    ))
    Invoke-Native -FilePath scp -Arguments @(
        "-i", $identity, "-P", "$Port", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
        (Join-Path $stage "api-transit.jar"),
        (Join-Path $stage "frontend.tar.gz"),
        (Join-Path $stage "SHA256SUMS"),
        "${DeployUser}@${ProductionHost}:/var/lib/api-transit-deploy/incoming/$releaseId/"
    )
    Invoke-Native -FilePath ssh -Arguments (@($sshArgs) + @(
        "$DeployUser@$ProductionHost",
        "sudo /usr/local/sbin/api-transit-release '$releaseId'"
    ))

    Invoke-WebRequest -Uri "https://linknux.com/" -UseBasicParsing -TimeoutSec 20 | Out-Null
    $models = Invoke-RestMethod -Uri "https://api.linknux.com/public/models?size=1" -TimeoutSec 20
    if ($null -eq $models.items) { throw "Public model smoke test returned an invalid response" }
    Write-Host "Production release completed: $releaseId"
} finally {
    Pop-Location
    if ($stage) {
        $resolvedStage = [IO.Path]::GetFullPath($stage)
        $resolvedTemporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if ($resolvedStage.StartsWith($resolvedTemporaryRoot, [StringComparison]::OrdinalIgnoreCase) `
            -and (Split-Path -Leaf $resolvedStage).StartsWith("api-transit-deploy-")) {
            Remove-Item -LiteralPath $resolvedStage -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
