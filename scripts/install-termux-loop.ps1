[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_.:-]+$')]
    [string]$Serial,
    [string]$PackagePath = (Join-Path $PSScriptRoot '../termux/loop/dist/termux-gradle-loop.tar.gz'),
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'),
    [switch]$SkipPackages,
    [switch]$AcceptSdkLicenses
)

$ErrorActionPreference = 'Stop'
$archive = (Resolve-Path -LiteralPath $PackagePath).Path
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) { throw "Package is not a file: $archive" }
$checksumPath = "$archive.sha256"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) { throw "Missing package checksum: $checksumPath" }
$expectedHash = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0]
if ($expectedHash -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'Package SHA256 verification failed'
}
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "ADB not found: $AdbPath" }

function Invoke-DeviceAdb {
    param([string[]]$Arguments)
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB operation failed (exit $LASTEXITCODE): $($Arguments -join ' ')" }
}

# run-as requires a debuggable Termux build. This helper never roots the device
# or changes ADB transport settings. Normal devices can run install.sh in Termux.
Invoke-DeviceAdb -Arguments @('get-state')
Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'com.termux', 'id')

$token = [guid]::NewGuid().ToString('N')
$remoteArchive = "/data/local/tmp/termux-gradle-loop-$token.tar.gz"
$privateStage = "/data/data/com.termux/files/home/lateral-loop-source-$token"
Invoke-DeviceAdb -Arguments @('push', $archive, $remoteArchive)

$installerFlags = ''
if ($SkipPackages) { $installerFlags += ' --skip-packages' }
if ($AcceptSdkLicenses) { $installerFlags += ' --accept-sdk-licenses' }

# All shell-interpolated paths below are constants or a generated hexadecimal
# token; no caller-controlled shell text is inserted. Keep sources per-run so a
# previous installation and any existing user source tree remain intact.
$setup = @"
set -eu
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export PATH=/data/data/com.termux/files/usr/bin:/system/bin
export TMPDIR=/data/data/com.termux/files/usr/tmp
mkdir -p '$privateStage'
cp '$remoteArchive' '$privateStage/package.tar.gz'
cd '$privateStage'
tar -xzf package.tar.gz
cd termux-gradle-loop
sha256sum -c SHA256SUMS
exec /data/data/com.termux/files/usr/bin/bash '$privateStage/termux-gradle-loop/install.sh'$installerFlags
"@
$setup = $setup.Replace("`r`n", "`n")
# ADB joins its argument list into a remote shell command, so explicitly quote
# the script once for Android's shell before /system/bin/sh receives it.
$quotedSetup = "'" + $setup.Replace("'", "'\''") + "'"
Invoke-DeviceAdb -Arguments @('shell', "run-as com.termux /system/bin/sh -c $quotedSetup")
Invoke-DeviceAdb -Arguments @('shell', 'rm', '-f', $remoteArchive)
Write-Host "Termux Gradle loop installed on $Serial. Source stage: $privateStage"
Write-Host 'Open Termux to use ~/termux-gradle-loop. Device ADB pairing remains a separate explicit step.'
