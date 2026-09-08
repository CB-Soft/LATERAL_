[CmdletBinding()]
param(
    [ValidateSet('P0', 'Core', 'Stress', 'All')]
    [string]$Mode = 'All',
    [string]$DeviceId = 'emulator-5554',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [int]$P0Repetitions = 5,
    [int]$StressActions = 30
)

$ErrorActionPreference = 'Stop'
if ($DeviceId -notmatch '^emulator-') {
    throw "Refusing to run focus regression against non-emulator device '$DeviceId'."
}
if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "adb was not found at '$AdbPath'."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$artifactRoot = Join-Path $repoRoot "build\focus-regression\$stamp"
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null
$sampleRows = [System.Collections.Generic.List[object]]::new()
$caseRows = [System.Collections.Generic.List[object]]::new()
$sampleTargets = @(0, 25, 50, 75, 100, 125, 150, 175, 200, 250, 300, 400, 500, 750, 950, 1200, 1600, 2200)

# Coordinates are for the checked emulator profile: display 0 = 1216x2688,
# display 2 = 1920x1200. Toolbar alignment is the app default (right), and
# the PhoneUI taskbar must be enabled in Beast settings for the P0 restore case.
$ui = @{
    PhoneChromeTab = @(610, 500)
    BeastApps = @(1190, 18)
    BeastShowAll = @(1250, 18)
    BeastMinAll = @(1330, 18)
    BeastCapture = @(1460, 18)
    BeastDisplay = @(1530, 18)
    BeastSettings = @(1610, 18)
    BeastWorkspace = @(250, 500)
}

