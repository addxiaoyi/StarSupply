[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $StarCoreJar,
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $MavenRepo = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2\repository')
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { throw 'Pass -JavaHome or set JAVA_HOME to Java 21.' }
$coreJar = (Resolve-Path -LiteralPath $StarCoreJar).Path
$paperApi = Join-Path $MavenRepo 'io\papermc\paper\paper-api\1.21.11-R0.1-SNAPSHOT\paper-api-1.21.11-R0.1-SNAPSHOT.jar'
$support = @(
    (Join-Path $MavenRepo 'net\kyori\adventure-api\4.26.1\adventure-api-4.26.1.jar'),
    (Join-Path $MavenRepo 'net\kyori\adventure-key\4.26.1\adventure-key-4.26.1.jar'),
    (Join-Path $MavenRepo 'net\kyori\examination-api\1.3.0\examination-api-1.3.0.jar')
)
$support += Get-ChildItem -LiteralPath (Join-Path $MavenRepo 'net\md-5\bungeecord-chat') -Filter '*.jar' -Recurse |
    Select-Object -ExpandProperty FullName
foreach ($dependency in (@($paperApi, $coreJar) + $support)) {
    if (-not (Test-Path -LiteralPath $dependency)) { throw "Missing dependency: $dependency" }
}
$classpath = (@($paperApi, $coreJar) + $support) -join [IO.Path]::PathSeparator
$output = Join-Path $PSScriptRoot 'build\classes'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') '-J-Xmx192m' '-J-XX:ActiveProcessorCount=1' '--release' '21' '-proc:none' `
    '-cp' $classpath '-d' $output (Join-Path $PSScriptRoot 'SupplyTradeProof.java')
if ($LASTEXITCODE -ne 0) { throw 'Trade proof compilation failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'plugin.yml') -Destination (Join-Path $output 'plugin.yml') -Force
$artifact = Join-Path $PSScriptRoot 'build\SupplyTradeProof.jar'
& (Join-Path $JavaHome 'bin\jar.exe') '-J-Xmx96m' '--create' '--file' $artifact '-C' $output '.'
if ($LASTEXITCODE -ne 0) { throw 'Trade proof packaging failed.' }
Get-FileHash -LiteralPath $artifact -Algorithm SHA256
