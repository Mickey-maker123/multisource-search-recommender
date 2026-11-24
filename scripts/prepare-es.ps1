param([string]$CacheDirectory,[switch]$Offline)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if (-not $CacheDirectory) { $CacheDirectory=Join-Path $root 'runtime\cache' }
$name='elasticsearch-8.19.0-windows-x86_64.zip'
$url="https://artifacts.elastic.co/downloads/elasticsearch/$name"
New-Item -Force -ItemType Directory $CacheDirectory | Out-Null
$zip=Join-Path $CacheDirectory $name
$checksum="$zip.sha512"
function Get-OfficialFile([string]$uri,[string]$destination) {
    if (Test-Path -LiteralPath $destination -PathType Leaf) { return }
    if ($Offline) { throw "Missing cached file: $destination" }
    & curl.exe --fail --location --retry 3 --output "$destination.part" $uri
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $uri" }
    Move-Item -LiteralPath "$destination.part" -Destination $destination
}
Get-OfficialFile "$url.sha512" $checksum
Get-OfficialFile $url $zip
$expected=([IO.File]::ReadAllText($checksum).Trim() -split '\s+')[0]
if ($expected -notmatch '^[0-9a-fA-F]{128}$' -or (Get-FileHash -Algorithm SHA512 $zip).Hash -ne $expected) {
    throw "Elasticsearch checksum mismatch. Inspect the files in $CacheDirectory before retry."
}
$runtime=Join-Path $root 'runtime'
$target=Join-Path $runtime 'elasticsearch-8.19.0'
if (Test-Path -LiteralPath $target) { throw 'Elasticsearch runtime already exists. Reuse it or extract in a fresh project directory.' }
New-Item -Force -ItemType Directory $runtime | Out-Null
$staging=New-Item -ItemType Directory -Path (Join-Path $runtime ('.extract-'+[guid]::NewGuid().ToString('N')))
Expand-Archive -LiteralPath $zip -DestinationPath $staging.FullName
$unpacked=Join-Path $staging.FullName 'elasticsearch-8.19.0'
if (-not (Test-Path (Join-Path $unpacked 'bin\elasticsearch.bat'))) { throw 'Unexpected Elasticsearch archive.' }
Move-Item -LiteralPath $unpacked -Destination $target
Remove-Item -LiteralPath $staging.FullName
Write-Output "Elasticsearch is ready in $target"