function Invoke-Adb([string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $AdbPath -s $DeviceId @Arguments 2>&1
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Send-Tap([int]$DisplayId, [int[]]$Point) {
    Invoke-Adb @('shell', 'input', '-d', "$DisplayId", 'tap', "$($Point[0])", "$($Point[1])") | Out-Null
}

function Get-ActivityState {
    $dump = Invoke-Adb @('shell', 'dumpsys', 'activity', 'activities')
    $tops = @{}
    $currentDisplay = $null
    foreach ($line in $dump) {
        if ($line -match '^Display #(\d+)') {
            $currentDisplay = [int]$Matches[1]
        } elseif ($null -ne $currentDisplay -and $line -match 'topResumedActivity=(.+)$') {
            $tops[$currentDisplay] = $Matches[1].Trim()
            $currentDisplay = $null
        }
    }
    $phoneTaskIds = @(
        $dump | ForEach-Object {
            if ($_ -match 'com\.lateral/\.MainActivity t(\d+)') { [int]$Matches[1] }
        } | Sort-Object -Unique
    )
    [pscustomobject]@{
        Display0Top = if ($tops.ContainsKey(0)) { $tops[0] } else { '<none>' }
        Display2Top = if ($tops.ContainsKey(2)) { $tops[2] } else { '<none>' }
        AllTops = ($tops.GetEnumerator() | Sort-Object Key | ForEach-Object { "$($_.Key):$($_.Value)" }) -join '; '
        PhoneTaskIds = $phoneTaskIds
    }
}

function Set-PhoneBaseline {
    Invoke-Adb @('shell', 'am', 'start', '--display', '0', '-n', 'com.lateral/.MainActivity') | Out-Null
    Start-Sleep -Milliseconds 500
    $state = Get-ActivityState
    if ($state.Display0Top -notmatch 'com\.lateral/\.MainActivity t(\d+)') {
        throw "PhoneUI baseline failed: $($state.Display0Top)"
    }
    return [int]$Matches[1]
}

function Save-PhoneScreenshot([string]$Name) {
    $remote = "/sdcard/$Name.png"
    $local = Join-Path $artifactRoot "$Name.png"
    Invoke-Adb @('shell', 'screencap', '-p', $remote) | Out-Null
    Invoke-Adb @('pull', $remote, $local) | Out-Null
    return $local
}

function Invoke-FocusCase {
    param(
        [string]$Name,
        [scriptblock]$Setup = {},
        [scriptblock]$Action,
        [scriptblock]$Cleanup = {},
        [int[]]$Targets = $sampleTargets,
        [string]$Priority = 'P1'
    )
    $phoneTaskId = Set-PhoneBaseline
    & $Setup
    Start-Sleep -Milliseconds 420
    $startState = Get-ActivityState
    if ($startState.Display0Top -notmatch "com\.lateral/\.MainActivity t$phoneTaskId\}") {
        throw "$Name setup displaced PhoneUI: $($startState.Display0Top)"
    }

    Invoke-Adb @('logcat', '-c') | Out-Null
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    & $Action
    $passed = $true
    foreach ($target in $Targets) {
        $remaining = $target - [int]$clock.ElapsedMilliseconds
        if ($remaining -gt 0) { Start-Sleep -Milliseconds $remaining }
        $state = Get-ActivityState
        $actual = [int]$clock.ElapsedMilliseconds
        $samplePass = $state.Display0Top -match "com\.lateral/\.MainActivity t$phoneTaskId\}" -and
            $state.PhoneTaskIds.Count -eq 1
        if (-not $samplePass) { $passed = $false }
        $sampleRows.Add([pscustomobject]@{
            case = $Name; priority = $Priority; target_ms = $target; actual_ms = $actual
            phone_task = $phoneTaskId; display0_top = $state.Display0Top
            display2_top = $state.Display2Top; task_placement = $state.AllTops
            duplicate_phone_tasks = [Math]::Max(0, $state.PhoneTaskIds.Count - 1)
            pass = $samplePass
        })
    }
    $safeName = $Name -replace '[^a-zA-Z0-9._-]', '-'
    $screenshot = Save-PhoneScreenshot $safeName
    $logPath = Join-Path $artifactRoot "$safeName-logcat.txt"
    Invoke-Adb @('logcat', '-d', '-v', 'brief') | Set-Content -LiteralPath $logPath
    $caseRows.Add([pscustomobject]@{
        action = $Name; priority = $Priority; starting_state = $startState.Display0Top
        samples = $Targets.Count; phone_task = $phoneTaskId
        screenshot = $screenshot; repetitions = 1; result = if ($passed) { 'PASS' } else { 'FAIL' }
    })
    & $Cleanup
    Write-Host ("{0}: {1}" -f $Name, $(if ($passed) { 'PASS' } else { 'FAIL' }))
}

function Open-Apps { Send-Tap 2 $ui.BeastApps; Start-Sleep -Milliseconds 450 }
function Close-Apps { Invoke-Adb @('shell', 'input', '-d', '2', 'keyevent', '111') | Out-Null; Start-Sleep -Milliseconds 350 }
function Min-All { Send-Tap 2 $ui.BeastMinAll; Start-Sleep -Milliseconds 450 }
function Show-All { Send-Tap 2 $ui.BeastShowAll; Start-Sleep -Milliseconds 700 }

if ($Mode -in @('P0', 'All')) {
    1..$P0Repetitions | ForEach-Object {
        Invoke-FocusCase -Name "p0-chrome-taskbar-restore-$($_)" -Priority 'P0' `
            -Setup { Min-All } -Action { Send-Tap 0 $ui.PhoneChromeTab }
    }
}

if ($Mode -in @('Core', 'All')) {
    Invoke-FocusCase -Name 'taskbar-active-click' -Setup { Show-All } -Action { Send-Tap 0 $ui.PhoneChromeTab }
    Invoke-FocusCase -Name 'taskbar-double-click' -Setup { Min-All } -Action {
        Send-Tap 0 $ui.PhoneChromeTab; Start-Sleep -Milliseconds 60; Send-Tap 0 $ui.PhoneChromeTab
    }
    Invoke-FocusCase -Name 'launcher-open' -Action { Open-Apps } -Cleanup { Close-Apps }
    Invoke-FocusCase -Name 'launcher-close-escape' -Setup { Open-Apps } `
        -Action { Invoke-Adb @('shell', 'input', '-d', '2', 'keyevent', '111') | Out-Null }
    Invoke-FocusCase -Name 'launcher-close-outside' -Setup { Open-Apps } -Action { Send-Tap 2 @(80, 300) }
    Invoke-FocusCase -Name 'min-all' -Setup { Show-All } -Action { Send-Tap 2 $ui.BeastMinAll }
    Invoke-FocusCase -Name 'show-all' -Setup { Min-All } -Action { Send-Tap 2 $ui.BeastShowAll }
    Invoke-FocusCase -Name 'capture' -Action { Send-Tap 2 $ui.BeastCapture }
    Invoke-FocusCase -Name 'settings-open' -Action { Send-Tap 2 $ui.BeastSettings } -Cleanup {
        Invoke-Adb @('shell', 'input', '-d', '2', 'keyevent', '4') | Out-Null
    }
    Invoke-FocusCase -Name 'workspace-tap' -Setup { Show-All } -Action { Send-Tap 2 $ui.BeastWorkspace }
    Invoke-FocusCase -Name 'workspace-drag' -Setup { Show-All } -Action {
        Invoke-Adb @('shell', 'input', '-d', '2', 'swipe', '250', '500', '420', '500', '180') | Out-Null
    }
    Invoke-FocusCase -Name 'workspace-scroll' -Setup { Show-All } -Action {
        Invoke-Adb @('shell', 'input', '-d', '2', 'swipe', '420', '700', '420', '300', '180') | Out-Null
    }
    Invoke-FocusCase -Name 'rapid-min-show-restore' -Action {
        Send-Tap 2 $ui.BeastMinAll; Start-Sleep -Milliseconds 350
        Send-Tap 2 $ui.BeastShowAll; Start-Sleep -Milliseconds 350
        Send-Tap 0 $ui.PhoneChromeTab
    }
}

if ($Mode -in @('Stress', 'All')) {
    $random = [Random]::new(20260907)
    $actions = @(
        @{ Name = 'min-all'; Run = { Send-Tap 2 $ui.BeastMinAll } },
        @{ Name = 'show-all'; Run = { Send-Tap 2 $ui.BeastShowAll } },
        @{ Name = 'chrome-taskbar'; Run = { Send-Tap 0 $ui.PhoneChromeTab } },
        @{ Name = 'launcher-open'; Run = { Send-Tap 2 $ui.BeastApps } },
        @{ Name = 'launcher-escape'; Run = { Invoke-Adb @('shell', 'input', '-d', '2', 'keyevent', '111') | Out-Null } },
        @{ Name = 'workspace-tap'; Run = { Send-Tap 2 $ui.BeastWorkspace } },
        @{ Name = 'workspace-scroll'; Run = {
            Invoke-Adb @('shell', 'input', '-d', '2', 'swipe', '400', '700', '400', '350', '120') | Out-Null
        } }
    )
    1..$StressActions | ForEach-Object {
        $action = $actions[$random.Next($actions.Count)]
        Invoke-FocusCase -Name ("randomized-{0:D2}-{1}" -f $_, $action.Name) -Priority 'P0' -Action $action.Run
    }
}

$samplesPath = Join-Path $artifactRoot 'samples.csv'
$summaryPath = Join-Path $artifactRoot 'summary.csv'
$reportPath = Join-Path $artifactRoot 'report.md'
$sampleRows | Export-Csv -NoTypeInformation -LiteralPath $samplesPath
$caseRows | Export-Csv -NoTypeInformation -LiteralPath $summaryPath
$report = @(
    '# PhoneUI focus regression report', '',
    "Device: $DeviceId", "Mode: $Mode", "Generated: $(Get-Date -Format o)", '',
    '| Action | Priority | Phone task | Samples | Result |',
    '|---|---:|---:|---:|---:|'
)
$report += $caseRows | ForEach-Object { "| $($_.action) | $($_.priority) | $($_.phone_task) | $($_.samples) | $($_.result) |" }
$report += @('', "Detailed timing: ``$samplesPath``", "Screenshots and logcat: ``$artifactRoot``")
$report | Set-Content -LiteralPath $reportPath

$failures = @($caseRows | Where-Object result -eq 'FAIL')
Write-Host "Report: $reportPath"
if ($failures.Count -gt 0) { throw "$($failures.Count) focus regression case(s) failed." }
