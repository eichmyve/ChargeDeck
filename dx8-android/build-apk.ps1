# Baut die Debug-APK mit den portablen Werkzeugen aus %LOCALAPPDATA%\DX8Build
$root = Join-Path $env:LOCALAPPDATA 'DX8Build'
$env:JAVA_HOME = (Get-ChildItem (Join-Path $root 'jdk') -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $root 'sdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = Join-Path (Get-ChildItem (Join-Path $root 'gradle') -Directory | Select-Object -First 1).FullName 'bin\gradle.bat'
Set-Location $PSScriptRoot
"sdk.dir=" + ($env:ANDROID_HOME -replace '\\', '\\') | Set-Content local.properties -Encoding ascii
& $gradle --no-daemon --console=plain assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Build fehlgeschlagen ($LASTEXITCODE)" }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
Copy-Item $apk (Join-Path (Split-Path $PSScriptRoot) 'DX8Monitor.apk') -Force
"APK: " + (Join-Path (Split-Path $PSScriptRoot) 'DX8Monitor.apk') + ("  ({0:N1} MB)" -f ((Get-Item $apk).Length / 1MB))
