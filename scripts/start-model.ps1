$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'use-deployment.ps1')
if (-not (Test-Path -LiteralPath $env:ENGINE_MODEL_PYTHON)) { throw 'Run scripts\prepare-model.ps1 or set ENGINE_MODEL_PYTHON.' }
$official=Join-Path $PSScriptRoot 'bge-m3-manifest.json'
$installed=Get-Content -Raw -Encoding utf8 (Join-Path $env:MODEL_PATH 'snapshot-manifest.json') | ConvertFrom-Json
$pinned=Get-Content -Raw -Encoding utf8 $official | ConvertFrom-Json
if ($installed.revision -ne $pinned.revision -or $installed.repo -ne $pinned.repo) { throw 'Unexpected model revision.' }
foreach ($file in $pinned.files.PSObject.Properties) {
    if ($installed.files.($file.Name).sha256 -ne $file.Value.sha256) { throw "Unexpected model checksum manifest: $($file.Name)" }
}
$env:MODEL_MAX_LENGTH='512'
$env:TORCH_NUM_THREADS='4'
$env:MODEL_BATCH_SIZE='2'
$env:PYTHONDONTWRITEBYTECODE='1'
& $env:ENGINE_MODEL_PYTHON -m uvicorn app:app --app-dir (Join-Path $root 'model-service') --host 127.0.0.1 --port $deployment.modelPort --no-access-log --http h11
if ($LASTEXITCODE -ne 0) { throw 'Model process exited with an error.' }
