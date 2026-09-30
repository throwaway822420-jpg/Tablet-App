# Downloads Google's Android platform-tools (adb) into windows\Slate\platform-tools so the build
# bundles adb next to Slate.exe. By downloading you accept the Android SDK license:
# https://developer.android.com/studio/terms
$ErrorActionPreference = 'Stop'

$dest = Join-Path $PSScriptRoot '..\Slate\platform-tools'
$zip = Join-Path $env:TEMP 'platform-tools-latest-windows.zip'
$url = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'

Write-Host "Downloading $url"
Invoke-WebRequest -Uri $url -OutFile $zip

$tmp = Join-Path $env:TEMP ('slate-pt-' + [guid]::NewGuid())
Expand-Archive -Path $zip -DestinationPath $tmp
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
Move-Item (Join-Path $tmp 'platform-tools') $dest
Remove-Item $tmp -Recurse -Force
Remove-Item $zip -Force

Write-Host "adb is at $(Join-Path $dest 'adb.exe')"
