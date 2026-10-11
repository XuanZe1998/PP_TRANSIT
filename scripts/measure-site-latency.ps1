[CmdletBinding()]
param(
    [ValidateRange(1, 20)][int]$Runs = 3,
    [ValidateRange(1, 30)][int]$ConnectTimeoutSeconds = 5,
    [ValidateRange(2, 120)][int]$MaxTimeSeconds = 15,
    [string]$SiteOrigin = 'https://linknux.com',
    [string]$ApiOrigin = 'https://api.linknux.com',
    [string]$OutputPath,
    [switch]$NoProxy
)

$ErrorActionPreference = 'Stop'
$site = $SiteOrigin.TrimEnd('/')
$api = $ApiOrigin.TrimEnd('/')
foreach ($origin in @($site, $api)) {
    $uri = [uri]$origin
    if ($uri.Scheme -notin @('http', 'https') -or !$uri.Host -or $uri.AbsolutePath -ne '/') {
        throw "Expected an HTTP(S) origin without a path: $origin"
    }
}
if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    throw 'curl.exe is required for connection-phase timings.'
}

$common = @('-sS', '-L', '--connect-timeout', "$ConnectTimeoutSeconds", '--max-time', "$MaxTimeSeconds")
if ($NoProxy) { $common += @('--noproxy', '*') }
$nullDevice = if ($env:OS -eq 'Windows_NT') { 'NUL' } else { '/dev/null' }

$assets = @()
# Discover current production assets from the live document, never from local dist/.
# Retry discovery because an intermittent connection is precisely what this script measures.
for ($attempt = 1; $attempt -le 3 -and !$assets.Count; $attempt++) {
    $html = & curl.exe @common "$site/" 2>$null
    if ($LASTEXITCODE -ne 0) { continue }
    $assetMatches = [regex]::Matches(($html -join "`n"), '(?:src|href)="(/assets/[^"?]+\.(?:js|css))"')
    $assets = @($assetMatches | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
}
if (!$assets.Count) { Write-Warning 'Could not discover live assets; measuring page and API paths only.' }

$targets = @(
    @{ Name = 'home'; Url = "$site/" },
    @{ Name = 'market'; Url = "$site/market" },
    @{ Name = 'services'; Url = "$site/services" },
    @{ Name = 'docs'; Url = "$site/docs" },
    @{ Name = 'site-config'; Url = "$api/public/site-config" },
    @{ Name = 'models-summary'; Url = "$api/public/models/summary" }
)
foreach ($asset in $assets) {
    $targets += @{ Name = "asset:$($asset.Split('/')[-1])"; Url = "$site$asset" }
}

$rows = foreach ($target in $targets) {
    for ($run = 1; $run -le $Runs; $run++) {
        $raw = & curl.exe @common '-o' $nullDevice '-w' '%{json}' $target.Url 2>$null
        $exitCode = $LASTEXITCODE
        try { $result = ($raw -join '') | ConvertFrom-Json -ErrorAction Stop }
        catch { Write-Warning "No curl timing for $($target.Name) run $run (exit $exitCode)"; continue }
        $failurePhase = if ($exitCode -eq 0) { $null }
            elseif ($result.time_connect -eq 0) { 'tcp-connect' }
            elseif ($result.time_appconnect -eq 0 -and $target.Url.StartsWith('https://')) { 'tls' }
            elseif ($result.http_code -eq 0) { 'response' }
            else { 'transfer' }
        [pscustomobject]@{
            timestampUtc = (Get-Date).ToUniversalTime().ToString('o')
            target       = $target.Name
            run          = $run
            status       = [int]$result.http_code
            curlExit     = $exitCode
            failurePhase = $failurePhase
            remoteIp     = $result.remote_ip
            dnsMs        = [math]::Round(1000 * $result.time_namelookup)
            connectMs    = [math]::Round(1000 * $result.time_connect)
            tlsMs        = [math]::Round(1000 * $result.time_appconnect)
            firstByteMs  = if ($result.http_code -gt 0) { [math]::Round(1000 * $result.time_starttransfer) } else { $null }
            totalMs      = [math]::Round(1000 * $result.time_total)
            bytes        = [int64]$result.size_download
        }
    }
}
if ($OutputPath) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and !(Test-Path -LiteralPath $parent)) { throw "Output directory does not exist: $parent" }
    $rows | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Encoding UTF8
}
$rows
