param(
    [string]$Package = 'com.powercess.mbrain.dev',
    [string]$Serial = '',
    [string]$TestApk = "$PSScriptRoot/../app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
)
$ErrorActionPreference = 'Stop'
if ($Package -ne 'com.powercess.mbrain.dev') {
    throw 'Only the fixed development package com.powercess.mbrain.dev may be tested.'
}
$deviceArgs = if ($Serial) { @('-s', $Serial) } else { @() }
function Invoke-Adb([string[]]$Arguments) {
    $output = & adb @deviceArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $($Arguments[0])" }
    return $output
}
function Quote-Shell([string]$Value) { "'" + $Value.Replace("'", "'" + [char]34 + "'" + [char]34 + "'") + "'" }
$apkPath = ([string](Invoke-Adb @('shell', 'pm', 'path', $Package))).Replace('package:', '').Trim()
if (!$apkPath.StartsWith('/data/app/')) { throw 'Install the test app first.' }
$helper = "CLASSPATH=$(Quote-Shell $apkPath) timeout 30 app_process /system/bin com.powercess.mbrain.control.PhoneControlMain"
function Invoke-Phone([string]$Operation, [hashtable]$Arguments = @{}) {
    # Restrict selectors to the test app; global navigation and coordinates are explicit below.
    if ($Arguments.ContainsKey('text')) { $Arguments['package'] = $Package }
    $json = ConvertTo-Json -InputObject $Arguments -Compress -EscapeHandling EscapeNonAscii
    $output = Invoke-Adb @('shell', "$helper $Operation $(Quote-Shell $json)")
    $line = $output | Where-Object { $_.StartsWith('MBRAIN_RESULT:') } | Select-Object -Last 1
    if (!$line) { throw 'No helper response' }
    $reply = $line.Substring(14) | ConvertFrom-Json
    if (!$reply.ok) { throw "$Operation failed: $($reply.error)" }
    return $reply.data
}
Invoke-Adb @('push', (Resolve-Path $TestApk).Path, '/data/local/tmp/mbrain-control-tests.apk') | Out-Null
try {
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$Package/com.powercess.mbrain.MainActivity") | Out-Null
    $classpath = Quote-Shell "${apkPath}:/data/local/tmp/mbrain-control-tests.apk"
    $result = Invoke-Adb @('shell', "CLASSPATH=$classpath timeout 60 app_process /system/bin com.powercess.mbrain.control.CapabilitySmokeMain $(Quote-Shell $apkPath) $Package")
    if ($result -notcontains 'CAPABILITY_SMOKE_OK') { throw 'Device diagnostics failed' }
    $result
    $tree = Invoke-Phone ui_dump
    if ($tree.count -le 0) { throw 'Empty UI tree' }
    'PASS ui_dump'
    Invoke-Phone ui_click @{text='MCP'} | Out-Null
    Invoke-Phone ui_wait @{text='添加服务'; timeout_ms=5000} | Out-Null
    Invoke-Phone ui_click @{text='添加服务'} | Out-Null
    Invoke-Phone ui_wait @{text='HTTP 服务'; timeout_ms=5000} | Out-Null
    Invoke-Phone ui_click @{text='HTTP 服务'} | Out-Null
    Invoke-Phone ui_set_text @{text='服务名称';value='MBrain 中文😀输入验证'} | Out-Null
    Invoke-Phone ui_wait @{text='MBrain 中文😀输入验证'; timeout_ms=5000} | Out-Null
    'PASS Chinese + emoji input and present wait'
    Invoke-Phone ui_long_click @{text='MBrain 中文😀输入验证'} | Out-Null
    'PASS node long click'
    Invoke-Phone ui_navigate @{action='back'} | Out-Null
    # Derive swipe coordinates from the current root, so the script works at different resolutions.
    $bounds = (Invoke-Phone ui_dump).nodes[0].bounds
    $x = [int](($bounds[0] + $bounds[2]) / 2)
    $y1 = [int]($bounds[1] + ($bounds[3] - $bounds[1]) * 0.65)
    $y2 = [int]($bounds[1] + ($bounds[3] - $bounds[1]) * 0.4)
    Invoke-Phone ui_swipe @{x1=$x;y1=$y1;x2=$x;y2=$y2;duration_ms=250} | Out-Null
    'PASS swipe'
    Invoke-Phone ui_wait @{text='MBrain nonexistent control';state='absent';timeout_ms=0} | Out-Null
    try { Invoke-Phone ui_wait @{text='MBrain nonexistent control';timeout_ms=100} | Out-Null; throw 'Expected timeout' }
    catch { if (!$_.ToString().Contains('wait_timeout')) { throw } }
    'PASS absent wait and timeout error'
    Invoke-Phone ui_navigate @{action='home'} | Out-Null
    Invoke-Phone ui_navigate @{action='recents'} | Out-Null
    Invoke-Phone ui_navigate @{action='back'} | Out-Null
    'PASS home, recents and back'
} finally {
    Invoke-Adb @('shell', 'rm', '-f', '/data/local/tmp/mbrain-control-tests.apk') | Out-Null
    # Discard the unsaved test form, leaving installed app data intact.
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$Package/com.powercess.mbrain.MainActivity") | Out-Null
}
