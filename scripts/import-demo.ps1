param([string[]]$SourceId=@('manuals','orders','supervisors'),[ValidateRange(10,3600)][int]$TimeoutSeconds=600)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'use-deployment.ps1')
$url="http://127.0.0.1:$($deployment.enginePort)/api/engine"
$headers=@{Authorization="Bearer $($keys.adminKey)"}
$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
function Send-Body([string]$path,$payload) {
    $bytes=[Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 12))
    Invoke-RestMethod -TimeoutSec 30 -Method Post -Uri "$url/$path" -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8'
}
$users=foreach ($user in @('operator','supervisor')) {
    $ids=foreach ($source in @('manuals','orders','supervisors')) { @{domain='demo';source=$source;localUserId=$user} }
    $groups=@('staff')
    if ($user -eq 'supervisor') { $groups+='supervisors' }
    @{userId=$user;version=1;active=$true;groups=$groups;preferences=@();subscriptions=@();allowBehaviorProfile=$true;identities=@($ids)}
}
$applied=Send-Body 'v1/users:batch' @{schemaVersion='1.0';users=@($users)}
foreach ($outcome in $applied.results) {
    if ($outcome.status -notin @('applied','duplicate','stale')) { throw "Demo user initialization failed: $($outcome.code)" }
}
foreach ($id in $SourceId) {
    $job=Send-Body 'admin/v1/imports' @{schemaVersion='1.0';sourceId=$id}
    do {
        $run=Invoke-RestMethod -TimeoutSec 30 -Uri "$url/admin/v1/imports/$($job.runId)" -Headers $headers
        if ($run.state -eq 'completed') { break }
        if ($run.state -notin @('queued','running')) { throw "Source $id ended in $($run.state): $($run.code). Run $($job.runId)." }
        if ([DateTime]::UtcNow -gt $deadline) { throw "Timed out on source $id. Run $($job.runId)." }
        Start-Sleep -Milliseconds 500
    } while ($true)
    Write-Output "$id : $($run.state), $($run.processed) entries, $($run.errors) errors"
}
do {
    $status=(Invoke-RestMethod -TimeoutSec 30 -Uri "$url/admin/v1/status" -Headers $headers).search
    if ($status.state -eq 'available' -and $status.readyObjects -gt 0 -and $status.keywordObjects -eq $status.readyObjects -and $status.vectorObjects -eq $status.readyObjects) {
        Write-Output "Ready: $($status.readyObjects) objects, keywords and vectors. http://127.0.0.1:$($deployment.enginePort)/demo"
        break
    }
    if ([DateTime]::UtcNow -gt $deadline) { throw "Index timeout: ready=$($status.readyObjects), keyword=$($status.keywordObjects), vector=$($status.vectorObjects), model=$($status.model.state)" }
    Start-Sleep -Seconds 1
} while ($true)
