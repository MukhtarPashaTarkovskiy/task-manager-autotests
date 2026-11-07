[CmdletBinding(DefaultParameterSetName='Generate')]
param(
    [Parameter(Mandatory=$true,ParameterSetName='Generate')]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$')][string] $RunId,
    [ValidateSet('api-tests','ui-tests','Combined')][string] $Scope = 'Combined',
    [ValidateSet('Smoke','Regression','All','Selected')][string] $Kind = 'All',
    [string] $PreviousReport,
    [string] $HistoryRoot,
    [Parameter(Mandatory=$true,ParameterSetName='View')][string] $ReportPath,
    [switch] $Open,
    [switch] $PrepareMaven,
    [switch] $FromEnvironment
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
# Viewing is read-only: no run bootstrap, staging, audit or directory creation.
if ($PSCmdlet.ParameterSetName -eq 'View') {
    if (-not (Test-Path -LiteralPath (Join-Path $ReportPath 'index.html'))) { throw 'Saved report not found.' }
    & allure open $ReportPath
    exit $LASTEXITCODE
}
function Read-Identity([string] $Path) {
    $result = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^([^#!][^=]*)=(.*)$') { $result[$matches[1]] = $matches[2] }
    }
    return $result
}
$run = Join-Path $root "test-runs/$RunId"
$identity = Read-Identity (Join-Path $run 'run.properties')
$runCreated = if ($identity.ContainsKey('createdEpoch')) { [long]$identity.createdEpoch } else { 0 }
if ($identity.runId -cne $RunId -or $identity.environment -cne 'TEST') { throw 'Run identity mismatch.' }
$modules = if ($Scope -eq 'Combined') { @('api-tests','ui-tests') } else { @($Scope) }
$inputs = @()
foreach ($module in $modules) {
    $part = Join-Path $run "$module/$Kind"
    $p = Read-Identity (Join-Path $part 'run.properties')
    if ($p.runId -cne $RunId -or $p.module -cne $module -or $p.suite -cne $Kind) { throw 'Module Run ID or suite mismatch.' }
    if (-not (Test-Path (Join-Path $part 'completed.marker'))) { throw 'Run is incomplete; wait for TestNG to finish.' }
    $inputPath = Join-Path $part 'allure-results'
    if (@(Get-ChildItem -LiteralPath $inputPath -Filter '*-result.json').Count -eq 0) { throw 'No test results in selected module.' }
    $inputs += $inputPath
}
& (Join-Path $PSScriptRoot 'Test-ReportSecrets.ps1') -Paths $inputs -FromEnvironment:$FromEnvironment
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# History streams are separated by module composition, suite and actual test inventory.
$inventory = @($inputs | ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter '*-result.json' } | ForEach-Object {
    $test = [IO.File]::ReadAllText($_.FullName) | ConvertFrom-Json
    if ($test.fullName -and -not @($test.labels | Where-Object { $_.name -in @('AS_ID','ALLURE_ID') -and $_.value -eq '-1' }).Count) { [string]$test.fullName }
} | Sort-Object)
$sha = [Security.Cryptography.SHA256]::Create()
try { $fingerprint = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($inventory -join "`n"))))).Replace('-','').ToLowerInvariant() }
finally { $sha.Dispose() }
$stream = "$Scope-$Kind-$fingerprint"
if (-not $PreviousReport) {
    $historyRoots = @((Join-Path $root 'test-runs'))
    if ($HistoryRoot) { $historyRoots += $HistoryRoot }
    $candidates = @(foreach ($historyPath in $historyRoots) {
        if (Test-Path -LiteralPath $historyPath) {
            foreach ($manifestFile in Get-ChildItem -LiteralPath $historyPath -Filter 'report-manifest.json' -File -Recurse) {
                $candidate = Get-Content -LiteralPath $manifestFile.FullName -Raw | ConvertFrom-Json
                if ($candidate.stream -ceq $stream -and $candidate.runId -cne $RunId -and
                    ($runCreated -eq 0 -or [long]$candidate.runCreated -lt $runCreated) -and
                    (Test-Path -LiteralPath (Join-Path $manifestFile.DirectoryName 'history'))) {
                    [pscustomobject]@{ Path=$manifestFile.DirectoryName; Order=[long]$candidate.buildOrder }
                }
            }
        }
    })
    $selected = $candidates | Sort-Object Order,Path -Descending | Select-Object -First 1
    if ($selected) { $PreviousReport = $selected.Path }
}
if ($PreviousReport) {
    $previous = Get-Content -LiteralPath (Join-Path $PreviousReport 'report-manifest.json') -Raw | ConvertFrom-Json
    if ($previous.stream -cne $stream -or $previous.runId -ceq $RunId) { throw 'History must come from a different run of the same test inventory.' }
    if (-not (Test-Path (Join-Path $PreviousReport 'history'))) { throw 'Previous Allure 2 history missing.' }
}
$reportId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff') + '-' + [Guid]::NewGuid().ToString('N')
$bundle = Join-Path $run "reports/$Scope-$Kind/$reportId"
$staging = Join-Path $bundle 'input'
$output = Join-Path $bundle 'html'
New-Item -ItemType Directory -Path $staging | Out-Null
foreach ($inputPath in $inputs) {
    foreach ($file in Get-ChildItem -LiteralPath $inputPath -File) {
        # Environment is regenerated for the combined report. Never copy prior test results.
        if ($file.Name -eq 'environment.properties') { continue }
        $destination = Join-Path $staging $file.Name
        if (Test-Path -LiteralPath $destination) { throw 'Duplicate result/attachment filename; refusing overwrite.' }
        Copy-Item -LiteralPath $file.FullName -Destination $destination
    }
}
$buildOrder = 1
if ($PreviousReport) {
    Copy-Item -LiteralPath (Join-Path $PreviousReport 'history') -Destination (Join-Path $staging 'history') -Recurse
    $buildOrder = [long]$previous.buildOrder + 1
}
@{ name='Task Manager TEST'; type='local'; buildName=$RunId; buildOrder=$buildOrder; reportName="$Scope $Kind / $RunId" } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $staging 'executor.json') -Encoding utf8
"Environment=TEST`nRun=$RunId`nScope=$Scope`nSuite=$Kind" | Set-Content -LiteralPath (Join-Path $staging 'environment.properties') -Encoding utf8
if ($PrepareMaven) {
    Write-Host 'Prepared a separate input copy for the Maven plugin; canonical results/history remain unchanged.'
    Write-Host ('mvn -N allure:report "-Dallure.maven.input={0}" "-Dallure.maven.buildOrder={1}" "-Dallure.maven.runId={2}"' -f $staging,$buildOrder,$RunId)
    Write-Host ('mvn -N allure:serve "-Dallure.maven.input={0}" "-Dallure.maven.buildOrder={1}" "-Dallure.maven.runId={2}"' -f $staging,$buildOrder,$RunId)
    exit 0
}
& allure generate $staging -o $output
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
@{ runId=$RunId; runCreated=$runCreated; stream=$stream; buildOrder=$buildOrder; previousReport=$PreviousReport; scope=$Scope; kind=$Kind } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'report-manifest.json') -Encoding utf8
& (Join-Path $PSScriptRoot 'Test-ReportSecrets.ps1') -Paths @($output) -FromEnvironment:$FromEnvironment
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Saved report: $output"
if ($Open) { & allure open $output; exit $LASTEXITCODE }
