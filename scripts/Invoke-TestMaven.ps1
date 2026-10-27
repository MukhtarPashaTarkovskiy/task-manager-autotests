# Always launch in a fresh Windows PowerShell process, not by dot-sourcing.
[CmdletBinding(DefaultParameterSetName = 'Suite')]
param(
    [Parameter(Mandatory=$true, ParameterSetName='Suite')]
    [ValidateSet('ApiSmoke','UiSmoke','ApiRegression','UiRegression')][string] $Suite,
    [Parameter(Mandatory=$true, ParameterSetName='Test')]
    [ValidateSet('api-tests','ui-tests')][string] $Module,
    [Parameter(Mandatory=$true, ParameterSetName='Test')]
    [ValidatePattern('\A[A-Za-z_][A-Za-z0-9_.]*(#[A-Za-z_][A-Za-z0-9_]*)?\z')][string] $Test,
    [Parameter(Mandatory=$true, ParameterSetName='SelfTest')][switch] $SelfTest,
    [switch] $FromEnvironment,
    [switch] $Headless
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$selectionMode = $PSCmdlet.ParameterSetName
$requestedSelfTest = $SelfTest
. (Join-Path $PSScriptRoot 'Initialize-TestSecrets.ps1') -LibraryOnly
$SelfTest = $requestedSelfTest

# Windows Job Object: closing its handle kills the entire assigned process tree.
# cmd waits for stdin before starting Maven, so assignment precedes any child JVM.
Add-Type -TypeDefinition @"
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
public sealed class TestMavenJob : IDisposable {
    [StructLayout(LayoutKind.Sequential)] struct Basic {
        public long ProcessTime, JobTime; public uint Flags;
        public UIntPtr MinWorkingSet, MaxWorkingSet; public uint ActiveLimit;
        public UIntPtr Affinity; public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] struct Io {
        public ulong ReadOps, WriteOps, OtherOps, ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)] struct Extended {
        public Basic Basic; public Io Io; public UIntPtr ProcessMemory, JobMemory, PeakProcess, PeakJob;
    }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint size);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateJobObject(IntPtr handle, uint code);
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    public bool TerminateAndWait(int pid) {
        IntPtr child = OpenProcess(0x00100000, false, pid); // SYNCHRONIZE only, opened before termination
        if (child == IntPtr.Zero) throw new InvalidOperationException("Cannot observe synthetic descendant, code " + Marshal.GetLastWin32Error());
        try { Dispose(); return WaitForSingleObject(child, 5000) == 0; }
        finally { CloseHandle(child); }
    }
    private IntPtr handle;
    public TestMavenJob() {
        handle = CreateJobObject(IntPtr.Zero, null);
        if (handle == IntPtr.Zero) throw new InvalidOperationException("Cannot create process job");
        var info = new Extended(); info.Basic.Flags = 0x2000; // KILL_ON_JOB_CLOSE
        int size = Marshal.SizeOf(typeof(Extended));
        IntPtr ptr = Marshal.AllocHGlobal(size);
        try {
            Marshal.StructureToPtr(info, ptr, false);
            if (!SetInformationJobObject(handle, 9, ptr, (uint)size)) {
                Dispose(); throw new InvalidOperationException("Cannot protect process job");
            }
        } finally { Marshal.FreeHGlobal(ptr); }
    }
    public void Assign(Process process) {
        if (!AssignProcessToJobObject(handle, process.Handle))
            throw new InvalidOperationException("Cannot assign process job");
    }
    public void Dispose() { if (handle != IntPtr.Zero) { TerminateJobObject(handle, 1); CloseHandle(handle); handle=IntPtr.Zero; } }
}
"@
function Protect-TestOutput([string] $Text, [hashtable] $Secrets) {
    foreach ($value in @($Secrets.Values | Sort-Object { $_.Length } -Descending)) {
        if (-not [string]::IsNullOrEmpty($value)) {
            foreach ($form in @($value, [Uri]::EscapeDataString($value), [Security.SecurityElement]::Escape($value),
                    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value)))) {
                $Text = $Text.Replace($form, '[REDACTED]')
            }
        }
    }
    $Text = [regex]::Replace($Text, '(?im)(authorization|cookie|set-cookie|x-csrf-token)\s*[:=][^\r\n]*', '$1: [REDACTED]')
    return $Text
}
$plain = @{}
$process = $null
$startInfo = $null
$job = $null
$exitCode = 1
try {
    if ($SelfTest) {
        # No DPAPI file is read and Maven is never invoked in this mode.
        $plain = @{ TEST_USER_PASSWORD='FAKE-<>&-123' }
        $redacted = Protect-TestOutput ('failure ' + $plain.TEST_USER_PASSWORD) $plain
        if ($redacted.Contains($plain.TEST_USER_PASSWORD) -or -not $redacted.Contains('failure')) { throw 'Redaction failure.' }
        $command = 'powershell.exe -NoProfile -Command "if([string]::IsNullOrEmpty($env:TEST_USER_PASSWORD)){exit 9};[Console]::WriteLine([Diagnostics.Process]::GetCurrentProcess().Id);[Threading.Thread]::Sleep(60000)"'
    } else {
        $path = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData/Local/TaskManagerAutotests/secrets.clixml'
        if ($FromEnvironment) {
            # CI receives GitHub Secrets through the job environment, never through DPAPI.
            foreach ($name in @('TEST_USER_PASSWORD','TEST_DB_URL','TEST_DB_USERNAME','TEST_DB_PASSWORD','TEST_ALLOWED_DB_HOST','TEST_ALLOWED_DB_PORT')) {
                $plain[$name] = [Environment]::GetEnvironmentVariable($name)
                if ([string]::IsNullOrWhiteSpace($plain[$name])) { throw 'Required CI setting missing.' }
            }
            Assert-TestDatabase $plain.TEST_DB_URL $plain.TEST_ALLOWED_DB_HOST $plain.TEST_ALLOWED_DB_PORT
        } else { $plain = Read-PrivateStore $path }
        if ($selectionMode -eq 'Suite') {
            $Module = if ($Suite.StartsWith('Api')) { 'api-tests' } else { 'ui-tests' }
            $suiteFile = if ($Suite.EndsWith('Smoke')) { 'smoke-tests.xml' } else { 'regression-tests.xml' }
            $selection = '-Dsurefire.suiteXmlFiles=' + $suiteFile
        } else { $selection = '-Dtest=' + $Test }
        $maven = (Get-Command mvn.cmd -CommandType Application).Source
        if ($maven -match '[%&|<>^!\r\n"]') { throw 'Unsafe Maven executable path.' }
        # Keep console diagnostics, avoid normal Surefire XML/text copies of test parameters.
        $command = 'call "' + $maven + '" -B -ntp -pl ' + $Module + ' ' + $selection +
            ' -DdisableXmlReport=true -Dsurefire.useFile=false -Dmaven.test.redirectTestOutputToFile=false test'
    }
    if (-not $SelfTest) {
        # Isolate every invocation; preserve old generated files instead of deleting them.
        $workspaceRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
        $resultsPath = Join-Path $workspaceRoot "$Module/target/allure-results"
        $archiveRoot = Join-Path $workspaceRoot 'target/runs/archive'
        $runStamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff') + '-' + [Guid]::NewGuid().ToString('N')
        foreach ($candidate in @($resultsPath,$archiveRoot)) {
            if (-not [IO.Path]::GetFullPath($candidate).StartsWith($workspaceRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe result path.' }
        }
        New-Item -ItemType Directory -Force -Path $archiveRoot | Out-Null
        if (Test-Path -LiteralPath $resultsPath) {
            Move-Item -LiteralPath $resultsPath -Destination (Join-Path $archiveRoot "$runStamp-$Module")
        }
    }
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = Join-Path $env:SystemRoot 'System32/cmd.exe'
    $startInfo.Arguments = '/d /s /c "set /p TM_START_GATE= >nul & ' + $command + '"'
    $startInfo.WorkingDirectory = Split-Path -Parent $PSScriptRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($name in @('MAVEN_OPTS','MAVEN_ARGS','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_SKIP_RC')) {
        $startInfo.EnvironmentVariables.Remove($name)
    }
    $startInfo.EnvironmentVariables['MAVEN_SKIP_RC'] = '1'
    foreach ($name in $plain.Keys) { $startInfo.EnvironmentVariables[$name] = $plain[$name] }
    $startInfo.EnvironmentVariables['TEST_BASE_URL'] = 'https://task-manager-test-588r.onrender.com'
    $startInfo.EnvironmentVariables['HEADLESS'] = $Headless.IsPresent.ToString().ToLowerInvariant()
    $job = New-Object TestMavenJob
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw 'Process start failed.' }
    $stdout = if ($SelfTest) { $process.StandardOutput.ReadLineAsync() } else { $process.StandardOutput.ReadToEndAsync() }
    $stderr = $process.StandardError.ReadToEndAsync()
    $job.Assign($process)
    $process.StandardInput.WriteLine('start')
    $process.StandardInput.Close()
    # Polling allows PowerShell cancellation to unwind through finally.
    if (-not $SelfTest) {
        while (-not $process.WaitForExit(200)) { }
        $exitCode = $process.ExitCode
    } else { $exitCode = 0 }
    if ($SelfTest) {
        if ($exitCode -ne 0) { throw 'Environment handoff failed.' }
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        $childId = 0
        while ($childId -eq 0 -and [DateTime]::UtcNow -lt $deadline) {
            while (-not $stdout.IsCompleted -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
            if (-not $stdout.IsCompleted) { break }
            $line = $stdout.GetAwaiter().GetResult()
            if ($null -eq $line) { break }
            if ($line -match '\A[1-9][0-9]*\z') { $childId = [int]$line }
            else { $stdout = $process.StandardOutput.ReadLineAsync() }
        }
        if ($childId -eq 0) { if ($stderr.IsCompleted) { Write-Host (Protect-TestOutput ($stderr.GetAwaiter().GetResult()) $plain) }; throw 'Synthetic descendant did not start.' }
        if (-not $job.TerminateAndWait($childId)) { throw 'Job did not terminate descendant.' }
        $job = $null
        Write-Host 'PASS: synthetic child environment, descendant termination and diagnostic redaction. No Maven or network.'
    } else {
        Write-Host (Protect-TestOutput ($stdout.GetAwaiter().GetResult()) $plain)
        Write-Host (Protect-TestOutput ($stderr.GetAwaiter().GetResult()) $plain)
        Write-Host "Maven exit code: $exitCode"
        # Audit every report format, including TestNG/Surefire siblings of allure-results.
        & (Join-Path $PSScriptRoot 'Test-ReportSecrets.ps1') -Paths @((Join-Path $workspaceRoot "$Module/target")) -FromEnvironment:$FromEnvironment
        if ($LASTEXITCODE -ne 0) { throw 'Artifact audit failed.' }
        if (Test-Path -LiteralPath $resultsPath) {
            if ($selectionMode -eq 'Suite') {
                $snapshot = Join-Path $workspaceRoot "target/runs/$Suite"
                if (-not [IO.Path]::GetFullPath($snapshot).StartsWith($workspaceRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe snapshot path.' }
                if (Test-Path -LiteralPath $snapshot) {
                    Move-Item -LiteralPath $snapshot -Destination (Join-Path $archiveRoot "$runStamp-$Suite")
                }
                New-Item -ItemType Directory -Path $snapshot | Out-Null
                Copy-Item -LiteralPath $resultsPath -Destination (Join-Path $snapshot 'allure-results') -Recurse
                Write-Host "Audited suite results saved: target/runs/$Suite/allure-results"
            }
        }
    }
} catch {
    Write-Host 'FAILED: storage, endpoint validation or process execution. Secret-bearing exception details suppressed.'
    if ($SelfTest) { Write-Host ('Synthetic check line: {0}; category: {1}; reason: {2}' -f $_.InvocationInfo.ScriptLineNumber, $_.CategoryInfo.Category, (Protect-TestOutput $_.Exception.Message $plain)) }
    $exitCode = 1
} finally {
    if ($null -ne $job) { $job.Dispose() }
    if ($null -ne $process) {
        # Also handle failure before job assignment (cmd has not passed the stdin gate).
        try { if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() } } catch { }
        $process.Dispose()
    }
    if ($null -ne $startInfo) { $startInfo.EnvironmentVariables.Clear() }
    $plain.Clear()
    $stdout = $null
    $stderr = $null
}
exit $exitCode
