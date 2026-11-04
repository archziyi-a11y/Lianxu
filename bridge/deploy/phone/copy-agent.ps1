$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$token = (Get-Content -Raw -LiteralPath (Join-Path $dir 'agent.token')).Trim()
if ($token.Length -lt 32) { throw 'Invalid agent token.' }
Set-Clipboard -Value $token
Write-Output 'Phone agent token copied to clipboard.'
