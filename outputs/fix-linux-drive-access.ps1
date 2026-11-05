$ErrorActionPreference = 'Stop'
$logPath = 'D:\youtube-content-intelligence\outputs\linux-drive-access-fix.log'
try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Administrator privileges are required to change Windows hibernation settings.'
    }
    & "$env:SystemRoot\System32\powercfg.exe" /hibernate off
    if ($LASTEXITCODE -ne 0) { throw 'powercfg could not disable hibernation.' }
    Set-ItemProperty -LiteralPath 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power' -Name HiberbootEnabled -Type DWord -Value 0
    $result = @('SUCCESS: Hibernation disabled; Fast Startup disabled.', '', 'Current sleep capabilities:')
    $result += & "$env:SystemRoot\System32\powercfg.exe" /a
    $result += @('', 'D: volume dirty-bit status:')
    $result += & "$env:SystemRoot\System32\fsutil.exe" dirty query D:
    $result += @('', 'No shutdown, restart, filesystem repair, or ACL changes were performed.')
    $result | Set-Content -LiteralPath $logPath -Encoding UTF8
} catch {
    ('FAILED: ' + $_.Exception.Message) | Set-Content -LiteralPath $logPath -Encoding UTF8
    exit 1
}
