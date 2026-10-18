# 一键：构建 -> 安装 -> 看日志
$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Users\HXD\AppData\Local\Android\Sdk' }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$adb = Join-Path $sdk 'platform-tools\adb.exe'

Set-Location $PSScriptRoot
& 'D:\AndroidTools\gradle-8.7\bin\gradle.bat' assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw '构建失败' }

& $adb install -r -d (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')
& $adb shell am force-stop com.lz.hanzi
& $adb shell am start -n com.lz.hanzi/.MainActivity
& $adb logcat -d -s AndroidRuntime:E HanziInk:V ActivityTaskManager:I | Select-Object -Last 40
