[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jar = Join-Path $root 'target\starsupply-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run .\mvnw.cmd verify first.' }
$reports = Get-ChildItem -LiteralPath (Join-Path $root 'target\surefire-reports') -Filter 'TEST-*.xml'
if (-not $reports) { throw 'No test reports found. Run verify first.' }
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
    & git diff --exit-code --quiet HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Commit tracked source changes before packaging a reproducible review.' }
    $tracked = & git -c core.quotepath=false ls-files
    if ($LASTEXITCODE -ne 0) { throw 'Git file listing failed.' }
    foreach ($file in $tracked) {
        $destination = Join-Path $stage $file
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $root $file) -Destination $destination
    }
    $commit = (& git rev-parse HEAD).Trim()
} finally { Pop-Location }
$release = Join-Path $stage 'release'
New-Item -ItemType Directory -Path $release | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $release 'StarSupply-0.1.0.jar')
Copy-Item -LiteralPath (Join-Path $root 'src\main\resources\config.yml') -Destination (Join-Path $release 'config.yml')
$manifest = [ordered]@{
    plugin = 'StarSupply'; version = '0.1.0'; source_commit = $commit
    java = 21; paper = '1.21.11'; starcore_commit = 'f769e62799e769a18fe51305b545869a17f80cd1'
    main_schema = 8; tavern_schema = 1; insurance_schema = 2
    jar_sha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
    test_reports = @{ tests=$tests; failures=$failures; errors=$errors; skipped=$skipped }
    runtime_evidence = 'docs/PAPER-ACCEPTANCE.md'
    folia_full_stack_verified = $false
    upstream_siege_fix = 'docs/upstream/starcore-siege-treasury.patch'
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $release 'manifest.json') -Encoding utf8
$archive = Join-Path $dist 'StarSupply-0.1.0-review.zip'
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $archive -Force
Copy-Item -LiteralPath (Join-Path $release 'StarSupply-0.1.0.jar') -Destination (Join-Path $dist 'StarSupply-0.1.0.jar') -Force
Copy-Item -LiteralPath (Join-Path $release 'manifest.json') -Destination (Join-Path $dist 'manifest.json') -Force
Get-FileHash -LiteralPath $archive -Algorithm SHA256
Write-Output "Review sources: $stage"
