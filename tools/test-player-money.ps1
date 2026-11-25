[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $StarCoreJar,
    [string] $JavaHome = $env:JAVA_HOME,
    [switch] $Offline
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$companion = (Resolve-Path -LiteralPath $StarCoreJar).Path
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JavaHome to a Java 21 installation.'
}
$compiled = Join-Path $root 'target/test-classes/dev/starsupply/integration/CorePlayerMoneyTest.class'
if (-not (Test-Path -LiteralPath $compiled)) { throw 'Run mvnw verify against the pinned Core dependency first.' }
$newestSource = (Get-ChildItem -LiteralPath (Join-Path $root 'src') -Recurse -File -Filter '*.java' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
if ((Get-Item -LiteralPath $compiled).LastWriteTime -lt $newestSource) { throw 'Rebuild the changed sources with mvnw verify first.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [System.IO.Compression.ZipFile]::OpenRead($companion)
try {
    if (-not $jar.GetEntry('dev/starcore/starcore/foundation/economy/PlayerMoneyService.class') -or
        -not $jar.GetEntry('dev/starcore/starcore/module/treasury/TaxFundingService.class')) {
        throw 'The companion must contain both player-money and tax-funding receipt patches.'
    }
} finally { $jar.Dispose() }
$reports = Join-Path $root 'target/player-money-companion-reports'
$testPomPath = Join-Path $root 'target/player-money-companion-pom.xml'
[xml]$testPom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw
$namespaces = [System.Xml.XmlNamespaceManager]::new($testPom.NameTable)
$namespaces.AddNamespace('m','http://maven.apache.org/POM/4.0.0')
$surefire = $testPom.SelectSingleNode('//m:plugin[m:artifactId="maven-surefire-plugin"]',$namespaces)
if (-not $surefire) { throw 'The project must configure Maven Surefire.' }
$config = $testPom.CreateElement('configuration',$testPom.DocumentElement.NamespaceURI)
foreach ($setting in @{
    classesDirectory=(Join-Path $root 'target/classes')
    testClassesDirectory=(Join-Path $root 'target/test-classes')
    reportsDirectory=$reports
}.GetEnumerator()) {
    $node = $testPom.CreateElement($setting.Key,$testPom.DocumentElement.NamespaceURI)
    $node.InnerText = $setting.Value
    [void]$config.AppendChild($node)
}
foreach ($setting in @(
    @{parent='classpathDependencyExcludes';child='classpathDependencyExclude';value='dev.starcore:starcore'},
    @{parent='additionalClasspathElements';child='additionalClasspathElement';value=$companion}
)) {
    $parent = $testPom.CreateElement($setting.parent,$testPom.DocumentElement.NamespaceURI)
    $child = $testPom.CreateElement($setting.child,$testPom.DocumentElement.NamespaceURI)
    $child.InnerText = $setting.value
    [void]$parent.AppendChild($child)
    [void]$config.AppendChild($parent)
}
$oldConfig = $surefire.SelectSingleNode('m:configuration',$namespaces)
if ($oldConfig) { [void]$surefire.RemoveChild($oldConfig) }
[void]$surefire.AppendChild($config)
$testPom.Save($testPomPath)
$oldJava = $env:JAVA_HOME
$oldMaven = $env:MAVEN_OPTS
try {
    $env:JAVA_HOME = $JavaHome
    $env:MAVEN_OPTS = '-Xms32m -Xmx192m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC'
    $arguments = @('-B','-ntp','-f',$testPomPath,'-DforkCount=0','-Dstarsupply.test.actual-player-money=true','surefire:test')
    if ($Offline) { $arguments = @('-o') + $arguments }
    & (Join-Path $root 'mvnw.cmd') @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Companion receipt regression failed.' }
} finally {
    $env:JAVA_HOME = $oldJava
    $env:MAVEN_OPTS = $oldMaven
}
[xml]$receiptReport = Get-Content -LiteralPath (Join-Path $reports 'TEST-dev.starsupply.integration.CorePlayerMoneyTest.xml') -Raw
$corePaths = @($receiptReport.testsuite.properties.property | Where-Object name -eq 'surefire.test.class.path' |
    ForEach-Object { $_.value -split ';' | Where-Object { $_ -match 'starcore.*jar$' } })
if ($corePaths.Count -ne 1 -or $corePaths[0] -ne $companion) { throw 'The tests did not isolate the supplied Core JAR.' }
$actualCases = @($receiptReport.testsuite.testcase | Where-Object { $_.name -match '\[2\]' })
if (-not $actualCases.Count) { throw 'Actual Core receipt scenarios were not executed.' }
Write-Output "Actual Core receipt scenarios: $($actualCases.Count)"
[xml]$taxReport = Get-Content -LiteralPath (Join-Path $reports 'TEST-dev.starsupply.integration.CoreTaxPaymentTest.xml') -Raw
$actualTaxCases = @($taxReport.testsuite.testcase | Where-Object { $_.name -match '\[2\]' })
if (-not $actualTaxCases.Count) { throw 'Actual Core tax receipt scenarios were not executed.' }
Write-Output "Actual Core tax receipt scenarios: $($actualTaxCases.Count)"
Get-FileHash -LiteralPath $companion -Algorithm SHA256
