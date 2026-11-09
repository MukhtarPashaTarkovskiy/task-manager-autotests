# Synthetic report pipeline check: no browser, database, application or real credentials.
$ErrorActionPreference='Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runIds = @(('pipeline-' + [Guid]::NewGuid().ToString('N')), ('pipeline-' + [Guid]::NewGuid().ToString('N')))
foreach ($key in @('TEST_USER_PASSWORD','TEST_DB_PASSWORD','TEST_DB_USERNAME','TEST_DB_URL','TEST_ALLOWED_DB_HOST')) {
    [Environment]::SetEnvironmentVariable($key, "synthetic-check-$key")
}
# The real Get-AllureReport implementation is exercised with a local fake CLI.
function global:allure {
    $global:LASTEXITCODE=0
    if ($args[0] -eq 'open') { return }
    if ($args[0] -ne 'generate') { throw 'Unexpected CLI invocation' }
    $source=$args[1]; $output=$args[3]
    New-Item -ItemType Directory -Path "$output/history" | Out-Null
    'synthetic local report' | Set-Content "$output/index.html"
    $executor=Get-Content "$source/executor.json" -Raw | ConvertFrom-Json
    $trend=@()
    if (Test-Path "$source/history/history-trend.json") { $trend=@(Get-Content "$source/history/history-trend.json" -Raw | ConvertFrom-Json) }
    @(@{buildOrder=$executor.buildOrder; data=@{passed=2}})+$trend | ConvertTo-Json -Depth 5 | Set-Content "$output/history/history-trend.json"
}
$previous=$null
foreach ($id in $runIds) {
    $run=Join-Path $root "test-runs/$id"
    New-Item -ItemType Directory -Path $run | Out-Null
    "runId=$id`nenvironment=TEST`ncreatedEpoch=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())" | Set-Content "$run/run.properties"
    foreach ($module in @('api-tests','ui-tests')) {
        $part="$run/$module/Selected"
        New-Item -ItemType Directory -Path "$part/allure-results" | Out-Null
        "runId=$id`nmodule=$module`nsuite=Selected" | Set-Content "$part/run.properties"
        'done' | Set-Content "$part/completed.marker"
        @{uuid=[Guid]::NewGuid().ToString();name='Synthetic';fullName="pipeline.$module";historyId="pipeline.$module";labels=@();status='passed';start=1;stop=2} |
            ConvertTo-Json -Depth 4 | Set-Content "$part/allure-results/$module-result.json"
    }
    $before = Get-FileHash "$run/api-tests/Selected/allure-results/api-tests-result.json"
    $params=@{RunId=$id;Scope='Combined';Kind='Selected';FromEnvironment=$true}
    if ($previous) {$params.PreviousReport=$previous}
    & "$root/scripts/Get-AllureReport.ps1" @params
    if ($LASTEXITCODE -ne 0) {throw 'Report generation failed'}
    $bundle=Get-ChildItem "$run/reports/Combined-Selected" -Directory | Select-Object -First 1
    $previous="$($bundle.FullName)/html"
    if ((Get-FileHash "$run/api-tests/Selected/allure-results/api-tests-result.json").Hash -ne $before.Hash) {throw 'Source modified'}
    if (@(Get-ChildItem "$($bundle.FullName)/input" -Filter '*-result.json').Count -ne 2) {throw 'Results mixed'}
}
$trend=@(Get-Content "$previous/history/history-trend.json" -Raw | ConvertFrom-Json)
if ($trend.Count -ne 2) {throw 'History not transferred'}
$filesBefore=@(Get-ChildItem "$root/test-runs" -Recurse -File).Count
& "$root/scripts/Get-AllureReport.ps1" -ReportPath $previous
if (@(Get-ChildItem "$root/test-runs" -Recurse -File).Count -ne $filesBefore) {throw 'Viewing created files'}
Write-Host 'PASS: selected run, combined API/UI, History/Trend transfer, immutable source, read-only viewing.'
Write-Host "Synthetic results retained: $($runIds -join ', ')"
