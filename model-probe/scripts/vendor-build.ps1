# model-probe vendored engine build script
# Clones the upstream BazaarLink LLMprobe-engine, builds it, and copies the
# CommonJS dist output (plus its runtime package metadata) into ./vendor.
#
# The vendored runtime only needs the `dist/` tree + package.json. The probe
# engine's `runProbes()` API has no runtime npm dependencies (it uses Node's
# built-in fetch), so we do not copy node_modules.

param(
  [string]$Upstream = "https://github.com/Bazaarlinkorg/LLMprobe-engine.git",
  [string]$Ref = "main"
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$vendorDir = Join-Path $root "vendor\llmprobe-engine"
$tmp = Join-Path $env:TEMP "llmprobe-engine-build"

if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

Write-Host "[vendor] cloning $Upstream (ref: $Ref) ..."
git clone --depth 1 --branch $Ref $Upstream $tmp 2>$null
if ($LASTEXITCODE -ne 0) {
  git clone --depth 1 $Upstream $tmp 2>$null
}

Write-Host "[vendor] installing dependencies ..."
Push-Location $tmp
npm install 2>&1 | Out-Null
Write-Host "[vendor] building dist ..."
npm run build 2>&1 | Out-Null
Pop-Location

if (-not (Test-Path (Join-Path $tmp "dist\index.js"))) {
  throw "Build failed: dist/index.js not produced."
}

# Recreate the vendored directory
if (Test-Path $vendorDir) { Remove-Item -Recurse -Force $vendorDir }
New-Item -ItemType Directory -Force -Path $vendorDir | Out-Null

# Copy dist + runtime package metadata
Copy-Item (Join-Path $tmp "dist") (Join-Path $vendorDir "dist") -Recurse
Copy-Item (Join-Path $tmp "package.json") (Join-Path $vendorDir "package.json")
Copy-Item (Join-Path $tmp "LICENSE") (Join-Path $vendorDir "LICENSE") -ErrorAction SilentlyContinue

# Record the upstream commit for provenance
$commit = (git -C $tmp rev-parse HEAD 2>$null)
if (-not $commit) { $commit = "unknown" }
@"
# vendored LLMprobe-engine
Upstream : $Upstream
Ref      : $Ref
Commit   : $commit
BuiltAt  : $(Get-Date -Format o)
"@ | Set-Content (Join-Path $vendorDir "PROVENANCE.txt")

Write-Host "[probe] done -> $vendorDir (commit $commit)"

# Clean up
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue