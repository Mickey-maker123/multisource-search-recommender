param([switch]$Offline,[string]$MavenRepository)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME -and (Test-Path (Join-Path $root 'runtime\jdk\bin\java.exe'))) {
    $env:JAVA_HOME=Join-Path $root 'runtime\jdk'
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a Java 17 JDK.' }
$mvn=Join-Path $root 'runtime\maven\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $mvn)) { $mvn=(Get-Command mvn.cmd -ErrorAction Stop).Source }
$buildArgs=@('-B','-f',(Join-Path $root 'engine\pom.xml'))
if ($MavenRepository) { $buildArgs+="-Dmaven.repo.local=$MavenRepository" }
if ($Offline) { $buildArgs+='-o' }
& $mvn @buildArgs clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
