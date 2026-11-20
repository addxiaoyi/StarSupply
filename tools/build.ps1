[CmdletBinding()]
param(
    [Parameter()]
    [string] $StarCoreRoot = '',
    [Parameter()]
    [string] $Maven = '',
    [Parameter()]
    [string] $JavaHome = '',
    [Parameter()]
    [switch] $SkipStarCore
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($StarCoreRoot)) {
    $StarCoreRoot = Join-Path $root '..\starsupply-deps\starcore'
}
$starCore = (Resolve-Path $StarCoreRoot).Path
if ([string]::IsNullOrWhiteSpace($Maven)) {
    $Maven = Join-Path $root 'mvnw.cmd'
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $JavaHome = $env:JAVA_HOME
    } else {
        $java = (Get-Command java.exe -ErrorAction Stop).Source
        $JavaHome = Split-Path (Split-Path $java -Parent) -Parent
    }
}

$mavenPath = (Resolve-Path $Maven).Path
$javaPath = (Resolve-Path $JavaHome).Path
if (-not (Test-Path (Join-Path $javaPath 'bin\java.exe'))) {
    throw "Java 21 was not found at $javaPath"
}
if (-not (Test-Path (Join-Path $starCore 'pom.xml'))) {
    throw "StarCore pom.xml was not found at $starCore"
}
if (-not (Test-Path (Join-Path $root 'pom.xml'))) {
    throw "StarSupply pom.xml was not found at $root"
}

$env:JAVA_HOME = $javaPath
$env:MAVEN_OPTS = '-Xms128m -Xmx768m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC'
$common = @('-B', '-ntp')

if (-not $SkipStarCore) {
    Push-Location $starCore
    try {
        $commit = (& git rev-parse HEAD).Trim()
        if ($commit -ne 'f769e62799e769a18fe51305b545869a17f80cd1') {
            throw "StarCore checkout is $commit; expected the pinned public API revision."
        }
        & $mavenPath @common '-Dmaven.test.skip=true' '-Dspotless.skip=true' 'install'
        if ($LASTEXITCODE -ne 0) { throw "StarCore install failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

Push-Location $root
try {
    & $mavenPath @common 'verify'
    if ($LASTEXITCODE -ne 0) { throw "StarSupply verify failed with exit code $LASTEXITCODE" }
    Get-FileHash (Join-Path $root 'target\starsupply-0.1.0.jar') -Algorithm SHA256
} finally {
    Pop-Location
}
