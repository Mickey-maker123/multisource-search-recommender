param(
    [ValidateRange(1,65535)][int]$EnginePort=18080,
    [ValidateRange(1,65535)][int]$ModelPort=18081,
    [ValidateRange(19200,19299)][int]$EsPort=19200,
    [ValidateRange(1,65535)][int]$TransportPort=19300
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$ports=@($EnginePort,$ModelPort,$EsPort,$TransportPort)
if (($ports | Sort-Object -Unique).Count -ne 4) { throw 'Choose four distinct ports.' }
$inUse=[Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port
foreach ($port in $ports) { if ($port -in $inUse) { throw "TCP port $port is in use. Select a different port." } }
$private=Join-Path $root '.local'
$settings=Join-Path $private 'deployment.json'
$names=@('credentials.json','query-identities.json','directory-sources.json','deployment.json')
foreach ($name in $names) { if (Test-Path -LiteralPath (Join-Path $private $name)) { throw 'Demo already initialized or interrupted. Use a fresh directory.' } }
$groups=@{manuals=@('staff');orders=@('staff');supervisors=@('supervisors')}
$sources=foreach ($id in @('manuals','orders','supervisors')) {
    $folder=Join-Path $root "examples\maintenance\$id"
    if (-not (Test-Path -LiteralPath $folder -PathType Container)) { throw "Missing demo directory: $folder" }
    $source=@{sourceId=$id;label=$id;directory=(Join-Path $private "demo-input\$id");acl=@{public=$false;users=@();groups=$groups[$id]};
        parseAllowed=$true;parseAs=@{user='parser';groups=$groups[$id]};deleteMissing=$true;scanIntervalSeconds=0;maxFiles=100}
    if ($id -eq 'orders') {
        $source.tableMapping=@{schemaVersion='1.0';idColumn='workOrderId';titleColumn='title';fields=@{
          workOrderId=@{column='workOrderId';type='string';required=$true};equipmentId=@{column='equipmentId';type='string';semantic=$true};
          status=@{column='status';type='string'};action=@{column='action';type='string';semantic=$true}}}
    }
    $source
}
New-Item -Force -ItemType Directory $private | Out-Null
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $private /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict credentials directory.' }
$demoInput=Join-Path $private 'demo-input'
if (Test-Path -LiteralPath $demoInput) { throw 'A demo-input directory already exists. Use a fresh deployment.' }
New-Item -ItemType Directory $demoInput | Out-Null
foreach ($id in @('manuals','orders','supervisors')) {
    Copy-Item -Recurse -LiteralPath (Join-Path $root "examples\maintenance\$id") -Destination $demoInput
}
function New-Token {
    $bytes=New-Object byte[] 32
    $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') } finally { $rng.Dispose() }
}
function Get-Digest([string]$value) {
    $sha=[Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($value))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function Write-Config([string]$name,$value) {
    [IO.File]::WriteAllText((Join-Path $private $name),($value | ConvertTo-Json -Depth 12),(New-Object Text.UTF8Encoding($false)))
}
$keys=@{queryToken=New-Token;managerToken=New-Token;adminKey=New-Token;modelKey=New-Token}
$principals=@(
    @{tokenSha256=Get-Digest $keys.queryToken;domain='demo';userId='operator';groups=@('staff');active=$true}
    @{tokenSha256=Get-Digest $keys.managerToken;domain='demo';userId='supervisor';groups=@('staff','supervisors');active=$true}
)
Write-Config 'query-identities.json' @{principals=$principals}
Write-Config 'directory-sources.json' @{schemaVersion='1.0';sources=@($sources)}
Write-Config 'credentials.json' $keys
Write-Config 'deployment.json' @{enginePort=$EnginePort;modelPort=$ModelPort;esPort=$EsPort;transportPort=$TransportPort}
"Demo initialized. Start the three services, then run scripts\import-demo.ps1. URL: http://127.0.0.1:$EnginePort/demo"
