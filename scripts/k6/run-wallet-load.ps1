[CmdletBinding()]
param(
  [Parameter()]
  [ValidateSet('mixed', 'read', 'write')]
  [string]$Mode = 'mixed',

  [Parameter()]
  [string]$BaseUrl = 'https://api.linknux.com',

  [Parameter()]
  [string]$UserDataFile = (Join-Path $PSScriptRoot 'user-token-pool.csv'),

  [Parameter()]
  [double]$WriteRatio = 0.3,

  [Parameter()]
  [decimal]$CustomAmount = 12.34,

  [Parameter()]
  [string]$PaymentMethod = 'alipay',

  [Parameter()]
  [int]$WalletPage = 2,

  [Parameter()]
  [int]$WalletPageSize = 50,

  [Parameter()]
  [string]$StagePlanJson = '',

  [Parameter()]
  [string]$SummaryOut = '',

  [Parameter()]
  [switch]$AllowTokenReuse
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$resolvedUserDataFile = [IO.Path]::GetFullPath($UserDataFile)
if (!(Test-Path -LiteralPath $resolvedUserDataFile)) {
  throw "USER_DATA_FILE not found: $resolvedUserDataFile"
}

if ($WriteRatio -lt 0 -or $WriteRatio -gt 1) {
  throw "WriteRatio must be in range [0,1]."
}

$env:BASE_URL = ($BaseUrl -replace '/+$', '')
$env:SCENARIO_MODE = $Mode
$env:AUTH_MODE = 'token'
$env:USER_DATA_FILE = $resolvedUserDataFile
$env:WRITE_RATIO = "$WriteRatio"
$env:CUSTOM_AMOUNT = $CustomAmount.ToString([Globalization.CultureInfo]::InvariantCulture)
$env:PAYMENT_METHOD = $PaymentMethod
$env:WALLET_PAGE = "$WalletPage"
$env:WALLET_PAGE_SIZE = "$WalletPageSize"
$env:ALLOW_TOKEN_REUSE = if ($AllowTokenReuse) { 'true' } else { 'false' }

if (-not $StagePlanJson) {
  $StagePlanJson = '[{"duration":"2m","target":20},{"duration":"2m","target":50},{"duration":"2m","target":100},{"duration":"2m","target":200},{"duration":"2m","target":400},{"duration":"2m","target":800}]'
}
$env:STAGE_PLAN = $StagePlanJson

if (-not $SummaryOut) {
  $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
  $SummaryOut = Join-Path $PSScriptRoot "results\wallet-load-$Mode-$stamp.json"
}
$resolvedSummaryOut = [IO.Path]::GetFullPath($SummaryOut)
$summaryDir = Split-Path -Parent $resolvedSummaryOut
if (-not (Test-Path -LiteralPath $summaryDir)) {
  New-Item -ItemType Directory -Path $summaryDir | Out-Null
}
$env:SUMMARY_OUT = $resolvedSummaryOut

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (Get-Command k6 -ErrorAction SilentlyContinue) {
  Push-Location $repoRoot
  try {
    & k6 run scripts/k6/wallet-load.js
    $k6ExitCode = $LASTEXITCODE
  } finally {
    Pop-Location
  }
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
  $userDataDir = Split-Path -Parent $resolvedUserDataFile
  $userDataName = Split-Path -Leaf $resolvedUserDataFile
  $summaryName = Split-Path -Leaf $resolvedSummaryOut
  $dockerArgs = @(
    'run', '--rm',
    '-v', "${repoRoot}:/work:ro",
    '-v', "${userDataDir}:/k6-data:ro",
    '-v', "${summaryDir}:/k6-output",
    '-w', '/work',
    '-e', 'BASE_URL', '-e', 'SCENARIO_MODE', '-e', 'AUTH_MODE',
    '-e', 'WRITE_RATIO', '-e', 'CUSTOM_AMOUNT', '-e', 'PAYMENT_METHOD',
    '-e', 'WALLET_PAGE', '-e', 'WALLET_PAGE_SIZE', '-e', 'STAGE_PLAN',
    '-e', 'ALLOW_TOKEN_REUSE',
    '-e', "USER_DATA_FILE=/k6-data/$userDataName",
    '-e', "SUMMARY_OUT=/k6-output/$summaryName",
    'grafana/k6:latest', 'run', 'scripts/k6/wallet-load.js'
  )
  & docker @dockerArgs
  $k6ExitCode = $LASTEXITCODE
} else {
  throw 'Neither k6 nor Docker is installed.'
}

if (Test-Path -LiteralPath $resolvedSummaryOut) {
  $summary = Get-Content -LiteralPath $resolvedSummaryOut -Raw | ConvertFrom-Json
  $stageRows = foreach ($stage in ($env:STAGE_PLAN | ConvertFrom-Json)) {
    $tag = "{load_stage:$($stage.target)}"
    $failedMetric = $summary.metrics.PSObject.Properties["http_req_failed$tag"].Value
    $durationMetric = $summary.metrics.PSObject.Properties["http_req_duration$tag"].Value
    $requestsMetric = $summary.metrics.PSObject.Properties["http_reqs$tag"].Value
    $serverErrorMetric = $summary.metrics.PSObject.Properties["server_error_rate$tag"].Value
    $timeoutMetric = $summary.metrics.PSObject.Properties["timeout_rate$tag"].Value
    if ($null -eq $failedMetric -or $null -eq $durationMetric -or $null -eq $requestsMetric `
        -or $null -eq $serverErrorMetric -or $null -eq $timeoutMetric) { continue }
    $failedRate = [double]$failedMetric.values.rate
    $p95 = [double]$durationMetric.values.'p(95)'
    $p99 = [double]$durationMetric.values.'p(99)'
    $serverErrorRate = [double]$serverErrorMetric.values.rate
    $timeoutRate = [double]$timeoutMetric.values.rate
    [PSCustomObject]@{
      VUs = [int]$stage.target
      QPS = [Math]::Round([double]$requestsMetric.values.rate, 1)
      FailedPercent = [Math]::Round($failedRate * 100, 3)
      ServerErrorPercent = [Math]::Round($serverErrorRate * 100, 3)
      TimeoutPercent = [Math]::Round($timeoutRate * 100, 3)
      P95ms = [Math]::Round($p95, 1)
      P99ms = [Math]::Round($p99, 1)
      Stable = $failedRate -lt 0.01 -and $serverErrorRate -lt 0.0005 `
        -and $timeoutRate -lt 0.0005 -and $p95 -lt 1200 -and $p99 -lt 3000
    }
  }
  $stageRows | Format-Table -AutoSize
  $maximumStable = $null
  foreach ($row in $stageRows) {
    if (-not $row.Stable) { break }
    $maximumStable = $row.VUs
  }
  $maximumStableLabel = if ($null -eq $maximumStable) { 'none' } else { "$maximumStable" }
  Write-Host "Maximum stable concurrency in this run: $maximumStableLabel VUs"
  Write-Host "Full k6 summary: $resolvedSummaryOut"
}

if ($k6ExitCode -ne 0) {
  throw "k6 finished with exit code $k6ExitCode because one or more thresholds failed."
}
