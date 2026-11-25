[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $SourceStage,
    [Parameter(Mandatory)] [string] $Stage,
    [Parameter(Mandatory)] [string] $StarCoreJar,
    [Parameter(Mandatory)] [string] $MineflayerModule,
    [string] $Player = 'SupplyBuyer',
    [int] $GamePort = 25749,
    [int] $RconPort = 25759
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$SourceStage = (Resolve-Path -LiteralPath $SourceStage).Path
$Stage = [IO.Path]::GetFullPath($Stage)
if (Test-Path -LiteralPath $Stage) { throw 'Destination must be a new isolated stage.' }
if (-not $Stage.StartsWith('C:\tmp\starsupply-', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The fixture stage must be under C:\tmp\starsupply-...'
}
$properties = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $SourceStage 'server.properties')) {
    if ($line -match '^([^#!][^=]*)=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
}
if ($properties['server-ip'] -ne '127.0.0.1' -or $properties['online-mode'] -ne 'false') {
    throw 'Source must be a loopback offline acceptance stage.'
}
foreach ($port in @([int]$properties['server-port'], [int]$properties['rcon.port'], $GamePort, $RconPort)) {
    if ($port -lt 1024 -or $port -gt 65535 -or (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) {
        throw "Acceptance port must be valid and stopped: $port"
    }
}
New-Item -ItemType Directory -Path $Stage | Out-Null
foreach ($name in @('cache', 'libraries', 'versions', 'config', $properties['level-name'],
        ($properties['level-name'] + '_nether'), ($properties['level-name'] + '_the_end'))) {
    Copy-Item -LiteralPath (Join-Path $SourceStage $name) -Destination (Join-Path $Stage $name) -Recurse
}
foreach ($name in @('folia.jar', 'server.properties', 'eula.txt', 'bukkit.yml', 'spigot.yml', 'ops.json',
        'whitelist.json', 'banned-ips.json', 'banned-players.json', 'usercache.json')) {
    if (Test-Path -LiteralPath (Join-Path $SourceStage $name)) {
        Copy-Item -LiteralPath (Join-Path $SourceStage $name) -Destination (Join-Path $Stage $name)
    }
}
$plugins = Join-Path $Stage 'plugins'
New-Item -ItemType Directory -Path $plugins | Out-Null
foreach ($name in @('StarCore', 'StarSupply')) {
    Copy-Item -LiteralPath (Join-Path $SourceStage ('plugins\' + $name)) -Destination (Join-Path $plugins $name) -Recurse
}
Copy-Item -LiteralPath $StarCoreJar -Destination (Join-Path $plugins 'StarCore-1.0.0.jar')
$coreSourceHash = (Get-FileHash -LiteralPath $StarCoreJar).Hash
$stagedJar = Join-Path $plugins 'StarSupply-0.1.0.jar'
Copy-Item -LiteralPath (Join-Path $root 'target\starsupply-0.1.0.jar') -Destination $stagedJar
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($testJar in @($stagedJar, (Join-Path $plugins 'StarCore-1.0.0.jar'))) {
    $archive = [System.IO.Compression.ZipFile]::Open($testJar, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $reader = [IO.StreamReader]::new($archive.GetEntry('plugin.yml').Open())
        try { $descriptor = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($descriptor -notmatch '(?m)^folia-supported:\s*true\s*$') {
            if ($descriptor -match '(?m)^folia-supported:') { throw 'Unexpected Folia descriptor entry.' }
            $archive.GetEntry('plugin.yml').Delete()
            $writer = [IO.StreamWriter]::new($archive.CreateEntry('plugin.yml').Open(), [Text.UTF8Encoding]::new($false))
            try { $writer.Write($descriptor + "`nfolia-supported: true`n") } finally { $writer.Dispose() }
        }
    } finally { $archive.Dispose() }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build\SupplyRetirementProof-1.0.0.jar') -Destination $plugins
$propertiesFile = Join-Path $Stage 'server.properties'
$text = [IO.File]::ReadAllText($propertiesFile) -replace '(?m)^server-port=.*$', "server-port=$GamePort" `
    -replace '(?m)^rcon.port=.*$', "rcon.port=$RconPort"
[IO.File]::WriteAllText($propertiesFile, $text, [Text.UTF8Encoding]::new($false))
$proof = Join-Path $Stage 'folia-proof'
New-Item -ItemType Directory -Path $proof | Out-Null
Set-Content -LiteralPath (Join-Path $proof 'allow-region-retirement-fixture') -Value 'Isolated copied stage; controlled failure injection, not survival acquisition.'
& node (Join-Path $PSScriptRoot 'prepare-player.cjs') $Stage $MineflayerModule $Player
if ($LASTEXITCODE -ne 0) { throw 'Copied player recipe preparation failed.' }
[ordered]@{
    source_stage=$SourceStage; stage=$Stage; created=(Get-Date).ToString('o')
    source_starsupply_sha256=(Get-FileHash -LiteralPath (Join-Path $root 'target\starsupply-0.1.0.jar')).Hash
    test_starsupply_sha256=(Get-FileHash -LiteralPath $stagedJar).Hash
    source_starcore_sha256=$coreSourceHash
    test_starcore_sha256=(Get-FileHash -LiteralPath (Join-Path $plugins 'StarCore-1.0.0.jar')).Hash
    fixture_sha256=(Get-FileHash -LiteralPath (Join-Path $plugins 'SupplyRetirementProof-1.0.0.jar')).Hash
    test_descriptor_only=$true; source_stage_unchanged=$true
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $proof 'stage-manifest.json') -Encoding utf8
Write-Output "Isolated region-retirement stage prepared: $Stage"
