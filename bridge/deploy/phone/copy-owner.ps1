$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$line = @(Get-Content -LiteralPath (Join-Path $dir 'config.env') | Where-Object { $_ -like 'LIANXU_OWNER_TOKEN=*' })
if ($line.Count -ne 1) { throw 'Owner token entry is missing or duplicated.' }
$token = $line[0].Substring('LIANXU_OWNER_TOKEN='.Length)
if ($token.Length -lt 32) { throw 'Invalid owner token.' }
Set-Clipboard -Value $token
Write-Output 'Phone owner token copied to clipboard.'
