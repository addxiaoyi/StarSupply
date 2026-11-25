$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$fixture = Join-Path $root ('target\restoration-test-' + [guid]::NewGuid().ToString('N'))
$plugins = Join-Path $fixture 'plugins'
$backup = Join-Path $fixture 'folia-proof\current-build-test'
New-Item -ItemType Directory -Path $plugins,$backup -Force | Out-Null
'server-ip=127.0.0.1', 'online-mode=false', 'server-port=25729', 'rcon.port=25739' |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties')
# Only this test scope substitutes the Windows process/listener inventory.
function Get-NetTCPConnection { param($State,$LocalPort) if ($global:StarSupplyRestorationTestListening) { [pscustomobject]@{LocalPort=$LocalPort} } }
function Get-CimInstance { param($ClassName,$Filter) if ($global:StarSupplyRestorationTestRunning) { [pscustomobject]@{CommandLine='java -jar folia.jar'} } }
function Hash($file) { (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash }
function Assert-Fails($pattern, [scriptblock] $action) {
    $failed = $false
    try { & $action } catch { $failed = $true; if ($_.Exception.Message -notmatch $pattern) { throw } }
    if (-not $failed) { throw "Expected failure: $pattern" }
}
$original = Join-Path $backup 'StarSupply-0.1.0.jar'
$optional = Join-Path $backup 'Optional.jar'
$installed = Join-Path $plugins 'StarSupply-0.1.0.jar'
$manifestPath = Join-Path $backup 'manifest.json'
Set-Content -LiteralPath $original -Value 'original business plugin'
Set-Content -LiteralPath $optional -Value 'original optional plugin'
Set-Content -LiteralPath $installed -Value 'temporary test plugin'
$manifest = @{
    stage=$fixture; restoration='pending'; original_starsupply_sha256=(Hash $original)
    test_jar_sha256=(Hash $installed); source_jar_sha256=('0' * 64)
    temporarily_removed_plugins=@(@{name='Optional.jar';sha256=(Hash $optional)})
}
function Save-Manifest { $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath }
Save-Manifest
$global:StarSupplyRestorationTestListening = $true
Assert-Fails 'still listening' { & "$PSScriptRoot\restore-stage.ps1" $fixture $manifestPath }
$global:StarSupplyRestorationTestListening = $false
$global:StarSupplyRestorationTestRunning = $true
Assert-Fails 'Folia JVM is still running' { & "$PSScriptRoot\restore-stage.ps1" $fixture $manifestPath }
$global:StarSupplyRestorationTestRunning = $false
Set-Content -LiteralPath (Join-Path $plugins 'Optional.jar') -Value 'independent newer plugin'
Assert-Fails 'changed independently' { & "$PSScriptRoot\restore-stage.ps1" $fixture $manifestPath }
if ((Hash $installed) -ne $manifest.test_jar_sha256) { throw 'Failed preflight modified the first plugin' }
Copy-Item -LiteralPath $optional -Destination (Join-Path $plugins 'Optional.jar') -Force
$manifest.temporarily_removed_plugins[0].name = '..\outside.jar'
Save-Manifest
Assert-Fails 'Invalid plugin entry' { & "$PSScriptRoot\restore-stage.ps1" $fixture $manifestPath }
$manifest.temporarily_removed_plugins[0].name = 'Optional.jar'
Save-Manifest
Set-Content -LiteralPath $original -Value 'corrupt original backup'
Assert-Fails 'backup is missing or changed' { & "$PSScriptRoot\restore-stage.ps1" $fixture $manifestPath }
Set-Content -LiteralPath $original -Value 'original business plugin'
& "$PSScriptRoot\restore-stage.ps1" $fixture $manifestPath
if ((Hash $installed) -ne $manifest.original_starsupply_sha256) { throw 'Restoration hash mismatch' }
& "$PSScriptRoot\restore-stage.ps1" $fixture $manifestPath
$saved = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($saved.restoration -ne 'all_plugin_hashes_restored' -or -not $saved.recovered_after_interruption) { throw 'Missing recovery record' }
Write-Output 'PASS: 7 stage restoration checks (listeners, starting JVM, independent edits, path escape, corrupt backup, exact restoration, repeated recovery).'
Remove-Variable -Scope Global -Name StarSupplyRestorationTestListening,StarSupplyRestorationTestRunning
