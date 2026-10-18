# 安装并启动 墨格练字（构建产物需先存在）
$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Users\HXD\AppData\Local\Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'

if (-not (Test-Path $apk)) { throw "找不到 APK，请先构建：$apk" }

$devices = & $adb devices | Select-String -Pattern '\tdevice$'
if (-not $devices) {
    Write-Host "没有检测到设备。请检查：" -ForegroundColor Yellow
    Write-Host "  1) 平板已用 USB 连到电脑，USB 模式选『传输文件』"
    Write-Host "  2) 开发者选项里已打开『USB 调试』"
    Write-Host "  3) 平板上弹出的『允许 USB 调试』已点确定"
    exit 1
}

& $adb install -r -d $apk
& $adb shell am start -n com.lz.hanzi/.MainActivity
Write-Host "已启动。用 M-Pencil 2 写字，底部状态栏会显示实时压感 / 线宽 / 设备类型。" -ForegroundColor Green
