# Run interactively from your own Windows terminal after enabling QQ SMTP and
# obtaining a dedicated app authorization code. Never paste the code into chat.
[CmdletBinding()]
param(
    [string]$HostName = '207.57.122.109',
    [string]$KeyPath = "$HOME/.ssh/linknux_admin_ed25519",
    [string]$RecipientAddress = ''
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $KeyPath)) { throw "Admin key not found: $KeyPath" }
$emailPattern = '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'
$sender = (Read-Host 'QQ SMTP sender address (example: 123456@qq.com, no backslash)').Trim()
if ($sender -notmatch $emailPattern) {
    throw 'Invalid sender email. Re-run with the full QQ address (e.g. 123456@qq.com); do not type a backslash before @.'
}
Write-Host 'Sender email accepted.'
# Default to the validated sender. Avoid a second interactive prompt that may
# receive pasted/masked characters instead of an actual blank Enter.
$recipient = if ($RecipientAddress.Trim()) { $RecipientAddress.Trim() } else { $sender }
if ($recipient -notmatch $emailPattern) {
    throw 'Invalid -RecipientAddress. Supply a full email address or omit the parameter to use the sender.'
}
Write-Host 'Alert recipient is set to the sender unless -RecipientAddress was provided; connecting to server.'
$sshArgs = @('-i', $KeyPath, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', "linknux-admin@$HostName")
function Remote([string]$command) {
    & ssh @sshArgs $command
    if ($LASTEXITCODE -ne 0) { throw "Remote command failed (exit $LASTEXITCODE)" }
}
function RemoteInput([string]$text, [string]$command) {
    $text | & ssh @sshArgs $command
    if ($LASTEXITCODE -ne 0) { throw "Remote input failed (exit $LASTEXITCODE)" }
}

Remote 'sudo test ! -e /etc/fail2ban/jail.d/90-linknux-mail.local && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq msmtp-mta && sudo install -d -o root -g root -m 0700 /etc/linknux-security'
$secret = Read-Host 'QQ SMTP authorization code (not your QQ login password)' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    if (-not $plain -or $plain -match '[\r\n]' -or $plain.Trim().Length -lt 12) { throw 'This is too short for a QQ SMTP authorization code. Use the generated mail-client authorization code, not the SMS verification code.' }
    $plain = $plain.Trim()
    RemoteInput $plain "sudo sh -c 'umask 077; cat > /etc/linknux-security/smtp-password'"
} finally {
    $plain = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $secret.Dispose()
}
$config = @"
defaults
auth on
tls on
tls_starttls on
tls_trust_file /etc/ssl/certs/ca-certificates.crt
account linknux
host smtp.qq.com
port 587
from $sender
user $sender
passwordeval "cat /etc/linknux-security/smtp-password"
account default : linknux
"@
RemoteInput $config "sudo sh -c 'umask 077; cat > /etc/msmtprc'"
Remote 'sudo chmod 0600 /etc/msmtprc /etc/linknux-security/smtp-password; sudo chown root:root /etc/msmtprc /etc/linknux-security/smtp-password; command -v sendmail >/dev/null'
try {
    RemoteInput "To: $recipient`nFrom: $sender`nSubject: Linknux security alert test`n`nSMTP test; no ban has been enabled for mail yet." 'sudo sendmail -t'
} catch {
    Remote 'sudo rm -f /etc/msmtprc /etc/linknux-security/smtp-password'
    throw 'SMTP test failed. No email alert was enabled, and the unverified authorization code was removed. Confirm QQ SMTP is enabled and use its generated authorization code.'
}
if ((Read-Host 'Did the test message arrive? Type YES to enable ban emails') -cne 'YES') {
    Remote 'sudo rm -f /etc/msmtprc /etc/linknux-security/smtp-password'
    Write-Output 'SMTP accepted the message, but mail alerts were NOT enabled without delivery confirmation; sender configuration was removed.'
    return
}
$mailJail = @"
[DEFAULT]
destemail = $recipient
sender = $sender
mta = sendmail
action = %(action_mw)s
"@
RemoteInput $mailJail "sudo sh -c 'umask 077; cat > /etc/fail2ban/jail.d/90-linknux-mail.local'"
try {
    Remote 'sudo fail2ban-client -t && sudo fail2ban-client reload --restart'
    Remote 'sudo fail2ban-client get linknux-probes actions | grep -q sendmail-whois && sudo fail2ban-client get sshd actions | grep -q sendmail-whois'
    Remote 'sudo fail2ban-client set linknux-probes unbanip 192.0.2.42 >/dev/null 2>&1 || true'
    Remote 'sudo fail2ban-client set linknux-probes banip 192.0.2.42; sudo fail2ban-client set linknux-probes unbanip 192.0.2.42'
    if ((Read-Host 'Did the [Fail2Ban] linknux-probes BAN email arrive (not the started email)? Type YES to retain') -cne 'YES') {
        throw 'Ban email not confirmed'
    }
    Write-Output 'Mail alerts enabled and tested. Ban events trigger mail; Fail2ban may also send jail start/stop notifications.'
} catch {
    $failure = $_.Exception.Message
    Remote 'sudo rm -f /etc/fail2ban/jail.d/90-linknux-mail.local /etc/msmtprc /etc/linknux-security/smtp-password; sudo fail2ban-client reload --restart'
    throw "Mail alert activation was rolled back; bans remain active. Cause: $failure"
}
