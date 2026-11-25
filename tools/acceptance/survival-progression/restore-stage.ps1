[CmdletBinding()]
param([Parameter(Mandatory)] [string] $Stage, [Parameter(Mandatory)] [string] $ManifestPath)
$ErrorActionPreference = 'Stop'
$Stage = (Resolve-Path -LiteralPath $Stage).Path
$ManifestPath = (Resolve-Path -LiteralPath $ManifestPath).Path
$proof = Join-Path $Stage 'folia-proof'
if (-not $ManifestPath.StartsWith($proof + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Restoration manifest must be inside this stage folia-proof directory.'
}
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
if ([IO.Path]::GetFullPath($manifest.stage) -ne $Stage) { throw 'Restoration manifest belongs to another stage.' }
$properties = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $Stage 'server.properties')) {
    if ($line -match '^([^#!][^=]*)=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
}
if ($properties['server-ip'] -ne '127.0.0.1' -or $properties['online-mode'] -ne 'false') {
    throw 'Restoration requires an isolated loopback stage.'
}
foreach ($port in @([int]$properties['server-port'], [int]$properties['rcon.port'])) {
    if ($port -lt 1 -or $port -gt 65535) { throw 'Invalid acceptance listener port.' }
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "The stage is still listening on $port; stop it before restoring plugins."
    }
}
# An unbound JVM may still be starting. Its Windows command line normally includes
# only folia.jar, so stop for inspection if any such process exists.
if (Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object CommandLine -Match '(?i)\bfolia\.jar\b') {
    throw 'A Folia JVM is still running; identify and stop this stage before restoration.'
}
$backup = Split-Path -Parent $ManifestPath
$plugins = Join-Path $Stage 'plugins'
$entries = @(@{name='StarSupply-0.1.0.jar'; sha256=$manifest.original_starsupply_sha256}) + @($manifest.temporarily_removed_plugins)
$copies = @()
foreach ($entry in $entries) {
    if (-not $entry.name -or [IO.Path]::GetFileName($entry.name) -ne $entry.name -or $entry.name -notmatch '\.jar$' -or
        $entry.sha256 -notmatch '^[A-Fa-f0-9]{64}$') { throw 'Invalid plugin entry in restoration manifest.' }
    $saved = Join-Path $backup $entry.name
    $installed = Join-Path $plugins $entry.name
    $installedHash = if (Test-Path -LiteralPath $installed) { (Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash } else { $null }
    if ($installedHash -eq $entry.sha256) { continue }
    if ($installedHash -and ($entry.name -ne 'StarSupply-0.1.0.jar' -or
        $installedHash -notin @($manifest.test_jar_sha256, $manifest.source_jar_sha256))) {
        throw "Plugin changed independently after staging: $($entry.name); preserved for inspection."
    }
    if (-not (Test-Path -LiteralPath $saved) -or (Get-FileHash -LiteralPath $saved -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw "Original plugin backup is missing or changed: $($entry.name)"
    }
    $copies += @{source=$saved; destination=$installed}
}
# Validate every entry before changing any installed plugin.
foreach ($copy in $copies) { Copy-Item -LiteralPath $copy.source -Destination $copy.destination -Force }
foreach ($entry in $entries) {
    if ((Get-FileHash -LiteralPath (Join-Path $plugins $entry.name) -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw "Plugin restoration verification failed: $($entry.name)"
    }
}
$manifest.restoration = 'all_plugin_hashes_restored'
$manifest | Add-Member -MemberType NoteProperty -Name recovered_after_interruption -Value $true -Force
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ManifestPath -Encoding utf8
Write-Output "Interrupted stage restored; manifest: $ManifestPath"
