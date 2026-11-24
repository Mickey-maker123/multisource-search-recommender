param([string]$Python,[string]$CacheDirectory,[switch]$Offline)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$runtime=Join-Path $root 'runtime'
if (-not $CacheDirectory) { $CacheDirectory=Join-Path $runtime 'cache' }
$wheelhouse=Join-Path $CacheDirectory 'wheelhouse'
$model=Join-Path $CacheDirectory 'models\bge-m3'
$venv=Join-Path $runtime 'model-env'
New-Item -Force -ItemType Directory $wheelhouse,$runtime | Out-Null
if (-not $Python) { $Python=(& py -3.11 -c 'import sys; print(sys.executable)') }
if (-not $Python -or -not (Test-Path -LiteralPath $Python)) { throw 'Supply a full CPython 3.11 x64 installation using -Python.' }
& $Python -c 'import struct,sys;sys.exit(0 if sys.version_info[:2]==(3,11) and struct.calcsize("P")==8 else 1)'
if ($LASTEXITCODE -ne 0) { throw 'Use 64-bit CPython 3.11.' }
if (-not (Test-Path (Join-Path $venv 'Scripts\python.exe'))) {
    & $Python -m venv $venv
    if ($LASTEXITCODE -ne 0) { throw 'Virtual environment creation failed.' }
}
$py=Join-Path $venv 'Scripts\python.exe'
$lock=Join-Path $root 'model-service\requirements.windows.lock.txt'
if (-not $Offline) {
    & $py -m pip download --disable-pip-version-check --only-binary=:all: --no-deps --dest $wheelhouse --index-url https://download.pytorch.org/whl/cpu 'torch==2.6.0+cpu'
    if ($LASTEXITCODE -ne 0) { throw 'Failed to download the CPU Torch wheel.' }
    & $py -m pip wheel --disable-pip-version-check --find-links $wheelhouse --wheel-dir $wheelhouse -r $lock 'setuptools==75.8.0'
    if ($LASTEXITCODE -ne 0) { throw 'Wheel cache preparation failed.' }
}
& $py -m pip install --disable-pip-version-check --no-index --only-binary=:all: --find-links $wheelhouse -r $lock 'setuptools==75.8.0'
if ($LASTEXITCODE -ne 0) { throw 'Model environment installation failed.' }
& $py -m pip check
if ($LASTEXITCODE -ne 0) { throw 'Model environment dependency check failed.' }
$fileArgs=@((Join-Path $PSScriptRoot 'prepare-model-files.py'),$model)
if ($Offline) { $fileArgs+='--offline' }
& $py @fileArgs
if ($LASTEXITCODE -ne 0) { throw 'Model preparation failed.' }
# Keep the cache reusable without duplicating multi-gigabyte weights.
[IO.File]::WriteAllText((Join-Path $runtime 'model-path.txt'),(Resolve-Path $model).Path,(New-Object Text.UTF8Encoding($false)))
Write-Output 'Model ready. Run scripts\start-model.ps1 after demo initialization.'
