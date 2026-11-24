param(
    [Parameter(Mandatory=$true)][ValidateSet('posts','files','business-json','mysql','status','run-status','users','events','events-local')][string]$Mode,
    [string]$InputPath,[string]$ImportRoot,[string]$MySqlDriver
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'use-deployment.ps1')
$classPath=$jar
if ($Mode -eq 'mysql') {
    if (-not $MySqlDriver -or -not (Test-Path -LiteralPath $MySqlDriver -PathType Leaf)) { throw 'Supply the independently installed JDBC driver using -MySqlDriver.' }
    $classPath+=';'+(Resolve-Path -LiteralPath $MySqlDriver).Path
}
$cliArgs=@($Mode)
if ($InputPath) { $cliArgs+=$InputPath }
if ($ImportRoot) { $cliArgs+=$ImportRoot }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') "-Dengine.home=$root" -cp $classPath local.federated.engine.IngestCli @cliArgs
if ($LASTEXITCODE -ne 0) { throw 'Import failed.' }
