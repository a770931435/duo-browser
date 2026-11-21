# 建置 APK 並複製到 dist\（gradlew.bat 在含中文的路徑下無法執行，所以直接呼叫本機 Gradle）
#   測試版：powershell -ExecutionPolicy Bypass -File build.ps1
#   正式版：powershell -ExecutionPolicy Bypass -File build.ps1 -Release
param([switch]$Release)
$ErrorActionPreference = 'Stop'
# 工具位置：預設 %LOCALAPPDATA%\devtools；使用者資料夾含中文的電腦可設環境變數 DUO_DEVTOOLS（例如 C:\devtools）
$root = if ($env:DUO_DEVTOOLS) { $env:DUO_DEVTOOLS } else { "$env:LOCALAPPDATA\devtools" }
$buildDir = if ($env:DUO_BUILD_DIR) { $env:DUO_BUILD_DIR } else { "$env:LOCALAPPDATA\DuoBrowserBuild" }
$jdk = Get-ChildItem "$root\jdk-x" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
if (-not $env:JAVA_HOME) { throw "找不到 JDK（$root\jdk-x 或 JAVA_HOME）" }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
# local.properties 會被 OneDrive 在兩台電腦間同步，每次建置都改寫成這台電腦的 SDK 路徑
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path $sdk)) { throw "找不到 Android SDK：$sdk" }
[IO.File]::WriteAllText("$PSScriptRoot\local.properties", "sdk.dir=$($sdk -replace '\\','\\')`n")
$task = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
if ($Release -and -not (Test-Path "$PSScriptRoot\release\keystore.properties")) {
  throw "找不到 release\keystore.properties（正式版簽章），無法建置正式版"
}
Push-Location $PSScriptRoot
try { & "$root\gradle-8.10.2\bin\gradle.bat" $task --no-daemon --console=plain } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

New-Item -ItemType Directory -Force "$PSScriptRoot\dist" | Out-Null
if ($Release) {
  $gradleText = [IO.File]::ReadAllText("$PSScriptRoot\app\build.gradle.kts")
  $ver = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
  $apk = "$buildDir\app\outputs\apk\release\app-release.apk"
  $out = "$PSScriptRoot\dist\DuoBrowser-$ver.apk"
} else {
  $apk = "$buildDir\app\outputs\apk\debug\app-debug.apk"
  $out = "$PSScriptRoot\dist\DuoBrowser-debug.apk"
}
Copy-Item $apk $out -Force
Write-Host "APK: $out"
