[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Stage,
    [Parameter(Mandatory)] [string] $JavaHome,
    [Parameter(Mandatory)] [string] $MineflayerModule,
    [Parameter(Mandatory)] [string] $PathfinderModule,
    [ValidateSet('full', 'vanilla')] [string] $Profile = 'full',
    [string] $Heap = '',
    [string] $Player = 'SupplyCook',
    [string] $ClientScript = '',
    [int] $ClientTimeoutMs = 600000,
    [string[]] $ClientArguments = @()
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$Stage = (Resolve-Path -LiteralPath $Stage).Path
$sourceJar = Join-Path $root 'target\starsupply-0.1.0.jar'
$java = Join-Path $JavaHome 'bin\java.exe'
foreach ($required in @($sourceJar, $java, $MineflayerModule, $PathfinderModule)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing acceptance input: $required" }
}
$properties = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $Stage 'server.properties')) {
    if ($line -match '^([^#!][^=]*)=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
}
if ($properties['server-ip'] -ne '127.0.0.1' -or $properties['online-mode'] -ne 'false') {
    throw 'Only a loopback-bound offline acceptance stage may be modified.'
}
function Assert-StageStopped {
    foreach ($port in @([int]$properties['server-port'], [int]$properties['rcon.port'])) {
        if ($port -lt 1 -or $port -gt 65535) { throw 'Invalid acceptance listener port.' }
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
            throw "The isolated stage is still listening on $port; plugin files were not restored or changed."
        }
    }
}
Assert-StageStopped
# A terminated launcher cannot execute its finally block. Recover its exact
# original files before creating another baseline or removing more plugins.
foreach ($pending in @(Get-ChildItem -Path (Join-Path $Stage 'folia-proof\current-build-*\manifest.json') -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending)) {
    $previous = Get-Content -LiteralPath $pending.FullName -Raw | ConvertFrom-Json
    if ($previous.restoration -eq 'pending') {
        & (Join-Path $PSScriptRoot 'restore-stage.ps1') -Stage $Stage -ManifestPath $pending.FullName
    }
}
if (-not $Heap) { $Heap = if ($Profile -eq 'vanilla') { '384M' } else { '1024M' } }
$plugins = Join-Path $Stage 'plugins'
$stagedJar = Join-Path $plugins 'StarSupply-0.1.0.jar'
$backup = Join-Path $Stage ('folia-proof\current-build-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $backup | Out-Null
Copy-Item -LiteralPath $stagedJar -Destination (Join-Path $backup 'StarSupply-0.1.0.jar')
$originalHash = (Get-FileHash -LiteralPath $stagedJar -Algorithm SHA256).Hash
$optional = @()
if ($Profile -eq 'vanilla') {
    $retainedPlugins = @('StarCore-1.0.0.jar', 'StarSupply-0.1.0.jar')
    if ($ClientArguments.Count -ge 2 -and $ClientArguments[0] -in @('supply-plan', 'logistics', 'logistics-replay',
            'mining', 'surface', 'farming', 'honey', 'scaffolding', 'fireworks')) {
        $logisticsPlan = Get-Content -LiteralPath $ClientArguments[1] -Raw | ConvertFrom-Json
        if ($ClientArguments[0] -eq 'supply-plan' -or $logisticsPlan.nativeReplay -eq $true -or $logisticsPlan.demandBudget) {
            $retainedPlugins += 'SupplyNativeProof-1.0.0.jar'
        }
    }
    $optional = @(Get-ChildItem -LiteralPath $plugins -File -Filter '*.jar' |
        Where-Object Name -notin $retainedPlugins |
        ForEach-Object { @{ name=$_.Name; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } })
}
$manifest = [ordered]@{
    profile=$Profile; heap=$Heap; stage=$Stage; player=$Player
    original_starsupply_sha256=$originalHash
    source_jar_sha256=(Get-FileHash -LiteralPath $sourceJar -Algorithm SHA256).Hash
    temporarily_removed_plugins=$optional
    restoration='pending'
}
$manifestPath = Join-Path $backup 'manifest.json'
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8
$acceptanceExit = 1
$oldHeap = $env:STARSUPPLY_ACCEPTANCE_HEAP
try {
    foreach ($entry in $optional) {
        Move-Item -LiteralPath (Join-Path $plugins $entry.name) -Destination (Join-Path $backup $entry.name)
    }
    Copy-Item -LiteralPath $sourceJar -Destination $stagedJar -Force
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open($stagedJar, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $archive.GetEntry('plugin.yml').Delete()
        $descriptor = [System.IO.File]::ReadAllText((Join-Path $root 'src\main\resources\plugin.yml'))
        if ($descriptor -match '(?m)^folia-supported:') { throw 'Expected the production Paper descriptor.' }
        $writer = [System.IO.StreamWriter]::new($archive.CreateEntry('plugin.yml').Open(),
            [System.Text.UTF8Encoding]::new($false))
        try { $writer.Write($descriptor + "`nfolia-supported: true`n") } finally { $writer.Dispose() }
    } finally { $archive.Dispose() }
    $manifest['test_jar_sha256'] = (Get-FileHash -LiteralPath $stagedJar -Algorithm SHA256).Hash
    Copy-Item -LiteralPath $stagedJar -Destination (Join-Path $backup 'StarSupply-test.jar')
    # ZipArchive Update expands the shaded JAR in this long-lived launcher. The
    # disposed archive is no longer needed while Java and Mineflayer are running.
    $manifest['launcher_managed_bytes_before_collection'] = [GC]::GetTotalMemory($false)
    $archive = $null
    $writer = $null
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
    [GC]::Collect()
    $manifest['launcher_managed_bytes_after_collection'] = [GC]::GetTotalMemory($true)
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    $env:STARSUPPLY_ACCEPTANCE_HEAP = $Heap
    $runArguments = @((Join-Path $PSScriptRoot 'run.cjs'), $Stage, $MineflayerModule, $PathfinderModule, $java, $Player)
    if ($ClientScript) {
        $runArguments += @((Resolve-Path -LiteralPath $ClientScript).Path, [string]$ClientTimeoutMs,
            (ConvertTo-Json -InputObject @($ClientArguments) -Compress))
    }
    & node @runArguments
    $acceptanceExit = $LASTEXITCODE
} finally {
    $env:STARSUPPLY_ACCEPTANCE_HEAP = $oldHeap
    # A timed-out server must be stopped before any JAR restoration.
    Assert-StageStopped
    Copy-Item -LiteralPath (Join-Path $backup 'StarSupply-0.1.0.jar') -Destination $stagedJar -Force
    foreach ($entry in $optional) {
        $saved = Join-Path $backup $entry.name
        if (Test-Path -LiteralPath $saved) { Move-Item -LiteralPath $saved -Destination (Join-Path $plugins $entry.name) }
        if ((Get-FileHash -LiteralPath (Join-Path $plugins $entry.name) -Algorithm SHA256).Hash -ne $entry.sha256) {
            throw ('Optional plugin restoration failed: ' + $entry.name)
        }
    }
    if ((Get-FileHash -LiteralPath $stagedJar -Algorithm SHA256).Hash -ne $originalHash) {
        throw 'Original StarSupply JAR restoration failed.'
    }
    $manifest['restoration'] = 'all_plugin_hashes_restored'
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    Write-Output "Isolated plugin files restored; manifest: $manifestPath"
}
exit $acceptanceExit
