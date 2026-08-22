<#
.SYNOPSIS
    Start N dummy backends on this Windows machine and reverse-tunnel their
    ports to a remote VPS over SSH, so the VPS can hit them at
    http://localhost:<port>/ without any VPS-side routing/Docker changes.

.EXAMPLE
    # 10 backends on ports 9001-9010, 1MB bodies, tunnelled to a VPS
    .\start-local-backends-tunnel.ps1 -Count 10 -StartPort 9001 -VpsHost vuhongquang@my-vps -BodyBytes 1048576

.NOTES
    Requires Python 3 (python.org installer, "Add to PATH" checked) and the
    built-in Windows OpenSSH client (ssh.exe, present by default on Win11).
    Ctrl+C stops the tunnel and kills every backend this script started.
#>
param(
    [Parameter(Mandatory = $true)][int]$Count,
    [Parameter(Mandatory = $true)][int]$StartPort,
    [Parameter(Mandatory = $true)][string]$VpsHost,
    [int]$BodyBytes = 65536
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$dummyScript = Join-Path $scriptDir "dummy_backend.py"

if (-not (Test-Path $dummyScript)) {
    Write-Error "Can't find dummy_backend.py next to this script ($scriptDir)"
    exit 1
}

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) {
    Write-Error "No 'python' or 'py' found on PATH. Install Python 3 first."
    exit 1
}

$processes = @()
$forwardArgs = @()

for ($i = 0; $i -lt $Count; $i++) {
    $port = $StartPort + $i
    $outLog = Join-Path $env:TEMP "dummy_local_$port.out.log"
    $errLog = Join-Path $env:TEMP "dummy_local_$port.err.log"
    $proc = Start-Process -FilePath $python.Source `
        -ArgumentList @($dummyScript, $port, "svc$i", $BodyBytes) `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
        -PassThru -WindowStyle Hidden
    $processes += $proc
    $forwardArgs += "-R"
    $forwardArgs += "${port}:127.0.0.1:${port}"
    Write-Host "started backend on :$port (pid $($proc.Id), logs $outLog / $errLog)"
}

Start-Sleep -Seconds 1
foreach ($p in $processes) {
    if ($p.HasExited) {
        Write-Warning "backend pid $($p.Id) died on startup -- check its log"
    }
}

try {
    Write-Host ""
    Write-Host "Opening SSH reverse tunnel to $VpsHost for ports $StartPort..$($StartPort + $Count - 1)"
    Write-Host "On the VPS, curl http://localhost:<port>/ to reach these backends. Ctrl+C to stop everything."
    Write-Host ""
    & ssh -N @forwardArgs $VpsHost
}
finally {
    Write-Host ""
    Write-Host "Stopping $($processes.Count) local backend(s)..."
    foreach ($p in $processes) {
        if (-not $p.HasExited) {
            Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        }
    }
}
