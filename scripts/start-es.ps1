$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'use-deployment.ps1')
$distribution=$env:ENGINE_ES_HOME
$exe=Join-Path $distribution 'bin\elasticsearch.bat'
if (-not (Test-Path -LiteralPath $exe)) { throw 'Run scripts\prepare-es.ps1 or set ENGINE_ES_HOME to Elasticsearch 8.19.0.' }
$config=Join-Path $private 'search\config'
$logs=Join-Path $private 'search\logs'
New-Item -Force -ItemType Directory $config,$logs | Out-Null
$utf8=New-Object Text.UTF8Encoding($false)
$base=$private.Replace('\','/').Replace("'","''")
$yaml=@"
cluster.name: engine-v01
node.name: public-demo
network.host: 127.0.0.1
http.port: $($deployment.esPort)
transport.port: $($deployment.transportPort)
discovery.type: single-node
xpack.security.enabled: false
xpack.ml.enabled: false
ingest.geoip.downloader.enabled: false
path.data: '$base/search/data'
path.logs: '$base/search/logs'
"@
[IO.File]::WriteAllText((Join-Path $config 'elasticsearch.yml'),$yaml,$utf8)
$gc=(Join-Path $logs 'gc.log').Replace('\','/')
$options=[IO.File]::ReadAllText((Join-Path $distribution 'config\jvm.options')).Replace('file=logs/gc.log',"file=$gc")
[IO.File]::WriteAllText((Join-Path $config 'jvm.options'),$options,$utf8)
Copy-Item -Force -LiteralPath (Join-Path $distribution 'config\log4j2.properties') -Destination $config
$env:ES_PATH_CONF=$config
$env:ES_JAVA_HOME=Join-Path $distribution 'jdk'
$env:ES_JAVA_OPTS='-Xms512m -Xmx512m'
Push-Location $private
try { & $exe } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Elasticsearch exited with an error.' }
