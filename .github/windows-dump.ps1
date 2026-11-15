# Prints the Windows runner's state, for when it can no longer start java.exe or node.exe
# (0xC0000142, STATUS_DLL_INIT_FAILED) and for a baseline on every share: the numbers that tell
# the candidate causes apart. Commit charge (each JVM commits heap, metaspace and code cache, so it
# can run out while RAM is free), the kernel pools, handles and processes, the desktop heap (the
# conhost count, the window station and the size the registry gives it) and the system log's
# warnings, with the job object the runner is in.
#
#   .github/windows-dump.ps1 baseline|failure
#
# It runs in this process, and starts none (java and node are what fail): a runspace, cut after
# 20 seconds, which prints what it has by then. Nothing in it can fail the step.
param([string] $State = 'baseline')

$body = {
  param($summary)

  function Section($title) { "`n--- $title" }
  function Try-Run($what, [scriptblock] $block) {
    try { & $block } catch { "$what failed: $($_.Exception.Message)" }
  }

  Section 'Memory and handles'
  Try-Run 'Get-Counter' {
    $names = '\Memory\Committed Bytes', '\Memory\Commit Limit', '\Memory\% Committed Bytes In Use',
      '\Memory\Available MBytes', '\Memory\Pool Paged Bytes', '\Memory\Pool Nonpaged Bytes',
      '\Process(_Total)\Handle Count', '\System\Processes', '\System\Threads'
    $samples = (Get-Counter $names -ErrorAction Stop).CounterSamples
    $value = @{}
    foreach ($s in $samples) { $value[($s.Path -replace '^\\\\[^\\]+', '')] = $s.CookedValue }
    $summary.commitPercent = [math]::Round($value['\memory\% committed bytes in use'], 1)
    $summary.committedGB = [math]::Round($value['\memory\committed bytes'] / 1GB, 1)
    $summary.commitLimitGB = [math]::Round($value['\memory\commit limit'] / 1GB, 1)
    $summary.pagedMB = [math]::Round($value['\memory\pool paged bytes'] / 1MB)
    $summary.nonpagedMB = [math]::Round($value['\memory\pool nonpaged bytes'] / 1MB)
    $summary.handles = [math]::Round($value['\process(_total)\handle count'])
    $samples | Format-Table @{n = 'Counter'; e = { $_.Path -replace '^\\\\[^\\]+', '' } },
      @{n = 'Value'; e = { [math]::Round($_.CookedValue, 1) } } -AutoSize | Out-String -Width 200
  }
  if ($null -eq $summary.commitPercent) {
    Try-Run 'Win32_OperatingSystem' {
      $os = Get-CimInstance Win32_OperatingSystem -OperationTimeoutSec 5 -ErrorAction Stop
      # Both in KB: the commit limit and what of it is left.
      $total = $os.TotalVirtualMemorySize; $free = $os.FreeVirtualMemory
      $summary.commitPercent = [math]::Round(100 * ($total - $free) / $total, 1)
      $summary.committedGB = [math]::Round(($total - $free) / 1MB, 1)
      $summary.commitLimitGB = [math]::Round($total / 1MB, 1)
      "Commit (Win32_OperatingSystem): $($summary.committedGB) of $($summary.commitLimitGB) GB, $($summary.commitPercent)%; physical free $([math]::Round($os.FreePhysicalMemory / 1MB, 1)) GB"
    }
  }

  Section 'Processes'
  Try-Run 'Get-Process' {
    $all = Get-Process
    $count = @{}
    foreach ($name in 'java', 'conhost', 'node', 'gradle', 'cmd', 'pwsh', 'powershell') {
      $count[$name] = @($all | Where-Object ProcessName -EQ $name).Count
    }
    $summary.conhost = $count.conhost
    "$($all.Count) processes: " + (($count.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name) $($_.Value)" }) -join ', ')
    $columns = 'ProcessName', 'Id', 'SessionId', @{n = 'WorkingSetMB'; e = { [math]::Round($_.WorkingSet64 / 1MB) } },
      @{n = 'PrivateMB'; e = { [math]::Round($_.PrivateMemorySize64 / 1MB) } }, 'HandleCount'
    'Top 10 by working set:'
    $all | Sort-Object WorkingSet64 -Descending | Select-Object -First 10 | Format-Table $columns -AutoSize | Out-String -Width 200
    'Top 10 by handles:'
    $all | Sort-Object HandleCount -Descending | Select-Object -First 10 | Format-Table $columns -AutoSize | Out-String -Width 200
  }

  Section 'Session, window station and desktop heap'
  Try-Run 'The session' {
    $me = [Diagnostics.Process]::GetCurrentProcess()
    "User $([Security.Principal.WindowsIdentity]::GetCurrent().Name), session $($me.SessionId), pid $($me.Id)"
  }
  Try-Run 'The window station' {
    if (-not ('MutasticDump' -as [type])) {
      Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class MutasticDump {
  [StructLayout(LayoutKind.Sequential)]
  struct StartupInfo {
    // The strings are the process's own, so they're read, not marshalled (which would free them).
    public int cb; public IntPtr lpReserved, lpDesktop, lpTitle;
    public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
    public short wShowWindow, cbReserved2; public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
  }
  [StructLayout(LayoutKind.Sequential)]
  struct BasicLimit {
    public long PerProcessUserTimeLimit, PerJobUserTimeLimit; public uint LimitFlags;
    public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize; public uint ActiveProcessLimit;
    public UIntPtr Affinity; public uint PriorityClass, SchedulingClass;
  }
  [StructLayout(LayoutKind.Sequential)]
  struct ExtendedLimit {
    public BasicLimit Basic; public ulong Io1, Io2, Io3, Io4, Io5, Io6;
    public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
  }
  [StructLayout(LayoutKind.Sequential)]
  struct Accounting {
    public long TotalUserTime, TotalKernelTime, ThisPeriodUserTime, ThisPeriodKernelTime;
    public uint TotalPageFaultCount, TotalProcesses, ActiveProcesses, TotalTerminatedProcesses;
  }

  [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern void GetStartupInfoW(out StartupInfo info);
  [DllImport("user32.dll")] static extern IntPtr GetProcessWindowStation();
  [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
  static extern bool GetUserObjectInformationW(IntPtr obj, int index, StringBuilder info, int length, out int needed);
  [DllImport("kernel32.dll", SetLastError = true)] static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool QueryInformationJobObject(IntPtr job, int infoClass, out ExtendedLimit info, int length, out int returned);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool QueryInformationJobObject(IntPtr job, int infoClass, out Accounting info, int length, out int returned);

  public static string StartupDesktop() { StartupInfo info; GetStartupInfoW(out info); return Marshal.PtrToStringUni(info.lpDesktop); }

  public static string WindowStation() {
    var name = new StringBuilder(256); int needed;
    return GetUserObjectInformationW(GetProcessWindowStation(), 2, name, name.Capacity * 2, out needed)
      ? name.ToString() : "unknown (error " + Marshal.GetLastWin32Error() + ")";
  }

  public static string Job() {
    bool inJob;
    if (!IsProcessInJob(new IntPtr(-1), IntPtr.Zero, out inJob)) return "unknown (error " + Marshal.GetLastWin32Error() + ")";
    if (!inJob) return "none";
    var text = new StringBuilder("this process is in one");
    ExtendedLimit limit; Accounting count; int returned;
    // A null job handle is the job this process is in.
    if (QueryInformationJobObject(IntPtr.Zero, 9, out limit, Marshal.SizeOf(typeof(ExtendedLimit)), out returned)) {
      text.AppendFormat("; limit flags 0x{0:X}, active processes at most {1}, process memory at most {2} MB, job memory at most {3} MB, peak process memory {4} MB, peak job memory {5} MB",
        limit.Basic.LimitFlags, limit.Basic.ActiveProcessLimit, (ulong) limit.ProcessMemoryLimit >> 20,
        (ulong) limit.JobMemoryLimit >> 20, (ulong) limit.PeakProcessMemoryUsed >> 20, (ulong) limit.PeakJobMemoryUsed >> 20);
    } else {
      text.Append("; its limits are unreadable (error " + Marshal.GetLastWin32Error() + ")");
    }
    if (QueryInformationJobObject(IntPtr.Zero, 1, out count, Marshal.SizeOf(typeof(Accounting)), out returned)) {
      text.AppendFormat("; {0} processes active, {1} started, {2} terminated", count.ActiveProcesses, count.TotalProcesses, count.TotalTerminatedProcesses);
    }
    return text.ToString();
  }
}
'@
    }
    "Window station $([MutasticDump]::WindowStation()), startup desktop $([MutasticDump]::StartupDesktop())"
    "Job object: $([MutasticDump]::Job())"
  }
  Try-Run 'The desktop heap size' {
    $windows = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\SubSystems' -ErrorAction Stop).Windows
    # SharedSection=<shared>,<interactive desktops>,<non-interactive desktops>, in KB.
    if ($windows -match 'SharedSection=([\d,]+)') { "Desktop heap (SharedSection, KB): $($Matches[1])" } else { 'No SharedSection' }
  }

  # Get-WinEvent throws when nothing matches, which is the answer hoped for.
  function Get-Events($filter, $most) {
    try { Get-WinEvent -FilterHashtable $filter -MaxEvents $most -ErrorAction Stop }
    catch { if ($_.FullyQualifiedErrorId -like 'NoMatchingEventsFound*') { 'None.' } else { throw } }
  }

  Section 'System log: warnings and worse, the past hour (newest 20)'
  $since = (Get-Date).AddHours(-1)
  Try-Run 'Get-WinEvent' {
    $events = Get-Events @{ LogName = 'System'; Level = 1, 2, 3; StartTime = $since } 20
    if ($events -is [string]) { return $events }
    $events | Format-Table TimeCreated, Id, LevelDisplayName, ProviderName,
      @{n = 'Message'; e = { $m = "$($_.Message)" -replace '\s+', ' '; $m.Substring(0, [math]::Min(160, $m.Length)) } } -AutoSize -Wrap |
      Out-String -Width 300
  }
  # Application Popup (26) logs at Information, so it's asked for by name.
  Section 'System log: Resource-Exhaustion-Detector, Win32k and Application Popup, the past hour'
  Try-Run 'Get-WinEvent' {
    $providers = 'Microsoft-Windows-Resource-Exhaustion-Detector', 'Microsoft-Windows-Win32k', 'Application Popup'
    $events = Get-Events @{ LogName = 'System'; ProviderName = $providers; StartTime = $since } 10
    if ($events -is [string]) { return $events }
    $events | Format-Table TimeCreated, Id, ProviderName,
      @{n = 'Message'; e = { $m = "$($_.Message)" -replace '\s+', ' '; $m.Substring(0, [math]::Min(200, $m.Length)) } } -AutoSize -Wrap |
      Out-String -Width 300
  }
}

try {
  $summary = [hashtable]::Synchronized(@{})
  $output = [Management.Automation.PSDataCollection[psobject]]::new()
  $ps = [powershell]::Create().AddScript($body).AddArgument($summary)
  $run = $ps.BeginInvoke([Management.Automation.PSDataCollection[psobject]]::new(), $output)
  $done = $run.AsyncWaitHandle.WaitOne(20000)

  if ($State -eq 'baseline') { Write-Host "::group::The runner's state at the end of the share" }
  else { Write-Host "The runner's state now that it can't start java.exe:" }
  foreach ($line in $output.ReadAll()) { Write-Host ("$line".TrimEnd()) }
  foreach ($e in $ps.Streams.Error) { Write-Host "error: $e" }
  if (-not $done) {
    Write-Host 'The dump took over 20 seconds, so it stopped there.'
    $null = $ps.BeginStop($null, $null)
  }
  if ($State -eq 'baseline') { Write-Host '::endgroup::' }

  function Show($v) { if ($null -eq $v) { '?' } else { $v } }
  Write-Host ("::notice title=Windows runner, $State::commit $(Show $summary.commitPercent)% " +
    "($(Show $summary.committedGB) of $(Show $summary.commitLimitGB) GB), conhost $(Show $summary.conhost), " +
    "pools paged $(Show $summary.pagedMB) MB nonpaged $(Show $summary.nonpagedMB) MB, handles $(Show $summary.handles)")
} catch {
  Write-Host "The runner's state couldn't be dumped: $($_.Exception.Message)"
}
