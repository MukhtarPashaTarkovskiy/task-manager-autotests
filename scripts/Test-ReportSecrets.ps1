[CmdletBinding()]
param([string[]] $Paths = @('api-tests/target','ui-tests/target'), [switch] $FromEnvironment)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$plain = @{}
try {
    if ($FromEnvironment) {
        foreach ($name in @('TEST_USER_PASSWORD','TEST_DB_PASSWORD','TEST_DB_USERNAME','TEST_DB_URL','TEST_ALLOWED_DB_HOST')) {
            $value = [Environment]::GetEnvironmentVariable($name)
            if ([string]::IsNullOrWhiteSpace($value)) { throw 'Missing audit input.' }
            $plain[$name] = $value
        }
    } else {
        . (Join-Path $PSScriptRoot 'Initialize-TestSecrets.ps1') -LibraryOnly
        $path = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData/Local/TaskManagerAutotests/secrets.clixml'
        $plain = Read-PrivateStore $path
    }
    $forms = @{}
    # The numeric port is a connection setting, not a secret; random IDs/timestamps can contain it.
    foreach ($key in @('TEST_USER_PASSWORD','TEST_DB_PASSWORD','TEST_DB_USERNAME','TEST_DB_URL','TEST_ALLOWED_DB_HOST')) {
        $value = $plain[$key]
        $forms[$key] = @($value, [Uri]::EscapeDataString($value),
            [Security.SecurityElement]::Escape($value),
            [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value)),
            (($value | ConvertTo-Json -Compress).Trim('"')))
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $files = 0; $leaks = 0
    foreach ($directory in $Paths) {
        if (-not (Test-Path -LiteralPath $directory)) { throw 'Audit directory missing.' }
        foreach ($file in Get-ChildItem -LiteralPath $directory -Recurse -File) {
            if ($file.Extension -in @('.docx','.xlsx')) {
                $zip = [IO.Compression.ZipFile]::OpenRead($file.FullName)
                try {
                    $parts = foreach ($entry in $zip.Entries) {
                        if ($entry.FullName.EndsWith('.xml')) {
                            $reader = New-Object IO.StreamReader($entry.Open())
                            try { $reader.ReadToEnd() } finally { $reader.Dispose() }
                        }
                    }
                    $text = $parts -join "`n"
                } finally { $zip.Dispose() }
            } elseif ($file.Extension -in @('.json','.xml','.txt','.log','.html','.properties','.dump','.dumpstream','.js','.csv','.md','.java','.ps1','.yml','.yaml')) {
                $text = [IO.File]::ReadAllText($file.FullName)
            } else { continue }
            $files++
            $categories = @()
            foreach ($key in $forms.Keys) {
                foreach ($form in $forms[$key]) {
                    if ($form.Length -gt 0 -and $text.Contains($form)) { $categories += $key; break }
                }
            }
            if ($categories.Count -gt 0) {
                $leaks++
                Write-Host ('FAIL: {0}; categories: {1}' -f $file.FullName, (($categories | Sort-Object) -join ', '))
            } elseif ($file.Extension -in @('.docx','.xlsx')) { Write-Host ('PASS: {0}' -f $file.Name) }
        }
    }
    if ($files -eq 0) { throw 'No textual artifacts.' }
    if ($leaks -ne 0) { Write-Host 'FAIL: sensitive data detected; do not publish artifacts.'; exit 1 }
    Write-Host "PASS: $files textual artifacts checked; no configured secret values detected. PNG requires visual review."
} catch { Write-Host 'FAIL: artifact audit could not complete; no secret details printed.'; exit 1 }
finally { $plain.Clear(); $forms = $null; $text = $null; $value = $null }
exit 0
