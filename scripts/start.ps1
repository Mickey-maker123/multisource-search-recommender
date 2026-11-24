$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'use-deployment.ps1')
$java=Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw 'Set JAVA_HOME to a Java 17 JDK.' }
if (-not (Test-Path -LiteralPath $jar)) { throw 'Download the Windows core ZIP or run scripts\build.ps1.' }
& $java "-Dengine.home=$root" -jar $jar
if ($LASTEXITCODE -ne 0) { throw 'Engine process exited with an error.' }
