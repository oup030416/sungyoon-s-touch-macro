param(
    [string]$Adb = 'adb',
    [Parameter(Mandatory = $true)][string]$Serial
)
$ErrorActionPreference = 'Stop'
# Android 13+ phone or emulator, USB/wireless debugging. Run after installing and opening the app.
# Session-only: restart this helper after reboot, force-stop, or an app update.
$deviceArgs = @('-s', $Serial)
$sdk = & $Adb @deviceArgs shell getprop ro.build.version.sdk
if ([int]$sdk -lt 33) { throw 'Touch merging requires Android 13 or later.' }
$identity = & $Adb @deviceArgs shell id -u
if ($identity.Trim() -ne '2000') { throw 'Use the ordinary adb shell identity, not adb root.' }
$status = & $Adb @deviceArgs shell content call --uri content://com.sungyoon.helper.touchbridge --method status
if (($status -join '') -match 'connected=true') { Write-Output 'Touch bridge is already connected.'; return }
$packagePath = & $Adb @deviceArgs shell pm path com.sungyoon.helper
$apkPath = ($packagePath | Where-Object { $_ -match '/base\.apk$' } | Select-Object -First 1) -replace '^package:', ''
$packageInfo = & $Adb @deviceArgs shell cmd package list packages -U com.sungyoon.helper
$match = [regex]::Match(($packageInfo -join "`n"), '(?m)^package:com\.sungyoon\.helper uid:(\d+)\s*$')
if (!$apkPath -or !$match.Success -or $apkPath -notmatch '^/[A-Za-z0-9_./=+~-]+$') {
    throw 'Cannot resolve the installed app and UID.'
}
$appUid = $match.Groups[1].Value
# Detach on-device. Binder verifies the application UID; the provider accepts only shell registration.
& $Adb @deviceArgs shell "CLASSPATH='$apkPath' nohup app_process /system/bin com.sungyoon.helper.service.ShellTouchBridge $appUid > /data/local/tmp/sungyoon-touch-bridge.log 2>&1 < /dev/null &"
if ($LASTEXITCODE -ne 0) { throw 'Could not start touch bridge.' }
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    Start-Sleep -Milliseconds 300
    $status = & $Adb @deviceArgs shell content call --uri content://com.sungyoon.helper.touchbridge --method status
    if (($status -join '') -match 'connected=true') {
        Write-Output 'Touch bridge connected. Turn On in the app to test simultaneous holds and touch.'
        return
    }
}
& $Adb @deviceArgs shell cat /data/local/tmp/sungyoon-touch-bridge.log
throw 'Touch bridge did not connect. Inspect adb logcat for ShellTouchBridge errors.'
