# Windows PowerShell 5.1; run in a fresh -NoProfile process.
[CmdletBinding()]
param([switch] $LibraryOnly, [switch] $SelfTest)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function ConvertFrom-PrivateValue([Security.SecureString] $Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
function Assert-TestDatabase([string] $Url, [string] $AllowedHost, [string] $AllowedPort) {
    $dns = '\A(?=.{1,253}\z)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+\z'
    if ($AllowedHost -cnotmatch $dns -or $AllowedPort -cnotmatch '\A[1-9][0-9]{0,4}\z' -or [int]$AllowedPort -gt 65535) {
        throw 'Invalid approved endpoint.'
    }
    $match = [regex]::Match($Url, '\Ajdbc:postgresql://(?<host>[a-z0-9.-]+)(?::(?<port>[1-9][0-9]{0,4}))?/task_manager_test\?sslmode=(require|verify-full)\z')
    if (-not $match.Success) { throw 'JDBC URL must use the test database and sslmode=require or verify-full only.' }
    $port = 5432
    if ($match.Groups['port'].Success) { $port = [int]$match.Groups['port'].Value }
    if ($match.Groups['host'].Value -cne $AllowedHost -or $port -ne [int]$AllowedPort) { throw 'Test endpoint mismatch.' }
}
function Assert-PrivatePath([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $repo = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\') + '\'
    if ($full.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { throw 'Store inside repository.' }
    $itemPath = $full
    while ($itemPath) {
        if (Test-Path -LiteralPath $itemPath) {
            $item = Get-Item -LiteralPath $itemPath -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Reparse point refused.' }
        }
        $parent = Split-Path -Parent $itemPath
        if ($parent -eq $itemPath) { break }
        $itemPath = $parent
    }
}
function Set-PrivateAcl([string] $Path, [bool] $Directory) {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    if ($Directory) {
        $acl = Get-Acl -LiteralPath $Path
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    } else {
        $acl = Get-Acl -LiteralPath $Path
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'Allow')
    }
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($existing in @($acl.Access)) { $null = $acl.RemoveAccessRuleSpecific($existing) }
    $acl.AddAccessRule($rule)
    if ($Directory) { [IO.Directory]::SetAccessControl($Path, $acl) }
    else { [IO.File]::SetAccessControl($Path, $acl) }
}
function Assert-PrivateAcl([string] $Path) {
    Assert-PrivatePath $Path
    $acl = Get-Acl -LiteralPath $Path
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid -or -not $acl.AreAccessRulesProtected) { throw 'Unsafe owner or inheritance.' }
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $sid -or
        $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne 'FullControl') { throw 'Unsafe permissions.' }
}
function Write-PrivateStore([string] $Path, $Record) {
    Assert-PrivatePath $Path
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Path $directory | Out-Null
        Set-PrivateAcl $directory $true
    }
    Assert-PrivateAcl $directory
    # Recover only this script's encrypted GUID-named temporary files from interrupted saves.
    foreach ($pending in @(Get-ChildItem -LiteralPath $directory -Filter '*.pending' -File)) {
        if ($pending.Name -match '\A[0-9a-f-]{36}\.pending\z') {
            Assert-PrivatePath $pending.FullName
            Remove-Item -LiteralPath $pending.FullName -Force
        }
    }
    if (Test-Path -LiteralPath $Path) { Assert-PrivateAcl $Path }
    $temporary = Join-Path $directory ([Guid]::NewGuid().ToString() + '.pending')
    try {
        # Export-Clixml encrypts SecureString fields using current-user DPAPI on Windows.
        $Record | Export-Clixml -LiteralPath $temporary
        Set-PrivateAcl $temporary $false
        Assert-PrivateAcl $temporary
        if (Test-Path -LiteralPath $Path) {
            [IO.File]::Copy($temporary, $Path, $true)
        } else { [IO.File]::Move($temporary, $Path) }
        Set-PrivateAcl $Path $false
        Assert-PrivateAcl $Path
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            try { Remove-Item -LiteralPath $temporary -Force } catch { Write-Warning 'Encrypted temporary file could not be removed.' }
        }
    }
}
function Read-PrivateStore([string] $Path) {
    Assert-PrivateAcl (Split-Path -Parent $Path)
    Assert-PrivateAcl $Path
    $record = Import-Clixml -LiteralPath $Path
    if ($record.SchemaVersion -ne 2) { throw 'Unsupported store schema.' }
    $plain = @{}
    try {
        foreach ($name in @('TEST_USER_PASSWORD','TEST_DB_URL','TEST_DB_USERNAME','TEST_DB_PASSWORD','TEST_ALLOWED_DB_HOST','TEST_ALLOWED_DB_PORT')) {
            if ($record.$name -isnot [Security.SecureString]) { throw 'Unencrypted field refused.' }
            $plain[$name] = ConvertFrom-PrivateValue $record.$name
            if ([string]::IsNullOrWhiteSpace($plain[$name])) { throw 'Empty field.' }
        }
        Assert-TestDatabase $plain.TEST_DB_URL $plain.TEST_ALLOWED_DB_HOST $plain.TEST_ALLOWED_DB_PORT
        return ,$plain
    } catch { $plain.Clear(); throw 'Store validation failed.' }
    finally { $record = $null }
}
if ($LibraryOnly) { return }
$path = $null
$record = $null
$plain = @{}
try {
    if ($env:OS -ne 'Windows_NT') { throw 'Windows required.' }
    if ($SelfTest) {
        # This mode never reads or changes the actual credential store.
        $directory = Join-Path (Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData/Local/Temp') ('tm-dpapi-check-' + [Guid]::NewGuid().ToString())
        $path = Join-Path $directory 'synthetic.clixml'
        $plain = @{
            TEST_USER_PASSWORD = 'FAKE-user-<>&-123'; TEST_DB_URL = 'jdbc:postgresql://test-db.invalid/task_manager_test?sslmode=verify-full'
            TEST_DB_USERNAME = 'FAKE-user'; TEST_DB_PASSWORD = 'FAKE-db-<>&-456'
            TEST_ALLOWED_DB_HOST = 'test-db.invalid'; TEST_ALLOWED_DB_PORT = '5432'
        }
    } else {
        Write-Host 'Use the confirmed TEST PostgreSQL endpoint. Same-account processes can decrypt DPAPI.'
        foreach ($name in @('TEST_ALLOWED_DB_HOST','TEST_ALLOWED_DB_PORT','TEST_USER_PASSWORD','TEST_DB_URL','TEST_DB_USERNAME','TEST_DB_PASSWORD')) {
            $secure = Read-Host $name -AsSecureString
            try { $plain[$name] = ConvertFrom-PrivateValue $secure } finally { $secure.Dispose() }
        }
        $path = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData/Local/TaskManagerAutotests/secrets.clixml'
    }
    foreach ($value in $plain.Values) { if ([string]::IsNullOrWhiteSpace($value)) { throw 'Empty input.' } }
    Assert-TestDatabase $plain.TEST_DB_URL $plain.TEST_ALLOWED_DB_HOST $plain.TEST_ALLOWED_DB_PORT
    $fields = @{ SchemaVersion = 2 }
    foreach ($name in $plain.Keys) { $fields[$name] = ConvertTo-SecureString $plain[$name] -AsPlainText -Force }
    $record = [pscustomobject]$fields
    Write-PrivateStore $path $record
    $roundtrip = Read-PrivateStore $path
    try {
        foreach ($name in $plain.Keys) { if ($plain[$name] -cne $roundtrip[$name]) { throw 'Roundtrip mismatch.' } }
    } finally { $roundtrip.Clear() }
    if ($SelfTest) {
        # Repeated save exercises replacement and ACL preservation as well.
        Write-PrivateStore $path $record
        $loaded = Read-PrivateStore $path
        $loaded.Clear()
        $raw = [IO.File]::ReadAllText($path)
        foreach ($value in $plain.Values) { if ($raw.Contains($value)) { throw 'Plaintext in store.' } }
        $raw = $null
        $acl = Get-Acl -LiteralPath $path
        $everyone = New-Object Security.Principal.SecurityIdentifier('S-1-1-0')
        $extra = New-Object Security.AccessControl.FileSystemAccessRule($everyone, 'Read', 'Allow')
        $acl.AddAccessRule($extra)
        [IO.File]::SetAccessControl($path, $acl)
        $rejectedAcl = $false
        try { $null = Read-PrivateStore $path } catch { $rejectedAcl = $true }
        Set-PrivateAcl $path $false
        if (-not $rejectedAcl) { throw 'Unsafe ACL was accepted.' }
        if (@(Get-ChildItem -LiteralPath $directory -Filter '*.pending').Count -ne 0) { throw 'Temporary file left behind.' }
        $badUrls = @(
            'jdbc:postgresql://production.invalid/task_manager_test?sslmode=verify-full',
            'jdbc:postgresql://test-db.invalid:5433/task_manager_test?sslmode=verify-full',
            'jdbc:postgresql://test-db.invalid/task_manager?sslmode=verify-full',
            'jdbc:postgresql://test-db.invalid/task_manager_test?sslmode=disable',
            'jdbc:postgresql://test-db.invalid/task_manager_test?sslmode=prefer',
            'jdbc:postgresql://test-db.invalid/task_manager_test?sslmode=allow',
            'jdbc:postgresql://test-db.invalid/task_manager_test',
            'jdbc:postgresql://test-db.invalid/task_manager_test?sslmode=verify-full&user=x',
            "jdbc:postgresql://test-db.invalid/task_manager_test?sslmode=verify-full`n"
        )
        foreach ($url in $badUrls) {
            $rejected = $false
            try { Assert-TestDatabase $url 'test-db.invalid' '5432' } catch { $rejected = $true }
            if (-not $rejected) { throw 'Unsafe URL accepted.' }
        }
        Assert-TestDatabase 'jdbc:postgresql://test-db.invalid:5432/task_manager_test?sslmode=verify-full' 'test-db.invalid' '5432'
        # Exercise storage and loading of both modes without rewriting the URL.
        foreach ($mode in @('require','verify-full')) {
            $expectedUrl = 'jdbc:postgresql://test-db.invalid:5432/task_manager_test?sslmode=' + $mode
            $record.TEST_DB_URL = ConvertTo-SecureString $expectedUrl -AsPlainText -Force
            Write-PrivateStore $path $record
            $loaded = Read-PrivateStore $path
            try {
                if ($loaded.TEST_DB_URL -cne $expectedUrl) { throw 'TLS mode or JDBC URL changed.' }
            } finally { $loaded.Clear() }
        }
        Write-Host 'PASS: synthetic DPAPI roundtrip, replacement, ACL, plaintext absence and JDBC validation. No network.'
    } else { Write-Host 'SAVED: DPAPI storage verified. No connection or tests were run.' }
} catch {
    if ($SelfTest) { Write-Host ('Synthetic check failed at line {0}, category {1}, type {2}' -f $_.InvocationInfo.ScriptLineNumber, $_.CategoryInfo.Category, $_.Exception.GetType().Name) }
    Write-Host 'FAILED: check TEST hostname/port, JDBC URL with sslmode=require or verify-full, storage location and permissions. No input details printed.'
    exit 1
} finally {
    $plain.Clear()
    $record = $null
    if ($SelfTest -and $null -ne $path) {
        # Delete only files created by this synthetic run; never recursively remove a computed directory.
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
        if (Get-Variable directory -ErrorAction SilentlyContinue) { Remove-Item -LiteralPath $directory -ErrorAction SilentlyContinue }
    }
}
