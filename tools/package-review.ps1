[CmdletBinding()]
param(
    [string] $StarCoreJar = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jar = Join-Path $root 'target\starsupply-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run .\mvnw.cmd verify first.' }
$jarItem = Get-Item -LiteralPath $jar
$mainSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\main') -Recurse -File |
    Where-Object { $_.Extension -in @('.java', '.sql', '.yml') })
$latestMainSource = ($mainSources | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
if ($jarItem.LastWriteTime -lt $latestMainSource) { throw 'The JAR is older than current production source. Run verify first.' }
$mainSchema = (Get-ChildItem -LiteralPath (Join-Path $root 'src\main\resources\db') -Filter 'v*.sql' |
    ForEach-Object { [int]($_.BaseName.Substring(1)) } | Measure-Object -Maximum).Maximum
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archiveReader = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $schemaEntry = $archiveReader.GetEntry("db/v$mainSchema.sql")
    if (-not $schemaEntry) { throw "The JAR does not contain schema v$mainSchema." }
    $streamReader = [System.IO.StreamReader]::new($schemaEntry.Open())
    try { $schemaSql = $streamReader.ReadToEnd() } finally { $streamReader.Dispose() }
    if ($schemaSql -notmatch "PRAGMA\s+user_version\s*=\s*$mainSchema\b") {
        throw "The packaged database migration does not declare schema v$mainSchema."
    }
} finally { $archiveReader.Dispose() }
$runtimePatch = 'docs/upstream/starcore-folia-logistics.patch'
$patchSha = (Get-FileHash -LiteralPath (Join-Path $root $runtimePatch) -Algorithm SHA256).Hash
$companionSha = $null
if ($StarCoreJar) {
    $StarCoreJar = (Resolve-Path -LiteralPath $StarCoreJar).Path
    $companionSha = (Get-FileHash -LiteralPath $StarCoreJar -Algorithm SHA256).Hash
}
$reports = Get-ChildItem -LiteralPath (Join-Path $root 'target\surefire-reports') -Filter 'TEST-*.xml'
if (-not $reports) { throw 'No test reports found. Run verify first.' }
$latestTestSource = (Get-ChildItem -LiteralPath (Join-Path $root 'src\test') -Recurse -File -Filter '*.java' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
$latestTestReport = ($reports | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
if ($latestTestReport -lt $latestTestSource) { throw 'Test reports are older than current tests. Run verify first.' }
$tests = 0; $failures = 0; $errors = 0; $skipped = 0
foreach ($report in $reports) {
    [xml]$suite = Get-Content -LiteralPath $report.FullName -Raw
    $tests += [int]$suite.testsuite.tests
    $failures += [int]$suite.testsuite.failures
    $errors += [int]$suite.testsuite.errors
    $skipped += [int]$suite.testsuite.skipped
}
if ($failures -or $errors) { throw 'Test reports contain failures or errors.' }
$dist = Join-Path $root 'target\dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$stage = Join-Path $dist ('review-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
Push-Location $root
try {
    $tracked = @(& git -c core.quotepath=false ls-files)
    if ($LASTEXITCODE -ne 0) { throw 'Git file listing failed.' }
    $untrackedSources = @(& git -c core.quotepath=false ls-files --others --exclude-standard | Where-Object {
        # Isolated acceptance compilers may leave unignored out/ and JAR files.
        # Include reviewable source, not the resulting probe binaries or caches.
        $isSource = $_ -match '^(src|docs|examples|tools)/' -and
            $_ -match '\.(java|sql|md|ps1|py|js|cjs|mjs|json|yml|yaml|xml|properties|toml|sh|cmd|bat|txt)$' -and
            $_ -notmatch '(^|/)(build|out|target|node_modules|__pycache__)/'
        $isSource -or $_ -in @('README.md', 'pom.xml', 'LICENSE')
    })
    if ($LASTEXITCODE -ne 0) { throw 'Git file listing failed.' }
    $sourceFiles = @($tracked + $untrackedSources) | Sort-Object -Unique
    $snapshot = [System.Text.StringBuilder]::new()
    foreach ($file in $sourceFiles) {
        $path = Join-Path $root $file
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Source file is missing: $file" }
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        [void] $snapshot.AppendLine("$file`t$hash")
        $destination = Join-Path $stage $file
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $path -Destination $destination
    }
    $commit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Git revision lookup failed.' }
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $sourceSnapshotSha = [System.BitConverter]::ToString($sha256.ComputeHash(
            [System.Text.Encoding]::UTF8.GetBytes($snapshot.ToString()))).Replace('-', '')
    } finally { $sha256.Dispose() }
} finally { Pop-Location }
$release = Join-Path $stage 'release'
New-Item -ItemType Directory -Path $release | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $release 'StarSupply-0.1.0.jar')
Copy-Item -LiteralPath (Join-Path $root 'src\main\resources\config.yml') -Destination (Join-Path $release 'config.yml')
if ($StarCoreJar) {
    Copy-Item -LiteralPath $StarCoreJar -Destination (Join-Path $release 'StarCore-1.0.0-compat.jar')
}
$manifest = [ordered]@{
    plugin = 'StarSupply'; version = '0.1.0'; source_commit = $commit
    source_snapshot_sha256 = $sourceSnapshotSha; source_file_count = $sourceFiles.Count
    source_snapshot_includes_worktree_content = $true
    java = 21; paper = '1.21.11'; starcore_commit = 'f769e62799e769a18fe51305b545869a17f80cd1'
    main_schema = $mainSchema; tavern_schema = 1; insurance_schema = 2
    jar_sha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
    test_reports = @{ tests=$tests; failures=$failures; errors=$errors; skipped=$skipped }
    runtime_evidence = @('docs/PAPER-ACCEPTANCE.md', 'docs/FOLIA.md')
    folia_full_stack_verified = $false
    starcore_runtime_patch = $runtimePatch
    starcore_runtime_patch_sha256 = $patchSha
    starcore_companion_jar_sha256 = $companionSha
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $release 'manifest.json') -Encoding utf8
$archive = Join-Path $dist 'StarSupply-0.1.0-review.zip'
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $archive -Force
Copy-Item -LiteralPath (Join-Path $release 'StarSupply-0.1.0.jar') -Destination (Join-Path $dist 'StarSupply-0.1.0.jar') -Force
Copy-Item -LiteralPath (Join-Path $release 'manifest.json') -Destination (Join-Path $dist 'manifest.json') -Force
Get-FileHash -LiteralPath $archive -Algorithm SHA256
Write-Output "Review sources: $stage"
