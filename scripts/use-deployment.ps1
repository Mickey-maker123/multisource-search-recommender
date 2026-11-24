$root=Split-Path $PSScriptRoot -Parent
$private=Join-Path $root '.local'
$settings=Join-Path $private 'deployment.json'
if (-not (Test-Path -LiteralPath $settings)) { throw 'Run scripts\init-demo.ps1 first.' }
$deployment=Get-Content -Raw -Encoding utf8 $settings | ConvertFrom-Json
$keys=Get-Content -Raw -Encoding utf8 (Join-Path $private 'credentials.json') | ConvertFrom-Json
$env:ENGINE_HOST='127.0.0.1'
$env:ENGINE_PORT=[string]$deployment.enginePort
$env:ENGINE_DOMAIN='demo'
$env:ENGINE_ADMIN_KEY=$keys.adminKey
$env:EMBEDDING_API_KEY=$keys.modelKey
$env:ENGINE_ES_URL="http://127.0.0.1:$($deployment.esPort)"
$env:ENGINE_ES_INDEX='engine-v01-items'
$env:ENGINE_MODEL_URL="http://127.0.0.1:$($deployment.modelPort)"
$env:ENGINE_QUERY_AUTH_FILE=Join-Path $private 'query-identities.json'
$env:ENGINE_DIRECTORY_SOURCES=Join-Path $private 'directory-sources.json'
if (-not $env:ENGINE_TASK_CARD_STRATEGY) { $env:ENGINE_TASK_CARD_STRATEGY='task-bm25' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME=Join-Path $root 'runtime\jdk' }
if (-not $env:ENGINE_ES_HOME) { $env:ENGINE_ES_HOME=Join-Path $root 'runtime\elasticsearch-8.19.0' }
if (-not $env:ENGINE_MODEL_PYTHON) {
    $env:ENGINE_MODEL_PYTHON=Join-Path $root 'runtime\model-env\Scripts\python.exe'
    if (-not (Test-Path $env:ENGINE_MODEL_PYTHON)) { $env:ENGINE_MODEL_PYTHON=Join-Path $root 'runtime\python\python.exe' }
}
if (-not $env:MODEL_PATH) {
    $pointer=Join-Path $root 'runtime\model-path.txt'
    if (Test-Path -LiteralPath $pointer) { $env:MODEL_PATH=[IO.File]::ReadAllText($pointer).Trim() }
    else { $env:MODEL_PATH=Join-Path $root 'runtime\models\bge-m3' }
}
$jar=Join-Path $root 'lib\engine.jar'
if (-not (Test-Path -LiteralPath $jar)) { $jar=Join-Path $root 'engine\target\engine.jar' }
if (-not $env:ENGINE_SEMANTIC_MIN) { $env:ENGINE_SEMANTIC_MIN="0.55" }
