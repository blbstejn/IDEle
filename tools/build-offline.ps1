# Build a Windows-installable plugin ZIP with locally installed JDK 21.
# IMPORTANT: compile-time IntelliJ stubs are NOT shipped; only the real Rider SDK validates plugin API compatibility.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$build = Join-Path $root 'build'
$out = Join-Path $build 'offline-export'
$version = '1.1.1'

function Invoke-Checked([string]$command, [string[]]$arguments) {
    & $command @arguments
    if ($LASTEXITCODE -ne 0) { throw "$command failed with exit code $LASTEXITCODE" }
}

$stubClasses = Join-Path $build 'offline-stubs'
$mainClasses = Join-Path $build 'classes/java/main'
$testClasses = Join-Path $build 'classes/java/test'
$exportPlugin = Join-Path $out 'IDEle'
$exportLib = Join-Path $exportPlugin 'lib'
$zip = Join-Path $root "IDEle-$version.zip"
# Remove stale archives even if an earlier export used the same filename.
if (Test-Path $zip) { Remove-Item -LiteralPath $zip -Force }
foreach ($dir in @($stubClasses, $mainClasses, $testClasses, $out)) {
    if (Test-Path $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
}
foreach ($dir in @($stubClasses, $mainClasses, $testClasses, $exportLib)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
$stubFiles = @(Get-ChildItem (Join-Path $root 'offline-stubs') -Recurse -Filter '*.java' -File | Select-Object -ExpandProperty FullName)
$mainFiles = @(Get-ChildItem (Join-Path $root 'src/main/java') -Recurse -Filter '*.java' -File | Select-Object -ExpandProperty FullName)
$testFiles = @(Get-ChildItem (Join-Path $root 'src/test/java') -Recurse -Filter '*.java' -File | Select-Object -ExpandProperty FullName)
Invoke-Checked 'javac' (@('--release', '21', '-encoding', 'UTF-8', '-d', $stubClasses) + $stubFiles)
Invoke-Checked 'javac' (@('--release', '21', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-cp', $stubClasses, '-d', $mainClasses) + $mainFiles)
Invoke-Checked 'javac' (@('--release', '21', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-cp', $mainClasses, '-d', $testClasses) + $testFiles)
Invoke-Checked 'java' @('-Djava.awt.headless=true', '-cp', "$mainClasses;$testClasses", 'dev.riderlan.core.IntegrationTest')
$jar = Join-Path $exportLib 'idele.jar'
Invoke-Checked 'jar' @('--create', '--file', $jar, '-C', $mainClasses, '.', '-C', (Join-Path $root 'src/main/resources'), '.')
$jarEntries = & jar --list --file $jar
if ($LASTEXITCODE -ne 0 -or !($jarEntries -contains 'META-INF/plugin.xml')) {
    throw 'The plugin descriptor is missing from the export.'
}
if (@($jarEntries | Where-Object { $_ -like 'com/intellij/*' }).Count -ne 0) {
    throw 'IDE compile-time stubs must not be packaged in the plugin.'
}
Compress-Archive -Path $exportPlugin -DestinationPath $zip -CompressionLevel Optimal -Force
Write-Host "Unsigned preview ZIP (Rider SDK verification still required): $zip"
