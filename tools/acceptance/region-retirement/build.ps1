[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $StarCoreJar,
    [Parameter(Mandatory)] [string] $StarSupplyJar,
    [Parameter(Mandatory)] [string] $JavaHome,
    [string] $MavenRepo = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2\repository')
)
$ErrorActionPreference = 'Stop'
$dependencies = @((Resolve-Path -LiteralPath $StarCoreJar).Path,
    (Resolve-Path -LiteralPath $StarSupplyJar).Path,
    (Join-Path $MavenRepo 'io\papermc\paper\paper-api\1.21.11-R0.1-SNAPSHOT\paper-api-1.21.11-R0.1-SNAPSHOT.jar'))
foreach ($support in @('net\kyori', 'net\md-5')) {
    $dependencies += Get-ChildItem -LiteralPath (Join-Path $MavenRepo $support) -Recurse -Filter '*.jar' |
        Select-Object -ExpandProperty FullName
}
foreach ($file in $dependencies) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing dependency: $file" }
}
$classes = Join-Path $PSScriptRoot 'build\classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') '-J-Xmx128m' '-J-XX:ActiveProcessorCount=1' '--release' '21' '-encoding' 'UTF-8' '-proc:none' `
    '-cp' ($dependencies -join [IO.Path]::PathSeparator) '-d' $classes (Join-Path $PSScriptRoot 'src\local\test\SupplyRetirementProof.java')
if ($LASTEXITCODE -ne 0) { throw 'Region retirement fixture compilation failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'plugin.yml') -Destination (Join-Path $classes 'plugin.yml') -Force
$jar = Join-Path $PSScriptRoot 'build\SupplyRetirementProof-1.0.0.jar'
& (Join-Path $JavaHome 'bin\jar.exe') '-J-Xmx96m' '--create' '--file' $jar '-C' $classes '.'
if ($LASTEXITCODE -ne 0) { throw 'Region retirement fixture packaging failed.' }
Get-FileHash -LiteralPath $jar -Algorithm SHA256 | Format-List Hash,Path
