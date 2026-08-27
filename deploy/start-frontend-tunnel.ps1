$ErrorActionPreference = 'Stop'

$wranglerConfig = Join-Path $env:APPDATA 'xdg.config\.wrangler\config\default.toml'
$cloudflared = 'D:\Programs\cloudfare\cloudflared.exe'
$accountId = 'ee7ca83512f85910194ca3240e172e10'
$tunnelId = '470f8060-4896-45c0-bd08-211c1c08c2ce'

if (-not (Test-Path -LiteralPath $wranglerConfig)) {
    throw 'Wrangler login configuration was not found.'
}
if (-not (Test-Path -LiteralPath $cloudflared)) {
    throw 'cloudflared.exe was not found.'
}

$config = Get-Content -LiteralPath $wranglerConfig -Raw
$oauthMatch = [regex]::Match($config, '(?m)^oauth_token\s*=\s*"([^"]+)"')
if (-not $oauthMatch.Success) {
    throw 'Wrangler OAuth token was not found.'
}

$headers = @{ Authorization = "Bearer $($oauthMatch.Groups[1].Value)" }
$tokenReply = Invoke-RestMethod `
    -Uri "https://api.cloudflare.com/client/v4/accounts/$accountId/cfd_tunnel/$tunnelId/token" `
    -Headers $headers `
    -TimeoutSec 20
if (-not $tokenReply.success -or [string]::IsNullOrWhiteSpace($tokenReply.result)) {
    throw 'Unable to obtain the frontend tunnel token.'
}

$env:TUNNEL_TOKEN = $tokenReply.result
$env:TUNNEL_URL = 'http://127.0.0.1:5173'
& $cloudflared tunnel --loglevel info run
