[CmdletBinding()]
param([ValidateSet('Smoke','Regression')][string] $Kind = 'Regression', [switch] $Open)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$api = Join-Path $root "target/runs/Api$Kind/allure-results"
$ui = Join-Path $root "target/runs/Ui$Kind/allure-results"
if (-not (Test-Path $api) -or -not (Test-Path $ui)) { throw 'Both suite result directories are required.' }
& (Join-Path $PSScriptRoot 'Test-ReportSecrets.ps1') -Paths @($api,$ui)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$output = Join-Path $root "target/allure-$Kind"
& allure generate $api $ui --clean -o $output
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($Open) { & allure open $output }
